package qupath.lib.gui.images.stores;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Weigher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.regions.RegionRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.function.Predicate;

class ImageCache<T> {

    private static final Logger logger = LoggerFactory.getLogger(AbstractImageRegionStore.class);

    private final AsyncCache<RegionRequest, T> cache;
    private final long maxSizeBytes;

    private boolean isClosed = false;

    private final ExecutorService pool = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual()
                    .name("tile-cache", 1)
                    .factory()
    );


    ImageCache(final SizeEstimator<T> sizeEstimator, final long maxSizeBytes) {
        this.maxSizeBytes = maxSizeBytes;

        // Because Caffeine uses integer weights, and we sometimes have *very* large images, we convert our size estimates KB
        Weigher<RegionRequest, T> weigher = (var r, var t) -> (int)Long.min(Integer.MAX_VALUE, sizeEstimator.getApproxImageSize(t)/1024);
        long maxWeight = Long.max(1, maxSizeBytes / 1024);
        this.cache = Caffeine.newBuilder()
                .weigher(weigher)
                .maximumWeight(maxWeight)
//				.softValues() // Not possible for an async cache
                .recordStats()
                .removalListener((k, v, cause) -> {
                    if (cause == RemovalCause.COLLECTED) {
                        logger.debug("Cached tile collected: {}", k);
                    } else {
                        logger.trace("Cached tile removed due to {}: {}",cause, k);
                    }
                })
                .executor(pool)
                .buildAsync();

    }


    /**
     * Get the tile cache size, in bytes.
     * Image tiles larger than this cannot be cached.
     * @return
     */
    public long getMaxSizeBytes() {
        return maxSizeBytes;
    }

    public T getIfPresent(final RegionRequest request) {
        var future = cache.getIfPresent(request);
        return future == null ? null : future.getNow(null);
    }

    public ConcurrentMap<RegionRequest, T> getCache() {
        return cache.synchronous().asMap();
    }


    public long getCacheSize() {
        return cache.synchronous().estimatedSize();
    }

    public T getCachedTile(RegionRequest request) {
        return getIfPresent(request);
    }

    Set<RegionRequest> getKeys() {
        return cache.synchronous().asMap().keySet();
    }

    /**
     * Get a map of all cached tiles pertaining to a specific ImageServer.
     * @param serverPath the value of {@link ImageServer#getPath()}
     * @return
     */
    public Map<RegionRequest, T> getCachedTilesForServer(String serverPath) {
        Map<RegionRequest, T> tiles = new HashMap<>();
        for (var entry : cache.asMap().entrySet()) {
            if (Objects.equals(serverPath, entry.getKey().getPath())) {
                var future = entry.getValue();
                if (future.isDone()) {
                    var img = future.getNow(null);
                    if (img != null) {
                        tiles.put(entry.getKey(), future.getNow(null));
                    }
                }
            }
        }
        return tiles;
    }


    /**
     * Submit an image tile request, returning a future that can be used to get the tile.
     * @param server
     * @param request
     * @return
     */
    protected Future<T> requestImageTile(final ImageServer<T> server, final RegionRequest request) {
        return isClosed ? cache.getIfPresent(request) : cache.get(request, r -> readTile(server, r));
    }

    private T readTile(ImageServer<T> server, RegionRequest request) {
        if (isClosed) {
            logger.warn("Tile cache is closed, request will be ignored");
            return null;
        }
        try {
            var img = server.readRegion(request);
            logger.debug("Read tile {} in {}", request, Thread.currentThread());
            return img;
        } catch (IOException e) {
            logger.error("Error reading image region: {} ({})", e.getMessage(), request);
            logger.debug("Error reading image region", e);
            return null; // TODO: Consider exception propagation
        }
    }


    /**
     * Clear the cache and cancel any pending requests.
     */
    public void clearCache() {
        clearCache(true);
    }


    /**
     * Clear the cache and optionally stop any pending requests.
     * @param cancelRunning cancel any tasks that are currently fetching tiles
     */
    public synchronized void clearCache(final boolean cancelRunning) {
        if (cancelRunning) {
            var map = cache.asMap();
            for (var future : map.values()) {
                future.cancel(cancelRunning);
            }
        }
        cache.synchronous().invalidateAll();
    }


    private synchronized void clearCache(Predicate<RegionRequest> filter) {
        var iterator = cache.asMap().entrySet().iterator();
        List<RegionRequest> toRemove = new ArrayList<>();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (filter.test(entry.getKey())) {
                toRemove.add(entry.getKey());
                entry.getValue().cancel(false);
            }
        }
        cache.synchronous().invalidateAll(toRemove);
        cache.synchronous().cleanUp();
    }

    public synchronized void clearCacheForServer(final String serverPath) {
        clearCache(r -> Objects.equals(serverPath, r.getPath()));
    }

    public void clearCacheForRequestOverlap(final RegionRequest request) {
        clearCache(r -> r.overlapsRequest(request));
    }


    public void close() {
        // Try to cancel all workers
        isClosed = true;
        pool.shutdownNow();
        clearCache();
    }



}
