package qupath.process.gui.commands.ml;

import java.util.ArrayList;
import java.util.List;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Skin;
import javafx.scene.control.Skinnable;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.util.Subscription;
import qupath.fx.utils.FXUtils;
import qupath.fx.utils.GridPaneUtils;
import qupath.lib.gui.commands.MiniViewers;
import qupath.lib.gui.viewer.QuPathViewer;

/**
 * A mini viewer and associated controls to view overlays for pixel classifier output and features.
 */
class TrainingViewerPane extends Control implements Skinnable {

    private final QuPathViewer viewer;
    private final PixelClassifierOverlayManager overlayManager;

    /**
     * Resolution at which the classifier is being trained.
     */
    private final ObjectProperty<ClassificationResolution> resolution = new SimpleObjectProperty<>();

    /**
     * List of all available features.
     */
    private final ObservableList<String> availableFeatures = FXCollections.observableArrayList();

    TrainingViewerPane(QuPathViewer viewer, PixelClassifierOverlayManager overlayManager) {
        super();
        this.viewer = viewer;
        this.overlayManager = overlayManager;
    }

    public ObjectProperty<ClassificationResolution> resolutionProperty() {
        return resolution;
    }

    public ClassificationResolution getResolution() {
        return resolutionProperty().get();
    }

    public void setResolution(ClassificationResolution resolution) {
        resolutionProperty().set(resolution);
    }

    public ObservableList<String> getAvailableFeatures() {
        return availableFeatures;
    }

    protected Skin<?> createDefaultSkin() {
        return new TrainingViewerPaneSkin(this, viewer);
    }

    private static class TrainingViewerPaneSkin implements Skin<TrainingViewerPane> {

        private final TrainingViewerPane skinnable;

        private final BorderPane pane = new BorderPane();

        private final MiniViewers.MiniViewerManager miniViewer;
        private final ComboBox<String> comboDisplayFeatures = PixelClassifierUtils.createHGrowComboBox();
        private final Slider sliderFeatureOpacity = new Slider(0.0, 1.0, 1.0);
        private final Spinner<Double> spinFeatureMin = FXUtils.createDynamicStepSpinner(-Double.MAX_VALUE, Double.MAX_VALUE, 0, 0.1, 1);
        private final Spinner<Double> spinFeatureMax = FXUtils.createDynamicStepSpinner(-Double.MAX_VALUE, Double.MAX_VALUE, 1, 0.1, 1);

        private final Tooltip resolutionTooltip = new Tooltip();

        // Store for convenience
        private final PixelClassifierOverlayManager overlayManager;

        // Store to avoid garbage collection
        private final ObjectProperty<Double> minDisplay;
        private final ObjectProperty<Double> maxDisplay;

        private final ListChangeListener<String> featureChangeListener = this::handleAvailableFeaturesChange;
        private Subscription subscription;

        private TrainingViewerPaneSkin(TrainingViewerPane skinnable, QuPathViewer viewer) {
            this.skinnable = skinnable;
            this.miniViewer = MiniViewers.createManager(viewer);
            this.overlayManager = skinnable.overlayManager;
            this.minDisplay = overlayManager.featureMinDisplayProperty().asObject();
            this.maxDisplay = overlayManager.featureMaxDisplayProperty().asObject();
            initialize();
        }

        private void initialize() {
            initializeViewer();
            initializeControls();
        }

        private void initializeViewer() {
            var viewerPane = miniViewer.getPane();
            Tooltip.install(viewerPane, new Tooltip("View image at classification resolution"));
            pane.setCenter(viewerPane);
        }

        private void initializeControls() {

            comboDisplayFeatures.getSelectionModel().selectedItemProperty().subscribe(overlayManager::ensureOverlaySet);
            comboDisplayFeatures.setMaxWidth(Double.MAX_VALUE);
            spinFeatureMin.setPrefWidth(100);
            spinFeatureMax.setPrefWidth(100);

            var btnFeatureAuto = new Button("Auto");
            btnFeatureAuto.setMinWidth(Button.USE_PREF_SIZE);
            btnFeatureAuto.setOnAction(e -> overlayManager.autoFeatureContrast());

            comboDisplayFeatures.getItems().setAll(PixelClassifierOverlayManager.DEFAULT_CLASSIFICATION_OVERLAY);
            comboDisplayFeatures.getSelectionModel().select(PixelClassifierOverlayManager.DEFAULT_CLASSIFICATION_OVERLAY);

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

            var paneFeatures = new GridPane();
            spinFeatureMax.setTooltip(new Tooltip("Choose classification result or feature overlay to display (Warning: This requires a lot of memory & computation!)"));
            spinFeatureMin.setTooltip(new Tooltip("Min display value for feature overlay"));
            spinFeatureMax.setTooltip(new Tooltip("Max display value for feature overlay"));
            sliderFeatureOpacity.setTooltip(new Tooltip("Adjust classification/feature overlay opacity"));

            GridPaneUtils.addGridRow(paneFeatures, 0, 0, null,
                    comboDisplayFeatures, comboDisplayFeatures, comboDisplayFeatures, comboDisplayFeatures);
            GridPaneUtils.addGridRow(paneFeatures, 1, 0, null,
                    sliderFeatureOpacity, spinFeatureMin, spinFeatureMax, btnFeatureAuto);

            GridPaneUtils.setMaxWidth(Double.MAX_VALUE, comboDisplayFeatures, sliderFeatureOpacity);
            GridPaneUtils.setFillWidth(Boolean.TRUE, comboDisplayFeatures, sliderFeatureOpacity);
            GridPaneUtils.setHGrowPriority(Priority.ALWAYS, comboDisplayFeatures, sliderFeatureOpacity);
            paneFeatures.setHgap(5);
            paneFeatures.setVgap(5);
            paneFeatures.setPadding(new Insets(5));
            paneFeatures.prefWidthProperty().bind(pane.prefWidthProperty());

            pane.setBottom(paneFeatures);
        }

        @Override
        public TrainingViewerPane getSkinnable() {
            return skinnable;
        }

        @Override
        public Node getNode() {
            return pane;
        }

        @Override
        public void install() {
            Skin.super.install();
            bindControls();
        }

        private void bindControls() {
            minDisplay.bindBidirectional(spinFeatureMin.getValueFactory().valueProperty());
            maxDisplay.bindBidirectional(spinFeatureMax.getValueFactory().valueProperty());
            overlayManager.selectedNameProperty().bind(comboDisplayFeatures.getSelectionModel().selectedItemProperty());
            overlayManager.overlayOpacityProperty().bindBidirectional(sliderFeatureOpacity.valueProperty());
            skinnable.getAvailableFeatures().addListener(featureChangeListener);
            subscription = skinnable.resolutionProperty().subscribe(this::updateResolution);
            updateResolution(skinnable.getResolution());
            updateAvailableFeatures();
        }

        private void updateResolution(ClassificationResolution resolution) {
            var server = skinnable.viewer.getServer();
            if (resolution != null && server != null) {
                resolutionTooltip.setText("Classification resolution: \n" + resolution);
                miniViewer.setDownsample(resolution.cal.getAveragedPixelSize().doubleValue()  /
                        server.getPixelCalibration().getAveragedPixelSize().doubleValue());
            } else {
                resolutionTooltip.setText("No image available");
            }
            Tooltip.install(miniViewer.getPane(), resolutionTooltip);
        }

        private void handleAvailableFeaturesChange(ListChangeListener.Change<? extends String> change) {
            updateAvailableFeatures();
        }

        private void updateAvailableFeatures() {
            String currentSelection = comboDisplayFeatures.getSelectionModel().getSelectedItem();
            List<String> featureNames = new ArrayList<>();
            featureNames.add(PixelClassifierOverlayManager.DEFAULT_CLASSIFICATION_OVERLAY);
            featureNames.addAll(skinnable.getAvailableFeatures());
            if (comboDisplayFeatures.getItems().equals(featureNames))
                return;
            comboDisplayFeatures.getItems().setAll(featureNames);
            if (featureNames.contains(currentSelection))
                comboDisplayFeatures.getSelectionModel().select(currentSelection);
            else
                comboDisplayFeatures.getSelectionModel().selectFirst();
        }

        @Override
        public void dispose() {
            unbindControls();
        }

        private void unbindControls() {
            minDisplay.unbindBidirectional(spinFeatureMin.getValueFactory().valueProperty());
            maxDisplay.unbindBidirectional(spinFeatureMax.getValueFactory().valueProperty());
            overlayManager.selectedNameProperty().unbind();
            overlayManager.overlayOpacityProperty().unbindBidirectional(sliderFeatureOpacity.valueProperty());
            skinnable.getAvailableFeatures().removeListener(featureChangeListener);
            if (subscription != null)
                subscription.unsubscribe();
        }

    }

}
