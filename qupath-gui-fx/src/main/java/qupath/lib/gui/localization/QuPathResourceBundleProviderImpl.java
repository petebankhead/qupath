package qupath.lib.gui.localization;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.UserDirectoryManager;
import qupath.lib.gui.localization.spi.QuPathResourceBundleProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.spi.AbstractResourceBundleProvider;

public class QuPathResourceBundleProviderImpl extends AbstractResourceBundleProvider
        implements QuPathResourceBundleProvider {

    private static final Logger logger = LoggerFactory.getLogger(QuPathResourceBundleProvider.class);

    // Directory containing the code
    private Path codePath;

    public QuPathResourceBundleProviderImpl() {
        try {
            codePath = Paths.get(
                            QuPathResourceBundleProviderImpl.class
                                    .getProtectionDomain()
                                    .getCodeSource()
                                    .getLocation()
                                    .toURI())
                    .getParent();
        } catch (Exception e) {
            logger.debug("Error identifying code directory: " + e.getLocalizedMessage(), e);
        }
    }

    @Override
    public ResourceBundle getBundle(String baseName, Locale locale) {
        return searchForBundle(baseName, locale);
    }

    @Override
    public boolean hasBundle(String baseName, Locale locale) {
        return searchForBundlePath(baseName, locale) != null;
    }

    private ResourceBundle searchForBundle(String baseName, Locale locale) {
        Path propertiesPath = searchForBundlePath(baseName, locale);
        if (propertiesPath != null) {
            try (var reader = Files.newBufferedReader(propertiesPath, StandardCharsets.UTF_8)) {
                logger.debug("Reading bundle from {}", propertiesPath);
                return new PropertyResourceBundle(reader);
            } catch (Exception e) {
                logger.debug(e.getLocalizedMessage(), e);
            }
        }
        return null;
    }


    private Path searchForBundlePath(String baseName, Locale locale) {
        String propertiesName = getShortPropertyFileName(baseName, locale);
        for (var localizationDirectory : getLocalizationDirectoryPaths()) {
            logger.debug("Searching for {} in {}", propertiesName, localizationDirectory);
            try {
                var propertiesPath = localizationDirectory.resolve(propertiesName);
                if (Files.isRegularFile(propertiesPath)) {
                    return propertiesPath;
                }
            } catch (Exception e) {
                logger.debug(e.getLocalizedMessage(), e);
            }
        }
        return null;
    }


    private String getShortPropertyFileName(String baseName, Locale locale) {
        String bundleName = toBundleName(baseName, locale);
        int ind = bundleName.replace('.', '/').lastIndexOf('/');
        String propertiesBaseName = ind < 0 ? bundleName : bundleName.substring(ind+1);
        return propertiesBaseName + ".properties";
    }


    private List<Path> getLocalizationDirectoryPaths() {
        List<Path> paths = new ArrayList<>();
        var userSearchPath = getUserLocalizationDirectoryOrNull();
        if (userSearchPath != null)
            paths.add(userSearchPath);
        var codeSearchPath = getCodeLocalizationDirectoryOrNull();
        if (codeSearchPath != null)
            paths.add(codeSearchPath);
        return paths;
    }

    private static Path getDirectoryOrNull(Path path) {
        if (path != null && Files.isDirectory(path))
            return path;
        return null;
    }

    private Path getUserLocalizationDirectoryOrNull() {
        var userPath = UserDirectoryManager.getInstance().getLocalizationDirectoryPath();
        if (userPath != null)
            return getDirectoryOrNull(userPath);
        return null;
    }

    private Path getCodeLocalizationDirectoryOrNull() {
        if (codePath == null)
            return null;
        return getDirectoryOrNull(codePath.resolve(UserDirectoryManager.DIR_LOCALIZATION));
    }

}
