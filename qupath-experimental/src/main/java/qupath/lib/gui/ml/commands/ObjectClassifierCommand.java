/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package qupath.lib.gui.ml.commands;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.bytedeco.opencv.opencv_ml.ANN_MLP;
import org.bytedeco.opencv.opencv_ml.KNearest;
import org.bytedeco.opencv.opencv_ml.RTrees;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.stage.Stage;
import qupath.lib.classifiers.Normalization;
import qupath.lib.classifiers.PathClassifierTools;
import qupath.lib.geom.Point2;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.commands.interfaces.PathCommand;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.ml.ClassificationPieChart;
import qupath.lib.gui.tools.PaneTools;
import qupath.lib.io.GsonTools;
import qupath.lib.objects.PathDetectionObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectFilter;
import qupath.lib.objects.PathObjectTools;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.opencv.ml.OpenCVClassifiers;
import qupath.opencv.ml.OpenCVClassifiers.OpenCVStatModel;
import qupath.opencv.ml.objects.OpenCVMLClassifier;
import qupath.opencv.ml.objects.features.FeatureExtractors;


/**
 * Command used to create and show a suitable dialog box for interactive display of OpenCV classifiers.
 * <p>
 * This is intended as a replacement for 'Create detection classifier' in QuPath v0.1.2, supporting better 
 * classifier options and serialization.
 * 
 * @author Pete Bankhead
 *
 */
public class ObjectClassifierCommand implements PathCommand {
	
	final private static String name = "Train detection classifier";
	
	private QuPathGUI qupath;
	
	// TODO: Check use of static dialog
	private Stage dialog;
//	private ClassifierBuilderPanel<PathObjectClassifier> panel;

	
	public ObjectClassifierCommand(final QuPathGUI qupath) {
		this.qupath = qupath;
	}
	
	@Override
	public void run() {
		dialog = null;
		if (dialog == null) {
			dialog = new Stage();
			if (qupath != null)
				dialog.initOwner(qupath.getStage());
			dialog.setTitle(name);
			
			BorderPane pane = new BorderPane();
			var panel = new ObjectClassifierPane(qupath);
			pane.setCenter(panel.getPane());
//			List<PathObjectClassifier> classifiers = OpenCVMLClassifier.createDefaultClassifiers();
//			panel = new ClassifierBuilderPanel<>(qupath, classifiers, classifiers.get(0));
//			pane.setCenter(panel.getPane());
			
			ScrollPane scrollPane = new ScrollPane(pane);
			scrollPane.setFitToWidth(true);
			scrollPane.setFitToHeight(true);
			dialog.setScene(new Scene(scrollPane));
		}
		
		dialog.setOnCloseRequest(e -> {
//			// If we don't have a classifier yet, just remove completely
//			if (panel.getSelectedFeatures().isEmpty()) {
//				resetPanel();
//				return;
//			}
//			
//			// If we have a classifier, give option to hide
//			DialogButton button = Dialogs.showYesNoCancelDialog("Classifier builder", "Retain classifier for later use?");
//			if (button == DialogButton.CANCEL)
//				e.consume();
//			else if (button == DialogButton.NO) {
//				resetPanel();
//			}
		});
		
		dialog.sizeToScene();
		
		dialog.show();
	}
	
	
//	/**
//	 * Handle cleanup whenever a dialog should be closed (and forgotten)
//	 */
//	private void resetPanel() {
//		if (panel == null)
//			return;
//		qupath.removeImageDataChangeListener(panel);
//		panel.setImageData(qupath.getImageData(), null);
//		if (dialog != null)
//			dialog.setOnCloseRequest(null);
//		dialog = null;
//		panel = null;
//	}
	
	
	
	static class ObjectClassifierPane {
		
		private final static Logger logger = LoggerFactory.getLogger(ObjectClassifierPane.class);
		
		private QuPathGUI qupath;
		
		/**
		 * Use all classifications for training and prediction, or only some.
		 */
		private static enum OutputClasses { ALL, SELECTED;
			
			@Override
			public String toString() {
				switch(this) {
				case ALL:
					return "All classes";
				case SELECTED:
					return "Selected classes";
				default:
					throw new IllegalArgumentException();
				}
			}
		
		}

		/**
		 * Use all measurements for training and prediction, or only some.
		 */
		private static enum TrainingFeatures { ALL, SELECTED;
			
			@Override
			public String toString() {
				switch(this) {
				case ALL:
					return "All measurements";
				case SELECTED:
					return "Selected measurements";
				default:
					throw new IllegalArgumentException();
				}
			}
			
		}
		
		/**
		 * Specify kind of annotations to use for training
		 */
		private static enum TrainingAnnotations { ALL, POINTS, AREAS;
			
			@Override
			public String toString() {
				switch(this) {
				case ALL:
					return "All unlocked annotations";
				case POINTS:
					return "Points onlys";
				case AREAS:
					return "Areas onlys";
				default:
					throw new IllegalArgumentException();
				}
			}
			
		}

		
		/**
		 * Main GUI pane
		 */
		private GridPane pane;

		private ReadOnlyObjectProperty<PathObjectFilter> objectFilter;

		private ReadOnlyObjectProperty<OpenCVStatModel> selectedModel;

		private ReadOnlyObjectProperty<OutputClasses> outputClasses;
		private ReadOnlyObjectProperty<TrainingFeatures> trainingFeatures;

		private ReadOnlyObjectProperty<TrainingAnnotations> trainingAnnotations;

		private OpenCVMLClassifier classifier;
		private Set<PathClass> selectedClasses = new HashSet<>();
		
		private Set<String> selectedMeasurements = new LinkedHashSet<>();
		
		
		/**
		 * Text relevant to the current cursor location when over a viewer
		 */
		private StringProperty cursorLocation = new SimpleStringProperty();
		
		/**
		 * If true, update classification as automatically
		 */
		private BooleanProperty livePrediction = new SimpleBooleanProperty(false);
		
		/**
		 * Visualization of the training object proportions
		 */
		private ClassificationPieChart pieChart;
		
		private boolean classifierInvalid;
		
		ObjectClassifierPane(QuPathGUI qupath) {
			this.qupath = qupath;
			selectedClasses.addAll(qupath.getAvailablePathClasses());
			initialize();
		}
		
		private void invalidateClassifier() {
			classifierInvalid = true;
			if (livePrediction.get())
				updateClassifier(true);
		}
		
		public Pane getPane() {
			return pane;
		}
		
		private List<PathObject> getTrainingAnnotations(PathObjectHierarchy hierarchy) {
			Predicate<PathObject> trainingFilter = (PathObject p) -> p.isAnnotation() && p.getPathClass() != null && p.hasROI() && !p.isLocked();
			switch (trainingAnnotations.get()) {
				case AREAS:
					trainingFilter = trainingFilter.and(PathObjectFilter.ROI_AREA);
					break;
				case POINTS:
					trainingFilter = trainingFilter.and(PathObjectFilter.ROI_POINT);
					break;
				default:
					break;
			}
			return hierarchy.getAnnotationObjects()
					.stream()
					.filter(trainingFilter)
					.collect(Collectors.toList());
		}
		
		private void updateClassifier(boolean doClassification) {
			
			var filter = objectFilter.get();
			var temp = selectedModel == null ? null : selectedModel.get();
			if (temp == null)
				classifier = null;
			else
				classifier = OpenCVMLClassifier.create(temp, objectFilter.get());
			if (classifier == null) {
				pieChart.setData(Collections.emptyMap(), false);
				return;
			}
						
			var imageData = qupath.getImageData();
			if (imageData == null) {
				logger.warn("No image - cannot update classifier");
				pieChart.setData(Collections.emptyMap(), false);
				return;
			}
			var detections = imageData.getHierarchy()
					.getFlattenedObjectList(null)
					.stream()
					.filter(filter)
					.collect(Collectors.toList());
			List<String> measurements;
			if (trainingFeatures.get() == TrainingFeatures.SELECTED)
				measurements = new ArrayList<>(selectedMeasurements);
			else
				measurements = new ArrayList<>(PathClassifierTools.getAvailableFeatures(detections));
			if (measurements.isEmpty()) {
				logger.warn("No measurements - cannot update classifier");
				pieChart.setData(Collections.emptyMap(), false);
				return;
			}
			
			// Get training annotations & associated objects
			var hierarchy = imageData.getHierarchy();
			var trainingAnnotations = getTrainingAnnotations(hierarchy);
			
			// Use a set for detections because we might need to check if we have the same detection for multiple classes
			Map<PathClass, Set<PathObject>> map = new TreeMap<>();
			for (var annotation : trainingAnnotations) {
				var pathClass = annotation.getPathClass();
				if (outputClasses.get() == OutputClasses.ALL || selectedClasses.contains(pathClass)) {
					var set = map.computeIfAbsent(pathClass, p -> new HashSet<>());
					var roi = annotation.getROI();
					if (roi.isPoint()) {
						for (Point2 p : annotation.getROI().getAllPoints()) {
							var pathObjectsTemp = PathObjectTools.getObjectsForLocation(
									hierarchy, p.getX(), p.getY(), roi.getZ(), roi.getT(), -1);
							pathObjectsTemp.removeIf(objectFilter.get().negate());
							set.addAll(pathObjectsTemp);
						}
					} else {
						var pathObjectsTemp = hierarchy.getObjectsForROI(PathDetectionObject.class, annotation.getROI());
						pathObjectsTemp.removeIf(objectFilter.get().negate());
						set.addAll(pathObjectsTemp);
					}
				}
			}
			map.entrySet().removeIf(e -> e.getValue().isEmpty());
			if (map.size() <= 1) {
				logger.warn("Not enough training data - samples for at least two classes are needed");
				return;
			}
			
//			var map = PathClassificationLabellingHelper.getClassificationMap(imageData.getHierarchy(), trainFromPoints);
			
			var extractor = FeatureExtractors.createMeasurementListFeatureExtractor(measurements);
			double pcaVariance = -1;
			classifier.updateClassifier(map, extractor, Normalization.NONE, pcaVariance);
			
			var counts = new LinkedHashMap<PathClass, Integer>();
			for (var entry : map.entrySet()) {
				counts.put(entry.getKey(), entry.getValue().size());
			}
			pieChart.setData(counts, true);
			
			if (doClassification) {
				if (classifier.classifyObjects(imageData) > 0) {
					imageData.getHierarchy().fireObjectClassificationsChangedEvent(this, detections);
				}
			}
			
		}
		
		private boolean showAdvancedOptions() {
			Dialogs.showErrorNotification("Advanced options", "Not yet implemented!");
			return false;
		}
		
		private boolean saveAndApply() {
			Dialogs.showErrorNotification("Advanced options", "Not yet fully implemented!");
			updateClassifier(true);
			if (classifier != null) {
				try {
					var json = GsonTools.getInstance(true).toJson(classifier);
					System.err.println(json);
					var classifier2 = GsonTools.getInstance().fromJson(json, OpenCVMLClassifier.class);
					logger.info("Classification deserialized: {}", classifier2);
				} catch (Exception e) {
					logger.error("Error attempting classifier serialization " + e.getLocalizedMessage(), e);
				}
			}
			
			return false;
		}
		
		private boolean editClassifierParameters() {
			var model = selectedModel.get();
			if (model == null) {
				Dialogs.showErrorMessage("Edit parameters", "No classifier selected!");
				return false;
			}
			Dialogs.showParameterDialog("Edit parameters", model.getParameterList());
			invalidateClassifier();
			return true;
		}
		
		private void initialize() {
			
			pane = new GridPane();
			int row = 0;
			
			/*
			 * Input object type
			 */
			var labelObjects = new Label("Objects");
			var comboObjects = new ComboBox<PathObjectFilter>();
			comboObjects.getItems().addAll(
					PathObjectFilter.DETECTIONS_ALL,
					PathObjectFilter.DETECTIONS,
					PathObjectFilter.CELLS,
					PathObjectFilter.TILES
					);
			labelObjects.setLabelFor(comboObjects);
			objectFilter = comboObjects.getSelectionModel().selectedItemProperty();
			comboObjects.getSelectionModel().select(PathObjectFilter.DETECTIONS_ALL);
			objectFilter.addListener((v, o, n) -> invalidateClassifier());
			
			PaneTools.addGridRow(pane, row++, 0, 
					"Choose object type to classify (default is all detections)",
					labelObjects, comboObjects, comboObjects);
			
			/*
			 * Classifier type
			 */
			var labelClassifier = new Label("Classifier");
			var comboClassifier = new ComboBox<OpenCVStatModel>();
			comboClassifier.getItems().addAll(
					OpenCVClassifiers.createStatModel(RTrees.class),
					OpenCVClassifiers.createStatModel(ANN_MLP.class),
					OpenCVClassifiers.createStatModel(KNearest.class)
					);
			labelClassifier.setLabelFor(comboClassifier);
			selectedModel = comboClassifier.getSelectionModel().selectedItemProperty();
			comboClassifier.getSelectionModel().selectFirst();
			selectedModel.addListener((v, o, n) -> invalidateClassifier());
			var btnEditClassifier = new Button("Edit");
			btnEditClassifier.setMaxWidth(Double.MAX_VALUE);
			btnEditClassifier.setOnAction(e -> editClassifierParameters());
			btnEditClassifier.disableProperty().bind(selectedModel.isNull());
			
			PaneTools.addGridRow(pane, row++, 0, 
					"Choose classifier type (RTrees or ANN_MLP are generally good choices)",
					labelClassifier, comboClassifier, btnEditClassifier);
			
			/*
			 * Feature selection
			 */
			var labelFeatures = new Label("Features");
			var comboFeatures = new ComboBox<TrainingFeatures>();
			comboFeatures.getItems().setAll(TrainingFeatures.values());
			comboFeatures.getSelectionModel().select(TrainingFeatures.ALL);
			labelFeatures.setLabelFor(comboFeatures);
			trainingFeatures = comboFeatures.getSelectionModel().selectedItemProperty();
			var btnSelectFeatures = new Button("Select");
			btnSelectFeatures.setMaxWidth(Double.MAX_VALUE);
			btnSelectFeatures.disableProperty().bind(
					trainingFeatures.isNotEqualTo(TrainingFeatures.SELECTED)
					);
			btnSelectFeatures.setOnAction(e -> {
				if (promptToSelectFeatures())
					invalidateClassifier();
			});
			PaneTools.addGridRow(pane, row++, 0, 
					"Choose features for the classifier",
					labelFeatures, comboFeatures, btnSelectFeatures);
			
			/*
			 * Output classes
			 */
			var labelClasses = new Label("Classes");
			var comboClasses = new ComboBox<OutputClasses>();
			comboClasses.getItems().setAll(OutputClasses.values());
			comboClasses.getSelectionModel().select(OutputClasses.ALL);
			labelClasses.setLabelFor(comboClasses);
			outputClasses = comboClasses.getSelectionModel().selectedItemProperty();
			outputClasses.addListener(v -> invalidateClassifier());
			var btnSelectClasses = new Button("Select");
			btnSelectClasses.setMaxWidth(Double.MAX_VALUE);
			btnSelectClasses.disableProperty().bind(
					outputClasses.isEqualTo(OutputClasses.ALL)
					);
			btnSelectClasses.setOnAction(e -> {
				if (promptToSelectClasses()) {
					invalidateClassifier();
				}
			});
			
			PaneTools.addGridRow(pane, row++, 0, 
					"Choose which classes to use when training the classifier - annotations with other classifications will be ignored",
					labelClasses, comboClasses, btnSelectClasses);
			
			/*
			 * Training annotations
			 */
			var labelTraining = new Label("Training");
			var comboTraining = new ComboBox<TrainingAnnotations>();
			comboTraining.getItems().setAll(TrainingAnnotations.values());
			comboTraining.getSelectionModel().select(TrainingAnnotations.ALL);
			trainingAnnotations = comboTraining.getSelectionModel().selectedItemProperty();
			trainingAnnotations.addListener(v -> invalidateClassifier());
			
			PaneTools.addGridRow(pane, row++, 0, 
					"Choose what kind of annotations to use for training",
					labelTraining, comboTraining, comboTraining);
			
			
			/*
			 * Additional options & live predict
			 */
			var btnAdvancedOptions = new Button("Advanced options");
			btnAdvancedOptions.setTooltip(new Tooltip("Advanced options to customize preprocessing and classifier behavior"));
			btnAdvancedOptions.setOnAction(e -> {
				if (showAdvancedOptions())
					invalidateClassifier();
			});
			
			var btnLive = new ToggleButton("Live update");
			btnLive.selectedProperty().bindBidirectional(livePrediction);
			btnLive.setTooltip(new Tooltip("Toggle whether to calculate classification 'live' while viewing the image"));
			livePrediction.addListener((v, o, n) -> {
				if (n) {
					updateClassifier(n);				
					return;
				}
			});
			
			var panePredict = PaneTools.createColumnGridControls(btnAdvancedOptions, btnLive);
			pane.add(panePredict, 0, row++, pane.getColumnCount(), 1);
			
			/*
			 * Save classifier
			 */
			var btnSave = new Button("Save & Apply");
			btnSave.setMaxWidth(Double.MAX_VALUE);
			btnSave.setOnAction(e -> saveAndApply());
			pane.add(btnSave, 0, row++, pane.getColumnCount(), 1);
			
			/*
			 * Training proportions (pie chart)
			 */
			pieChart = new ClassificationPieChart();
			
			var chart = pieChart.getChart();
			chart.setLabelsVisible(false);
			chart.setLegendVisible(true);
			chart.setPrefSize(40, 40);
			chart.setMaxSize(100, 100);
			chart.setLegendSide(Side.RIGHT);
			GridPane.setVgrow(chart, Priority.ALWAYS);
			Tooltip.install(chart, new Tooltip("View training classes by proportion"));
			pane.add(chart, 0, row++, pane.getColumnCount(), 1);
			
			// Label showing cursor location
			var labelCursor = new Label();
			labelCursor.textProperty().bindBidirectional(cursorLocation);
			labelCursor.setMaxWidth(Double.MAX_VALUE);
			labelCursor.setAlignment(Pos.CENTER);
			labelCursor.setTooltip(new Tooltip("Prediction for current cursor location"));
			pane.add(labelCursor, 0, row++, pane.getColumnCount(), 1);
						
			PaneTools.setMaxWidth(Double.MAX_VALUE, comboTraining, comboObjects, comboClassifier, comboFeatures, comboClasses, panePredict);
			PaneTools.setHGrowPriority(Priority.ALWAYS, comboTraining, comboObjects, comboClassifier, comboFeatures, comboClasses, panePredict);
			PaneTools.setFillWidth(Boolean.TRUE, comboTraining, comboObjects, comboClassifier, comboClasses, panePredict);

//			pane.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
			pane.setHgap(5);
			pane.setVgap(6);
			
			qupath.getStage().getScene().addEventFilter(MouseEvent.MOUSE_MOVED, e -> updateLocationText(e));
			
//			var btnCreateObjects = new Button("Create objects");
//			btnCreateObjects.setTooltip(new Tooltip("Create annotations or detections from pixel classification"));
//			btnCreateObjects.disableProperty().bind(classificationComplete);
//			btnCreateObjects.setOnAction(e -> {
//				var server = getClassificationServerOrShowError();
//				var imageData = viewer.getImageData();
//				if (imageData != null && server != null)
//					promptToCreateObjects(imageData, server);
//			});
//			
//			var btnClassifyObjects = new Button("Classify detections");
//			btnClassifyObjects.setTooltip(new Tooltip("Assign classifications to detection objects based on the corresponding pixel classification"));
//			btnClassifyObjects.disableProperty().bind(classificationComplete);
//			btnClassifyObjects.setOnAction(e -> classifyObjects());
//			
//			var panePostProcess = PaneTools.createColumnGridControls(btnCreateObjects, btnClassifyObjects);
//					
//			pane.add(panePostProcess, 0, row++, pane.getColumnCount(), 1);
//
//			PaneTools.setMaxWidth(Double.MAX_VALUE, pane.getChildren().stream().filter(p -> p instanceof Region).toArray(Region[]::new));
			
			pane.setPadding(new Insets(5));
			
//			stage = new Stage();
//			stage.setScene(new Scene(pane));
//			
//			stage.setMinHeight(400);
//			stage.setMinWidth(500);
//
//			stage.initOwner(QuPathGUI.getInstance().getStage());
//			
//			stage.getScene().getRoot().disableProperty().bind(
//					QuPathGUI.getInstance().viewerProperty().isNotEqualTo(viewer)
//					);
//			
//			updateTitle();
//			
//			updateFeatureCalculator();
//			
////			pane.getChildren().stream().forEach(c -> {
////				if (c instanceof Control)
////					((Control)c).setMinSize(Control.USE_PREF_SIZE, Control.USE_PREF_SIZE);
////			});
//			PaneTools.setMinWidth(
//					Region.USE_PREF_SIZE,
//					PaneTools.getContentsOfType(stage.getScene().getRoot(), Region.class, true).toArray(Region[]::new));
//			
//			stage.show();
//			stage.setOnCloseRequest(e -> destroy());
//			
//			viewer.getView().addEventFilter(MouseEvent.MOUSE_MOVED, mouseListener);
//			
//			viewer.getImageDataProperty().addListener(imageDataListener);
//			if (viewer.getImageData() != null)
//				viewer.getImageData().getHierarchy().addPathObjectListener(hierarchyListener);
			
		}
		
		
		boolean promptToSelectFeatures() {
			var imageData = qupath.getImageData();
			if (imageData == null)
				return false;
			var detections = imageData.getHierarchy().getFlattenedObjectList(null)
					.stream()
					.filter(objectFilter.get())
					.collect(Collectors.toList());
			
			var measurements = PathClassifierTools.getAvailableFeatures(detections);
			if (measurements.isEmpty()) {
				Dialogs.showErrorMessage("Select features", "No features available for specified objects!");
				return false;
			}
			
			var featuresPane = new SelectionPane<>(measurements);
			featuresPane.selectItems(selectedMeasurements);
			if (!Dialogs.showConfirmDialog("Select features", featuresPane.getPane()))
				return false;
			selectedMeasurements.clear();
			selectedMeasurements.addAll(featuresPane.getSelectedItems());
			return true;
		}
		
		boolean promptToSelectClasses() {
			var imageData = qupath.getImageData();
			if (imageData == null)
				return false;
			var annotations = getTrainingAnnotations(imageData.getHierarchy());
			var pathClasses = annotations.stream().map(p -> p.getPathClass()).collect(Collectors.toCollection(TreeSet::new));
			var classesPane = new SelectionPane<>(pathClasses);
			classesPane.selectItems(selectedClasses);
			if (!Dialogs.showConfirmDialog("Select classes", classesPane.getPane()))
				return false;
			selectedClasses.clear();
			selectedClasses.addAll(classesPane.getSelectedItems());
			return true;
		}
		
		
		void updateLocationText(MouseEvent e) {
			String text = "";
			for (var viewer : qupath.getViewers()) {
				var hierarchy = viewer.getHierarchy();
				if (hierarchy == null)
					continue;
				var view = viewer.getView();
				var p = view.screenToLocal(e.getScreenX(), e.getScreenY());
				if (view.contains(p)) {
					var p2 = viewer.componentPointToImagePoint(p.getX(), p.getY(), null, false);
					var pathObjects = PathObjectTools.getObjectsForLocation(hierarchy,
							p2.getX(), p2.getY(),
							viewer.getZPosition(), viewer.getTPosition(), 0);
					if (!pathObjects.isEmpty()) {
						text = pathObjects.stream()
								.filter(pathObject -> pathObject.isDetection())
								.map(pathObject -> {
							var pathClass = pathObject.getPathClass();
							return pathClass == null ? "Unclassified" : pathClass.toString();
						}).collect(Collectors.joining(", "));
					}
				}
			}
			cursorLocation.set(text);
		}
		
	}
	
	
	static class SelectionPane<T> {
		
		private TableView<SelectableItem<T>> tableFeatures = new TableView<>();

		private BorderPane pane;
		
		SelectionPane(Collection<T> features) {
			pane = makeFeatureSelectionPanel();
			updateItems(features);
		}

		public Pane getPane() {
			return pane;
		}

		public List<T> getSelectedItems() {
			List<T> selectedFeatures = new ArrayList<>();
			for (SelectableItem<T> feature : tableFeatures.getItems()) {
				if (feature.isSelected())
					selectedFeatures.add(feature.getItem());
			}
			return selectedFeatures;
		}
		

		private BorderPane makeFeatureSelectionPanel() {
			TableColumn<SelectableItem<T>, String> columnName = new TableColumn<>("Name");
			columnName.setCellValueFactory(new PropertyValueFactory<>("item"));
			columnName.setEditable(false);

			TableColumn<SelectableItem<T>, Boolean> columnSelected = new TableColumn<>("Selected");
			columnSelected.setCellValueFactory(new PropertyValueFactory<>("selected"));
			columnSelected.setCellFactory(column -> new CheckBoxTableCell<>());
			columnSelected.setEditable(true);
			columnSelected.setResizable(false);

			columnName.prefWidthProperty().bind(tableFeatures.widthProperty().subtract(columnSelected.widthProperty()));

			tableFeatures.getColumns().add(columnName);
			tableFeatures.getColumns().add(columnSelected);
			tableFeatures.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
			tableFeatures.setEditable(true);
			

			ContextMenu menu = new ContextMenu();
			MenuItem itemSelect = new MenuItem("Select");
			itemSelect.setOnAction(e -> {
				for (SelectableItem<T> feature : tableFeatures.getSelectionModel().getSelectedItems())
					feature.setSelected(true);
			});
			menu.getItems().add(itemSelect);
			MenuItem itemDeselect = new MenuItem("Deselect");
			itemDeselect.setOnAction(e -> {
				for (SelectableItem<T> feature : tableFeatures.getSelectionModel().getSelectedItems())
					feature.setSelected(false);
			});
			menu.getItems().add(itemDeselect);

			tableFeatures.setContextMenu(menu);

			// Button to update the features
			BorderPane panelButtons = new BorderPane();
			Button btnSelectAll = new Button("Select all");
			btnSelectAll.setOnAction(e -> {
				for (SelectableItem<T> feature : tableFeatures.getItems())
					feature.setSelected(true);

			});
			Button btnSelectNone = new Button("Select none");
			btnSelectNone.setOnAction(e -> {
				for (SelectableItem<T> feature : tableFeatures.getItems())
					feature.setSelected(false);
			});
			GridPane panelSelectButtons = PaneTools.createColumnGridControls(btnSelectAll, btnSelectNone);

			panelButtons.setTop(panelSelectButtons);

			BorderPane panelFeatures = new BorderPane();
			panelFeatures.setCenter(tableFeatures);
			panelFeatures.setBottom(panelButtons);
			

			return panelFeatures;
		}

		void selectItems(Collection<T> toSelect) {
			for (var item : toSelect) {
				var temp = itemPool.get(item);
				if (temp != null)
					temp.setSelected(true);
			}
		}
		
		private ObservableList<SelectableItem<T>> getSelectableFeatures() {
			return tableFeatures.getItems();
		}

		private void updateItems(final Collection<T> availableItems) {
			// Ensure we have a set, to avoid duplicate woes
			Set<T> availableItemSet;
			if (availableItems instanceof Set)
				availableItemSet = (Set<T>)availableItems;
			else
				availableItemSet = new TreeSet<>(availableItems);
			
			List<SelectableItem<T>> items = new ArrayList<>();
			for (T item : availableItemSet) {
				items.add(getSelectableItem(item));
			}

			// It may be the case that this was requested on a background thread - if so, make sure the GUI is updated correctly
			if (Platform.isFxApplicationThread()) {
				tableFeatures.getItems().setAll(items);
			} else {
				Platform.runLater(() -> {
					tableFeatures.getItems().setAll(items);
				});
			}
		}


		private Map<T, SelectableItem<T>> itemPool = new HashMap<>();



		private SelectableItem<T> getSelectableItem(final T item) {
			SelectableItem<T> feature = itemPool.get(item);
			if (feature == null) {
				feature = new SelectableItem<>(item);
				itemPool.put(item, feature);
			}
			return feature;
		}


		public static class SelectableItem<T> {

			private ObjectProperty<T> item = new SimpleObjectProperty<>();
			private BooleanProperty selected = new SimpleBooleanProperty(false);

			public SelectableItem(final T item) {
				this.item.set(item);
			}

			public ReadOnlyObjectProperty<T> itemProperty() {
				return item;
			}

			public BooleanProperty selectedProperty() {
				return selected;
			}

			public boolean isSelected() {
				return selected.get();
			}

			public void setSelected(final boolean selected) {
				this.selected.set(selected);
			}

			public T getItem() {
				return item.get();
			}

		}
		
	}
	
	
}