package qupath.process.gui.tools.wand;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import qupath.fx.prefs.annotations.BooleanPref;
import qupath.fx.prefs.annotations.DoublePref;
import qupath.fx.prefs.annotations.Pref;
import qupath.fx.prefs.annotations.PrefCategory;
import qupath.fx.prefs.controlsfx.PropertySheetUtils;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.localization.QuPathResources;

@PrefCategory("Prefs.Drawing")
public class WandPreferences {

    @Pref(value = "Prefs.Drawing.wandType", type = WandToolEventHandler.WandType.class)
    public final ObjectProperty<WandToolEventHandler.WandType> wandType = WandToolEventHandler.wandTypeProperty();

    @DoublePref("Prefs.Drawing.wandSigma")
    public final DoubleProperty wandSigma = WandToolEventHandler.wandSigmaPixelsProperty();

    @DoublePref("Prefs.Drawing.wandSensivity")
    public final DoubleProperty wandSensitivity = WandToolEventHandler.wandSensitivityProperty();

    @BooleanPref("Prefs.Drawing.wandUseOverlays")
    public final BooleanProperty useOverlays = WandToolEventHandler.wandUseOverlaysProperty();

    private WandPreferences() {
    }

    static void installPreferences(QuPathGUI qupath) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> installPreferences(qupath));
            return;
        }
        // Add preference to adjust Wand tool behavior
        qupath.getPreferencePane()
                .getPropertySheet()
                .getItems()
                .addAll(PropertySheetUtils.parseAnnotatedItemsWithResources(QuPathResources.getLocalizedResourceManager(), new WandPreferences()));
    }

}
