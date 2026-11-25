package qupath.opencv.ml.pixel.measurements;

import qupath.lib.objects.classes.PathClass;

import java.awt.image.WritableRaster;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class ClassifiedPixelCounter implements PixelCounter {

    private final List<PathClass> pathClasses;

    ClassifiedPixelCounter(List<PathClass> pathClasses) {
        this.pathClasses = List.copyOf(pathClasses);
    }

    @Override
    public ClassifiedPixelCounts getCounts(WritableRaster raster, PixelClassifierCounts.MaskPredicate predicate) {
        assert raster.getNumBands() == 1;

        long[] rawCounts = new long[pathClasses.size()];
        long total = 0L;
        for (int y = 0; y < raster.getHeight(); y++) {
            for (int x = 0; x < raster.getWidth(); x++) {
                if (predicate.test(x, y)) {
                    total++;
                    int sample = raster.getSample(x, y, 0);
                    rawCounts[sample]++;
                }
            }
        }

        Map<PathClass, Long> counts = new LinkedHashMap<>();
        for (int i = 0; i < pathClasses.size(); i++) {
            long localCount = rawCounts[i];
            counts.compute(pathClasses.get(i), (_, val) -> val == null ? localCount : val + localCount);
        }

        return ClassifiedPixelCounts.create(counts, total);
    }

}
