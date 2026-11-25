package qupath.opencv.ml.pixel.measurements;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.awt.common.BufferedImageTools;
import qupath.lib.images.cache.ImageCache;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.TileRequest;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.interfaces.ROI;

import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.DoublePredicate;

public class PixelClassifierCounts {

    private static final Logger logger = LoggerFactory.getLogger(PixelClassifierCounts.class);

    private static final int MAX_LABELS = 512;

    private final ImageCache cache;
    private final ImageServer<BufferedImage> server;

    private final PixelCounter counter;

    private final Map<TileRequest, ClassifiedPixelCounts> fullTileCounts = new ConcurrentHashMap<>();
    private final Map<ROI, ClassifiedPixelCounts> roiTileCounts = new ConcurrentHashMap<>();
    private final Map<String, ClassifiedPixelCounts> fullImageCounts = new ConcurrentHashMap<>();

    public PixelClassifierCounts(ImageCache cache, ImageServer<BufferedImage> server, Map<Integer, PathClass> labels) {
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


    public ClassifiedPixelCounts getCachedCounts(ROI roi) {
        if (roi == null) {
            return fullImageCounts.computeIfAbsent(server.getPath(), _ -> calculateForFullImage(false));
        } else {
            return roiTileCounts.computeIfAbsent(roi, r -> calculateForROI(r, false));
        }
    }

    public ClassifiedPixelCounts getCounts(ROI roi) {
        if (roi == null) {
            return fullImageCounts.computeIfAbsent(server.getPath(), _ -> calculateForFullImage(true));
        } else {
            return roiTileCounts.computeIfAbsent(roi, r -> calculateForROI(r, true));
        }
    }

    public ClassifiedPixelCounts getCachedCounts() {
        return getCachedCounts(null);
    }

    public ClassifiedPixelCounts getCounts() {
        return getCounts(null);
    }


    private ClassifiedPixelCounts calculateForFullImage(boolean requestMissingTiles) {
        var tiles = server.getTileRequestManager().getTileRequestsForLevel(0);
        List<ClassifiedPixelCounts> counts = new ArrayList<>();
        for (var tile : tiles) {
            var count = fullTileCounts.computeIfAbsent(tile, t -> calculateForTile(t, requestMissingTiles));
            if (!requestMissingTiles && count == null)
                return null;
        }
        return ClassifiedPixelCounts.sum(counts);
    }

    private ClassifiedPixelCounts calculateForROI(ROI roi, boolean requestMissingTiles) {
        try {
            return calculateForROIImpl(roi, requestMissingTiles);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return null;
        }
    }

    private ClassifiedPixelCounts calculateForROIImpl(ROI roi, boolean requestMissingTiles) throws ExecutionException, InterruptedException {
        Map<TileRequest, BufferedImage> requests = new LinkedHashMap<>();
        // Check for tiles, and stop early if we don't have everything that we need
        for (var tile : getTileRequests(roi)) {
            var img = cache.getIfPresent(tile.getRegionRequest());
            if (img == null && !requestMissingTiles)
                return null;
            requests.put(tile, img);
        }
        if (requests.isEmpty())
            return ClassifiedPixelCounts.EMPTY;
        List<ClassifiedPixelCounts> allCounts = new ArrayList<>();
        for (var tile : requests.keySet()) {
            var mask = createMaskPredicate(roi, tile);
            if (mask.equals(MaskPredicate.ALWAYS_FALSE))
                continue;
            // TODO: If ALWAYS_TRUE, we can reuse the full tiles counts
            // if (mask.equals(MaskPredicate.ALWAYS_TRUE))
            allCounts.add(
                    counter.getCounts(
                            cache.requestImageTile(tile.getRegionRequest(), server).get().getRaster(),
                            mask
                    )
            );
        }
        return ClassifiedPixelCounts.sum(allCounts);
    }

    private static MaskPredicate createMaskPredicate(ROI roi, TileRequest tile) {
        if (roi.isPoint()) {
            Set<SimplePoint> simplePoints = new HashSet<>();
            var region = tile.getRegionRequest();
            for (var p : roi.getAllPoints()) {
                int x = (int)Math.floor((p.getX() - region.getX()) / region.getDownsample());
                int y = (int)Math.floor((p.getY() - region.getY()) / region.getDownsample());
                if (x >= 0 && y >= 0 && x < tile.getTileWidth() && y < tile.getTileHeight()) {
                    simplePoints.add(new SimplePoint(x, y));
                }
            }
            if (simplePoints.isEmpty())
                return MaskPredicate.ALWAYS_FALSE;
            else
                return (x, y) -> simplePoints.contains(new SimplePoint(x, y));
        } else {
            var shape = roi.getShape();
            if (completelyContainsTile(shape, tile, 2.0)) {
                return MaskPredicate.ALWAYS_TRUE;
            }
            return MaskPredicate.fromIntRaster(createMask(tile, roi).getRaster(), 0);
        }
    }

    /**
     * Check if a ROI shape completely contains tile. This should err on the side of caution and
     * only return true if it is very confident that all pixels of the tile are inside the shape.
     * @param shape
     * @param tile
     * @param padding pad the tile to make the estimate more conservative (and deal with non-zero stroke thickness for shape masks)
     * @return
     */
    private static boolean completelyContainsTile(Shape shape, TileRequest tile, double padding) {
        return shape.contains(
                tile.getImageX()-padding,
                tile.getImageY()-padding,
                tile.getImageWidth()+padding*2,
                tile.getImageHeight()+padding*2
        );
    }

    private ClassifiedPixelCounts calculateForTile(TileRequest tile, boolean requestMissingTiles) {
        try {
            return calculateForTileImpl(tile, requestMissingTiles);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return null;
        }
    }

    private ClassifiedPixelCounts calculateForTileImpl(TileRequest tile, boolean requestMissingTiles) throws ExecutionException, InterruptedException {
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


    private record SimplePoint(int x, int y) {}

    @FunctionalInterface
    public interface MaskPredicate {

        MaskPredicate ALWAYS_TRUE = (_, _) -> true;

        MaskPredicate ALWAYS_FALSE = (_, _) -> false;

        static MaskPredicate fromDoubleRaster(Raster raster, double falseValue) {
            return (x, y) -> raster.getSampleDouble(x, y, 0) != falseValue;
        }

        static MaskPredicate fromIntRaster(Raster raster, int falseValue) {
            return (x, y) -> raster.getSample(x, y, 0) != falseValue;
        }

        boolean test(int x, int y);

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
