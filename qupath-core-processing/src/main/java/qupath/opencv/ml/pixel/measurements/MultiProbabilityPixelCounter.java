package qupath.opencv.ml.pixel.measurements;

import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.classes.PathClassTools;

import java.awt.image.WritableRaster;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoublePredicate;

class MultiProbabilityPixelCounter implements PixelCounter {

    private final List<PathClass> pathClasses;
    private final DoublePredicate positivePredicate;

    MultiProbabilityPixelCounter(List<PathClass> pathClasses, DoublePredicate positivePredicate) {
        this.pathClasses = List.copyOf(pathClasses);
        this.positivePredicate = positivePredicate;
    }

    @Override
    public ClassifiedPixelCounts getCounts(WritableRaster raster, PixelClassifierCounts.MaskPredicate predicate) {
        assert raster.getNumBands() == pathClasses.size();

        Map<PathClass, Long> counts = new LinkedHashMap<>();
        double[] values = null;
        var accumulator = new PathClassAccumulator(pathClasses.size());
        long total = 0L;
        for (int y = 0; y < raster.getHeight(); y++) {
            for (int x = 0; x < raster.getWidth(); x++) {
                if (predicate.test(x, y)) {
                    total++;
                    // We want to update the counts for the PathClass that merges all the names from the
                    // individual positive bands (channels)
                    values = raster.getPixel(x, y, values);
                    accumulator.reset();
                    for (int b = 0; b < values.length; b++) {
                        if (positivePredicate.test(values[b])) {
                            accumulator.add(pathClasses.get(b));
                        }
                    }
                    var result = accumulator.get();
                    if (result != null) {
                        counts.compute(result, (_, val) -> val == null ? 1 : val + 1);
                    }
                }
            }
        }
        return ClassifiedPixelCounts.create(counts, total);
    }

    private static final class PathClassAccumulator {

        private final PathClass[] pathClasses;
        private int size = 0;

        PathClassAccumulator(int n) {
            this.pathClasses = new PathClass[n];
        }

        void reset() {
            size = 0;
        }

        void add(PathClass pathClass) {
            pathClasses[size++] = pathClass;
        }

        PathClass get() {
            if (size == 0)
                return null;
            else if (size == 1)
                return pathClasses[0];
            else if (size == 2) {
                return PathClassTools.mergeClasses(pathClasses[0], pathClasses[1]);
            } else {
                PathClass output = pathClasses[0];
                for (int i = 1; i < pathClasses.length; i++) {
                    output = PathClassTools.mergeClasses(output, pathClasses[i]);
                }
                return output;
            }
        }

    }

}
