package qupath.process.gui.commands.ml;

import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.Control;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Skin;
import javafx.scene.control.Skinnable;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;


class AccuracyPane<T> extends Control implements Skinnable {

    private final ObservableList<ConfusionMatrix<T>> confusionMatrices = FXCollections.observableArrayList();

    public ObservableList<ConfusionMatrix<T>> getConfusionMatrices() {
        return confusionMatrices;
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new ConfusionMatrixSkin<>(this);
    }

    private static class ConfusionMatrixSkin<T> implements Skin<AccuracyPane<T>> {

        private final AccuracyPane<T> skinnable;

        private final GridPane pane = new GridPane();
        private final BorderPane borderPane = new BorderPane(pane);
        private final ConfusionMatrixPane<T> confusionMatrixPane = new ConfusionMatrixPane<>();
        private final TableView<Metric> tableMetrics = new TableView<>();

        private final ListChangeListener<ConfusionMatrix<T>> listChangeListener = this::handleMatricesChange;

        private ConfusionMatrixSkin(AccuracyPane<T> skinnable) {
            this.skinnable = skinnable;
            initializeTable();
            initialize();
        }

        private void initialize() {
            confusionMatrixPane.setPadding(new Insets(10));
            confusionMatrixPane.setMinHeight(Control.USE_COMPUTED_SIZE);
            confusionMatrixPane.setMaxHeight(Double.MAX_VALUE);

            var scrollConfusion = new ScrollPane(confusionMatrixPane);
            scrollConfusion.setFitToWidth(true);
            scrollConfusion.setFitToHeight(true);
            scrollConfusion.setMaxHeight(Double.MAX_VALUE);
//            var titledConfusion = new TitledPane("Confusion matrix", scrollConfusion);
//            titledConfusion.setMaxHeight(Double.MAX_VALUE);
//            titledConfusion.setMinHeight(TitledPane.USE_COMPUTED_SIZE);

            var splitPane = new SplitPane(
                    tableMetrics,
                    scrollConfusion
            );
            splitPane.setOrientation(Orientation.VERTICAL);

            borderPane.setCenter(splitPane);
//            borderPane.setBottom(tableMetrics);
//            pane.add(confusionMatrixPane, 0, 0, GridPane.REMAINING, 1);
//            pane.add(tableMetrics, 0, 1,  GridPane.REMAINING, 1);
        }

        private void initializeTable() {
            var colName = new TableColumn<Metric, String>("Metric");
            colName.setCellValueFactory(v -> new SimpleStringProperty(v.getValue().name()));

            var colValue = new TableColumn<Metric, Number>("Value");
            colValue.setCellValueFactory(v -> new SimpleDoubleProperty(v.getValue().value()));

            tableMetrics.getColumns().add(colName);
            tableMetrics.getColumns().add(colValue);

            tableMetrics.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_LAST_COLUMN);
        }

        @Override
        public AccuracyPane<T> getSkinnable() {
            return skinnable;
        }

        @Override
        public Node getNode() {
            return borderPane;
        }

        @Override
        public void install() {
            Skin.super.install();
            skinnable.getConfusionMatrices().addListener(listChangeListener);
            updateList();
        }

        @Override
        public void dispose() {
            skinnable.getConfusionMatrices().removeListener(listChangeListener);
        }

        private void handleMatricesChange(ListChangeListener.Change<? extends ConfusionMatrix<T>> change) {
            updateList();
        }

        private void updateList() {
            var list = skinnable.getConfusionMatrices();
            if (list.isEmpty()) {
                confusionMatrixPane.setConfusionMatrix(null);
                tableMetrics.getItems().clear();
                return;
            }
            // TODO: Combine confusion matrices!
            var confusion = ConfusionMatrix.sum(list);
            confusionMatrixPane.setConfusionMatrix(confusion);
            tableMetrics.getItems().setAll(
                    new Metric("Accuracy", confusion.getAccuracy()),
                    new Metric("F1", confusion.getF1()),
                    new Metric("Precision", confusion.getPrecision()),
                    new Metric("Recall", confusion.getRecall())
            );
        }

    }


    record Metric(String name, double value) {}

}
