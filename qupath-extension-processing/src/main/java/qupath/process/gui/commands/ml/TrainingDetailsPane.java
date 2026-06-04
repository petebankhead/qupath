package qupath.process.gui.commands.ml;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ObservableValue;
import javafx.scene.Node;
import javafx.scene.control.Control;
import javafx.scene.control.Skin;
import javafx.scene.control.Skinnable;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeSortMode;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableRow;
import javafx.scene.control.TreeTableView;
import javafx.util.Subscription;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.plugins.parameters.Parameter;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.opencv.ml.OpenCVClassifiers;

class TrainingDetailsPane extends Control implements Skinnable {

//    private final StringProperty text = new SimpleStringProperty();

    private final StringProperty classifierType = new SimpleStringProperty();
    private final ObjectProperty<Duration> trainingTime = new SimpleObjectProperty<>();
    private final ObjectProperty<ParameterList> parameters = new SimpleObjectProperty<>();

    TrainingDetailsPane() {
        super();
    }

    void update(OpenCVClassifiers.OpenCVStatModel model,
                Map<PathClass, Integer> labels,
                Duration trainingTime) {

        if (model == null) {
            this.classifierType.set(null);
            this.parameters.set(null);
            this.trainingTime.set(null);
        } else {
            this.classifierType.set(model.getName());
            var params = model.getParameterList();
            this.parameters.set(params == null ? null : params.duplicate());
            this.trainingTime.set(trainingTime);
        }

    }

    protected Skin<?> createDefaultSkin() {
        return new PixelClassifierDetailsPaneSkin(this);
    }

    private static class PixelClassifierDetailsPaneSkin implements Skin<TrainingDetailsPane> {

        private final TrainingDetailsPane skinnable;

        private final TreeTableView<Item> treeTable = new TreeTableView<>();

        private final TreeItem<Item> tiClassifier = new TreeItem<>(new Item("Classifier"));
        private final TreeItem<Item> tiParameters = new TreeItem<>(new Item("Parameters"));

        private Subscription subscription;

        private PixelClassifierDetailsPaneSkin(TrainingDetailsPane skinnable) {
            this.skinnable = skinnable;
            initializeTable();
        }

        private void initializeTable() {
            var root = new TreeItem<Item>(new Item("ROOT"));
            root.getChildren().add(tiClassifier);
            root.getChildren().add(tiParameters);

            root.setExpanded(true);
            tiClassifier.setExpanded(true);
            tiParameters.setExpanded(true);

            var colNames = new TreeTableColumn<Item, String>("Key");
            colNames.setCellValueFactory(i -> i.getValue().getValue().nameProperty());
            colNames.setSortable(false);


            var colValues = new TreeTableColumn<Item, String>("Value");
            colValues.setCellValueFactory(i -> i.getValue().getValue().valueProperty());
            colValues.setSortable(false);

            treeTable.setRoot(root);
            treeTable.setRowFactory(n -> new Row());
            treeTable.getColumns().add(colNames);
            treeTable.getColumns().add(colValues);
            treeTable.setShowRoot(false);
            treeTable.setColumnResizePolicy(TreeTableView.CONSTRAINED_RESIZE_POLICY_LAST_COLUMN);
            treeTable.setSortMode(TreeSortMode.ONLY_FIRST_LEVEL);
        }

        private void updateClassifier() {
            var modelType = skinnable.classifierType.get();
            if (modelType == null) {
                tiClassifier.getChildren().clear();
            } else {
                var newItems = new ArrayList<TreeItem<Item>>();
                newItems.add(
                        createTreeItem("Type", modelType));
                var duration = skinnable.trainingTime.get();
                if (duration != null) {
                    long millis = duration.toMillis();
                    if (millis >= 1) {
                        newItems.add(
                                createTreeItem("Training time", duration.toMillis() + " ms"));
                    } else {
                        newItems.add(
                                createTreeItem("Training time", "<1 ms"));
                    }
                }
                tiClassifier.getChildren().setAll(newItems);
            }
        }

        private void updateParameters() {
            var params = skinnable.parameters.get();
            Map<String, Parameter<?>> map = params == null ? Map.of() : params.getParameters();
            List<TreeItem<Item>> newItems = new ArrayList<>();
            for (var param : map.values()) {
                var val = param.getValueOrDefault();
                var stringVal = val == null ? null : Objects.toString(val);
                if (val != null && val.equals(param.getDefaultValue()))
                    stringVal += " (default)";
                newItems.add(
                        createTreeItem(
                                param.getPrompt(),
                                stringVal)
                );
            }
            tiParameters.getChildren().setAll(newItems);
        }

        private static TreeItem<Item> createTreeItem(String name, String value) {
            return new TreeItem<>(new Item(name, value));
        }

        @Override
        public TrainingDetailsPane getSkinnable() {
            return skinnable;
        }

        @Override
        public Node getNode() {
            return treeTable;
        }

        @Override
        public void install() {
            Skin.super.install();
            this.subscription = skinnable.classifierType.subscribe(this::updateClassifier)
                    .and(skinnable.trainingTime.subscribe(this::updateClassifier))
                    .and(skinnable.parameters.subscribe(this::updateParameters));
        }

        @Override
        public void dispose() {
            if (subscription != null) {
                subscription.unsubscribe();
                subscription = null;
            }
        }
    }

    private static class Item {

        private final StringProperty name;
        private final StringProperty value;

        Item(String name) {
            this(name, null);
        }

        Item(String name, String value) {
            this.name = new SimpleStringProperty(name);
            this.value = new SimpleStringProperty(value);
        }

        ObservableValue<String> nameProperty() {
            return name;
        }

        ObservableValue<String> valueProperty() {
            return value;
        }

    }

    private static class Row extends TreeTableRow<Item> {

        @Override
        protected void updateItem(Item value, boolean empty) {
            super.updateItem(value, empty);
            String style = null;
            if (value != null) {
                var val = value.valueProperty().getValue();
                if (val == null) {
                    // Style as title
                    style = "-fx-font-weight: bold;";
                } else if (val.endsWith("(default)")) {
                    // Make defaults less prominent
                    // (Ideally, we'd change the font only... but not sure how here)
                    style = "-fx-opacity: 0.65;";
                }
            }
            setStyle(style);
        }

    }


}
