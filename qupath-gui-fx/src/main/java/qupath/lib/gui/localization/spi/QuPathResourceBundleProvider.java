package qupath.lib.gui.localization.spi;

import java.util.Locale;
import java.util.spi.ResourceBundleProvider;

public interface QuPathResourceBundleProvider extends ResourceBundleProvider {

    /**
     * Attempt to determine whether a bundle exists for a given locale <i>without</i> loading it,
     * if possible. Note that in some instances loading may still be necessary.
     * @param baseName
     * @param locale
     * @return
     */
    boolean hasBundle(String baseName, Locale locale);

}
