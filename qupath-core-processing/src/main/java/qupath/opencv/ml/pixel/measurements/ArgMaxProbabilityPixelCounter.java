package qupath.opencv.ml.pixel.measurements;

import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.classes.PathClassTools;

import java.awt.image.WritableRaster;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class ArgMaxProbabilityPixelCounter implements PixelCounter {

    private final List<PathClass> pathClasses;

    ArgMaxProbabilityPixelCounter(List<PathClass> pathClasses) {
        this.pathClasses = List.copyOf(pathClasses);
    }

    @Override
    public ClassifiedPixelCounts getCounts(WritableRaster raster, PixelClassifierCounts.MaskPredicate predicate) {
        assert raster.getNumBands() == pathClasses.size();

        Map<PathClass, Long> counts = new LinkedHashMap<>();
        double[] values = null;
        long total = 0L;
        long nonIgnoredTotal = 0L;
        for (int y = 0; y < raster.getHeight(); y++) {
            for (int x = 0; x < raster.getWidth(); x++) {
                if (predicate.test(x, y)) {
                    total++;
                    // We want to update the counts for the PathClass that merges all the names from the
                    // individual positive bands (channels)
                    values = raster.getPixel(x, y, values);
                    int ind = argMax(values);
                    if (ind >= 0) {
                        var pathClass = pathClasses.get(ind);
                        if (!PathClassTools.isIgnoredClass(pathClass))
                            nonIgnoredTotal++;
                        counts.compute(pathClass, (_, val) -> val == null ? 1 : val + 1);
                    }
                }
            }
        }
        return ClassifiedPixelCounts.create(counts, total, nonIgnoredTotal);
    }

    private static int argMax(double[] values) {
        int ind = -1;
        double maxValue = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < values.length; i++) {
            double val = values[i];
            if (val > maxValue) {
                ind = i;
                maxValue = val;
            }
        }
        return ind;
    }

}
