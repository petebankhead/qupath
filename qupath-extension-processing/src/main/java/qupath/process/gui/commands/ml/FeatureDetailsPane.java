package qupath.process.gui.commands.ml;

import java.util.List;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.Skin;
import javafx.scene.control.Skinnable;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
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

        private final TableView<VariableImportance> table = new TableView<>();
        private final TableColumn<VariableImportance, String> columnName = new TableColumn<>("Name");
        private final TableColumn<VariableImportance, Number> columnImportance = new TableColumn<>("Importance");

        private final BorderPane pane = new BorderPane(table);
        private final Label label = new Label("Feature importance is only calculated for RTrees classifiers");

        private FeatureDetailsPaneSkin(FeatureDetailsPane skinnable) {
            this.skinnable = skinnable;

            columnName.setCellValueFactory(this::extractName);
            columnImportance.setCellValueFactory(this::extractValue);

            table.getColumns().add(columnName);
            table.getColumns().add(columnImportance);
            table.getItems().addListener(this::handleListChange);
            table.setPlaceholder(new Label("No classifier trained"));
            table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);

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
            Bindings.bindContent(table.getItems(), skinnable.unmodifiableImportance);
        }

        @Override
        public void dispose() {
            Bindings.unbindContent(table.getItems(), skinnable.unmodifiableImportance);
        }

        private void handleListChange(ListChangeListener.Change<? extends VariableImportance> event) {
            boolean hasImportance = false;
            for (var v : event.getList()) {
                if (Double.isFinite(v.importance())) {
                    hasImportance = true;
                    break;
                }
            }
            columnImportance.setVisible(hasImportance);
            columnName.setSortable(hasImportance);
            if (hasImportance || event.getList().isEmpty()) {
                pane.setBottom(null);
            } else {
                pane.setBottom(label);
            }
        }

    }

}
