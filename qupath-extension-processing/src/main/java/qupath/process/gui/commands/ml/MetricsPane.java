package qupath.process.gui.commands.ml;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Control;
import javafx.scene.control.Pagination;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Skin;
import javafx.scene.control.Skinnable;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableRow;
import javafx.scene.control.TreeTableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import qupath.opencv.ml.ConfusionMatrix;


class MetricsPane<T> extends Control implements Skinnable {

    /**
     * Observable list of all confusion matrices to display.
     * These are typically from different cross-validation folds.
     */
    private final ObservableList<ConfusionMatrix<T>> confusionMatrices = FXCollections.observableArrayList();

    /**
     * Boolean property to show merged results by summing the confusion matrices only.
     * If false, the table shows results for each individual confusion matrix.
     */
    private final BooleanProperty showMergedOnly = new SimpleBooleanProperty(true);

    public ObservableList<ConfusionMatrix<T>> getConfusionMatrices() {
        return confusionMatrices;
    }

    public BooleanProperty showMergedOnlyProperty() {
        return showMergedOnly;
    }

    public boolean getShowMergedOnly() {
        return showMergedOnlyProperty().get();
    }

    public void setShowMergedOnly(boolean showOnly) {
        showMergedOnlyProperty().set(showOnly);
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new ConfusionMatrixSkin<>(this);
    }

    private static class ConfusionMatrixSkin<T> implements Skin<MetricsPane<T>> {

        private final MetricsPane<T> skinnable;

        private final GridPane pane = new GridPane();
        private final BorderPane borderPane = new BorderPane(pane);
        private final ConfusionMatrixControl<T> confusionMatrixControl = new ConfusionMatrixControl<>();
        private final TreeTableView<TableItem.NumberItem> tableMetrics = new TreeTableView<>();
        private final CheckBox cbMerged = new CheckBox();

        private final Pagination pagination = new Pagination();

        private final TreeItem<TableItem.NumberItem> rootMerged = new TreeItem<>(TableItem.NumberItem.createEmpty("ROOT"));
        private final TreeItem<TableItem.NumberItem> rootAll = new TreeItem<>(TableItem.NumberItem.createEmpty("ROOT"));

        private final ListChangeListener<ConfusionMatrix<T>> listChangeListener = this::handleMatricesChange;

        private ConfusionMatrixSkin(MetricsPane<T> skinnable) {
            this.skinnable = skinnable;
            initializeTable();
            initialize();
        }

        private void initialize() {
            confusionMatrixControl.setPadding(new Insets(10));
            confusionMatrixControl.setMinHeight(Control.USE_COMPUTED_SIZE);
            confusionMatrixControl.setMaxHeight(Double.MAX_VALUE);

            var paneTable = new BorderPane(tableMetrics);
            cbMerged.setText("Show merged results");
            cbMerged.setMaxWidth(Double.MAX_VALUE);
            cbMerged.setAlignment(Pos.CENTER);
            cbMerged.selectedProperty().bindBidirectional(skinnable.showMergedOnlyProperty());
            paneTable.setBottom(cbMerged);

            var scrollConfusion = new ScrollPane(confusionMatrixControl);
            scrollConfusion.setFitToWidth(true);
            scrollConfusion.setFitToHeight(true);
            scrollConfusion.setMaxHeight(Double.MAX_VALUE);

            var splitPane = new SplitPane(
                    paneTable,
                    scrollConfusion
            );
            splitPane.setOrientation(Orientation.VERTICAL);

            borderPane.setCenter(splitPane);
        }

        private void initializeTable() {
            var colName = new TreeTableColumn<TableItem.NumberItem, String>("Metric");
            colName.setCellValueFactory(v -> v.getValue().getValue().nameProperty());

            var colValue = new TreeTableColumn<TableItem.NumberItem, Number>("Value");
            colValue.setCellValueFactory(v -> v.getValue().getValue().valueProperty());

            tableMetrics.getColumns().add(colName);
            tableMetrics.getColumns().add(colValue);

            tableMetrics.setColumnResizePolicy(TreeTableView.CONSTRAINED_RESIZE_POLICY_LAST_COLUMN);
            tableMetrics.setRowFactory(t -> new Row());

            tableMetrics.rootProperty().bind(Bindings.when(skinnable.showMergedOnly)
                    .then(rootMerged)
                    .otherwise(rootAll));
            tableMetrics.setShowRoot(false);
        }

        @Override
        public MetricsPane<T> getSkinnable() {
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
                confusionMatrixControl.setConfusionMatrix(null);
                rootAll.getChildren().clear();
                rootMerged.getChildren().clear();
                return;
            }

            // TODO: Combine confusion matrices!
            var confusionMaxtrixSum = ConfusionMatrix.sum("Merged", list);
            confusionMatrixControl.setConfusionMatrix(confusionMaxtrixSum);
            List<TreeItem<TableItem.NumberItem>> newItems = new ArrayList<>();

            var labels = confusionMaxtrixSum.getLabels();

            List<TreeItem<TableItem.NumberItem>> mergedItems = new ArrayList<>();
            if (labels.size() > 2) {
                mergedItems.add(createItem("Average", metricsForMatrix(confusionMaxtrixSum)));
            }
            for (var label : labels) {
                mergedItems.add(
                        createItem(Objects.toString(label), metricsForLabel(confusionMaxtrixSum, label)));
            }
            rootMerged.getChildren().setAll(mergedItems);

            List<TreeItem<TableItem.NumberItem>> allItems = new ArrayList<>();
            for (var matrix: list) {
                List<TreeItem<TableItem.NumberItem>> byLabel = new ArrayList<>();
                String name = matrix.getName();
                if (labels.size() > 2) {
                    byLabel.add(createItem("Average", metricsForMatrix(matrix)));
                }
                for (var label : labels) {
                    byLabel.add(
                            createItem(Objects.toString(label), metricsForLabel(matrix, label)));
                }
                allItems.add(createItem(name, byLabel));
            }
            rootAll.getChildren().setAll(allItems);
        }

        private static TreeItem<TableItem.NumberItem> createItem(String title, List<TreeItem<TableItem.NumberItem>> children) {
            var item = new TreeItem<>(TableItem.NumberItem.createEmpty(title));
            item.getChildren().addAll(children);
            item.setExpanded(true);
            return item;
        }

        private static List<TreeItem<TableItem.NumberItem>> metricsForMatrix(ConfusionMatrix<?> matrix) {
            return List.of(
                    createItem("Accuracy", matrix.getAccuracy()),
                    createItem("F1", matrix.getF1()),
                    createItem("Precision", matrix.getPrecision()),
                    createItem("Recall", matrix.getRecall())
            );
        }

        private static <T> List<TreeItem<TableItem.NumberItem>> metricsForLabel(ConfusionMatrix<T> matrix, T label) {
            return List.of(
                    createItem("F1", matrix.getF1(label)),
                    createItem("Precision", matrix.getPrecision(label)),
                    createItem("Recall", matrix.getRecall(label))
            );
        }

        private static TreeItem<TableItem.NumberItem> createItem(String name, double value) {
            return new TreeItem<>(TableItem.NumberItem.create(name, value));
        }

    }

    private static class Row extends TreeTableRow<TableItem.NumberItem> {

        @Override
        protected void updateItem(TableItem.NumberItem value, boolean empty) {
            super.updateItem(value, empty);
            String style = null;
            if (value != null && value.isEmpty()) {
                // Style as title
                style = "-fx-font-weight: bold;";
            }
            setStyle(style);
        }

    }

}
