package qupath.process.gui.commands.ml;

import java.util.List;
import javafx.beans.binding.Bindings;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.Skin;
import javafx.scene.control.Skinnable;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.CornerRadii;
import javafx.scene.paint.Color;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.tools.ColorToolsFX;
import qupath.opencv.ml.OpenCVClassifiers;
import qupath.opencv.ml.OpenCVClassifiers.RTreesClassifier.VariableImportance;

class FeatureDetailsPane extends Control implements Skinnable {

    private final ObservableList<VariableImportance> importance =
            FXCollections.observableArrayList();

    private final ObservableList<VariableImportance> unmodifiableImportance =
            FXCollections.unmodifiableObservableList(importance);

    FeatureDetailsPane() {
        super();
    }

    void update(OpenCVClassifiers.OpenCVStatModel model,
                List<String> featureNames) {

        if (model instanceof OpenCVClassifiers.RTreesClassifier rtrees) {
            importance.setAll(rtrees.getVariableImportance(featureNames));
        } else {
            importance.setAll(featureNames.stream()
                    .map(n -> new OpenCVClassifiers.RTreesClassifier.VariableImportance(n, Double.NaN))
                    .toList());
        }

    }

    protected Skin<?> createDefaultSkin() {
        return new FeatureDetailsPaneSkin(this);
    }

    private static class FeatureDetailsPaneSkin implements Skin<FeatureDetailsPane> {

        private final FeatureDetailsPane skinnable;

        private final ObservableList<VariableImportance> baseList = FXCollections.observableArrayList();
        private final SortedList<VariableImportance> sortedList = new SortedList<>(baseList);
        private final TableView<VariableImportance> table = new TableView<>(sortedList);
        private final TableColumn<VariableImportance, String> columnName = new TableColumn<>("Name");
        private final TableColumn<VariableImportance, Number> columnImportance = new TableColumn<>("Importance");

        private final BorderPane pane = new BorderPane(table);
        private final Label label = new Label("Feature importance is only calculated for RTrees classifiers");

        private final DoubleProperty maxImportance = new SimpleDoubleProperty(1.0);

        private FeatureDetailsPaneSkin(FeatureDetailsPane skinnable) {
            this.skinnable = skinnable;

            columnName.setCellValueFactory(this::extractName);
            columnImportance.setCellValueFactory(this::extractValue);

            columnImportance.setMinWidth(100);
            columnImportance.setMaxWidth(100);
            columnImportance.setCellFactory(v -> new ImportanceCell());

            sortedList.comparatorProperty().bind(table.comparatorProperty());

            table.getColumns().add(columnName);
            table.getColumns().add(columnImportance);
            table.getItems().addListener(this::handleListChange);
            table.setPlaceholder(new Label("No classifier trained"));
            table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_LAST_COLUMN);

            label.setMaxWidth(Double.MAX_VALUE);
            label.setAlignment(Pos.CENTER);
            label.setPadding(new Insets(5));
        }

        private ObservableValue<String> extractName(TableColumn.CellDataFeatures<VariableImportance, String> features) {
            var val = features.getValue();
            return new SimpleStringProperty(val.name());
        }

        private ObservableValue<Number> extractValue(TableColumn.CellDataFeatures<VariableImportance, Number> features) {
            var val = features.getValue();
            return new SimpleDoubleProperty(val.importance());
        }

        @Override
        public FeatureDetailsPane getSkinnable() {
            return skinnable;
        }

        @Override
        public Node getNode() {
            return pane;
        }

        @Override
        public void install() {
            Skin.super.install();
            Bindings.bindContent(baseList, skinnable.unmodifiableImportance);
        }

        @Override
        public void dispose() {
            Bindings.unbindContent(baseList, skinnable.unmodifiableImportance);
        }

        private void handleListChange(ListChangeListener.Change<? extends VariableImportance> event) {
            double maxImportance = 0.0;
            for (var v : event.getList()) {
                if (Double.isFinite(v.importance())) {
                    maxImportance = Math.max(v.importance(), maxImportance);
                }
            }
            boolean hasImportance = maxImportance > 0;
            columnImportance.setVisible(hasImportance);
            columnName.setSortable(hasImportance);
            if (hasImportance || event.getList().isEmpty()) {
                pane.setBottom(null);
            } else {
                pane.setBottom(label);
            }
            this.maxImportance.set(maxImportance);
        }

        /**
         * Cell to show importance with a colored bar in the background as a visual cue.
         */
        private class ImportanceCell extends TableCell<VariableImportance, Number> {

            private static final Color fill = ColorToolsFX.getColorWithOpacity(Color.LIGHTGREEN, 0.5);
            private static final CornerRadii cornerRadii = new CornerRadii(0, 2, 2, 0, false);

            ImportanceCell() {
                super();
                backgroundProperty().bind(
                        Bindings.createObjectBinding(this::computeBackground,
                            maxImportance, itemProperty(), widthProperty()));
            }

            private Background computeBackground() {
                var val = getItem();
                if (val == null)
                    return null;
                double importance = val.doubleValue();
                if (!Double.isFinite(val.doubleValue()) || importance <= 0)
                    return null;
                double pad = 4.0;
                double width = getWidth() - pad * 2;
                double length = val.doubleValue() / maxImportance.get() * width;
                return new Background(
                        new BackgroundFill(
                                fill,
                                cornerRadii,
                                new Insets(pad, width - length, pad, pad))
                );
            }

            @Override
            protected void updateItem(Number item, boolean empty) {
                super.updateItem(item, empty);
                if (item == null || empty || !Double.isFinite(item.doubleValue())) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                double val = item.doubleValue();
                setText(GeneralTools.formatNumber(val, 5));
            }

        }

    }

}
