package qupath.opencv.ml.pixel.measurements;

import qupath.lib.images.cache.ImageCache;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.lazy.interfaces.LazyNumericValue;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.classes.PathClassTools;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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

        var metadata = server.getMetadata();
        List<PathClass> ignoredClasses = new ArrayList<>();
        List<PathClass> nonIgnoredClasses = new ArrayList<>();
        List<PathClass> normalClasses = new ArrayList<>();
        List<PathClass> derivedClasses = new ArrayList<>();

        for (var pathClass : metadata.getClassificationLabels().values()) {
            if (PathClassTools.isIgnoredClass(pathClass)) {
                ignoredClasses.add(pathClass);
            } else {
                nonIgnoredClasses.add(pathClass);
                if (pathClass.isDerivedClass())
                    derivedClasses.add(pathClass);
                else
                    normalClasses.add(pathClass);
            }

            cachedMeasurements.add(
                    new ExactClassArea(classifierCounts, pathClass, true, pixelArea, pixelUnit)
            );
            fullMeasurements.add(
                    new ExactClassArea(classifierCounts, pathClass, false, pixelArea, pixelUnit)
            );
        }

//        boolean containsMulticlass = nDerivedClasses > 0
//                || (metadata.getChannelType() == ImageServerMetadata.ChannelType.MULTICLASS_PROBABILITY && metadata.getClassificationLabels().values().stream().distinct().count() > 1);

        if (!ignoredClasses.isEmpty() && nonIgnoredClasses.size() > 1) {
            cachedMeasurements.add(new QuantifiedROIArea(classifierCounts, true, pixelArea, pixelUnit));
            fullMeasurements.add(new QuantifiedROIArea(classifierCounts, false, pixelArea, pixelUnit));
        }

        cachedMeasurements.add(new TotalROIArea(classifierCounts, true, pixelArea, pixelUnit));
        fullMeasurements.add(new TotalROIArea(classifierCounts, false, pixelArea, pixelUnit));

        if (!ignoredClasses.isEmpty() && nonIgnoredClasses.size() > 1) {
            for (var pathClass : nonIgnoredClasses) {
                cachedMeasurements.add(new QuantifiedPercentageArea(classifierCounts, pathClass, true));
                fullMeasurements.add(new QuantifiedPercentageArea(classifierCounts, pathClass, false));
            }
        }

        for (var pathClass : metadata.getClassificationLabels().values()) {
            cachedMeasurements.add(new TotalPercentageArea(classifierCounts, pathClass, true));
            fullMeasurements.add(new TotalPercentageArea(classifierCounts, pathClass, false));
        }

        // TODO: Handle multiclass

        this.cachedMeasurements = List.copyOf(cachedMeasurements);
        this.fullMeasurements = List.copyOf(fullMeasurements);
    }

    public List<LazyNumericValue<PathObject>> getCachedMeasurements() {
        return cachedMeasurements;
    }

    public List<LazyNumericValue<PathObject>> getMeasurements() {
        return fullMeasurements;
    }




    abstract static class AbstractLiveMeasurement implements LazyNumericValue<PathObject> {

        private final PixelClassifierCounts classifierCounts;
        private final String name;
        private final boolean live;
        private final String helpText;

        AbstractLiveMeasurement(PixelClassifierCounts classifierCounts, String name, boolean live, String helpText) {
            Objects.requireNonNull(classifierCounts);
            Objects.requireNonNull(name);
            this.classifierCounts = classifierCounts;
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

    static class QuantifiedROIArea extends AbstractLiveMeasurement {

        private final double pixelArea;

        QuantifiedROIArea(PixelClassifierCounts counts, boolean live, double pixelArea, String pixelUnit) {
            super(counts, "Quantified area (" + pixelUnit + ")", live, "Total rasterized ROI area, minus 'ignored' pixels (this is based on pixel counts, so might differ from the exact area based on geometry)");
            this.pixelArea = pixelArea;
        }

        @Override
        protected Number compute(ClassifiedPixelCounts counts) {
            return counts.getNonIgnoredCount() * pixelArea;
        }

    }

    static class TotalROIArea extends AbstractLiveMeasurement {

        private final double pixelArea;

        TotalROIArea(PixelClassifierCounts counts, boolean live, double pixelArea, String pixelUnit) {
            super(counts, "Total area (" + pixelUnit + ")", live, "Total rasterized ROI area (this is based on pixel counts, so might differ from the exact area based on geometry)");
            this.pixelArea = pixelArea;
        }

        @Override
        protected Number compute(ClassifiedPixelCounts counts) {
            return counts.getTotalCount() * pixelArea;
        }

    }

    static class ExactClassArea extends AbstractLiveMeasurement {

        private final PathClass pathClass;
        private final double pixelArea;

        ExactClassArea(PixelClassifierCounts counts, PathClass pathClass, boolean live, double pixelArea, String pixelUnit) {
            super(counts, pathClass + " area (" + pixelUnit + ")", live, "Area of " + pathClass);
            this.pathClass = pathClass;
            this.pixelArea = pixelArea;
        }

        @Override
        protected Number compute(ClassifiedPixelCounts counts) {
            return counts.getCountsForExactClass(pathClass) * pixelArea;
        }

    }

    static class QuantifiedPercentageArea extends AbstractLiveMeasurement {

        private final PathClass pathClass;

        QuantifiedPercentageArea(PixelClassifierCounts counts, PathClass pathClass, boolean live) {
            super(counts, pathClass + " % quantified area", live, "Percentage of quantified area classified as " + pathClass);
            this.pathClass = pathClass;
        }

        @Override
        protected Number compute(ClassifiedPixelCounts counts) {
            return counts.getCountsForExactClass(pathClass) * 100.0 / counts.getNonIgnoredCount();
        }

    }

    static class TotalPercentageArea extends AbstractLiveMeasurement {

        private final PathClass pathClass;

        TotalPercentageArea(PixelClassifierCounts counts, PathClass pathClass, boolean live) {
            super(counts, pathClass + " % total area", live, "Percentage of total area classified as " + pathClass);
            this.pathClass = pathClass;
        }

        @Override
        protected Number compute(ClassifiedPixelCounts counts) {
            return counts.getCountsForExactClass(pathClass) * 100.0 / counts.getTotalCount();
        }

    }




}
