package qupath.opencv.ml.pixel.measurements;

import qupath.lib.images.cache.ImageCache;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.lazy.interfaces.LazyNumericValue;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

public class DefaultPixelClassifierMeasurements implements PixelMeasurements {

    private final ImageServer<BufferedImage> server;
    private final PixelClassifierCounts classifierCounts;

    private final List<LazyNumericValue<PathObject>> cachedMeasurements;
    private final List<LazyNumericValue<PathObject>> fullMeasurements;

    public DefaultPixelClassifierMeasurements(ImageCache cache, ImageServer<BufferedImage> server) {
        this.server = server;
        this.classifierCounts = new PixelClassifierCounts(cache, server, server.getMetadata().getClassificationLabels());

        List<LazyNumericValue<PathObject>> cachedMeasurements = new ArrayList<>();
        List<LazyNumericValue<PathObject>> fullMeasurements = new ArrayList<>();

        double pixelArea = 1.0;
        String pixelUnit = "px^2";
        var cal = server.getPixelCalibration();
        if (cal.hasPixelSizeMicrons()) {
            pixelArea = cal.getPixelWidthMicrons() * cal.getPixelHeightMicrons();
            pixelUnit = "µm^2";
        }

        for (var pathClass : server.getMetadata().getClassificationLabels().values()) {
            cachedMeasurements.add(
                    new ExactClassArea(pathClass, true, pixelArea, pixelUnit)
            );
            fullMeasurements.add(
                    new ExactClassArea(pathClass, false, pixelArea, pixelUnit)
            );
        }
        cachedMeasurements.add(new TotalROIArea(true, pixelArea, pixelUnit));
        fullMeasurements.add(new TotalROIArea(false, pixelArea, pixelUnit));

        this.cachedMeasurements = List.copyOf(cachedMeasurements);
        this.fullMeasurements = List.copyOf(fullMeasurements);
    }

    public List<LazyNumericValue<PathObject>> getCachedMeasurements() {
        return cachedMeasurements;
    }

    public List<LazyNumericValue<PathObject>> getMeasurements() {
        return fullMeasurements;
    }




    abstract class AbstractLiveMeasurement implements LazyNumericValue<PathObject> {

        private final String name;
        private final boolean live;
        private final String helpText;

        AbstractLiveMeasurement(String name, boolean live, String helpText) {
            this.name = name;
            this.live = live;
            this.helpText = helpText;
        }

        @Override
        public String getName() {
            return live ? "(Live) " + name : name;
        }

        @Override
        public Number getValue(PathObject input) {
            var roi = input.isRootObject() ? null : input.getROI();
            ClassifiedPixelCounts counts;
            if (live) {
                counts = roi == null ? classifierCounts.getCachedCounts() : classifierCounts.getCachedCounts(roi);
            } else {
                counts = roi == null ? classifierCounts.getCounts() : classifierCounts.getCounts(roi);
            }
            return counts == null ? null : compute(counts);
        }

        protected abstract Number compute(ClassifiedPixelCounts counts);

        @Override
        public String getHelpText() {
            return helpText;
        }

    }

    class TotalROIArea extends AbstractLiveMeasurement {

        private final double pixelArea;

        TotalROIArea(boolean live, double pixelArea, String pixelUnit) {
            super("Total area (" + pixelUnit + ")", live, "Total rasterized ROI area (this is based on pixel counts, so might differ from the exact area based on geometry)");
            this.pixelArea = pixelArea;
        }

        @Override
        protected Number compute(ClassifiedPixelCounts counts) {
            return counts.getTotalCount() * pixelArea;
        }

    }

    class ExactClassArea extends AbstractLiveMeasurement {

        private final PathClass pathClass;
        private final double pixelArea;

        ExactClassArea(PathClass pathClass, boolean live, double pixelArea, String pixelUnit) {
            super("Area of " + pathClass + " (" + pixelUnit + ")", live, "Area of " + pathClass);
            this.pathClass = pathClass;
            this.pixelArea = pixelArea;
        }

        @Override
        protected Number compute(ClassifiedPixelCounts counts) {
            return counts.getCountsForExactClass(pathClass) * pixelArea;
        }

    }




}
