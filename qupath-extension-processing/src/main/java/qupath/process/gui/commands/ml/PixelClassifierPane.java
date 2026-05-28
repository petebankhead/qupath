/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2018 - 2026 QuPath developers, The University of Edinburgh
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

package qupath.process.gui.commands.ml;

import ij.CompositeImage;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Scene;
import javafx.scene.chart.PieChart;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.text.TextAlignment;
import javafx.stage.Stage;
import org.bytedeco.javacpp.indexer.FloatIndexer;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_ml.ANN_MLP;
import org.bytedeco.opencv.opencv_ml.KNearest;
import org.bytedeco.opencv.opencv_ml.LogisticRegression;
import org.bytedeco.opencv.opencv_ml.RTrees;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;
import qupath.fx.utils.FXUtils;
import qupath.fx.utils.GridPaneUtils;
import qupath.imagej.gui.IJExtension;
import qupath.imagej.tools.IJTools;
import qupath.lib.classifiers.pixel.PixelClassifier;
import qupath.lib.classifiers.pixel.PixelClassifierMetadata;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.charts.ChartTools;
import qupath.lib.gui.commands.MiniViewers;
import qupath.lib.gui.dialogs.ProjectDialogs;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.tools.ColorToolsFX;
import qupath.lib.gui.tools.GuiTools;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.gui.viewer.overlays.PixelClassificationOverlay;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.ImageServerMetadata.ChannelType;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.images.servers.ServerTools;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyEvent;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyListener;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.RectangleROI;
import qupath.opencv.ml.FeaturePreprocessor;
import qupath.opencv.ml.OpenCVClassifiers;
import qupath.opencv.ml.OpenCVClassifiers.OpenCVStatModel;
import qupath.opencv.ml.OpenCVClassifiers.RTreesClassifier;
import qupath.opencv.ml.pixel.PixelClassifiers;
import qupath.opencv.ops.ImageOp;
import qupath.opencv.ops.ImageOps;
import qupath.process.gui.commands.ml.op.DefaultMultiscaleImageDataOpBuilder;
import qupath.process.gui.commands.ml.op.ImageDataOpBuilder;
import qupath.process.gui.commands.ml.PixelClassifierTraining.ClassifierTrainingData;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.stream.IntStream;
import qupath.process.gui.commands.ml.op.Multiscale3DImageDataOpBuilder;

/**
 * Main user interface for interactively training a {@link PixelClassifier}.
 * 
 * @author Pete Bankhead
 */
public class PixelClassifierPane {
	
	private static final Logger logger = LoggerFactory.getLogger(PixelClassifierPane.class);
	
	private static final ObservableList<ImageDataOpBuilder> defaultFeatureCalculatorBuilders = FXCollections.observableArrayList();

	private final QuPathGUI qupath;

    private final ObservableList<ClassificationResolution> resolutions = FXCollections.observableArrayList();
	private final ComboBox<ClassificationResolution> comboResolutions = new ComboBox<>(resolutions);
	private ReadOnlyObjectProperty<ClassificationResolution> selectedResolution;
	
	// To display features as overlays across the image
	private final ComboBox<String> comboDisplayFeatures = new ComboBox<>();
	private final Slider sliderFeatureOpacity = new Slider(0.0, 1.0, 1.0);
	private final Spinner<Double> spinFeatureMin = FXUtils.createDynamicStepSpinner(-Double.MAX_VALUE, Double.MAX_VALUE, 0, 0.1, 1);
	private final Spinner<Double> spinFeatureMax = FXUtils.createDynamicStepSpinner(-Double.MAX_VALUE, Double.MAX_VALUE, 1, 0.1, 1);
	private final String DEFAULT_CLASSIFICATION_OVERLAY = "Show classification";

	/**
	 * Other images from which training annotations should be used
	 */
	private final List<ProjectImageEntry<BufferedImage>> trainingEntries = new ArrayList<>();
	
	private final Map<ProjectImageEntry<BufferedImage>, ImageData<BufferedImage>> trainingMap = new WeakHashMap<>();
	
	
	private MiniViewers.MiniViewerManager miniViewer;
	
	private final BooleanProperty livePrediction = new SimpleBooleanProperty(false);
	
	private ReadOnlyObjectProperty<OpenCVStatModel> selectedClassifier;

	private ReadOnlyObjectProperty<ImageDataOpBuilder> selectedFeatureCalculatorBuilder;

	private ReadOnlyObjectProperty<ImageServerMetadata.ChannelType> selectedOutputType;
	
	private final StringProperty cursorLocation = new SimpleStringProperty();
	
	private PieChart pieChart;

	private final HierarchyListener hierarchyListener = new HierarchyListener();
	
	/**
	 * The last trained classifier
	 */
	private final ObjectProperty<PixelClassifier> currentClassifier = new SimpleObjectProperty<>();
	
	private PixelClassificationOverlay overlay;
	private PixelClassificationOverlay featureOverlay;
	private final FeatureRenderer featureRenderer;

	private final MouseListener mouseListener = new MouseListener();

	private final PixelClassifierTraining helper = new PixelClassifierTraining(null);
	private final PixelClassifierAdvancedOptions advancedOptions = new PixelClassifierAdvancedOptions();

	private ImageOp preprocessingOp = null;

	private final ChangeListener<ImageData<BufferedImage>> imageDataListener = this::handleImageDataChange;

	private Stage stage;

	private void handleImageDataChange(ObservableValue<? extends ImageData<BufferedImage>> observable,
						ImageData<BufferedImage> oldValue, ImageData<BufferedImage> newValue) {
		if (oldValue != null)
			oldValue.getHierarchy().removeListener(hierarchyListener);
		if (newValue != null)
			newValue.getHierarchy().addListener(hierarchyListener);
		updateAvailableResolutions(newValue);
	}

	/**
	 * Constructor.
	 * @param qupath the current {@link QuPathGUI} that will be used for interactive training.
	 */
	public PixelClassifierPane(final QuPathGUI qupath) {
		this.qupath = qupath;
		featureRenderer = new FeatureRenderer(qupath.getImageRegionStore());
		initialize();
	}

	
	private void initialize() {
		
		var imageData = qupath.getImageData();
		
		int row = 0;
		
		// Classifier
        GridPane pane = new GridPane();
		
		var labelClassifier = new Label("Classifier");
		var comboClassifier = new ComboBox<OpenCVStatModel>();
		labelClassifier.setLabelFor(comboClassifier);
		
		selectedClassifier = comboClassifier.getSelectionModel().selectedItemProperty();
		selectedClassifier.addListener((v, o, n) -> updateClassifier());
		var btnEditClassifier = new Button("Edit");
		btnEditClassifier.setOnAction(e -> editClassifierParameters());
		btnEditClassifier.disableProperty().bind(selectedClassifier.isNull());
		
		GridPaneUtils.addGridRow(pane, row++, 0,
				"Choose classifier type (RTrees or ANN_MLP are generally good choices)",
				labelClassifier, comboClassifier, comboClassifier, btnEditClassifier);
		
		// Image resolution
		var labelResolution = new Label("Resolution");
		labelResolution.setLabelFor(comboResolutions);
		var btnResolution = new Button("Add");
		btnResolution.setOnAction(e -> addResolution());
		selectedResolution = comboResolutions.getSelectionModel().selectedItemProperty();
		
		GridPaneUtils.addGridRow(pane, row++, 0,
				"Choose the base image resolution based upon required detail in the classification (see preview on the right)",
				labelResolution, comboResolutions, comboResolutions, btnResolution);
		
		
		// Features
		var labelFeatures = new Label("Features");
		var comboFeatures = new ComboBox<ImageDataOpBuilder>();
		comboFeatures.setButtonCell(new OverrunListCell<>());
		comboFeatures.setCellFactory(l -> new OverrunListCell<>());
		comboFeatures.getItems().add(new DefaultMultiscaleImageDataOpBuilder(imageData));

		// TODO: Handle 3D serialization and remove warning
		if (imageData != null && imageData.getServer().nZSlices() > 1) {
			logger.warn("Adding 3D support (experimental, doesn't support saving/reloading classifiers!)");
			comboFeatures.getItems().add(new Multiscale3DImageDataOpBuilder());
		}

		labelFeatures.setLabelFor(comboFeatures);
		selectedFeatureCalculatorBuilder = comboFeatures.getSelectionModel().selectedItemProperty();
		
		var btnShowFeatures = new Button("Show");
		btnShowFeatures.setOnAction(e -> showFeatures());
		
		var btnCustomizeFeatures = new Button("Edit");
		btnCustomizeFeatures.disableProperty().bind(Bindings.createBooleanBinding(() -> {
			var calc = selectedFeatureCalculatorBuilder.get();
			return calc == null || !calc.canCustomize(imageData);
		},
				selectedFeatureCalculatorBuilder));
		btnCustomizeFeatures.setOnAction(e -> {
			if (selectedFeatureCalculatorBuilder.get().doCustomize(imageData)) {
				updateFeatureCalculator();
			}
		});
		comboFeatures.getItems().addAll(defaultFeatureCalculatorBuilders);

		comboFeatures.getSelectionModel().select(0);
		comboFeatures.getSelectionModel().selectedItemProperty().addListener((v, o, n) -> updateFeatureCalculator());

		GridPaneUtils.addGridRow(pane, row++, 0,
				"Select features for the classifier",
				labelFeatures, comboFeatures, btnCustomizeFeatures, btnShowFeatures);

		
		// Output
		var labelOutput = new Label("Output");
		var comboOutput = new ComboBox<ImageServerMetadata.ChannelType>();
		comboOutput.getItems().addAll(ImageServerMetadata.ChannelType.CLASSIFICATION, ImageServerMetadata.ChannelType.PROBABILITY);
		selectedOutputType = comboOutput.getSelectionModel().selectedItemProperty();
		selectedOutputType.addListener((v, o, n) -> {
			updateClassifier();
		});
		comboOutput.getSelectionModel().clearAndSelect(0);
		var btnShowOutput = new Button("Show");
		btnShowOutput.setOnAction(e -> showOutput());
		
		GridPaneUtils.addGridRow(pane, row++, 0,
				"Choose whether to output classifications only, or estimated probabilities per class (not all classifiers support probabilities, which also require more memory)",
				labelOutput, comboOutput, comboOutput, btnShowOutput);
		
		
		// Region
		var labelRegion = new Label("Region");
		var comboRegionFilter = PixelClassifierUI.createRegionFilterCombo(qupath.getOverlayOptions());
		GridPaneUtils.addGridRow(pane,  row++, 0, "Control where the pixel classification is applied during preview",
				labelRegion, comboRegionFilter, comboRegionFilter, comboRegionFilter);

		
		// Live predict
		var btnAdvancedOptions = new Button("Advanced options");
		btnAdvancedOptions.setTooltip(new Tooltip("Advanced options to customize preprocessing and classifier behavior"));
		btnAdvancedOptions.setOnAction(e -> {
			if (advancedOptions.promptToUpdateOptions()) {
				updateClassifier();
			}
		});
		
		// Live predict
		var btnProject = new Button("Load training");
		btnProject.setTooltip(new Tooltip("Train using annotations from more images in the current project"));
		btnProject.setOnAction(e -> {
			if (promptToLoadTrainingImages()) {
				updateClassifier();
				int n = trainingEntries.size();
				if (n > 0)
					btnProject.setText("Load training (" + n + ")");
				else
					btnProject.setText("Load training");
			}
		});
		btnProject.disableProperty().bind(qupath.projectProperty().isNull());
		
		var btnLive = new ToggleButton("Live prediction");
		btnLive.selectedProperty().bindBidirectional(livePrediction);
		btnLive.setTooltip(new Tooltip("Toggle whether to calculate classification 'live' while viewing the image"));
		livePrediction.addListener((v, o, n) -> {
			if (overlay == null) {
				if (n) {
					updateClassifier(n);				
					return;
				}
			} else {
				overlay.setLivePrediction(n);
			}
			if (featureOverlay != null)
				featureOverlay.setLivePrediction(n);
		});
				
		var panePredict = GridPaneUtils.createColumnGridControls(btnProject, btnAdvancedOptions);
		pane.add(panePredict, 0, row++, pane.getColumnCount(), 1);
		pane.add(btnLive, 0, row++, pane.getColumnCount(), 1);
		
		pieChart = new PieChart();
		pieChart.getStyleClass().add("training-chart");
		pieChart.setAnimated(false);
		
		pieChart.setLabelsVisible(false);
		pieChart.setLegendVisible(true);
		pieChart.setMinSize(40, 40);
		pieChart.setPrefSize(120, 120);
		pieChart.setLegendSide(Side.RIGHT);
		var paneChart = new BorderPane(pieChart);

		GridPaneUtils.setFillWidth(Boolean.TRUE, paneChart);
		GridPaneUtils.setFillHeight(Boolean.TRUE, paneChart);
		GridPaneUtils.setVGrowPriority(Priority.ALWAYS, paneChart);
		GridPaneUtils.setHGrowPriority(Priority.ALWAYS, paneChart);

		pane.add(paneChart, 0, row++, pane.getColumnCount(), 1);
		
		// Label showing cursor location
		var labelCursor = new Label();
		labelCursor.textProperty().bindBidirectional(cursorLocation);
		labelCursor.setAlignment(Pos.CENTER);
		labelCursor.setTextAlignment(TextAlignment.CENTER);
		labelCursor.setContentDisplay(ContentDisplay.CENTER);
		labelCursor.setWrapText(true);
		labelCursor.setMaxHeight(Double.MAX_VALUE);
		labelCursor.setMinWidth(100);
		labelCursor.setPrefWidth(390);
		labelCursor.setMaxWidth(390);
		
		labelCursor.setTooltip(new Tooltip("Prediction for current cursor location"));
		paneChart.setBottom(labelCursor);

		paneChart.setMaxWidth(400);

		comboClassifier.getItems().addAll(
				OpenCVClassifiers.createStatModel(RTrees.class),
				OpenCVClassifiers.createStatModel(ANN_MLP.class),
				OpenCVClassifiers.createStatModel(LogisticRegression.class),
				OpenCVClassifiers.createStatModel(KNearest.class)
				);
		
		comboClassifier.getSelectionModel().clearAndSelect(1);
		
		GridPaneUtils.setHGrowPriority(Priority.ALWAYS, comboResolutions, comboClassifier, comboFeatures);
		GridPaneUtils.setFillWidth(Boolean.TRUE, comboResolutions, comboClassifier, comboFeatures);
		
		miniViewer = MiniViewers.createManager(qupath.getViewer());
		var viewerPane = miniViewer.getPane();
		Tooltip.install(viewerPane, new Tooltip("View image at classification resolution"));
		
		updateAvailableResolutions(imageData);	
		selectedResolution.addListener((v, o, n) -> {
			updateResolution(n);
			updateClassifier();
			ensureOverlaySet();
		});
		if (!comboResolutions.getItems().isEmpty())
			comboResolutions.getSelectionModel().clearAndSelect(resolutions.size()/2);
		
		pane.setHgap(5);
		pane.setVgap(6);
		
		var classifierName = new SimpleStringProperty(null);
		var panePostProcess = GridPaneUtils.createRowGrid(
				PixelClassifierUI.createSavePixelClassifierPane(qupath.projectProperty(), currentClassifier, classifierName),
				PixelClassifierUI.createPixelClassifierButtons(qupath.imageDataProperty(), currentClassifier, classifierName)
				);
		panePostProcess.setVgap(5);
		
		pane.add(panePostProcess, 0, row++, pane.getColumnCount(), 1);

		GridPaneUtils.setMaxWidth(Double.MAX_VALUE, pane.getChildren().stream().filter(p -> p instanceof Region).toArray(Region[]::new));
		
		var viewerBorderPane = new BorderPane(viewerPane);
		
		comboDisplayFeatures.getSelectionModel().selectedItemProperty().addListener((v, o, n) -> ensureOverlaySet());
		comboDisplayFeatures.setMaxWidth(Double.MAX_VALUE);
		spinFeatureMin.setPrefWidth(100);
		spinFeatureMax.setPrefWidth(100);
		spinFeatureMin.valueProperty().addListener((v, o, n) -> updateFeatureDisplayRange());
		spinFeatureMax.valueProperty().addListener((v, o, n) -> updateFeatureDisplayRange());
		sliderFeatureOpacity.valueProperty().addListener((v, o, n) -> {
			if (featureOverlay != null) {
				featureOverlay.setOpacity(n.doubleValue());
			}
			if (overlay != null)
				overlay.setOpacity(n.doubleValue());
			qupath.getViewerManager().repaintAllViewers();
		});

		var btnFeatureAuto = new Button("Auto");
		btnFeatureAuto.setOnAction(e -> autoFeatureContrast());
		comboDisplayFeatures.getItems().setAll(DEFAULT_CLASSIFICATION_OVERLAY);
		comboDisplayFeatures.getSelectionModel().select(DEFAULT_CLASSIFICATION_OVERLAY);
		var featureDisableBinding = comboDisplayFeatures.valueProperty().isEqualTo(DEFAULT_CLASSIFICATION_OVERLAY).or(comboDisplayFeatures.valueProperty().isNull());
		btnFeatureAuto.disableProperty().bind(featureDisableBinding);
		btnFeatureAuto.setMaxHeight(Double.MAX_VALUE);
		spinFeatureMin.disableProperty().bind(featureDisableBinding);
		spinFeatureMin.setEditable(true);
		FXUtils.restrictTextFieldInputToNumber(spinFeatureMin.getEditor(), true);
		FXUtils.resetSpinnerNullToPrevious(spinFeatureMin);
		
		spinFeatureMax.disableProperty().bind(featureDisableBinding);
		spinFeatureMax.setEditable(true);
		FXUtils.restrictTextFieldInputToNumber(spinFeatureMax.getEditor(), true);
		FXUtils.resetSpinnerNullToPrevious(spinFeatureMax);
		
		var paneFeatures = new GridPane();spinFeatureMax.setTooltip(new Tooltip("Choose classification result or feature overlay to display (Warning: This requires a lot of memory & computation!)"));
		spinFeatureMin.setTooltip(new Tooltip("Min display value for feature overlay"));
		spinFeatureMax.setTooltip(new Tooltip("Max display value for feature overlay"));
		sliderFeatureOpacity.setTooltip(new Tooltip("Adjust classification/feature overlay opacity"));
		
		GridPaneUtils.addGridRow(paneFeatures, 0, 0, null,
				comboDisplayFeatures, comboDisplayFeatures, comboDisplayFeatures, comboDisplayFeatures);
		GridPaneUtils.addGridRow(paneFeatures, 1, 0, null,
				sliderFeatureOpacity, spinFeatureMin, spinFeatureMax, btnFeatureAuto);

		comboDisplayFeatures.setCellFactory(l -> new OverrunListCell<>());
		comboDisplayFeatures.setButtonCell(new OverrunListCell<>());
		
		GridPaneUtils.setMaxWidth(Double.MAX_VALUE, comboDisplayFeatures, sliderFeatureOpacity);
		GridPaneUtils.setFillWidth(Boolean.TRUE, comboDisplayFeatures, sliderFeatureOpacity);
		GridPaneUtils.setHGrowPriority(Priority.ALWAYS, comboDisplayFeatures, sliderFeatureOpacity);
		paneFeatures.setHgap(5);
		paneFeatures.setVgap(5);
		paneFeatures.setPadding(new Insets(5));
		paneFeatures.prefWidthProperty().bind(viewerBorderPane.prefWidthProperty());
		viewerBorderPane.setBottom(paneFeatures);
		
		var splitPane = new BorderPane(viewerBorderPane);
		splitPane.setLeft(pane);
		pane.setMinWidth(400);
		
		var fullPane = splitPane;//new StackPane(splitPane);
		
		pane.setPadding(new Insets(5));
		
		stage = new Stage();
		stage.setScene(new Scene(fullPane));
		
		stage.setMinHeight(400);
		stage.setMinWidth(600);
		stage.sizeToScene();

		stage.initOwner(QuPathGUI.getInstance().getStage());
		stage.setTitle("Train pixel classifier");

		updateFeatureCalculator();
		
		GridPaneUtils.setMinWidth(
				Region.USE_PREF_SIZE,
				FXUtils.getContentsOfType(stage.getScene().getRoot(), Region.class, true).toArray(Region[]::new));

		// Hack... this seems to fix a bug whereby the stage would grow in size whenever
		// this combo box (and subsequently others) was clicked on
		comboRegionFilter.setPrefWidth(100);

		stage.show();
		stage.setOnCloseRequest(e -> destroy());
		
		qupath.getStage().addEventFilter(MouseEvent.MOUSE_MOVED, mouseListener);
		
		qupath.imageDataProperty().addListener(imageDataListener);
		if (qupath.getImageData() != null)
			qupath.getImageData().getHierarchy().addListener(hierarchyListener);
		
		stage.focusedProperty().addListener((v, o, n) -> {
			if (n) {
				for (var viewer : qupath.getAllViewers()) {
					var currentOverlay = viewer.getCustomPixelLayerOverlay();
					if (currentOverlay != this.featureOverlay && currentOverlay != this.overlay) {
						ensureOverlaySet();
						break;
					}
				}
			}
		});
	}

	
	/**
	 * Get all the training images currently requested.
	 * Often this is just the current image... unless there are a) multiple viewers, and/or b) project images required.
	 * @return
	 */
	private Collection<ImageData<BufferedImage>> getTrainingImageData() {
		// We use the current viewer to determine the image type
		var imageData = qupath.getImageData();
		if (imageData == null) {
			logger.warn("Cannot train classifier - a valid image needs to be open in the current viewer");
			return Collections.emptyList();
		}
		
		// Read annotations from all compatible images (which here means same channel names)
		List<ImageData<BufferedImage>> list = new ArrayList<>();
		for (var viewer : qupath.getAllViewers()) {
			var tempData = viewer.getImageData();
			if (tempData != null && compatibleChannels(imageData.getServer(), tempData.getServer()))
				list.add(tempData);
		}
		
		// Read any other requested images for the project
		if (!trainingEntries.isEmpty()) {
			var currentEntries = ProjectDialogs.getCurrentImages(qupath);
			for (var entry : trainingEntries) {
				try {
					if (currentEntries.contains(entry)) {
						logger.debug("Will not load data for {} - will use the training annotations from the open viewer", entry);
						var tempData = trainingMap.remove(entry);
						if (tempData != null)
							tempData.close();
					} else {
						var tempData = trainingMap.get(entry);
						if (tempData == null) {
							tempData = entry.readImageData();
							trainingMap.put(entry, tempData);
						}
						if (compatibleChannels(imageData.getServer(), tempData.getServer()))
							list.add(tempData);
					}
				} catch (Exception e) {
					logger.error(e.getLocalizedMessage(), e);
				}
			}
		}
		
		return list;
	}
	
	private static boolean compatibleChannels(ImageServer<?> server, ImageServer<?> server2) {
		if (server == null || server2 == null || server.nChannels() != server2.nChannels())
			return false;
		if (server == server2)
			return true;
		for (int c = 0; c < server.nChannels(); c++) {
			if (!server.getChannel(c).getName().equals(server2.getChannel(c).getName()))
				return false;
		}
		return true;
	}
	
	
	/**
	 * Add to the list of default feature calculator builders that will be available when 
	 * this pane is opened.
	 * <p>
	 * This provides a mechanism to install additional feature calculators.
	 * <p>
	 * Note that the builder will only be added if it is not already present.
	 * @param builder the builder to be installed
	 * 
	 * @return true if the builder was added, false otherwise.
	 */
	public static synchronized boolean installDefaultFeatureClassificationBuilder(ImageDataOpBuilder builder) {
		if (!Platform.isFxApplicationThread()) {
			logger.debug("Delegating installDefaultFeatureClassificationBuilder to the application thread");
			return FXUtils.callOnApplicationThread(() -> installDefaultFeatureClassificationBuilder(builder));
		}
		if (!defaultFeatureCalculatorBuilders.contains(builder)) {
			defaultFeatureCalculatorBuilders.add(builder);
			return true;
		}
		return false;
	}

		
	/**
	 * Update the available resolutions for the specified ImageData.
	 * @param imageData
	 */
	private void updateAvailableResolutions(ImageData<BufferedImage> imageData) {
		var selected = selectedResolution.get();
		if (imageData == null) {
			return;
		}
		var requestedResolutions = ClassificationResolution.getDefaultResolutions(imageData, selected);
		if (!resolutions.equals(requestedResolutions)) {
			resolutions.setAll(ClassificationResolution.getDefaultResolutions(imageData, selected));
			comboResolutions.getSelectionModel().select(selected);
		}
	}
	
	
	private void updateFeatureCalculator() {
		var cal = getSelectedResolution();
		var imageData = qupath.getImageData();
		
		// Check we can support the requested channels before proceeding
		// This is a bit of a hack because we know some implementations will fail with more channels than OpenCV
		// can handle (on a call to OpenCVTools.mergeChannels).
		// We'd rather show a notification instead of just logging the error - although this risks being a problem
		// for an implementation that *would* work, so we may consider restricting the check to only know failures.
		var featureOpBuilder = selectedFeatureCalculatorBuilder.get();
		var featureOp = featureOpBuilder.build(imageData, cal);
		int nFeatures = featureOp.getChannels(imageData).size();
		if (nFeatures > opencv_core.CV_CN_MAX) {
			Dialogs.showErrorNotification("Pixel classifier", "Too many features! Requested " + nFeatures + " but maximum is " + opencv_core.CV_CN_MAX +
					".\nFeatures will not be updated - please select a smaller number and continue training.");
			return;
		}
		helper.setFeatureOp(featureOp);
		var featureServer = helper.getFeatureServer(imageData);
		if (featureServer == null) {
			comboDisplayFeatures.getItems().setAll(DEFAULT_CLASSIFICATION_OVERLAY);
		} else {
			List<String> featureNames = new ArrayList<>();
			featureNames.add(DEFAULT_CLASSIFICATION_OVERLAY);
			for (var channel : featureServer.getMetadata().getChannels())
				featureNames.add(channel.getName());
			comboDisplayFeatures.getItems().setAll(featureNames);
		}
		comboDisplayFeatures.getSelectionModel().select(DEFAULT_CLASSIFICATION_OVERLAY);
		updateClassifier();
	}
	
	
	private void autoFeatureContrast() {
		var selectedChannel = featureRenderer == null ? null : featureRenderer.getSelectedChannel();
		if (selectedChannel != null) {
			featureRenderer.autoSetDisplayRange();
			double min = selectedChannel.getMinDisplay();
			double max = selectedChannel.getMaxDisplay();
			spinFeatureMin.getValueFactory().setValue(min);
			spinFeatureMax.getValueFactory().setValue(max);
		}
	}
	
	private void ensureOverlaySet() {
		if (featureOverlay != null) {
			featureOverlay.stop();
			featureOverlay = null;
		}
		
		for (var viewer : qupath.getAllViewers()) {
			if (viewer.getCustomPixelLayerOverlay() == featureOverlay)
				viewer.resetCustomPixelLayerOverlay();
		}
		
		var imageData = qupath.getImageData();
		if (imageData == null)
			return;
		String featureName = comboDisplayFeatures.getSelectionModel().getSelectedItem();
		if (DEFAULT_CLASSIFICATION_OVERLAY.equals(featureName)) {
			for (var viewer : qupath.getAllViewers())
				viewer.setCustomPixelLayerOverlay(overlay);
			return;
		}
		int channel = -1;
		var featureServer = helper.getFeatureServer(imageData);
		if (featureServer != null && featureName != null) {
			for (int c = 0; c < featureServer.nChannels(); c++) {
				if (featureName.equals(featureServer.getChannel(c).getName())) {
					channel = c;
					break;
				}
			}
			if (channel >= 0) {
				featureRenderer.setChannel(featureServer, channel, spinFeatureMin.getValue(), spinFeatureMax.getValue());
				featureOverlay = PixelClassificationOverlay.create(qupath.getOverlayOptions(), data -> helper.getFeatureServer(data), featureRenderer);
				featureOverlay.setMaxThreads(getLivePredictionThreads());
				featureOverlay.setLivePrediction(true);
				featureOverlay.setOpacity(sliderFeatureOpacity.getValue());
				featureOverlay.setLivePrediction(livePrediction.get());
				autoFeatureContrast();
			}
		}
		if (featureOverlay != null) {
			for (var viewer : qupath.getAllViewers())
				viewer.setCustomPixelLayerOverlay(featureOverlay);
		}
	}
	
	
	private int getLivePredictionThreads() {
		int n = advancedOptions.getNumThreads();
		return  n < 0 ? PathPrefs.numCommandThreadsProperty().get() : Math.max(n, 1);
	}
	
	
	private void updateFeatureDisplayRange() {
		if (featureRenderer == null || spinFeatureMin.getValue() == null || spinFeatureMax.getValue() == null)
			return;
		featureRenderer.setRange(spinFeatureMin.getValue(), spinFeatureMax.getValue());
		qupath.getViewerManager().repaintAllViewers();
	}
	
	private void updateClassifier() {
		updateClassifier(livePrediction.get());
	}
	
	
	
	private void updateClassifier(boolean doClassification) {
		if (doClassification)
			doClassification();
		else
			replaceOverlay(null);
	}
	
	
	
	private void doClassification() {
		var imageData = qupath.getImageData();
		if (imageData == null) {
			if (qupath.getAllViewers().stream().noneMatch(v -> v.getImageData() != null)) {
				logger.debug("doClassification() called, but no images are open"); 
				return;			
			}
		}
		
		var model = selectedClassifier.get();
		if (model == null) {
			Dialogs.showErrorNotification("Pixel classifier", "No classifier selected!");
			return;
		}

		this.helper.setBoundaryStrategy(advancedOptions.getBoundaryStrategy());

		ClassifierTrainingData trainingData;
		try {
			var trainingImages = getTrainingImageData();
			if (trainingImages.size() > 1)
				logger.info("Creating training data from {} images", trainingImages.size());
			trainingData = helper.createTrainingData(trainingImages);
		} catch (Exception e) {
			logger.error("Error when updating training data", e);
			return;
		}
		 if (trainingData == null) {
			 resetPieChart();
			 return;
		 }

		 // TODO: Optionally limit the number of training samples we use
		 //	     		var trainData = classifier.createTrainData(matFeatures, matTargets);

		 // Ensure we seed the RNG for reproducibility
		 opencv_core.setRNGSeed(advancedOptions.getRngSeed());
		 
		 // TODO: Prevent training K nearest neighbor with a huge number of samples (very slow!)
		 var actualMaxSamples = advancedOptions.getMaxSamples();
		 
		 var trainData = trainingData.getTrainData();
		 if (actualMaxSamples > 0 && trainData.getNTrainSamples() > actualMaxSamples)
			 trainData.setTrainTestSplit(actualMaxSamples, true);
		 else
			 trainData.shuffleTrainTest();

		 // Apply normalization, if we need to
		 FeaturePreprocessor preprocessor = advancedOptions.getNormalization().build(trainData.getTrainSamples(), false);
		 if (preprocessor.doesSomething()) {
			 preprocessingOp = ImageOps.ML.preprocessor(preprocessor);
		 } else
			 preprocessingOp = null;
		 
		 var labels = trainingData.getLabelMap();
		 // Using getTrainNormCatResponses() causes confusion if classes are not represented
//		 var targets = trainData.getTrainNormCatResponses();
		 var targets = trainData.getTrainResponses();
		 IntBuffer buffer = targets.createBuffer();
		 int n = (int)targets.total();
		 var rawCounts = new int[labels.size()];
		 for (int i = 0; i < n; i++) {
			 rawCounts[buffer.get(i)] += 1;
		 }
		 Map<PathClass, Integer> counts = new LinkedHashMap<>();
		 for (var entry : labels.entrySet()) {
			 counts.put(entry.getKey(), rawCounts[entry.getValue()]);
		 }
		 updatePieChart(counts);
		 
		 Mat weights = null;
		 if (advancedOptions.getReweightSamples()) {
			 weights = new Mat(n, 1, opencv_core.CV_32FC1);
			 FloatIndexer bufferWeights = weights.createIndexer();
			 float[] weightArray = new float[rawCounts.length];
			 for (int i = 0; i < weightArray.length; i++) {
				 int c = rawCounts[i];
				 weightArray[i] = c == 0 ? 1 : (float)n/c;
			 }
			 for (int i = 0; i < n; i++) {
				 int label = buffer.get(i);
				 bufferWeights.put(i, weightArray[label]);
			 }
			 bufferWeights.release();
		 }
		 
		 // Create TrainData in an appropriate format (e.g. labels or one-hot encoding)
		 var trainSamples = trainData.getTrainSamples();
		 var trainResponses = trainData.getTrainResponses();
		 preprocessor.apply(trainSamples, false);
		 trainData = model.createTrainData(trainSamples, trainResponses, weights, false);
		 
		 logger.info("Training data: {} x {}, Target data: {} x {}", trainSamples.rows(), trainSamples.cols(), trainResponses.rows(), trainResponses.cols());
		 model.train(trainData);
		 
		 // Calculate accuracy using whatever we can, as a rough guide to progress
		 var test = trainData.getTestSamples();
		 String testSet = "HELD-OUT TRAINING SET";
		 if (test.empty()) {
			 test = trainSamples;
			 testSet = "TRAINING SET";
		 } else {
			 preprocessor.apply(test, false);
			 buffer = trainData.getTestNormCatResponses().createBuffer();
		 }
		 var testResults = new Mat();
		 model.predict(test, testResults, null);
		 IntBuffer bufferResults = testResults.createBuffer();
		 int nTest = (int)testResults.rows();
		 int nCorrect = 0;
		 for (int i = 0; i < nTest; i++) {
			 if (bufferResults.get(i) == buffer.get(i))
				 nCorrect++;
		 }
		 logger.info("Current accuracy on the {}: {} %", testSet, GeneralTools.formatNumber(nCorrect*100.0/n, 1));

		 if (model instanceof RTreesClassifier trees) {
             if (trees.hasFeatureImportance() && imageData != null)
				 logVariableImportance(trees,
						 helper.getFeatureOp().getChannels(imageData).stream()
						 .map(ImageChannel::getName).toList());
		 }
		 
		 trainData.close();

		 
		 var featureCalculator = helper.getFeatureOp();
		 if (preprocessingOp != null)
			 featureCalculator = featureCalculator.appendOps(preprocessingOp);
		 
		 // TODO: CHECK IF INPUT SIZE SHOULD BE DEFINED
		 int inputWidth = 512;
		 int inputHeight = 512;
//		 int inputWidth = featureCalculator.getInputSize().getWidth();
//		 int inputHeight = featureCalculator.getInputSize().getHeight();
		 var cal = helper.getResolution();
		 var channelType = ImageServerMetadata.ChannelType.CLASSIFICATION;
		 if (model.supportsProbabilities()) {
			 channelType = selectedOutputType.get();
		 }
		 
		 // Channels are needed for probability output (and work for classification as well)
		 var labels2 = new TreeMap<Integer, PathClass>();
		 for (var entry : labels.entrySet()) {
			 var previous = labels2.put(entry.getValue(), entry.getKey());
			 if (previous != null)
				 logger.warn("Duplicate label found! {} matches with {} and {}, only the latter be used", entry.getValue(), previous, entry.getKey());
		 }
		 var channels = ServerTools.classificationLabelsToChannels(labels2, true);
		 
		 PixelClassifierMetadata metadata = new PixelClassifierMetadata.Builder()
				 .inputResolution(cal)
				 .inputShape(inputWidth, inputHeight)
				 .setChannelType(channelType)
				 .outputChannels(channels)
				 .build();

		 currentClassifier.set(PixelClassifiers.createClassifier(model, featureCalculator, metadata, true));

		 var overlay = PixelClassificationOverlay.create(qupath.getOverlayOptions(), currentClassifier.get(), getLivePredictionThreads());
		 replaceOverlay(overlay);
	}
		
	
	
	private void resetPieChart() {
		updatePieChart(Collections.emptyMap());
	}
	
	private void updatePieChart(Map<PathClass, Integer> counts) {
		if (!Platform.isFxApplicationThread()) {
			Platform.runLater(() -> updatePieChart(counts));
			return;
		}
		ChartTools.setPieChartData(pieChart, counts, PathClass::toString, p -> ColorToolsFX.getCachedColor(p.getColor()), true, !counts.isEmpty());
		if (counts.isEmpty())
			pieChart.setTitle(null);
		else
			pieChart.setTitle("Training data");
	}
	
	
	static void logVariableImportance(final RTreesClassifier trees, final List<String> features) {
		var importance = trees.getFeatureImportance();
		if (importance == null)
			return;
		try {
			var sorted = IntStream.range(0, importance.length)
					.boxed()
					.sorted((a, b) -> -Double.compare(importance[a], importance[b]))
					.mapToInt(i -> i).toArray();
			
			if (sorted.length != features.size())
				return;
			
			var sb = new StringBuilder("Variable importance:");
			for (int ind : sorted) {
				sb.append("\n");
				sb.append(String.format("%.4f \t %s", importance[ind], features.get(ind)));
			}
			logger.info(sb.toString());
			return;
		} catch (Exception e) {
			logger.debug("Error logging feature importance: {}", e.getLocalizedMessage());
			return;
		}
	}
	

	/**
	 * Replace the overlay - making sure to do this on the application thread
	 *
	 * @param newOverlay
	 */
	private void replaceOverlay(PixelClassificationOverlay newOverlay) {
		if (!Platform.isFxApplicationThread()) {
			Platform.runLater(() -> replaceOverlay(newOverlay));
			return;
		}
		if (overlay != null) {
			overlay.stop();
		}
		overlay = newOverlay;
		if (overlay != null) {
			overlay.setMaxThreads(advancedOptions.getNumThreads());
			overlay.setLivePrediction(livePrediction.get());
			overlay.setOpacity(sliderFeatureOpacity.getValue());
		}
		ensureOverlaySet();
	}
		
	



	private void destroy() {
		if (overlay != null)
			overlay.stop();
		
		qupath.imageDataProperty().removeListener(imageDataListener);

		for (var viewer : qupath.getAllViewers()) {
			viewer.resetCustomPixelLayerOverlay();
			if (featureOverlay != null) {
				viewer.getCustomOverlayLayers().remove(featureOverlay);
				featureOverlay.stop();
			}
	
//			viewer.imageDataProperty().removeListener(imageDataListener);
			var hierarchy = viewer.getHierarchy();
			if (hierarchy != null)
				hierarchy.removeListener(hierarchyListener);
		}
		featureOverlay = null;
		overlay = null;
//		setImageData(viewer, viewer.getImageData(), null);
		if (stage != null && stage.isShowing())
			stage.close();
		
		// Ensure we have closed any cached images
		for (var data : trainingMap.values()) {
			try {
				data.close();
			} catch (Exception e) {
                logger.warn("Error closing server: {}", e.getMessage(), e);
			}
		}
		trainingEntries.clear();
		trainingMap.clear();
	}
	
	
	
	
	private boolean editClassifierParameters() {
		var model = selectedClassifier.get();
		if (model == null) {
			Dialogs.showErrorMessage("Edit parameters", "No classifier selected!");
			return false;
		}
		GuiTools.showParameterDialog("Edit parameters", model.getParameterList());
		updateClassifier();
		return true;
	}
	
	
	private boolean showOutput() {
		if (overlay == null) {
			Dialogs.showErrorMessage("Show output", "No pixel classifier has been trained yet!");
			return false;
		}
		var viewer = qupath.getViewer();
		var imageData = viewer.getImageData();
		var server = imageData == null ? null : overlay.getPixelClassificationServer(imageData);
		if (server == null)
			return false;
		var selected = viewer.getSelectedObject();
		var roi = selected == null ? null : selected.getROI();
		double downsample = server.getDownsampleForResolution(0);
		RegionRequest request;
		if (roi == null) {
			request = RegionRequest.createInstance(
					server.getPath(), downsample, 
					0, 0, server.getWidth(), server.getHeight(), viewer.getZPosition(), viewer.getTPosition());			
		} else {
			request = RegionRequest.createInstance(server.getPath(), downsample, selected.getROI());
		}
		long estimatedPixels = (long)Math.ceil(request.getWidth()/request.getDownsample()) * (long)Math.ceil(request.getHeight()/request.getDownsample());
		double estimatedMB = (estimatedPixels * server.nChannels() * (server.getPixelType().getBytesPerPixel())) / (1024.0 * 1024.0);
		if (estimatedPixels >= Integer.MAX_VALUE - 16) {
			Dialogs.showErrorMessage("Extract output", "Requested region is too big! Try selecting a smaller region.");
			return false;
		} else if (estimatedMB >= 200.0) {
			if (!Dialogs.showConfirmDialog("Extract output",
					String.format("Extracting this region will require approximately %.1f MB - are you sure you want to try this?", estimatedMB)))
				return false;
		}
		
		try {
			var pathImage = IJTools.convertToImagePlus(
					server,
					request);
			var imp = pathImage.getImage();
			if (imp instanceof CompositeImage && server.getMetadata().getChannelType() != ChannelType.CLASSIFICATION) {
				imp.setDisplayMode(CompositeImage.GRAYSCALE);
			}
			if (roi != null && !(roi instanceof RectangleROI)) {
				imp.setRoi(IJTools.convertToIJRoi(roi, pathImage));
			}
			IJExtension.getImageJInstance();
			imp.show();
			return true;
		} catch (IOException e) {
			logger.error("Error showing output", e);
		}
		return false;
	}
	
	
	private void showFeatures() {
		var viewer = qupath.getViewer();
		ImageData<BufferedImage> imageData = viewer.getImageData();
		double cx = viewer.getCenterPixelX();
		double cy = viewer.getCenterPixelY();
		if (imageData == null)
			return;

		var op = helper.getFeatureOp();
		if (preprocessingOp != null)
			op = op.appendOps(preprocessingOp);

		try (var featureServer = ImageOps.buildServer(imageData, op, helper.getResolution())) {
			double downsample = featureServer.getDownsampleForResolution(0);
			int tw = (int)(featureServer.getMetadata().getPreferredTileWidth() * downsample);
			int th = (int)(featureServer.getMetadata().getPreferredTileHeight() * downsample);
			int x = (int)GeneralTools.clipValue(cx - tw/2.0, 0, featureServer.getWidth() - tw);
			int y = (int)GeneralTools.clipValue(cy - th/2.0, 0, featureServer.getHeight() - th);
			var request = RegionRequest.createInstance(
					featureServer.getPath(),
					downsample,
					x, y, tw, th, viewer.getZPosition(), viewer.getTPosition());
			
			var imp = IJTools.convertToImagePlus(featureServer, request).getImage();

			CompositeImage impComp = new CompositeImage(imp, CompositeImage.GRAYSCALE);
			impComp.setDimensions(imp.getStackSize(), 1, 1);
			for (int s = 1; s <= imp.getStackSize(); s++) {
				impComp.setPosition(s);
				impComp.resetDisplayRange();
			}
			impComp.setPosition(1);
			IJExtension.getImageJInstance();
			impComp.show();
		} catch (Exception e) {
			logger.error("Error calculating features", e);
		}
	}
	
	private boolean addResolution() {
		var imageData = qupath.getImageData();
		ImageServer<BufferedImage> server = imageData == null ? null : imageData.getServer();
		if (server == null) {
			GuiTools.showNoImageError("Add resolution");
			return false;
		}
		String units = null;
		Double pixelSize = null;
		PixelCalibration cal = server.getPixelCalibration();
		if (cal.hasPixelSizeMicrons()) {
			pixelSize = Dialogs.showInputDialog("Add resolution", "Enter requested pixel size in " + GeneralTools.micrometerSymbol(), 1.0);
			units = PixelCalibration.MICROMETER;
		} else {
			pixelSize = Dialogs.showInputDialog("Add resolution", "Enter requested downsample factor", 1.0);
		}
		
		if (pixelSize == null)
			return false;
		
		ClassificationResolution res;
		if (PixelCalibration.MICROMETER.equals(units)) {
			double scale = pixelSize / cal.getAveragedPixelSizeMicrons();
			res = new ClassificationResolution("Custom", cal.createScaledInstance(scale, scale, 1));
		} else
			res = new ClassificationResolution("Custom", cal.createScaledInstance(pixelSize, pixelSize, 1));

		List<ClassificationResolution> temp = new ArrayList<>(resolutions);
		temp.add(res);
		temp.sort(Comparator.comparingDouble((ClassificationResolution w) -> w.cal.getAveragedPixelSize().doubleValue()));
		resolutions.setAll(temp);
		comboResolutions.getSelectionModel().select(res);
		
		return true;
	}	
	
	
	private PixelCalibration getSelectedResolution() {
		return selectedResolution.get().cal;
	}
	
	
	private void updateResolution(ClassificationResolution resolution) {
		ImageServer<BufferedImage> server = qupath.getImageData() == null ? null : qupath.getImageData().getServer();
		if (server == null || miniViewer == null || resolution == null)
			return;
		Tooltip.install(miniViewer.getPane(), new Tooltip("Classification resolution: \n" + resolution));
		helper.setResolution(resolution.cal);
		miniViewer.setDownsample(resolution.cal.getAveragedPixelSize().doubleValue()  / server.getPixelCalibration().getAveragedPixelSize().doubleValue());
	}
	
	
	
	private boolean promptToLoadTrainingImages() {
		var project = qupath.getProject();
		if (project == null) {
			GuiTools.showNoProjectError("Pixel classifier");
			return false;
		}
		
		var listView = ProjectDialogs.createImageChoicePane(qupath, project.getImageList(), trainingEntries,
				"Specified image is open!");
		
		var pane = new BorderPane(listView);
		pane.setTop(new Label("Select images to use for training the pixel classifier.\n"
				+ "Note that more images will require more memory and more processing time!"));
		
		if (Dialogs.builder()
				.title("Pixel classifier training images")
				.content(pane)
				.resizable()
				.buttons(ButtonType.APPLY, ButtonType.CANCEL)
				.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.CANCEL)
			return false;
		
		trainingEntries.clear();
		trainingEntries.addAll(listView.getTargetItems());
		
		return true;
	}
	
	
	
	
	class MouseListener implements EventHandler<MouseEvent> {

		@Override
		public void handle(MouseEvent event) {
			if (overlay == null)
				return;
			for (var viewer : qupath.getAllViewers()) {
				var view = viewer.getView();
				var local = view.screenToLocal(event.getScreenX(), event.getScreenY());
				if (view.contains(local)) {
					updateCursorLocation(viewer, local);
					return;
				}
			}
		}
		
		void updateCursorLocation(QuPathViewer viewer, Point2D localPoint) {
			var p = viewer.componentPointToImagePoint(localPoint.getX(), localPoint.getY(), null, false);
			var server = overlay.getPixelClassificationServer(viewer.getImageData());
			String results = null;
			if (server != null)
				results = PixelClassificationOverlay.getDefaultLocationString(server,
						null, p.getX(), p.getY(), viewer.getZPosition(), viewer.getTPosition());
			if (results == null)
				cursorLocation.set("");
			else
				cursorLocation.set(results);
		}
		
		
	}
	
	
	
	class HierarchyListener implements PathObjectHierarchyListener {

		@Override
		public void hierarchyChanged(PathObjectHierarchyEvent event) {
			if (!event.isChanging() && !event.isObjectMeasurementEvent() && (event.isStructureChangeEvent() || event.isObjectClassificationEvent() || !event.getChangedObjects().isEmpty())) {
				if (event.isObjectClassificationEvent() || event.getChangedObjects().stream().anyMatch(p -> p.getPathClass() != null)) {
					if (event.getChangedObjects().stream().anyMatch(PathObject::isAnnotation) &&
							!(event.isAddedOrRemovedEvent() && event.getChangedObjects().stream().allMatch(PathObject::isLocked)))
						updateClassifier();
				}
			}
		}
		
	}


}
