package qupath.opencv.ml.pixel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.awt.common.BufferedImageTools;
import qupath.lib.images.cache.ImageCache;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.TileRequest;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.classes.PathClassTools;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.interfaces.ROI;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.DoublePredicate;

public class PixelClassifierMeasurements {

    private static final Logger logger = LoggerFactory.getLogger(PixelClassifierMeasurements.class);

    private static final int MAX_LABELS = 512;

    private final ImageCache cache;
    private final ImageServer<BufferedImage> server;

    private final PixelCounter counter;

    private final Map<TileRequest, PixelCounts> fullTileCounts = new ConcurrentHashMap<>();
    private final Map<ROI, PixelCounts> roiTileCounts = new ConcurrentHashMap<>();
    private final Map<String, PixelCounts> fullImageCounts = new ConcurrentHashMap<>();

    public PixelClassifierMeasurements(ImageCache cache, ImageServer<BufferedImage> server, Map<Integer, PathClass> labels) {
        Objects.requireNonNull(cache);
        Objects.requireNonNull(server);
        this.cache = cache;
        this.server = server;
        this.counter = createCounter(server, labelsToList(labels));
    }

    private List<PathClass> labelsToList(Map<Integer, PathClass> labels) {
        int max = -1;
        for (var entry : labels.entrySet()) {
            Integer val = entry.getKey();
            if (val != null) {
                if (val < 0)
                    throw new IllegalArgumentException("Negative label not supported: " + val);
                else {
                    max = Math.max(max, val);
                }
            } else {
                logger.warn("Null label for {}", entry.getValue());
            }
        }
        if (max < 0) {
            return List.of();
        }
        if (max > MAX_LABELS) {
            throw new IllegalArgumentException("More than " + MAX_LABELS + " not supported!");
        }
        var array = new PathClass[max + 1];
        for (var entry : labels.entrySet()) {
            array[entry.getKey()] = entry.getValue();
        }
        return List.of(array);
    }

    private PixelCounter createCounter(ImageServer<BufferedImage> server, List<PathClass> pathClasses) {
        var channelType = server.getMetadata().getChannelType();
        return switch (channelType) {
            case PROBABILITY -> new ArgMaxProbabilityPixelCounter(pathClasses);
            case MULTICLASS_PROBABILITY -> new MultiProbabilityPixelCounter(pathClasses, createProbabilityPredicate(server.getMetadata().getPixelType()));
            case CLASSIFICATION -> new ClassifiedPixelCounter(pathClasses);
            case DEFAULT, FEATURE, DENSITY -> throw new IllegalArgumentException("Channel type " + channelType + " not supported!");
        };
    }

    private static DoublePredicate createProbabilityPredicate(PixelType pixelType) {
        if (pixelType.isFloatingPoint())
            return v -> v >= 0.5;
        else
            return v -> v >= 127.5;
    }


    public PixelCounts getCachedCounts(ROI roi) {
        if (roi == null) {
            return fullImageCounts.computeIfAbsent(server.getPath(), _ -> calculateForFullImage(false));
        } else {
            return roiTileCounts.computeIfAbsent(roi, r -> calculateForROI(r, false));
        }
    }

    public PixelCounts getCounts(ROI roi) {
        if (roi == null) {
            return fullImageCounts.computeIfAbsent(server.getPath(), _ -> calculateForFullImage(true));
        } else {
            return roiTileCounts.computeIfAbsent(roi, r -> calculateForROI(r, true));
        }
    }

    public PixelCounts getCachedCounts() {
        return getCachedCounts(null);
    }

    public PixelCounts getCounts() {
        return getCounts(null);
    }


    private PixelCounts calculateForFullImage(boolean requestMissingTiles) {
        var tiles = server.getTileRequestManager().getTileRequestsForLevel(0);
        List<PixelCounts> counts = new ArrayList<>();
        for (var tile : tiles) {
            var count = fullTileCounts.computeIfAbsent(tile, t -> calculateForTile(t, requestMissingTiles));
            if (!requestMissingTiles && count == null)
                return null;
        }
        return sum(counts);
    }

    private PixelCounts calculateForROI(ROI roi, boolean requestMissingTiles) {
        try {
            return calculateForROIImpl(roi, requestMissingTiles);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return null;
        }
    }

    private PixelCounts calculateForROIImpl(ROI roi, boolean requestMissingTiles) throws ExecutionException, InterruptedException {
        Map<TileRequest, BufferedImage> requests = new LinkedHashMap<>();
        // Check for tiles, and stop early if we don't have everything that we need
        for (var tile : getTileRequests(roi)) {
            var img = cache.getIfPresent(tile.getRegionRequest());
            if (img == null && !requestMissingTiles)
                return null;
            requests.put(tile, img);
        }
        if (requests.isEmpty())
            return PixelCounts.EMPTY;
        List<PixelCounts> allCounts = new ArrayList<>();
        for (var tile : requests.keySet()) {
            allCounts.add(
                    counter.getCounts(
                            cache.requestImageTile(tile.getRegionRequest(), server).get().getRaster(),
                            MaskPredicate.fromIntRaster(createMask(tile, roi).getRaster(), 0)
                    )
            );
        }
        return sum(allCounts);
    }

    private PixelCounts calculateForTile(TileRequest tile, boolean requestMissingTiles) {
        try {
            return calculateForTileImpl(tile, requestMissingTiles);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return null;
        }
    }

    private PixelCounts calculateForTileImpl(TileRequest tile, boolean requestMissingTiles) throws ExecutionException, InterruptedException {
        var request = tile.getRegionRequest();
        var img = requestMissingTiles ? cache.requestImageTile(request, server).get() : cache.getIfPresent(request);
        if (img == null)
            return null;
        else
            return counter.getCounts(img.getRaster(), MaskPredicate.ALWAYS_TRUE);
    }

    private static BufferedImage createMask(TileRequest tile, ROI roi) {
        return BufferedImageTools.createROIMask(tile.getTileWidth(), tile.getTileHeight(), roi, tile.getRegionRequest());
    }




    private Collection<TileRequest> getTileRequests(ROI roi) {
        if (roi == null) {
            return server.getTileRequestManager().getTileRequestsForLevel(0);
        } else {
            return server.getTileRequestManager().getTileRequests(
                    RegionRequest.createInstance(server.getPath(), server.getDownsampleForResolution(0), roi));
        }
    }



    private interface PixelCounter {

        PixelCounts getCounts(WritableRaster raster, MaskPredicate predicate);

    }

    private static class ClassifiedPixelCounter implements PixelCounter {

        private final List<PathClass> pathClasses;

        private ClassifiedPixelCounter(List<PathClass> pathClasses) {
            this.pathClasses = List.copyOf(pathClasses);
        }

        @Override
        public PixelCounts getCounts(WritableRaster raster, MaskPredicate predicate) {
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

            return new PixelCounts(counts, total);
        }

    }

    private static class ArgMaxProbabilityPixelCounter implements PixelCounter {

        private final List<PathClass> pathClasses;

        private ArgMaxProbabilityPixelCounter(List<PathClass> pathClasses) {
            this.pathClasses = List.copyOf(pathClasses);
        }

        @Override
        public PixelCounts getCounts(WritableRaster raster, MaskPredicate predicate) {
            assert raster.getNumBands() == pathClasses.size();

            Map<PathClass, Long> counts = new LinkedHashMap<>();
            double[] values = null;
            long total = 0L;
            for (int y = 0; y < raster.getHeight(); y++) {
                for (int x = 0; x < raster.getWidth(); x++) {
                    if (predicate.test(x, y)) {
                        total++;
                        // We want to update the counts for the PathClass that merges all the names from the
                        // individual positive bands (channels)
                        values = raster.getPixel(x, y, values);
                        int ind = argMax(values);
                        if (ind >= 0) {
                            counts.compute(pathClasses.get(ind), (_, val) -> val == null ? 1 : val + 1);
                        }
                    }
                }
            }
            return new PixelCounts(counts, total);
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

    private static class MultiProbabilityPixelCounter implements PixelCounter {

        private final List<PathClass> pathClasses;
        private final DoublePredicate positivePredicate;

        private MultiProbabilityPixelCounter(List<PathClass> pathClasses, DoublePredicate positivePredicate) {
            this.pathClasses = List.copyOf(pathClasses);
            this.positivePredicate = positivePredicate;
        }

        @Override
        public PixelCounts getCounts(WritableRaster raster, MaskPredicate predicate) {
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
            return new PixelCounts(counts, total);
        }

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

    @FunctionalInterface
    public interface MaskPredicate {

        MaskPredicate ALWAYS_TRUE = (_, _) -> true;

        static MaskPredicate fromDoubleRaster(Raster raster, double falseValue) {
            return (x, y) -> raster.getSampleDouble(x, y, 0) != falseValue;
        }

        static MaskPredicate fromIntRaster(Raster raster, int falseValue) {
            return (x, y) -> raster.getSample(x, y, 0) != falseValue;
        }

        boolean test(int x, int y);

    }

    public static class PixelCounts {

        public static final PixelCounts EMPTY = new PixelCounts(Map.of(), 0L);

        private final Map<PathClass, Long> counts;
        private final long totalCount;

        PixelCounts(Map<PathClass, Long> counts, long totalCount) {
            this.counts = Map.copyOf(counts);
            this.totalCount = totalCount;
        }

        public long getTotalCount() {
            return totalCount;
        }

        public long getCountsForSetContains(String name) {
            long total = 0L;
            for (var entry : counts.entrySet()) {
                if (PathClassTools.containsName(entry.getKey(), name))
                    total += entry.getValue();
            }
            return total;
        }

        public long getCountsForUnclassified() {
            return counts.getOrDefault(PathClass.NULL_CLASS, 0L);
        }

        public long getCountsForExactClass(PathClass pathClass) {
            return counts.getOrDefault(pathClass, 0L);
        }

        public long getCountsForClassSetMatches(Set<String> names) {
            Objects.requireNonNull(names);
            long total = 0L;
            for (var entry : counts.entrySet()) {
                if (names.equals(entry.getKey().toSet()))
                    total += entry.getValue();
            }
            return total;
        }

    }

    private static PixelCounts sum(Collection<? extends PixelCounts> counts) {
        return sum(counts.toArray(PixelCounts[]::new));
    }

    private static PixelCounts sum(PixelCounts... counts) {
        if (counts.length == 0)
            return PixelCounts.EMPTY;
        else if (counts.length == 1) {
            return counts[0];
        }
        var map = new LinkedHashMap<PathClass, Long>();
        long total = 0L;
        for (var otherCounts : counts) {
            for (var entry : otherCounts.counts.entrySet()) {
                map.compute(entry.getKey(), (_, c) -> c == null ? entry.getValue() : c + entry.getValue());
                total += otherCounts.getTotalCount();
            }
        }
        return new PixelCounts(map, total);
    }


//    private interface IntSet {
//
//        boolean contains(int val);
//
//        int size();
//
//        default boolean containsAny(int... values) {
//           for (int val : values) {
//               if (contains(val))
//                   return true;
//           }
//           return false;
//        }
//
//         default boolean containsAll(int... values) {
//            for (int val : values) {
//                if (!contains(val))
//                    return false;
//            }
//            return true;
//         }
//
//    }
//
//    private record SingleIntSet(int value) implements IntSet {
//
//        @Override
//        public boolean contains(int val) {
//            return value == val;
//        }
//
//        @Override
//        public int size() {
//            return 1;
//        }
//
//    }
//
//    private record TwoIntSet(int value1, int value2) implements IntSet {
//
//        @Override
//        public boolean contains(int val) {
//            return value1 == val || value2 == val;
//        }
//
//        @Override
//        public int size() {
//            return 2;
//        }
//
//    }
//
//    private record ThreeIntSet(int value1, int value2, int value3) implements IntSet {
//
//        @Override
//        public boolean contains(int val) {
//            return value1 == val || value2 == val || value3 == val;
//        }
//
//    }
//
//
//    public static IntSet get(int val) {
//
//    }
//
//    public static IntSet get(int val1, int val2) {
//        if (val1 == val2) {
//            return get(val1);
//        } else if (val2 < val1) {
//            return get(val2, val1);
//        } else {
//
//        }
//    }


}
