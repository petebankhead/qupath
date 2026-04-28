package qupath.lib.images.servers.bioformats;

import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.io.File;
import java.io.IOException;
import java.lang.ref.Cleaner;
import java.nio.ByteOrder;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import loci.formats.ClassList;
import loci.formats.DimensionSwapper;
import loci.formats.FormatException;
import loci.formats.FormatTools;
import loci.formats.IFormatReader;
import loci.formats.ImageReader;
import loci.formats.Memoizer;
import loci.formats.MetadataTools;
import loci.formats.ReaderWrapper;
import loci.formats.gui.AWTImageTools;
import loci.formats.in.DynamicMetadataOptions;
import loci.formats.in.MetadataOptions;
import loci.formats.in.ZarrReader;
import loci.formats.meta.DummyMetadata;
import loci.formats.meta.MetadataStore;
import loci.formats.ome.OMEPyramidStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.common.GeneralTools;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.TileRequest;

/**
 * Helper class that manages a pool of readers.
 * The purpose is to allow multiple threads to take the next available reader.
 */
class ReaderPool implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ReaderPool.class);

    /**
     * Define a maximum memoization file size above which parallelization is disabled.
     * This is necessary to avoid creating multiple readers that are too large (e.g. sometimes
     * a memoization file can be over 1GB...)
     */
    private static final long MAX_PARALLELIZATION_MEMO_SIZE = 1024L * 1024L * 16L;

    private static final Pattern ZARR_FILE_PATTERN = Pattern.compile("\\.zarr/?(\\d+/?)?$");

    private static final int DEFAULT_TIMEOUT_SECONDS = 60;

    /**
     * Absolute maximum number of permitted readers (queue capacity)
     */
    private static final int MAX_QUEUE_CAPACITY = 128;

    private static MemoUtils memoizationHelper = new MemoUtils();

    private String id;
    private BioFormatsServerOptions options;
    private BioFormatsArgs args;
    private ClassList<IFormatReader> classList;

    private volatile boolean isClosed = false;

    private AtomicInteger totalReaders = new AtomicInteger(0);
    private List<IFormatReader> additionalReaders = Collections.synchronizedList(new ArrayList<>());
    private ArrayBlockingQueue<IFormatReader> queue;

    private OMEPyramidStore metadata;
    private IFormatReader mainReader;

    private ForkJoinTask<?> task;

    private int timeoutSeconds;

    // This may be reused by OMERO extension? Not sure, but need to change cautiously...
    ReaderPool(BioFormatsServerOptions options, String id, BioFormatsArgs args) throws FormatException, IOException {
        this.id = id;
        this.options = options;
        this.args = args;

        queue = new ArrayBlockingQueue<>(MAX_QUEUE_CAPACITY); // Set a reasonably large capacity (don't want to block when trying to add)
        metadata = (OMEPyramidStore) MetadataTools.createOMEXMLMetadata();

        timeoutSeconds = getTimeoutSeconds();

        // Create the main reader
        long startTime = System.currentTimeMillis();
        mainReader = createReader(options, null, id, metadata, args);

        long endTime = System.currentTimeMillis();
        logger.debug("Reader {} created in {} ms", mainReader, endTime - startTime);

        // Make the main reader available
        queue.add(mainReader);

        // Store the class so we don't need to go hunting later
        classList = unwrapClasslist(mainReader);
    }

    OMEPyramidStore getMetadata() {
        return metadata;
    }

    /**
     * Make the timeout adjustable.
     * See https://github.com/qupath/qupath/issues/1265
     *
     * @return
     */
    private int getTimeoutSeconds() {
        String timeoutString = System.getProperty("bioformats.readerpool.timeout", null);
        if (timeoutString != null) {
            try {
                return Integer.parseInt(timeoutString);
            } catch (NumberFormatException e) {
                logger.warn("Unable to parse timeout value: {}", timeoutString, e);
            }
        }
        return DEFAULT_TIMEOUT_SECONDS;
    }

    IFormatReader getMainReader() {
        return mainReader;
    }

    private void createAdditionalReader(BioFormatsServerOptions options, final ClassList<IFormatReader> classList,
                                        final String id, BioFormatsArgs args) {
        try {
            if (isClosed)
                return;
            logger.debug("Requesting new reader for thread {}", Thread.currentThread());
            var newReader = createReader(options, classList, id, null, args);
            if (newReader != null) {
                additionalReaders.add(newReader);
                queue.add(newReader);
                logger.debug("Created new reader (total={})", additionalReaders.size());
            } else
                logger.warn("New Bio-Formats reader could not be created (returned null)");
        } catch (Exception e) {
            logger.error("Error creating additional readers: " + e.getLocalizedMessage(), e);
        }
    }


    private int getMaxReaders() {
        int max = options == null ? Runtime.getRuntime().availableProcessors() : options.getMaxReaders();
        return Math.min(MAX_QUEUE_CAPACITY, Math.max(1, max));
    }


    /**
     * Create a new {@code IFormatReader}, with memoization if necessary.
     *
     * @param options   options used to control the reader generation
     * @param classList optionally specify a list of potential reader classes, if known (to avoid a more lengthy search)
     * @param id        file path for the image.
     * @param store     optional MetadataStore; this will be set in the reader if needed. If it is unspecified, a dummy store will be created a minimal metadata requested.
     * @param args      optional args to customize reading
     * @return the {@code IFormatReader}
     * @throws FormatException
     * @throws IOException
     */
    @SuppressWarnings("resource")
    private IFormatReader createReader(final BioFormatsServerOptions options, final ClassList<IFormatReader> classList,
                                       final String id, final MetadataStore store, BioFormatsArgs args) throws FormatException, IOException {

        int maxReaders = getMaxReaders();
        int nReaders = totalReaders.getAndIncrement();
        if (mainReader != null && nReaders > maxReaders) {
            logger.warn("No new reader will be created (already created {}, max readers {})", nReaders, maxReaders);
            totalReaders.decrementAndGet();
            return null;
        }

        IFormatReader imageReader;
        Matcher zarrMatcher = ZARR_FILE_PATTERN.matcher(id.toLowerCase());
        if (new File(id).isDirectory() || zarrMatcher.find()) {
            // Using new ImageReader() on a directory won't work
            imageReader = new ZarrReader();
            if (id.startsWith("https") && imageReader.getMetadataOptions() instanceof DynamicMetadataOptions zarrOptions) {
                zarrOptions.set("omezarr.alt_store", id);
            }
        } else {
            if (classList != null) {
                imageReader = new ImageReader(classList);
            } else {
                imageReader = new ImageReader();
            }
        }

        imageReader.setFlattenedResolutions(false);

        // Try to set any reader options that we have
        MetadataOptions metadataOptions = imageReader.getMetadataOptions();
        var readerOptions = args.readerOptions;
        if (!readerOptions.isEmpty() && metadataOptions instanceof DynamicMetadataOptions) {
            for (var option : readerOptions.entrySet()) {
                ((DynamicMetadataOptions) metadataOptions).set(option.getKey(), option.getValue());
            }
        }

        // TODO: Warning! Memoization does not play nicely with options like
        // --bfOptions zeissczi.autostitch=false
        // in a way that options don't have an effect unless QuPath is restarted.
        Memoizer memoizer = null;
        int memoizationTimeMillis = options.getMemoizationTimeMillis();
        File dir = null;
        File fileMemo = null;
        boolean useTempMemoDirectory = false;
        // Check if we want to (and can) use memoization
        if (BioFormatsServerOptions.allowMemoization() && memoizationTimeMillis >= 0) {
            // Try to use a specified directory
            String pathMemoization = options.getPathMemoization();
            if (pathMemoization != null && !pathMemoization.trim().isEmpty()) {
                dir = new File(pathMemoization);
                if (!dir.isDirectory()) {
                    logger.warn("Memoization path does not refer to a valid directory, will be ignored: {}", dir.getAbsolutePath());
                    dir = null;
                }
            }
            if (dir == null) {
                dir = createTempMemoDir();
                useTempMemoDirectory = dir != null;
            }
            if (dir != null) {
                try {
                    memoizer = new Memoizer(imageReader, memoizationTimeMillis, dir);
                    fileMemo = memoizer.getMemoFile(id);
                    // The call to .toPath() should throw an InvalidPathException if there are illegal characters
                    // If so, we want to know that now before committing to the memoizer
                    if (fileMemo != null && fileMemo.toPath() != null)
                        imageReader = memoizer;
                } catch (Exception e) {
                    logger.warn("Unable to use memoization: {}", e.getLocalizedMessage());
                    logger.debug(e.getLocalizedMessage(), e);
                    fileMemo = null;
                    memoizer = null;
                }
            }
        }


        if (store != null)
            imageReader.setMetadataStore(store);
        else {
            imageReader.setMetadataStore(new DummyMetadata());
            imageReader.setOriginalMetadataPopulated(false);
        }

        var swapDimensions = args.getSwapDimensions();
        if (swapDimensions != null)
            logger.debug("Creating DimensionSwapper for {}", swapDimensions);


        if (id != null) {
            if (fileMemo != null) {
                // If we're using a temporary directory, delete the memo file when app closes
                if (useTempMemoDirectory)
                    tempMemoFiles.add(fileMemo);

                long memoizationFileSize = fileMemo == null ? 0L : fileMemo.length();
                boolean memoFileExists = fileMemo != null && fileMemo.exists();
                try {
                    if (swapDimensions != null)
                        imageReader = DimensionSwapper.makeDimensionSwapper(imageReader);
                    imageReader.setId(id);
                } catch (Exception e) {
                    if (memoFileExists) {
                        logger.warn("Problem with memoization file {} ({}), will try to delete it", fileMemo.getName(), e.getLocalizedMessage());
                        fileMemo.delete();
                    }
                    imageReader.close();
                    if (swapDimensions != null)
                        imageReader = DimensionSwapper.makeDimensionSwapper(imageReader);
                    imageReader.setId(id);
                }
                memoizationFileSize = fileMemo == null ? 0L : fileMemo.length();
                if (memoizationFileSize > 0L) {
                    if (memoizationFileSize > MAX_PARALLELIZATION_MEMO_SIZE) {
                        logger.warn(String.format("The memoization file is very large (%.1f MB) - parallelization may be turned off to save memory",
                                memoizationFileSize / (1024.0 * 1024.0)));
                    }
                    memoizationSizeMap.put(id, memoizationFileSize);
                }
                if (memoizationFileSize == 0L)
                    logger.debug("No memoization file generated for {}", id);
                else if (!memoFileExists)
                    logger.debug(String.format("Generating memoization file %s (%.2f MB)", fileMemo.getAbsolutePath(), memoizationFileSize / 1024.0 / 1024.0));
                else
                    logger.debug("Memoization file exists at {}", fileMemo.getAbsolutePath());
            } else {
                if (swapDimensions != null)
                    imageReader = DimensionSwapper.makeDimensionSwapper(imageReader);
                imageReader.setId(id);
            }
        }

        if (swapDimensions != null) {
            // The series needs to be set before swapping dimensions
            if (args.series >= 0)
                imageReader.setSeries(args.series);
            ((DimensionSwapper) imageReader).swapDimensions(swapDimensions);
        }

        if (isClosed) {
            imageReader.close(false);
            return null;
        } else {
            cleanables.add(cleaner.register(this,
                    new ReaderCleaner(Integer.toString(cleanables.size() + 1), imageReader)));
        }

        return imageReader;
    }


    private IFormatReader nextQueuedReader() {
        var nextReader = queue.poll();
        if (nextReader != null) {
            return nextReader;
        }
        synchronized (this) {
            if (!isClosed && (task == null || task.isDone()) && totalReaders.get() < getMaxReaders()) {
                logger.debug("Requesting reader for {}", id);
                task = ForkJoinPool.commonPool().submit(() -> createAdditionalReader(options, classList, id, args));
            }
        }
        if (isClosed)
            return null;
        try {
            var reader = queue.poll(timeoutSeconds, TimeUnit.SECONDS);
            // See https://github.com/qupath/qupath/issues/1265
            if (reader == null) {
                logger.warn("Bio-Formats reader request timed out after {} seconds - returning main reader", timeoutSeconds);
                return mainReader;
            } else
                return reader;
        } catch (InterruptedException e) {
            logger.warn("Interrupted exception when awaiting next queued reader: {}", e.getLocalizedMessage());
            return isClosed ? null : mainReader;
        }
    }

    private void ensureOpen(IFormatReader reader) throws IOException, FormatException {
        if (!id.equals(reader.getCurrentFile())) {
            reader.close();
            reader.setFlattenedResolutions(false);
            reader.setId(id);
        }
    }


    BufferedImage openImage(TileRequest tileRequest, int series, int nChannels, boolean isRGB, ColorModel colorModel) throws IOException, InterruptedException {
        int level = tileRequest.getLevel();
        int tileX = tileRequest.getTileX();
        int tileY = tileRequest.getTileY();
        int tileWidth = tileRequest.getTileWidth();
        int tileHeight = tileRequest.getTileHeight();
        int z = tileRequest.getZ();
        int t = tileRequest.getT();

        byte[][] bytes;
        int effectiveC;
        ByteOrder order;
        boolean interleaved;
        int pixelType;
        boolean normalizeFloats;
        int[] samplesPerPixel;


        IFormatReader ipReader = null;
        try {
            ipReader = nextQueuedReader();
            if (ipReader == null) {
                throw new IOException("Reader is null - was the image already closed? " + id);
            }

            // Check if this is non-zero
            if (tileWidth <= 0 || tileHeight <= 0) {
                throw new IOException("Unable to request pixels for region with downsampled size " + tileWidth + " x " + tileHeight);
            }

            synchronized (ipReader) {
                ensureOpen(ipReader);
                ipReader.setSeries(series);

                // Some files provide z scaling (the number of z stacks decreases when the resolution becomes
                // lower, like the width and height), so z needs to be updated for levels > 0
                if (level > 0 && z > 0) {
                    ipReader.setResolution(0);
                    int zStacksFullResolution = ipReader.getSizeZ();
                    ipReader.setResolution(level);
                    int zStacksCurrentResolution = ipReader.getSizeZ();

                    if (zStacksFullResolution != zStacksCurrentResolution) {
                        z = (int) (z * zStacksCurrentResolution / (float) zStacksFullResolution);
                    }


                } else {
                    ipReader.setResolution(level);
                }

                order = ipReader.isLittleEndian() ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
                interleaved = ipReader.isInterleaved();
                pixelType = ipReader.getPixelType();
                normalizeFloats = ipReader.isNormalized();
                samplesPerPixel = IntStream.range(0, metadata.getChannelCount(series))
                        .map(channel -> metadata.getChannelSamplesPerPixel(series, channel).getValue())
                        .toArray();

                // Single-channel & RGB images are straightforward... nothing more to do
                if ((ipReader.isRGB() && isRGB) || nChannels == 1) {
                    // Read the image - or at least the first channel
                    int ind = ipReader.getIndex(z, 0, t);
                    try {
                        byte[] bytesSimple = ipReader.openBytes(ind, tileX, tileY, tileWidth, tileHeight);
                        return AWTImageTools.openImage(bytesSimple, ipReader, tileWidth, tileHeight);
                    } catch (Exception | UnsatisfiedLinkError e) {
                        logger.warn("Unable to open image {} for {}", ind, tileRequest.getRegionRequest());
                        throw convertToIOException(e);
                    }
                }
                // Read bytes for all the required channels
                effectiveC = ipReader.getEffectiveSizeC();
                bytes = new byte[effectiveC][];
                try {
                    for (int c = 0; c < effectiveC; c++) {
                        int ind = ipReader.getIndex(z, c, t);
                        bytes[c] = ipReader.openBytes(ind, tileX, tileY, tileWidth, tileHeight);
                    }
                } catch (ClosedChannelException e) {
                    // This occurs when a request is interrupted
                    logger.warn("Closed channel exception, closing reader");
                    ipReader.close(false);
                    throw e;
                } catch (Exception | UnsatisfiedLinkError e) {
                    throw convertToIOException(e);
                }
            }
        } catch (FormatException e) {
            logger.debug("Unable to open reader: {}", e.getMessage(), e);
            throw new IOException(e);
        } finally {
            if (Thread.interrupted()) {
                logger.debug("Thread interrupted, flag will be reset: {}", Thread.currentThread());
            }
            if (ipReader != null)
                queue.put(ipReader);
        }

        OMEPixelParser omePixelParser = new OMEPixelParser.Builder()
                .isInterleaved(interleaved)
                .pixelType(switch (pixelType) {
                    case FormatTools.UINT8 -> PixelType.UINT8;
                    case FormatTools.INT8 -> PixelType.INT8;
                    case FormatTools.UINT16 -> PixelType.UINT16;
                    case FormatTools.INT16 -> PixelType.INT16;
                    case FormatTools.UINT32 -> PixelType.UINT32;
                    case FormatTools.INT32 -> PixelType.INT32;
                    case FormatTools.FLOAT -> PixelType.FLOAT32;
                    case FormatTools.DOUBLE -> PixelType.FLOAT64;
                    default -> throw new IllegalStateException("Unexpected value: " + pixelType);
                })
                .byteOrder(order)
                .normalizeFloats(normalizeFloats)
                .effectiveNChannels(effectiveC)
                .samplesPerPixel(samplesPerPixel)
                .build();

        return omePixelParser.parse(bytes, tileWidth, tileHeight, nChannels, colorModel);
    }

    /**
     * Ensure a throwable is an IOException.
     * This gives the opportunity to include more human-readable messages for common errors.
     *
     * @param t
     * @return
     */
    static IOException convertToIOException(Throwable t) {
        if (GeneralTools.isMac()) {
            String message = t.getMessage();
            if (message != null) {
                if (message.contains("ome.jxrlib.JXRJNI")) {
                    return new IOException("Bio-Formats does not support JPEG-XR on Apple Silicon: " + t.getMessage(), t);
                }
                if (message.contains("org.libjpegturbo.turbojpeg.TJDecompressor")) {
                    return new IOException("Bio-Formats does not currently support libjpeg-turbo on Apple Silicon", t);
                }
            }
        }
        if (t instanceof IOException e)
            return e;
        return new IOException(t);
    }


    public BufferedImage openSeries(int series) throws InterruptedException, FormatException, IOException {
        IFormatReader reader = null;
        try {
            reader = nextQueuedReader();
            synchronized (reader) {
                int previousSeries = reader.getSeries();
                try {
                    reader.setSeries(series);
                    int nResolutions = reader.getResolutionCount();
                    if (nResolutions > 0) {
                        reader.setResolution(0);
                    }
                    // TODO: Handle color transforms here, or in the display of labels/macro images - in case this isn't RGB
                    byte[] bytesSimple = reader.openBytes(reader.getIndex(0, 0, 0));
                    return AWTImageTools.openImage(bytesSimple, reader, reader.getSizeX(), reader.getSizeY());
                } finally {
                    reader.setSeries(previousSeries);
                }
            }
        } finally {
            queue.put(reader);
        }
    }


    private static ClassList<IFormatReader> unwrapClasslist(IFormatReader reader) {
        while (true) {
            IFormatReader nextReader = null;
            if (reader instanceof ReaderWrapper wrapper)
                nextReader = wrapper.getReader();
            else if (reader instanceof ImageReader imageReader)
                nextReader = imageReader.getReader();
            if (nextReader == null)
                break;
            else
                reader = nextReader;
        }
        var classlist = new ClassList<>(IFormatReader.class);
        classlist.addClass(reader.getClass());
        return classlist;
    }


    @Override
    public void close() throws Exception {
        logger.debug("Closing ReaderManager");
        isClosed = true;
        if (task != null && !task.isDone())
            task.cancel(true);
        for (var c : cleanables) {
            try {
                c.clean();
            } catch (Exception e) {
                logger.error("Exception during cleanup: {}", e.getMessage(), e);
            }
        }
    }


    private static final Cleaner cleaner = Cleaner.create();
    private final List<Cleaner.Cleanable> cleanables = new ArrayList<>();


    /**
     * Map of memoization file sizes.
     */
    private static final Map<String, Long> memoizationSizeMap = new ConcurrentHashMap<>();

    /**
     * Temporary directory for storing memoization files
     */
    private static File dirMemoTemp = null;

    /**
     * Set of created temp memo files
     */
    private static final Set<File> tempMemoFiles = new HashSet<>();


    /**
     * Request the file size of any known memoization file for a specific ID.
     *
     * @param id
     * @return
     */
    public long getMemoizationFileSize(String id) {
        return memoizationSizeMap.getOrDefault(id, 0L);
    }

    /**
     * Get a temporary directory to use for memoization, creating it if it does not already exist.
     * @return a temporary directory
     * @throws IOException if the directory could not be created
     */
    static File createTempMemoDir() throws IOException {
        return getTempMemoDir(true);
    }

    private static File getTempMemoDir(boolean create) throws IOException {
        if (create && dirMemoTemp == null) {
            synchronized (ReaderPool.class) {
                if (dirMemoTemp == null) {
                    Path path = Files.createTempDirectory("qupath-memo-");
                    dirMemoTemp = path.toFile();
                    Runtime.getRuntime().addShutdownHook(new Thread() {
                        @Override
                        public void run() {
                            deleteTempMemoFiles();
                        }
                    });
                    logger.warn("Temp memoization directory created at {}", dirMemoTemp);
                    logger.warn("If you want to avoid this warning, either specify a memoization directory in the preferences or turn off memoization by setting the time to < 0");
                }
            }
        }
        return dirMemoTemp;
    }

    /**
     * Delete any memoization files registered as being temporary, and also the
     * temporary memoization directory (if it exists).
     * Note that this acts both recursively and rather conservatively, stopping if a file is
     * encountered that is not expected.
     */
    private static void deleteTempMemoFiles() {
        for (File f : tempMemoFiles) {
            // Be extra-careful not to delete too much...
            if (!f.exists())
                continue;
            if (!f.isFile() || !f.getName().endsWith(".bfmemo")) {
                logger.warn("Unexpected memoization file, will not delete {}", f.getAbsolutePath());
                return;
            }
            if (f.delete())
                logger.debug("Deleted temp memoization file {}", f.getAbsolutePath());
            else
                logger.warn("Could not delete temp memoization file {}", f.getAbsolutePath());
        }
        if (dirMemoTemp == null)
            return;
        deleteEmptyDirectories(dirMemoTemp);
    }

    /**
     * Delete a directory and all sub-directories, assuming each contains only empty directories.
     * This is applied recursively, stopping at the first failure (i.e. any directory containing files).
     *
     * @param dir
     * @return true if the directory could be deleted, false otherwise
     */
    private static boolean deleteEmptyDirectories(File dir) {
        if (!dir.isDirectory())
            return false;
        int nFiles = 0;
        var files = dir.listFiles();
        if (files == null) {
            logger.debug("Unable to list files for {}", dir);
            return false;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                if (!deleteEmptyDirectories(f))
                    return false;
            } else if (f.isFile())
                nFiles++;
        }
        if (nFiles == 0) {
            if (dir.delete()) {
                logger.debug("Deleting empty memoization directory {}", dir.getAbsolutePath());
                return true;
            } else {
                logger.warn("Could not delete temp memoization directory {}", dir.getAbsolutePath());
                return false;
            }
        } else {
            logger.warn("Temp memoization directory contains files, will not delete {}", dir.getAbsolutePath());
            return false;
        }
    }

}
