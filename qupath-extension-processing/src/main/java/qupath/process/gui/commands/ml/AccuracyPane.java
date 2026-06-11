package qupath.process.gui.commands.ml;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;
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
        private final TreeTableView<TableItem.NumberItem> tableMetrics = new TreeTableView<>();
        private final TreeItem<TableItem.NumberItem> root = new TreeItem<>(TableItem.NumberItem.createEmpty("ROOT"));

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
            var colName = new TreeTableColumn<TableItem.NumberItem, String>("Metric");
            colName.setCellValueFactory(v -> v.getValue().getValue().nameProperty());

            var colValue = new TreeTableColumn<TableItem.NumberItem, Number>("Value");
            colValue.setCellValueFactory(v -> v.getValue().getValue().valueProperty());

            tableMetrics.getColumns().add(colName);
            tableMetrics.getColumns().add(colValue);

            tableMetrics.setColumnResizePolicy(TreeTableView.CONSTRAINED_RESIZE_POLICY_LAST_COLUMN);

            tableMetrics.setRoot(root);
            tableMetrics.setShowRoot(false);
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
                root.getChildren().clear();
                return;
            }

            // TODO: Combine confusion matrices!
            var confusionMaxtrixSum = ConfusionMatrix.sum(list);
            confusionMatrixPane.setConfusionMatrix(confusionMaxtrixSum);
            List<TreeItem<TableItem.NumberItem>> newItems = new ArrayList<>();

            var averaged = new TreeItem<>(TableItem.NumberItem.createEmpty("Average"));
            averaged.getChildren().addAll(
                    createItem("Accuracy", confusionMaxtrixSum.getAccuracy()),
                    createItem("F1", confusionMaxtrixSum.getF1()),
                    createItem("Precision", confusionMaxtrixSum.getPrecision()),
                    createItem("Recall", confusionMaxtrixSum.getRecall())
            );
            averaged.setExpanded(true);
            newItems.add(averaged);

            for (var label : confusionMaxtrixSum.getLabels()) {
                var item = new TreeItem<>(TableItem.NumberItem.createEmpty(Objects.toString(label)));
                item.getChildren().addAll(
                        createItem("F1", confusionMaxtrixSum.getF1(label)),
                        createItem("Precision", confusionMaxtrixSum.getPrecision(label)),
                        createItem("Recall", confusionMaxtrixSum.getRecall(label))
                );
                item.setExpanded(true);
                newItems.add(item);
            }
            root.getChildren().setAll(newItems);
        }

        private static TreeItem<TableItem.NumberItem> createItem(String name, double value) {
            return new TreeItem<>(TableItem.NumberItem.create(name, value));
        }


    }

}
