/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * Copyright (C) 2018 - 2020 QuPath developers, The University of Edinburgh
 * %%
 * QuPath is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * QuPath is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License 
 * along with QuPath.  If not, see <https://www.gnu.org/licenses/>.
 * #L%
 */

package qupath.lib.gui.commands;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Scanner;
import java.util.stream.Collectors;

import org.controlsfx.dialog.ProgressDialog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.beans.property.BooleanProperty;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.input.Clipboard;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import qupath.fx.utils.FXUtils;
import qupath.fx.dialogs.FileChoosers;
import qupath.lib.common.GeneralTools;
import qupath.lib.display.ImageDisplay;
import qupath.lib.gui.QuPathGUI;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.panes.ProjectBrowser;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.fx.utils.GridPaneUtils;
import qupath.lib.gui.tools.GuiTools;
import qupath.lib.images.ImageData.ImageType;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerBuilder;
import qupath.lib.images.servers.ImageServerProvider;
import qupath.lib.images.servers.RotatedImageServer.Rotation;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Command to import image paths into an existing project.
 * 
 * @author Pete Bankhead
 */
class ProjectImportImagesCommand {
	
	private static final Logger logger = LoggerFactory.getLogger(ProjectImportImagesCommand.class);
		
	private static final String commandName = "Import images";
	
	private static final BooleanProperty pyramidalizeProperty = PathPrefs.createPersistentPreference("projectImportPyramidalize", true);
	private static final BooleanProperty importObjectsProperty = PathPrefs.createPersistentPreference("projectImportObjects", false);
	private static final BooleanProperty showImageSelectorProperty = PathPrefs.createPersistentPreference("showImageSelectorProperty", false);
	
	/**
	 * Prompt to import images to the current project.
	 *
	 * @param qupath QuPath instance, used to access the current project and stage
	 * @param builder if not null, this will be used to create the servers. If null, a combobox will be shown to choose an installed builder.
	 * @param defaultPaths URIs to use to prepopulate the list
	 * @return
	 */
	static List<ProjectImageEntry<BufferedImage>> promptToImportImages(QuPathGUI qupath, ImageServerBuilder<BufferedImage> builder, String... defaultPaths) {
		// TODO: I can only apologise for this code... it is dreadful
		var project = qupath.getProject();
		if (project == null) {
			GuiTools.showNoProjectError(commandName);
			return Collections.emptyList();
		}
		
		ListView<String> listView = new ListView<>();
		listView.setPrefWidth(480);
		listView.setMinHeight(100);
		listView.getItems().addAll(defaultPaths);
		listView.setPlaceholder(new Label("Drag & drop image or project files for import, \nor choose from the options below"));
		
		Button btnFile = new Button("Choose files");
		btnFile.setOnAction(e -> loadFromFileChooser(listView.getItems()));

		Button btnURL = new Button("Input URL");
		btnURL.setOnAction(e -> loadFromSingleURL(listView.getItems()));

		Button btnClipboard = new Button("From clipboard");
		btnClipboard.setOnAction(e -> loadFromClipboard(listView.getItems()));
		
		Button btnFileList = new Button("From path list");
		btnFileList.setOnAction(e -> loadFromTextFile(listView.getItems()));
		
		TitledPane paneList = new TitledPane("Image paths", listView);
		paneList.setCollapsible(false);
		

		BorderPane paneImages = new BorderPane();

		
		boolean requestBuilder = builder == null;
		ComboBox<ImageServerBuilder<BufferedImage>> comboBuilder = new ComboBox<>();
		Label labelBuilder = new Label("Image server");
		if (requestBuilder) {
			comboBuilder.setCellFactory(c -> FXUtils.createCustomListCell(ProjectImportImagesCommand::builderToString));
			comboBuilder.setButtonCell(FXUtils.createCustomListCell(ProjectImportImagesCommand::builderToString));
			List<ImageServerBuilder<BufferedImage>> availableBuilders = new ArrayList<>(ImageServerProvider.getInstalledImageServerBuilders(BufferedImage.class));
			if (!availableBuilders.contains(null))
				availableBuilders.addFirst(null);
			comboBuilder.getItems().setAll(availableBuilders);
			comboBuilder.getSelectionModel().selectFirst();
			labelBuilder.setLabelFor(comboBuilder);
			labelBuilder.setMinWidth(Label.USE_PREF_SIZE);
		}
		
		ComboBox<ImageType> comboType = new ComboBox<>();
		comboType.getItems().setAll(ImageType.values());
		Label labelType = new Label("Set image type");
		labelType.setLabelFor(comboType);
		labelType.setMinWidth(Label.USE_PREF_SIZE);
				
		ComboBox<Rotation> comboRotate = new ComboBox<>();
		comboRotate.getItems().setAll(Rotation.values());
		Label labelRotate = new Label("Rotate image");
		labelRotate.setLabelFor(comboRotate);
		labelRotate.setMinWidth(Label.USE_PREF_SIZE);
		
		TextField tfArgs = new TextField();
		Label labelArgs = new Label("Optional args");
		labelArgs.setLabelFor(tfArgs);
		labelArgs.setMinWidth(Label.USE_PREF_SIZE);
		
		CheckBox cbPyramidalize = new CheckBox("Auto-generate pyramids");
		cbPyramidalize.setSelected(pyramidalizeProperty.get());
		
		CheckBox cbImportObjects = new CheckBox("Import objects");
		cbImportObjects.setSelected(importObjectsProperty.get());
		
		CheckBox cbImageSelector = new CheckBox("Show image selector");
		cbImageSelector.setSelected(showImageSelectorProperty.get());

		GridPaneUtils.setMaxWidth(Double.MAX_VALUE, comboBuilder, comboType, comboRotate, cbPyramidalize, cbImportObjects, tfArgs, cbImageSelector);
		GridPaneUtils.setFillWidth(Boolean.TRUE, comboBuilder, comboType, comboRotate, cbPyramidalize, cbImportObjects, tfArgs, cbImageSelector);
		GridPaneUtils.setHGrowPriority(Priority.ALWAYS, comboBuilder, comboType, comboRotate, cbPyramidalize, cbImportObjects, tfArgs, cbImageSelector);
		
		GridPane paneType = new GridPane();
		paneType.setPadding(new Insets(5));
		paneType.setHgap(5);
		paneType.setVgap(5);
		int row = 0;
		if (requestBuilder)
			GridPaneUtils.addGridRow(paneType, row++, 0, "Specify the library used to open images", labelBuilder, comboBuilder);
		GridPaneUtils.addGridRow(paneType, row++, 0, "Specify the default image type for all images being imported (required for analysis, can be changed later under the 'Image' tab)", labelType, comboType);
		GridPaneUtils.addGridRow(paneType, row++, 0, "Optionally rotate images on import", labelRotate, comboRotate);
		GridPaneUtils.addGridRow(paneType, row++, 0, "Optionally pass reader-specific arguments to the image provider.\nUsually this should just be left empty.", labelArgs, tfArgs);
		GridPaneUtils.addGridRow(paneType, row++, 0, "Dynamically create image pyramids for large, single-resolution images", cbPyramidalize, cbPyramidalize);
		GridPaneUtils.addGridRow(paneType, row++, 0, "Read and import objects (e.g. annotations) from the image file, if possible", cbImportObjects, cbImportObjects);
		GridPaneUtils.addGridRow(paneType, row++, 0, "Show the 'Image selector' window whenever the same URI contains multiple images.\n"
				+ "If this is turned off, then all images will be import.", cbImageSelector, cbImageSelector);

		paneImages.setCenter(paneList);
		paneImages.setBottom(paneType);
		
		GridPane paneButtons = GridPaneUtils.createColumnGridControls(btnFile, btnURL, btnClipboard, btnFileList);
		paneButtons.setHgap(5);
		paneButtons.setPadding(new Insets(5));

		BorderPane pane = new BorderPane();
		pane.setCenter(paneImages);
		pane.setBottom(paneButtons);

		// Support drag & drop for files
		pane.setOnDragOver(e -> {
			e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });
		pane.setOnDragDropped(e -> {
			Dragboard dragboard = e.getDragboard();
			if (dragboard.hasFiles()) {
		        logger.trace("Files dragged onto project import dialog");
				try {
					var paths = dragboard.getFiles()
							.stream()
							.filter(f -> f.isFile() && !f.isHidden())
							.map(File::getAbsolutePath)
							.collect(Collectors.toCollection(ArrayList::new));
					paths.removeAll(listView.getItems());
					if (!paths.isEmpty())
						listView.getItems().addAll(paths);
				} catch (Exception ex) {
					Dialogs.showErrorMessage("Drag & Drop", ex);
					logger.error(ex.getMessage(), ex);
				}
			}
			e.setDropCompleted(true);
			e.consume();
        });



		Dialog<ButtonType> dialog = new Dialog<>();
		dialog.setResizable(true);
		dialog.initOwner(qupath.getStage());
		dialog.setTitle("Import images to project");
		ButtonType typeImport = new ButtonType("Import", ButtonData.OK_DONE);
		dialog.getDialogPane().getButtonTypes().addAll(typeImport, ButtonType.CANCEL);
		ScrollPane scroll = new ScrollPane(pane);
		scroll.setFitToHeight(true);
		scroll.setFitToWidth(true);
		dialog.getDialogPane().setContent(scroll);

		Optional<ButtonType> result = dialog.showAndWait();
		if (result.isEmpty() || result.get() != typeImport)
			return Collections.emptyList();

		ImageType type = comboType.getValue();
		Rotation rotation = comboRotate.getValue();
		boolean pyramidalize = cbPyramidalize.isSelected();
		boolean importObjects = cbImportObjects.isSelected();
		boolean showSelector = cbImageSelector.isSelected();
		pyramidalizeProperty.set(pyramidalize);
		importObjectsProperty.set(importObjects);
		showImageSelectorProperty.set(showSelector);

		ImageServerBuilder<BufferedImage> requestedBuilder = requestBuilder ? comboBuilder.getSelectionModel().getSelectedItem() : builder;

		// TODO: Use a smarter approach to splitting! Currently we support so few arguments that splitting on spaces should be ok... for now.
		var argsList = new ArrayList<>(parseArgsString(tfArgs.getText()));
		if (rotation != null && rotation != Rotation.ROTATE_NONE) {
			argsList.add("--rotate");
			argsList.add(rotation.toString());
		}
		if (!argsList.isEmpty())
			logger.debug("Args: [{}]", String.join(", ", argsList));
		String[] args = argsList.toArray(String[]::new);

		var worker = new ProjectImageImportTask(
				project, requestedBuilder, showSelector, pyramidalize, importObjects, type, listView.getItems(), args
		);
		ProgressDialog progress = new ProgressDialog(worker);
		progress.setTitle("Project import");
		qupath.getThreadPoolManager().submitShortTask(worker);
		progress.showAndWait();
		try {
			project.syncChanges();
		} catch (IOException e1) {
			Dialogs.showErrorMessage("Sync project", e1);
			logger.error(e1.getMessage(), e1);
		}
		qupath.refreshProject();

		Collection<String> pathFailed = worker.getFailedPaths();
		// Inform the user of any paths that didn't work
		if (!pathFailed.isEmpty()) {
			StringBuilder sb = new StringBuilder();
			sb.append("Unable to import ").append(pathFailed.size()).append(" paths:\n");
			for (String path : pathFailed)
				sb.append("\t").append(path).append("\n");
			sb.append("\n");

			logger.warn(sb.toString());

			TextArea textArea = new TextArea();
			textArea.setText(sb.toString());
			Dialogs.builder()
					.resizable()
					.error()
					.title(commandName)
					.headerText("Some images could not be imported")
					.content(textArea)
					.showAndWait();
		}

		try {
			return worker.get();
		} catch (Exception e) {
			logger.error("Exception importing project entries", e);
			return Collections.emptyList();
		}
	}

	/**
	 * Parse a string of arguments (e.g. from a textfield) into a list of strings.
	 * @param text
	 * @return
	 */
	private static List<String> parseArgsString(String text) {
		if (text == null || text.isBlank())
			return Collections.emptyList();
		// TODO: Splitting on spaces - consider if escaping is needed
		return Arrays.stream(text.split(" "))
				.filter(s -> !s.isBlank())
				.collect(Collectors.toList());
	}


	/**
	 * Convert the builder to a string to displaying to the user.
	 * @param item
	 * @return
	 */
	private static String builderToString(ImageServerBuilder<BufferedImage> item) {
		if (item == null)
			return "Default (let QuPath decide)";
		// Make the name a little more readable, without adding confusing
		String name = item.getName();
		if (name.toLowerCase().endsWith("builder"))
			return name.substring(0, name.length()-"builder".length()).strip();
		else
			return name;
	}
	
	
	public static ProjectImageEntry<BufferedImage> addSingleImageToProject(Project<BufferedImage> project, ImageServer<BufferedImage> server, ImageType type) {
		try {
			var entry = project.addImage(server.getBuilder());
			ProjectImageImportTask.initializeEntry(entry, type, false, false);
			return entry;
		} catch (Exception e) {
			return null;
		}
	}
	
	
	static boolean loadFromFileChooser(final List<String> list) {
		List<File> files = FileChoosers.promptForMultipleFiles(commandName);
		if (files == null)
			return false;
		boolean changes = false;
		for (File fileNew : files) {
			if (list.contains(fileNew.getAbsolutePath())) {
				Dialogs.showErrorMessage(commandName, "List already contains " + fileNew.getName());
				continue;
			}
			list.add(fileNew.getAbsolutePath());
			changes = true;
		}
		return changes;
	}
	
	
	static boolean loadFromSingleURL(final List<String> list) {
		String path = FileChoosers.promptForFilePathOrURI("Choose image path", "");
		if (path == null)
			return false;
		if (list.contains(path)) {
			Dialogs.showErrorMessage(commandName, "List already contains " + path);
			return false;
		}
		list.add(path);
		return true;
	}
	

	static int loadFromTextFile(final List<String> list) {
		File file = FileChoosers.promptForFile(commandName,
				FileChoosers.createExtensionFilter("Text file", "*.txt", "*.csv"));
		if (file == null)
			return 0;
		if (file.length() / 1024 / 1024 > 5) {
			Dialogs.showErrorMessage(commandName, String.format("%s is too large (%.2f MB) - \n"
					+ "please choose a text file containing only file paths or select another import option", file.getName(), file.length() / 1024.0 / 1024.0));
			return 0;
		}
		return loadFromTextFile(file, list);
	}
	
	
	static int loadFromTextFile(final File file, final List<String> list) {
		Scanner scanner = null;
		int changes = 0;
		try {
			scanner = new Scanner(file);
			while (scanner.hasNextLine()) {
				String s = scanner.nextLine().trim();
				if (isPossiblePath(s) && !list.contains(s)) {
					list.add(s);
					changes++;
				} else
					logger.warn("Cannot find image for path {}", s.trim());
			}
		} catch (FileNotFoundException e) {
			Dialogs.showErrorMessage(commandName, "File " + file.getName() + " not found!");
			return 0;
		} finally {
			if (scanner != null)
				scanner.close();
		}
		return changes;
	}
	
	
	/**
	 * Load potential image paths into a list.
	 * 
	 * @param list
	 * @return 
	 */
	static int loadFromClipboard(final List<String> list) {
		int changes = 0;
		List<File> clipboardFiles = Clipboard.getSystemClipboard().getFiles();
		if (clipboardFiles != null) {
			for (File f : clipboardFiles) {
				if (f.isFile() || !list.contains(f.getAbsolutePath())) {
					list.add(f.getAbsolutePath());
					changes++;
				}
			}
		}
		if (changes > 0)
			return changes;
		
		String clipboardString = Clipboard.getSystemClipboard().getString();
		List<String> possiblePaths = new ArrayList<>();
		if (clipboardString != null) {
			for (String s : GeneralTools.splitLines(clipboardString)) {
				if (isPossiblePath(s.trim()))
					possiblePaths.add(s.trim());
				else
					logger.warn("Cannot find image for path {}", s.trim());
			}
		}
		if (possiblePaths.isEmpty()) {
			Dialogs.showErrorMessage(commandName, "Could not find any valid paths on the clipboard!");
			return 0;
		}
		possiblePaths.removeAll(list);
		list.addAll(possiblePaths);
		return possiblePaths.size();
	}
	
	/**
	 * Checks is a path relates to an existing file, or a URI with a different scheme.
	 * @param path
	 * @return
	 */
	static boolean isPossiblePath(final String path) {
		try {
			URI uri;
			if (path.toLowerCase().startsWith("http") || path.toLowerCase().startsWith("file"))
				uri = GeneralTools.toEncodedURI(path);
			else
				uri = new File(path).toURI();
			if ("file".equals(uri.getScheme())) {
				if (GeneralTools.toPath(uri).toFile().exists())
					return true;
				else {
					logger.warn("File {} does not exist!", GeneralTools.toPath(uri));
					return false;
				}
			}
			return true;
		} catch (Exception e) {
			logger.debug("Exception trying to parse path " + path + ": " + e.getLocalizedMessage(), e);
			return false;
		}
	}


	public static BufferedImage getThumbnailRGB(ImageServer<BufferedImage> server, ImageDisplay imageDisplay) throws IOException {
		return ProjectImageThumbnails.getThumbnailRGB(server, imageDisplay);
	}
	
}
