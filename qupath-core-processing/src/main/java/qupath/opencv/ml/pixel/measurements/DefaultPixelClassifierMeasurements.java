package qupath.opencv.ml.pixel.measurements;

import qupath.lib.images.cache.ImageCache;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.lazy.interfaces.LazyNumericValue;
import qupath.lib.lazy.interfaces.LazyValue;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

public class DefaultPixelClassifierMeasurements implements PixelMeasurements {

    private final ImageServer<BufferedImage> server;
    private final PixelClassifierCounts measurements;

    private static final String AREA_PREFIX = "Area ";

    private final List<LazyNumericValue<PathObject>> cachedMeasurements;
    private final List<LazyNumericValue<PathObject>> fullMeasurements;

    public DefaultPixelClassifierMeasurements(ImageCache cache, ImageServer<BufferedImage> server) {
        this.server = server;
        this.measurements = new PixelClassifierCounts(cache, server, server.getMetadata().getClassificationLabels());

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

    private Number getCachedMeasurementValue(PathObject pathObject, String name) {
        ClassifiedPixelCounts counts;
        if (pathObject.isRootObject())
            counts = measurements.getCachedCounts();
        else
            counts = measurements.getCachedCounts(pathObject.getROI());
        return countsToArea(counts, name.substring(AREA_PREFIX.length()));
    }

    private double countsToArea(ClassifiedPixelCounts counts, String className) {
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

    private Number getMeasurementValue(PathObject pathObject, String name) {
        ClassifiedPixelCounts counts;
        if (pathObject.isRootObject())
            counts = measurements.getCounts();
        else
            counts = measurements.getCounts(pathObject.getROI());
        return countsToArea(counts, name.substring(AREA_PREFIX.length()));
    }

}
