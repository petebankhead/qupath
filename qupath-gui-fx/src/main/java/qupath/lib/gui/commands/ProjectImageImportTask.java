package qupath.lib.gui.commands;

import javafx.concurrent.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;
import qupath.fx.utils.FXUtils;
import qupath.lib.common.GeneralTools;
import qupath.lib.common.ThreadTools;
import qupath.lib.gui.panes.ServerSelector;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerBuilder;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.images.servers.ServerTools;
import qupath.lib.io.PathIO;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectReader;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectIO;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A JavaFX Task implementation for importing images to a project.
 */
class ProjectImageImportTask extends Task<List<ProjectImageEntry<BufferedImage>>> {

    private final static Logger logger = LoggerFactory.getLogger(ProjectImageImportTask.class);

    private final Project<BufferedImage> project;
    private final ImageServerBuilder<BufferedImage> requestedBuilder;
    private final List<String> items;
    private final String[] args;

    private final ImageData.ImageType type;
    private final boolean pyramidalize;
    private final boolean importObjects;
    private final boolean showSelector;

    private final AtomicLong counter = new AtomicLong();
    private long maxWork;
    private final List<ProjectImageEntry<BufferedImage>> failures = Collections.synchronizedList(new ArrayList<>());

    private final Set<String> failedPaths = new LinkedHashSet<>();

    ProjectImageImportTask(Project<BufferedImage> project, ImageServerBuilder<BufferedImage> requestedBuilder,
                           boolean showSelector, boolean pyramidalize, boolean importObjects, ImageData.ImageType imageType,
                           List<String> items, String... args) {
        this.project = project;
        this.showSelector = showSelector;
        this.pyramidalize = pyramidalize;
        this.importObjects = importObjects;
        this.type = imageType;
        this.requestedBuilder = requestedBuilder;
        this.items = List.copyOf(items);
        this.args = args.clone();
    }

    private ExecutorService createPool() {
        return Executors.newFixedThreadPool(
                ThreadTools.getParallelism(),
                ThreadTools.createThreadFactory("project-import", true));
    }


    @Override
    protected List<ProjectImageEntry<BufferedImage>> call() throws Exception {

        updateMessage("Checking for compatible image readers...");

        // Limit the size of the thread pool
        // The previous use of a cached thread pool caused trouble when importing may large, non-pyramidal images
        var pool = createPool();

        List<Future<ItemWithAllBuilders>> itemWithAllBuilders = new ArrayList<>();

        List<ProjectImageEntry<BufferedImage>> projectImages = new ArrayList<>();
        List<File> existingDataFiles = new ArrayList<>();
        for (var item : items) {
            // Try to load items from a project if possible
            if (isQuPathProject(item)) {
                // Import from a project
                try {
                    var tempProject = ProjectIO.loadProject(GeneralTools.toURI(item), BufferedImage.class);
                    projectImages.addAll(tempProject.getImageList());
                } catch (Exception e) {
                    logger.error("Unable to add images from {} ({})", item, e.getMessage(), e);
                    failedPaths.add(item);
                }
            } else if (isQuPathDataFile(item)) {
                // Import from a data file
                var file = new File(item);
                if (file.exists()) {
                    existingDataFiles.add(file);
                } else {
                    failedPaths.add(item);
                }
            } else {
                // Import from image URIs - we might have multiple images within the same URI
                itemWithAllBuilders.add(pool.submit(() -> new ItemWithAllBuilders(item, getServerBuilders(item))));
            }
        }

        // If we have projects, try adding images from these first
        handleImportFromProjects(projectImages);

        // Figure out how many 'standard' image paths with builders we have
        List<ImageServerBuilder.ServerBuilder<BufferedImage>> builders = new ArrayList<>();
        List<String> itemsForBuilders = new ArrayList<>(); // Should be the same length
        for (var result : itemWithAllBuilders) {
            try {
                var nextBuilders = result.get();
                if (nextBuilders.builders.isEmpty())
                    failedPaths.add(nextBuilders.item);
                else {
                    for (var b : nextBuilders.builders) {
                        builders.add(b);
                        itemsForBuilders.add(nextBuilders.item);
                    }
                }
            } catch (ExecutionException e) {
                logger.error("Execution exception importing image", e);
            }
        }

        // Determine the total number of images to add, from standard paths & data files
        maxWork = builders.size() + existingDataFiles.size();
        counter.set(0L);

        // If we have data files, use them next
        handleImportFromDataFiles(existingDataFiles);

        // Finally work through the standard images
        // We can parallelize the slow initialization step
        List<ProjectImageEntry<BufferedImage>> allAddedEntries = new ArrayList<>();
        if (!builders.isEmpty()) {
            if (maxWork == 1)
                updateMessage("Preparing to add 1 image to project");
            else
                updateMessage("Preparing " + maxWork + " images");

            // We might need to use the image selector to allow the user to choose what to import
            // when multiple images are found in a single file
            if (showSelector && maxWork > 1) {
                var selector = ServerSelector.createFromBuilders(builders);
                var selected = FXUtils.callOnApplicationThread(() -> selector.promptToSelectImages("Import"));
                builders.clear();
                itemsForBuilders.clear(); // We've lost track of what we can read now...
                if (selected != null) {
                    for (var s : selected) {
                        builders.add(s.getBuilder());
                        s.close(); // TODO: Don't waste open images by closing them again...
                    }
                }
            }

            // Add everything in order first - this should work, because we aren't trying to access the image
            List<ProjectImageEntry<BufferedImage>> entries = new ArrayList<>();
            for (var builder : builders) {
                entries.add(project.addImage(builder));
            }
            allAddedEntries.addAll(entries);

            // Initialize - this is the slow bit where we might discover that the image isn't supported
            int n = builders.size();
            int k = 0;
            for (var entry : entries) {
                String itemName = itemsForBuilders.isEmpty() ? null : itemsForBuilders.get(k);
                k++;
                pool.submit(() -> {
                    try {
                        initializeEntry(entry, type, pyramidalize, importObjects);
                    } catch (Exception e) {
                        failures.add(entry);
                        if (itemName != null)
                            failedPaths.add(itemName);
                        logger.warn("Exception adding {}", entry, e);
                    } finally {
                        long i = counter.incrementAndGet();
                        updateProgress(i, maxWork);
                        String name = entry.getImageName();
                        if (name != null) {
                            updateMessage("Added " + i + "/" + n + " - " + name);
                        }
                    }
                });
            }
        }
        pool.shutdown();
        try {
            pool.awaitTermination(60, TimeUnit.MINUTES);
        } catch (Exception e) {
            logger.error("Exception waiting for project import to complete: {}", e.getMessage(), e);
        }

        String errorMessage = null;
        if (!failures.isEmpty()) {
            if (failures.size() == 1)
                errorMessage = "Failed to load one image.";
            else
                errorMessage = "Failed to load " + failures.size() + " images.";
            if (requestedBuilder != null)
                errorMessage += "\nThe image type might not be supported by '" + requestedBuilder.getName() + "'";

            var toRemove = failures.stream().filter(p -> project.getImageList().contains(p)).toList();
            project.removeAllImages(toRemove, true);
        } else if (maxWork == 0 && !items.isEmpty()) {
            // If we have items, but no images to add, then probably none of the items were supported images
            errorMessage = "Unable to add images - see log for more details";
        }
        if (errorMessage != null) {
            Dialogs.builder()
                    .error()
//						.owner(qupath.getStage())
                    .title("Import images")
                    .contentText(errorMessage)
                    .show();
        }

        // Now save changes
        project.syncChanges();
        completeProgress();
        return allAddedEntries;
    }

    /**
     * Get a collection of paths that failed to import.
     * @return
     */
    Collection<String> getFailedPaths() {
        return failedPaths;
    }

    private static boolean isQuPathProject(String path) {
        return path.toLowerCase().endsWith(ProjectIO.DEFAULT_PROJECT_EXTENSION);
    }

    private static boolean isQuPathDataFile(String path) {
        return path.toLowerCase().endsWith(".qpdata");
    }

    /**
     * Import images from existing data files.
     *
     * @param existingDataFiles
     */
    private void handleImportFromDataFiles(List<File> existingDataFiles) {
        // Don't parallelize this because it might require a lot of memory for large data files
        if (existingDataFiles.isEmpty())
            return;
        if (existingDataFiles.size() == 1)
            updateMessage("Importing 1 image from existing data file");
        else
            updateMessage("Importing " + existingDataFiles.size() + " images from existing data files");
        for (var file : existingDataFiles) {
            try {
                ImageData<BufferedImage> imageData = PathIO.readImageData(file);
                var entry = project.addImage(imageData.getServer().getBuilder());
                initializeEntry(entry, imageData.getImageType(), pyramidalize, importObjects);
                entry.saveImageData(imageData);
                incrementProgress();
                imageData.getServer().close();
            } catch (Exception e) {
                failedPaths.add(file.getAbsolutePath());
                logger.warn("Unable to read image data from file: {}", file, e);
            }
        }
    }

    private void incrementProgress() {
        updateProgress(counter.incrementAndGet(), maxWork);
    }

    private void completeProgress() {
        updateProgress(maxWork, maxWork);
    }

    /**
     * Import existing images from other projects.
     *
     * @param projectImages
     */
    private void handleImportFromProjects(List<ProjectImageEntry<BufferedImage>> projectImages) {
        if (projectImages.isEmpty())
            return;
        if (projectImages.size() == 1)
            updateMessage("Importing 1 image from existing projects");
        else
            updateMessage("Importing " + projectImages.size() + " images from existing projects");
        for (var temp : projectImages) {
            try {
                project.addDuplicate(temp, true);
            } catch (Exception e) {
                failures.add(temp);
            }
        }
    }

    /**
     * Get all the server builders associated with a path or URI.
     * This usually provides 1 builder, but might provide more if the URI contains multiple images.
     *
     * @param item
     * @return
     */
    private List<ImageServerBuilder.ServerBuilder<BufferedImage>> getServerBuilders(String item) {
        try {
            var uri = GeneralTools.toURI(item);
            ImageServerBuilder.UriImageSupport<BufferedImage> support;
            if (requestedBuilder == null) {
                support = ImageServers.getImageSupport(uri, args);
                if (support == null)
                    logger.warn("Unable to open {} with any reader", uri);
            } else {
                support = ImageServers.getImageSupport(requestedBuilder, uri, args);
                if (support == null)
                    logger.warn("Unable to open {} with {}", uri, requestedBuilder.getName());
            }
            if (support != null)
                return support.getBuilders();
        } catch (Exception e) {
            logger.error("Unable to add {}", item, e);
        }
        return Collections.emptyList();
    }


    private record ItemWithAllBuilders(String item, List<ImageServerBuilder.ServerBuilder<BufferedImage>> builders) {}


    /**
     * Add a single ImageServer to a project, without considering sub-images.
     * <p>
     * This includes an optional attempt to request a thumbnail; if this fails, the image will not be added.
     *
     * @param entry the entry that should be initialized
     * @param type the ImageType that should be set for each entry being added
     * @param pyramidalizeSingleResolution if true, attempt to pyramidalize single-resolution image servers
     * @param importObjects if true, read objects from the server - if available
     * @return
     * @throws Exception
     */
    static ProjectImageEntry<BufferedImage> initializeEntry(ProjectImageEntry<BufferedImage> entry, ImageData.ImageType type, boolean pyramidalizeSingleResolution, boolean importObjects) throws Exception {
        try (ImageServer<BufferedImage> server = entry.getServerBuilder().build()) {
            // Set the image name
            String name = ServerTools.getDisplayableImageName(server);
            entry.setImageName(name);
            // The thumbnail generation has been moved to ProjectBrowser to avoid overhead

            // Pyramidalize this if we need to
            @SuppressWarnings("resource")
            ImageServer<BufferedImage> server2 = server;
            int minPyramidDimension = PathPrefs.minPyramidDimensionProperty().get();
            if (pyramidalizeSingleResolution && server.nResolutions() == 1 && Math.max(server.getWidth(), server.getHeight()) > minPyramidDimension) {
                var serverTemp = ImageServers.pyramidalize(server);
                if (serverTemp.nResolutions() > 1) {
                    logger.debug("Auto-generating image pyramid for {}", name);
                    server2 = serverTemp;
                } else
                    serverTemp.close();
            }

            // Initialize an ImageData object with a type, if required
            Collection<PathObject> pathObjects = importObjects && server2 instanceof PathObjectReader ? ((PathObjectReader)server2).readPathObjects() : Collections.emptyList();
            if (type != null || server != server2 || !pathObjects.isEmpty()) {
                var imageData = new ImageData<>(server2, type);
                if (!pathObjects.isEmpty())
                    imageData.getHierarchy().addObjects(pathObjects);
                entry.saveImageData(imageData);
            }
            if (server != server2)
                server2.close();
        }
        return entry;
    }

}
