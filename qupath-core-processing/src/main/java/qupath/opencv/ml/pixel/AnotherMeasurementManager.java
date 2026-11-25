package qupath.opencv.ml.pixel;

import qupath.lib.images.cache.ImageCache;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.lazy.interfaces.LazyNumericValue;
import qupath.lib.lazy.interfaces.LazyValue;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.classes.PathClassTools;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AnotherMeasurementManager implements PixelClassificationMeasurementManager {

    private final ImageServer<BufferedImage> server;
    private final PixelClassifierMeasurements measurements;
    private final List<String> measurementNames;

    private static final String AREA_PREFIX = "Area ";

    private final List<LazyNumericValue<PathObject>> cachedMeasurements;
    private final List<LazyNumericValue<PathObject>> fullMeasurements;

    public AnotherMeasurementManager(ImageCache cache, ImageServer<BufferedImage> server) {
        this.server = server;
        this.measurements = new PixelClassifierMeasurements(cache, server, server.getMetadata().getClassificationLabels());
        this.measurementNames = server.getMetadata().getClassificationLabels()
                .values()
                .stream()
                .filter(p -> !PathClassTools.isNullClass(p))
                .map(n -> AREA_PREFIX + n).toList();

        List<LazyNumericValue<PathObject>> cachedMeasurements = new ArrayList<>();
        List<LazyNumericValue<PathObject>> fullMeasurements = new ArrayList<>();

        for (var pathClass : server.getMetadata().getClassificationLabels().values()) {
            String name = AREA_PREFIX + pathClass;
            String description = "Area covered by pixels with class " + pathClass;
            cachedMeasurements.add(
                    LazyValue.createNumeric(name, description, p -> getCachedMeasurementValue(p, name))
            );
            fullMeasurements.add(
                    LazyValue.createNumeric(name, description, p -> getMeasurementValue(p, name))
            );
        }

        this.cachedMeasurements = List.copyOf(cachedMeasurements);
        this.fullMeasurements = List.copyOf(fullMeasurements);
    }

    public List<LazyNumericValue<PathObject>> getCachedMeasurements() {
        return cachedMeasurements;
    }

    public List<LazyNumericValue<PathObject>> getMeasurements() {
        return fullMeasurements;
    }

    @Override
    public Number getCachedMeasurementValue(PathObject pathObject, String name) {
        PixelClassifierMeasurements.PixelCounts counts;
        if (pathObject.isRootObject())
            counts = measurements.getCachedCounts();
        else
            counts = measurements.getCachedCounts(pathObject.getROI());
        return countsToArea(counts, name.substring(AREA_PREFIX.length()));
    }

    private double countsToArea(PixelClassifierMeasurements.PixelCounts counts, String className) {
        if (counts == null)
            return Double.NaN;
        var pathClass = PathClass.fromString(className);
        long n;
        if (pathClass.isDerivedClass())
            n = counts.getCountsForClassSetMatches(pathClass.toSet());
        else
            n = counts.getCountsForSetContains(className);
        return n * server.getMetadata().getPixelCalibration().getPixelWidth().doubleValue()
                * server.getMetadata().getPixelCalibration().getPixelHeight().doubleValue();
    }

    @Override
    public Number getMeasurementValue(PathObject pathObject, String name) {
        PixelClassifierMeasurements.PixelCounts counts;
        if (pathObject.isRootObject())
            counts = measurements.getCounts();
        else
            counts = measurements.getCounts(pathObject.getROI());
        return countsToArea(counts, name.substring(AREA_PREFIX.length()));
    }

    @Override
    public List<String> getMeasurementNames() {
        return measurementNames;
    }

}
