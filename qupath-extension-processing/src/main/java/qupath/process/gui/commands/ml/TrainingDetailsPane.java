package qupath.process.gui.commands.ml;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.Node;
import javafx.scene.control.Control;
import javafx.scene.control.Skin;
import javafx.scene.control.Skinnable;
import javafx.scene.control.TextArea;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.plugins.parameters.EmptyParameter;
import qupath.opencv.ml.OpenCVClassifiers;

class TrainingDetailsPane extends Control implements Skinnable {

    private final StringProperty text = new SimpleStringProperty();

    TrainingDetailsPane() {
        super();
    }

    void update(OpenCVClassifiers.OpenCVStatModel model,
                Map<PathClass, Integer> labels,
                Duration trainingTime) {

        StringBuilder sb = new StringBuilder()
                .append("CLASSIFIER")
                .append("\n - ")
                .append(model.getName())
                .append("\nTraining time: ")
                .append(trainingTime.toMillis()).append(" ms")
                .append("\n")
                .append("\n");
        var params = model.getParameterList();
        if (params != null) {
            sb.append("PARAMETERS");
            for (var entry : params.getParameters().entrySet()) {
                var p = entry.getValue();
                if (p.isHidden())
                    continue;
                sb.append("\n - ")
                        .append(p.getPrompt());
                if (!(p instanceof EmptyParameter)) {
                    sb.append(" = ")
                            .append(p.getValueOrDefault());
                    if (Objects.equals(p.getValueOrDefault(), p.getDefaultValue()))
                        sb.append(" (default)");
                }
            }
            sb.append("\n\n");
        }

        sb.append("OUTPUTS");
        for (var entry : labels.entrySet()) {
            sb.append("\n - ")
                    .append(entry.getKey())
                    .append(" = ")
                    .append(entry.getValue());
        }
        sb.append("\n")
                .append("\n");

//        sb.append("TRAINING DATA")
//                .append("\n - ")
//                .append("Training data: ").append(trainSamples.rows()).append(" x ").append(trainSamples.cols())
//                .append("\n - ")
//                .append("Target data: ").append(targets.rows()).append(" x ").append(targets.cols())
//                .append("\n")
//                .append("\n")
//                .append("Current accuracy on the ")
//                .append(testSet)
//                .append(": ")
//                .append(GeneralTools.formatNumber(nCorrect*100.0/n, 3))
//                .append("%")
//                .append("\n");

        this.text.set(sb.toString());
    }

    protected Skin<?> createDefaultSkin() {
        return new PixelClassifierDetailsPaneSkin(this);
    }

    private static class PixelClassifierDetailsPaneSkin implements Skin<TrainingDetailsPane> {

        private final TrainingDetailsPane skinnable;

        private final TextArea textArea = new TextArea();

        private PixelClassifierDetailsPaneSkin(TrainingDetailsPane skinnable) {
            this.skinnable = skinnable;
        }

        @Override
        public TrainingDetailsPane getSkinnable() {
            return skinnable;
        }

        @Override
        public Node getNode() {
            return textArea;
        }

        @Override
        public void install() {
            Skin.super.install();
            this.textArea.textProperty().bind(skinnable.text);
        }

        @Override
        public void dispose() {
            this.textArea.textProperty().unbind();
        }
    }

}
