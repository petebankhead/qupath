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

import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.common.GeneralTools;
import qupath.lib.images.cache.GenericImageCache;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ServerTools;
import qupath.lib.regions.RegionRequest;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Future;


/**
 * A generic ImageRegionStore.
 *
 * @author Pete Bankhead
 * @param <T> the generic parameter for an image (most likely BufferedImage)
 */
abstract class AbstractImageRegionStore<T> implements ImageRegionStore<T> {

	private static final Logger logger = LoggerFactory.getLogger(AbstractImageRegionStore.class);

	private final GenericImageCache<T> cache;

	/**
	 * Maximum size of thumbnail, in any dimension.
	 */
	private int maxThumbnailSize;
	
	/**
	 * Minimum size of thumbnail, in any dimension.
	 */
	private int minThumbnailSize = 16;


	protected AbstractImageRegionStore(final GenericImageCache<T> cache, final int thumbnailSize) {
		Objects.requireNonNull(cache);
		this.cache = cache;
		this.maxThumbnailSize = thumbnailSize;
	}


	/**
	 * Get the tile cache size, in bytes.
	 * Image tiles larger than this cannot be cached.
	 * @return
	 */
	public long getTileCacheSize() {
		return cache.getMaxSizeBytes();
	}
	
	/**
	 * Calculate the downsample value to use when generating a thumbnail image.
	 * @return
	 */
	private double calculateThumbnailDownsample(ImageServer<?> server) {
		// We'll have trouble if we try to downsample until we have very few pixels in any dimension
		double fullResolutionDownsample = server.getDownsampleForResolution(0);
		double targetDownsample = fullResolutionDownsample;
		if (server.nResolutions() == 1)
			return targetDownsample;
		int width = server.getWidth();
		int height = server.getHeight();
		double maxDim = Math.max(width, height);
		double minDim = Math.min(width, height);
		if (minDim > minThumbnailSize) {
			// Try to use a downsample that corresponds to an actual pyramid level, to avoid unnecessary
			// requests and caching
			double maxDownsample = minDim / minThumbnailSize;
			double target = Math.min(maxDim / maxThumbnailSize, maxDownsample);
			targetDownsample = Arrays.stream(server.getPreferredDownsamples())
					.filter(d -> d <= maxDownsample && GeneralTools.almostTheSame(target, d, 0.25))
					.sorted()
					.findFirst()
					.orElse(target);
		}
		targetDownsample = Math.max(targetDownsample, fullResolutionDownsample);
		logger.trace("Thumbnail downsample: {}, ({} x {})",
				targetDownsample, Math.round(width/targetDownsample), Math.round(height/targetDownsample));
		return targetDownsample;
	}
	

	RegionRequest getThumbnailRequest(final ImageServer<T> server, final int zPosition, final int tPosition) {
		return RegionRequest.createInstance(
				server.getPath(),
				calculateThumbnailDownsample(server),
				0, 0, server.getWidth(), server.getHeight(), zPosition, tPosition);
	}

	protected T getIfPresent(final RegionRequest request) {
		return cache.getIfPresent(request);
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
		return cache.getKeys().stream().filter(r -> sameRegionIgnoringPlane(request, r))
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

	
	public ConcurrentMap<RegionRequest, T> getCache() {
		return cache.getCache();
	}

	public long getCacheSize() {
		return cache.getCacheSize();
	}


	@Override
	public T getCachedTile(ImageServer<T> server, RegionRequest request) {
		return getIfPresent(request);
	}	
	
	/**
	 * Get a map of all cached tiles pertaining to a specific ImageServer.
	 * @param server
	 * @return
	 */
	public Map<RegionRequest, T> getCachedTilesForServer(ImageServer<T> server) {
		return cache.getCachedTilesForServer(server.getPath());
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
		return cache.requestImageTile(server, request);
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
	public void clearCache() {
		cache.clearCache();
	}
	
	
	/**
	 * Clear the cache, optionally including thumbnails and stopping any pending requests.
	 * 
	 * @param stopWaiting cancel any tasks that are currently fetching tiles
	 */
	public synchronized void clearCache(final boolean stopWaiting) {
		cache.clearCache(stopWaiting);
	}
	
	
	@Override
	public synchronized void clearCacheForServer(final ImageServer<T> server) {
		cache.clearCacheForServer(server.getPath());
	}
	
	@Override
	public synchronized void clearCacheForRequestOverlap(final RegionRequest request) {
		cache.clearCacheForRequestOverlap(request);
	}

	
	/* (non-Javadoc)
	 * @see qupath.lib.images.stores.ImageRegionStore#close()
	 */
	@Override
	public void close() {
		cache.close();
	}
	
}