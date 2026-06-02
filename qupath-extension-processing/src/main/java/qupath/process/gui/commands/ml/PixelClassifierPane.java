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

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
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
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.text.TextAlignment;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
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
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.images.servers.ServerTools;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyEvent;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyListener;
import qupath.lib.projects.ProjectImageEntry;
import qupath.opencv.ml.FeaturePreprocessor;
import qupath.opencv.ml.OpenCVClassifiers;
import qupath.opencv.ml.OpenCVClassifiers.OpenCVStatModel;
import qupath.opencv.ml.OpenCVClassifiers.RTreesClassifier;
import qupath.opencv.ml.pixel.PixelClassifiers;
import qupath.opencv.ops.ImageOp;
import qupath.opencv.ops.ImageOps;
import qupath.process.gui.commands.ml.op.MultiscaleImageDataOpBuilder;
import qupath.process.gui.commands.ml.op.ImageDataOpBuilder;
import qupath.process.gui.commands.ml.PixelClassifierTraining.ClassifierTrainingData;

import java.awt.image.BufferedImage;
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

	// To display features as overlays across the image
	private final ComboBox<String> comboDisplayFeatures = new ComboBox<>();
	private final Slider sliderFeatureOpacity = new Slider(0.0, 1.0, 1.0);
	private final Spinner<Double> spinFeatureMin = FXUtils.createDynamicStepSpinner(-Double.MAX_VALUE, Double.MAX_VALUE, 0, 0.1, 1);
	private final Spinner<Double> spinFeatureMax = FXUtils.createDynamicStepSpinner(-Double.MAX_VALUE, Double.MAX_VALUE, 1, 0.1, 1);

	/**
	 * Other images from which training annotations should be used
	 */
	private final List<ProjectImageEntry<BufferedImage>> trainingEntries = new ArrayList<>();
	
	private final Map<ProjectImageEntry<BufferedImage>, ImageData<BufferedImage>> trainingMap = new WeakHashMap<>();
	
	private final MiniViewers.MiniViewerManager miniViewer;
	
	private final BooleanProperty livePrediction = new SimpleBooleanProperty(false);

	private final ObjectProperty<ClassificationResolution> resolution = new SimpleObjectProperty<>();
	private final ObjectProperty<OpenCVStatModel> statModel = new SimpleObjectProperty<>();
	private final ObjectProperty<ImageDataOpBuilder> opBuilder = new SimpleObjectProperty<>();
	private final ObjectProperty<ImageServerMetadata.ChannelType> outputType = new SimpleObjectProperty<>();

	private final PieChart pieChart = new PieChart();

	private final PathObjectHierarchyListener hierarchyListener = this::handleHierarchyChange;
	
	/**
	 * The last trained classifier
	 */
	private final ObjectProperty<PixelClassifier> currentClassifier = new SimpleObjectProperty<>();

	private final PixelClassifierOverlayManager overlayManager;

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
		this.overlayManager = new PixelClassifierOverlayManager(qupath, helper);
		miniViewer = MiniViewers.createManager(qupath.getViewer());
		initializeOverlayManager();
		initialize();
	}

	private void initializeOverlayManager() {
		PixelClassifierUtils.bindBidirectional(overlayManager.featureMinDisplayProperty(), spinFeatureMin);
		PixelClassifierUtils.bindBidirectional(overlayManager.featureMaxDisplayProperty(), spinFeatureMax);
		overlayManager.classifierProperty().bind(currentClassifier);
		overlayManager.livePredictionProperty().bind(livePrediction);
	}


	private void initialize() {
		
		var imageData = qupath.getImageData();
		
		int row = 0;
		
		// Classifier
        GridPane pane = new GridPane();
		
		var labelClassifier = new Label("Classifier");
		var comboClassifier = new ComboBox<OpenCVStatModel>();
		labelClassifier.setLabelFor(comboClassifier);
		
		statModel.bind(comboClassifier.getSelectionModel().selectedItemProperty());
		statModel.addListener((v, o, n) -> updateClassifier());
		var btnEditClassifier = new Button("Edit");
		btnEditClassifier.setOnAction(e -> promptToEditClassifierParameters());
		btnEditClassifier.disableProperty().bind(statModel.isNull());
		
		GridPaneUtils.addGridRow(pane, row++, 0,
				"Choose classifier type (RTrees or ANN_MLP are generally good choices)",
				labelClassifier, comboClassifier, comboClassifier, btnEditClassifier);
		
		// Image resolution
		var labelResolution = new Label("Resolution");
		labelResolution.setLabelFor(comboResolutions);
		var btnResolution = new Button("Add");
		btnResolution.setOnAction(e -> promptToAddResolution());
		resolution.bind(comboResolutions.getSelectionModel().selectedItemProperty());
		
		GridPaneUtils.addGridRow(pane, row++, 0,
				"Choose the base image resolution based upon required detail in the classification (see preview on the right)",
				labelResolution, comboResolutions, comboResolutions, btnResolution);
		
		
		// Features
		var labelFeatures = new Label("Features");
		var comboFeatures = new ComboBox<ImageDataOpBuilder>();
		comboFeatures.setButtonCell(new OverrunListCell<>());
		comboFeatures.setCellFactory(l -> new OverrunListCell<>());
		comboFeatures.getItems().add(MultiscaleImageDataOpBuilder.create2D(imageData));

		// TODO: Handle 3D serialization and remove warning
		if (imageData != null && imageData.getServer().nZSlices() > 1) {
			logger.warn("Adding 3D support (experimental, doesn't support saving/reloading classifiers!)");
			comboFeatures.getItems().add(MultiscaleImageDataOpBuilder.create3D(imageData));
		}

		labelFeatures.setLabelFor(comboFeatures);
		opBuilder.bind(comboFeatures.getSelectionModel().selectedItemProperty());
		
		var btnShowFeatures = new Button("Show");
		btnShowFeatures.setOnAction(e -> PixelClassifierUtils.showImageJFeatureStack(qupath.getViewer(), helper, preprocessingOp));
		
		var btnCustomizeFeatures = new Button("Edit");
		btnCustomizeFeatures.disableProperty().bind(Bindings.createBooleanBinding(() -> {
			var calc = opBuilder.get();
			return calc == null || !calc.canCustomize(imageData);
		},
				opBuilder));
		btnCustomizeFeatures.setOnAction(e -> {
			if (opBuilder.get().doCustomize(imageData)) {
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
		outputType.bind(comboOutput.getSelectionModel().selectedItemProperty());
		outputType.addListener((v, o, n) -> {
			updateClassifier();
		});
		comboOutput.getSelectionModel().clearAndSelect(0);
		var btnShowOutput = new Button("Show");
		btnShowOutput.setOnAction(e -> PixelClassifierUtils.showImageJClassifierOutput(qupath.getViewer(), overlayManager.getOverlay()));
		
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
				overlayManager.predictionThreadsProperty().set(getLivePredictionThreads());
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

				
		var panePredict = GridPaneUtils.createColumnGridControls(btnProject, btnAdvancedOptions);
		pane.add(panePredict, 0, row++, pane.getColumnCount(), 1);
		pane.add(btnLive, 0, row++, pane.getColumnCount(), 1);

		pane.add(createPieChartPane(), 0, row++, pane.getColumnCount(), 1);

		comboClassifier.getItems().addAll(
				OpenCVClassifiers.createStatModel(RTrees.class),
				OpenCVClassifiers.createStatModel(ANN_MLP.class),
				OpenCVClassifiers.createStatModel(LogisticRegression.class),
				OpenCVClassifiers.createStatModel(KNearest.class)
				);
		
		comboClassifier.getSelectionModel().clearAndSelect(1);
		
		GridPaneUtils.setHGrowPriority(Priority.ALWAYS, comboResolutions, comboClassifier, comboFeatures);
		GridPaneUtils.setFillWidth(Boolean.TRUE, comboResolutions, comboClassifier, comboFeatures);
		
		var viewerPane = miniViewer.getPane();
		Tooltip.install(viewerPane, new Tooltip("View image at classification resolution"));
		
		updateAvailableResolutions(imageData);
		resolution.addListener((v, o, n) -> {
			updateResolution(n);
			updateClassifier();
			overlayManager.ensureOverlaySet();
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
		
		comboDisplayFeatures.getSelectionModel().selectedItemProperty().addListener((v, o, n) -> overlayManager.ensureOverlaySet());
		comboDisplayFeatures.setMaxWidth(Double.MAX_VALUE);
		spinFeatureMin.setPrefWidth(100);
		spinFeatureMax.setPrefWidth(100);


		var btnFeatureAuto = new Button("Auto");
		btnFeatureAuto.setOnAction(e -> overlayManager.autoFeatureContrast());
		comboDisplayFeatures.getItems().setAll(PixelClassifierOverlayManager.DEFAULT_CLASSIFICATION_OVERLAY);
		comboDisplayFeatures.getSelectionModel().select(PixelClassifierOverlayManager.DEFAULT_CLASSIFICATION_OVERLAY);
		overlayManager.selectedNameProperty().bind(comboDisplayFeatures.getSelectionModel().selectedItemProperty());
		var featureDisableBinding = comboDisplayFeatures.valueProperty().isEqualTo(PixelClassifierOverlayManager.DEFAULT_CLASSIFICATION_OVERLAY).or(comboDisplayFeatures.valueProperty().isNull());
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
		stage.setOnCloseRequest(this::handleStageCloseRequest);

		qupath.imageDataProperty().addListener(imageDataListener);
		if (qupath.getImageData() != null)
			qupath.getImageData().getHierarchy().addListener(hierarchyListener);
		
		stage.focusedProperty().subscribe(this::handleStageFocussed);
	}

	private void handleStageFocussed(boolean isFocused) {
		if (isFocused) {
			overlayManager.ensureOverlaySet();
		}
	}


	private Pane createPieChartPane() {
		initializePieChart();
		var paneChart = new BorderPane(pieChart);

		GridPaneUtils.setFillWidth(Boolean.TRUE, paneChart);
		GridPaneUtils.setFillHeight(Boolean.TRUE, paneChart);
		GridPaneUtils.setVGrowPriority(Priority.ALWAYS, paneChart);
		GridPaneUtils.setHGrowPriority(Priority.ALWAYS, paneChart);

		// Label showing cursor location (below pie chart)
		var labelCursor = new Label();
		labelCursor.textProperty().bind(overlayManager.cursorLocationProperty());
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
		return paneChart;
	}


	private void initializePieChart() {
		pieChart.getStyleClass().add("training-chart");
		pieChart.setAnimated(false);

		pieChart.setLabelsVisible(false);
		pieChart.setLegendVisible(true);
		pieChart.setMinSize(40, 40);
		pieChart.setPrefSize(120, 120);
		pieChart.setLegendSide(Side.RIGHT);
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
			if (tempData != null && PixelClassifierUtils.compatibleChannels(imageData.getServer(), tempData.getServer()))
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
						if (PixelClassifierUtils.compatibleChannels(imageData.getServer(), tempData.getServer()))
							list.add(tempData);
					}
				} catch (Exception e) {
					logger.error(e.getLocalizedMessage(), e);
				}
			}
		}
		
		return list;
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
	public static synchronized void installDefaultFeatureClassificationBuilder(ImageDataOpBuilder builder) {
		if (!Platform.isFxApplicationThread()) {
			logger.debug("Delegating installDefaultFeatureClassificationBuilder to the application thread");
			Platform.runLater(() -> installDefaultFeatureClassificationBuilder(builder));
		}
		if (!defaultFeatureCalculatorBuilders.contains(builder)) {
			defaultFeatureCalculatorBuilders.add(builder);
		}
	}

		
	/**
	 * Update the available resolutions for the specified ImageData.
	 * @param imageData
	 */
	private void updateAvailableResolutions(ImageData<BufferedImage> imageData) {
		if (imageData == null) {
			return;
		}
		var selected = resolution.get();
		var requestedResolutions = ClassificationResolution.getDefaultResolutions(imageData, selected);
		if (!resolutions.equals(requestedResolutions)) {
			resolutions.setAll(ClassificationResolution.getDefaultResolutions(imageData, selected));
			comboResolutions.getSelectionModel().select(selected);
		}
	}
	
	
	private void updateFeatureCalculator() {
		var resolution = this.resolution.get();
		if (resolution == null)
			return;
		var cal = resolution.getPixelCalibration();
		var imageData = qupath.getImageData();
		
		// Check we can support the requested channels before proceeding
		// This is a bit of a hack because we know some implementations will fail with more channels than OpenCV
		// can handle (on a call to OpenCVTools.mergeChannels).
		// We'd rather show a notification instead of just logging the error - although this risks being a problem
		// for an implementation that *would* work, so we may consider restricting the check to only know failures.
		var featureOpBuilder = opBuilder.get();
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
			comboDisplayFeatures.getItems().setAll(PixelClassifierOverlayManager.DEFAULT_CLASSIFICATION_OVERLAY);
		} else {
			List<String> featureNames = new ArrayList<>();
			featureNames.add(PixelClassifierOverlayManager.DEFAULT_CLASSIFICATION_OVERLAY);
			for (var channel : featureServer.getMetadata().getChannels())
				featureNames.add(channel.getName());
			comboDisplayFeatures.getItems().setAll(featureNames);
		}
		comboDisplayFeatures.getSelectionModel().select(PixelClassifierOverlayManager.DEFAULT_CLASSIFICATION_OVERLAY);
		updateClassifier();
	}




	private int getLivePredictionThreads() {
		int n = advancedOptions.getNumThreads();
		return  n < 0 ? PathPrefs.numCommandThreadsProperty().get() : Math.max(n, 1);
	}

	
	private void updateClassifier() {
		updateClassifier(livePrediction.get());
	}
	
	
	
	private void updateClassifier(boolean doClassification) {
		if (doClassification)
			doClassification();
		else
			overlayManager.resetOverlay();
	}
	
	
	
	private void doClassification() {
		var imageData = qupath.getImageData();
		if (imageData == null) {
			if (qupath.getAllViewers().stream().noneMatch(v -> v.getImageData() != null)) {
				logger.debug("doClassification() called, but no images are open"); 
				return;			
			}
		}
		
		var model = statModel.get();
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
		 int nTest = testResults.rows();
		 int nCorrect = 0;
		 for (int i = 0; i < nTest; i++) {
			 if (bufferResults.get(i) == buffer.get(i))
				 nCorrect++;
		 }
		 logger.info("Current accuracy on the {}: {} %", testSet, GeneralTools.formatNumber(nCorrect*100.0/n, 1));

		 if (model instanceof RTreesClassifier trees) {
             if (trees.hasFeatureImportance() && imageData != null) {
				 var featureNames = helper.getFeatureOp().getChannels(imageData).stream()
						.map(ImageChannel::getName).toList();
				 trees.logVariableImportance(featureNames);
			 }
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
			 channelType = outputType.get();
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


    private void handleStageCloseRequest(WindowEvent event) {
		qupath.imageDataProperty().removeListener(imageDataListener);

		for (var viewer : qupath.getAllViewers()) {
			var hierarchy = viewer.getHierarchy();
			if (hierarchy != null)
				hierarchy.removeListener(hierarchyListener);
		}
		overlayManager.close();

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
	
	
	
	
	private void promptToEditClassifierParameters() {
		var model = statModel.get();
		if (model == null) {
			Dialogs.showErrorMessage("Edit parameters", "No classifier selected!");
			return;
		}
		GuiTools.showParameterDialog("Edit parameters", model.getParameterList());
		updateClassifier();
	}


    private void promptToAddResolution() {
		var imageData = qupath.getImageData();
		ImageServer<BufferedImage> server = imageData == null ? null : imageData.getServer();
		if (server == null) {
			GuiTools.showNoImageError("Add resolution");
			return;
		}
		String units;
		Double pixelSize;
		PixelCalibration cal = server.getPixelCalibration();
		if (cal.hasPixelSizeMicrons()) {
			pixelSize = Dialogs.showInputDialog("Add resolution", "Enter requested pixel size in " + GeneralTools.micrometerSymbol(), 1.0);
			units = PixelCalibration.MICROMETER;
		} else {
			pixelSize = Dialogs.showInputDialog("Add resolution", "Enter requested downsample factor", 1.0);
			units = null;
		}
		
		if (pixelSize == null)
			return;
		
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
	

	private void handleHierarchyChange(PathObjectHierarchyEvent event) {
		// We want to update the classifier for every relevant event...  but not any unnecessary events
		if (event.isChanging() || event.isObjectMeasurementEvent())
			return;
		var changedObjects = event.getChangedObjects();
		if (event.isStructureChangeEvent() || event.isObjectClassificationEvent() || !changedObjects.isEmpty()) {
			if (event.isObjectClassificationEvent() || changedObjects.stream().anyMatch(p -> p.getPathClass() != null)) {
				if (changedObjects.stream().anyMatch(PathObject::isAnnotation) &&
						!(event.isAddedOrRemovedEvent() && changedObjects.stream().allMatch(PathObject::isLocked)))
					updateClassifier();
			}
		}
	}

}
