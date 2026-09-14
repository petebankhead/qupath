/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * Copyright (C) 2018 - 2025 QuPath developers, The University of Edinburgh
 * %%
 * QuPath is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * QuPath is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License 
 * along with QuPath.  If not, see <https://www.gnu.org/licenses/>.
 * #L%
 */

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
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;


/**
 * A generic ImageRegionStore.
 *
 * @author Pete Bankhead
 * @param <T> the generic parameter for an image (most likely BufferedImage)
 */
abstract class AbstractImageRegionStore<T> implements ImageRegionStore<T> {

	private static final Logger logger = LoggerFactory.getLogger(AbstractImageRegionStore.class);

	protected final AsyncCache<RegionRequest, T> originalCache;
	protected final ConcurrentMap<RegionRequest, CompletableFuture<T>> mapCacheAsync;
	protected final Map<RegionRequest, T> mapCache;

	/**
	 * Maximum size of thumbnail, in any dimension.
	 */
	private int maxThumbnailSize;
	
	/**
	 * Minimum size of thumbnail, in any dimension.
	 */
	private int minThumbnailSize = 16;

	/**
	 * Maximum tile cache size, in bytes
	 */
	private long tileCacheSizeBytes;

	private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();


	protected AbstractImageRegionStore(final SizeEstimator<T> sizeEstimator, final int thumbnailSize, final long tileCacheSizeBytes) {
		this.maxThumbnailSize = thumbnailSize;
		this.tileCacheSizeBytes = tileCacheSizeBytes;
		
		// Because Caffeine uses integer weights, and we sometimes have *very* large images, we convert our size estimates KB
		Weigher<RegionRequest, T> weigher = (var r, var t) -> (int)Long.min(Integer.MAX_VALUE, sizeEstimator.getApproxImageSize(t)/1024);
		long maxWeight = Long.max(1, tileCacheSizeBytes / 1024);
		this.originalCache = Caffeine.newBuilder()
				.weigher(weigher)
				.maximumWeight(maxWeight)
//				.softValues()
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
		this.mapCacheAsync = this.originalCache.asMap();
		this.mapCache = this.originalCache.synchronous().asMap();
	}


	/**
	 * Get the tile cache size, in bytes.
	 * Image tiles larger than this cannot be cached.
	 * @return
	 */
	public long getTileCacheSize() {
		return tileCacheSizeBytes;
	}
	
	/**
	 * Calculate the downsample value to use when generating a thumbnail image.
	 * @param width
	 * @param height
	 * @return
	 */
	double calculateThumbnailDownsample(int width, int height) {
		// We'll have trouble if we try to downsample until we have very few pixels in any dimension
		double maxDim = Math.max(width, height);
		double minDim = Math.min(width, height);
		if (minDim > minThumbnailSize) {
			double maxDownsample = minDim / minThumbnailSize;
			return Math.max(1, Math.min(maxDim / maxThumbnailSize, maxDownsample));
		}
		return 1.0;g
	}
	

	RegionRequest getThumbnailRequest(final ImageServer<T> server, final int zPosition, final int tPosition) {
		// Determine thumbnail size
		double downsample = 1;
		if (isPyramidalImageServer(server)) {
			downsample = calculateThumbnailDownsample(server.getWidth(), server.getHeight());
		}
		// Ensure we aren't accidentally upsampling (shouldn't actually happen)
		downsample = Math.max(downsample, 1);
		return RegionRequest.createInstance(server.getPath(), downsample, 0, 0, server.getWidth(), server.getHeight(), zPosition, tPosition);
	}

	protected T getIfPresent(final RegionRequest request) {
		var future = originalCache.getIfPresent(request);
		return future == null ? null : future.getNow(null);
	}
	
	/* (non-Javadoc)
	 * @see qupath.lib.images.stores.ImageRegionStore#getCachedThumbnail(qupath.lib.images.servers.ImageServer, int, int)
	 */
	@Override
	public T getCachedThumbnail(ImageServer<T> server, int zPosition, int tPosition) {
		RegionRequest request = getThumbnailRequest(server, zPosition, tPosition);
		return getIfPresent(request);
	}

	public T getClosestCachedThumbnail(ImageServer<T> server, int zPosition, int tPosition) {
		RegionRequest request = getThumbnailRequest(server, zPosition, tPosition);
		var dist = new PlaneDistance(request.getZ(), request.getT());
		return mapCache.keySet().stream().filter(r -> sameRegionIgnoringPlane(request, r))
				.sorted(Comparator.comparingDouble(dist::distance))
				.map(this::getIfPresent)
				.filter(Objects::nonNull)
				.findFirst()
				.orElse(null);
	}

	private static class PlaneDistance {
		private final int z;
		private final int t;
		public PlaneDistance(int z, int t) {
			this.z = z;
			this.t = t;
		}

		public int distance(RegionRequest region) {
			int dz = Math.abs(z - region.getZ());
			int dt = Math.abs(t - region.getT());
			return dz + dt;
		}

	}

	private static boolean sameRegionIgnoringPlane(RegionRequest r1, RegionRequest r2) {
		return Objects.equals(r1.getPath(), r2.getPath()) &&
				r1.getX() == r2.getX() && r1.getY() == r2.getY() &&
				r1.getWidth() == r2.getWidth() && r1.getHeight() == r2.getHeight();
	}

	
	public Map<RegionRequest, T> getCache() {
		return mapCache;
	}

	public long getCacheSize() {
		return originalCache.synchronous().estimatedSize();
	}

	
	/* (non-Javadoc)
	 * @see qupath.lib.images.stores.ImageRegionStore#getCachedTile(qupath.lib.images.servers.ImageServer, qupath.lib.regions.RegionRequest)
	 */
	@Override
	public T getCachedTile(ImageServer<T> server, RegionRequest request) {
		return getIfPresent(request);
	}	
	
	/**
	 * Get a map of all cached tiles pertaining to a specific ImageServer.
	 * @param server
	 * @return
	 */
	public synchronized Map<RegionRequest, T> getCachedTilesForServer(ImageServer<T> server) {
		Map<RegionRequest, T> tiles = new HashMap<>();
		var serverPath = server.getPath();
		for (var entry : mapCache.entrySet()) {
			if (entry.getValue() != null && entry.getKey().getPath().equals(serverPath))
				tiles.put(entry.getKey(), entry.getValue());
		}
		return tiles;
	}	
	
	
	private static boolean isPyramidalImageServer(ImageServer<?> server) {
		return server.nResolutions() > 1;
	}
	

	/**
	 * Submit an image tile request, returning a future that can be used to get the tile.
	 * @param server
	 * @param request
	 * @return
	 */
	protected Future<T> requestImageTile(final ImageServer<T> server, final RegionRequest request) {
		return originalCache.get(request, r -> readTile(server, r));
	}

	private T readTile(ImageServer<T> server, RegionRequest request) {
		try {
			System.err.println("Requesting in " + Thread.currentThread());
			var img = server.readRegion(request);
			logger.info("Read tile in {}", Thread.currentThread());
				System.err.println("Got " + img + " - " + Thread.currentThread());
				System.err.flush();
			return img;
		} catch (IOException e) {
			logger.error("Error reading image region: {} ({})", e.getMessage(), request);
			logger.debug("Error reading image region", e);
			return null; // TODO: Consider exception propagation
		}
	}
	


	/**
	 * Get a thumbnail image if it is cached, otherwise request it and return null.
	 * @param server
	 * @param zPosition
	 * @param tPosition
	 * @return
	 */
	public T getOrRequestThumbnail(ImageServer<T> server, int zPosition, int tPosition) {
		RegionRequest request = getThumbnailRequest(server, zPosition, tPosition);
		var future = requestImageTile(server, request);
		return future.isDone() ? future.resultNow() : null; // TODO: Consider possible failures
	}

	@Override
	public T getThumbnail(ImageServer<T> server, int zPosition, int tPosition, boolean addToCache) {
		RegionRequest request = getThumbnailRequest(server, zPosition, tPosition);
		var future = requestImageTile(server, request);
		try {
			// TODO: FIGURE OUT WHY THIS GETS STUCK FOR Z-STACKS!
			return future.get();
		} catch (Exception e) {
			logger.error("Error reading image region: {} ({})", e.getMessage(), request);
			return null;
		}
	}
	
	
	/**
	 * Clear the cache, including thumbnails, and cancel any pending requests.
	 */
	public synchronized void clearCache() {
		clearCache(true);
	}
	
	
	/**
	 * Clear the cache, optionally including thumbnails and stopping any pending requests.
	 * 
	 * @param stopWaiting cancel any tasks that are currently fetching tiles
	 */
	public synchronized void clearCache(final boolean stopWaiting) {
		if (stopWaiting) {
			for (var future : mapCacheAsync.values().toArray(Future[]::new)) {
				future.cancel(true);
			}
		}
		mapCacheAsync.clear();
		originalCache.synchronous().cleanUp();
	}


	private synchronized void clearCache(final boolean stopWaiting, Predicate<RegionRequest> filter) {
		var iterator = mapCacheAsync.entrySet().iterator();
		while (iterator.hasNext()) {
			var entry = iterator.next();
			if (filter.test(entry.getKey())) {
				if (stopWaiting) {
					entry.getValue().cancel(true);
				}
				iterator.remove();
			}
		}
		originalCache.synchronous().cleanUp();
	}
	
	
	@Override
	public synchronized void clearCacheForServer(final ImageServer<T> server) {
		var path = server.getPath();
		clearCache(true, r -> Objects.equals(path, r.getPath()));
	}
	
	@Override
	public synchronized void clearCacheForRequestOverlap(final RegionRequest request) {
		clearCache(true, r -> r.overlapsRequest(request));
	}

	
	/* (non-Javadoc)
	 * @see qupath.lib.images.stores.ImageRegionStore#close()
	 */
	@Override
	public void close() {
		// Try to cancel all workers
		clearCache(true);
		pool.shutdownNow();
		originalCache.synchronous().cleanUp();
		mapCacheAsync.clear();
	}
	
}