/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * Copyright (C) 2018 - 2026 QuPath developers, The University of Edinburgh
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.awt.common.AwtTools;
import qupath.lib.display.ImageDisplay;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerMetadata.ChannelType;
import qupath.lib.images.servers.PixelType;
import qupath.lib.regions.RegionRequest;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.awt.image.ImageObserver;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


/**
 * An ImageRegionStore suitable for either Swing or JavaFX applications.
 */
public class DefaultImageRegionStore extends AbstractImageRegionStore<BufferedImage> implements ImageRegionRenderer {

	private static final int DEFAULT_THUMBNAIL_WIDTH = 1000;

	private static final Logger logger = LoggerFactory.getLogger(DefaultImageRegionStore.class);
	
	private static boolean DEBUG_TILES = !Objects.equals(System.getProperty("qupath.debug.tiles", "false"), "false");

	DefaultImageRegionStore(int thumbnailWidth, long tileCacheSize) {
		super(new BufferedImageSizeEstimator(), thumbnailWidth, tileCacheSize);
	}

	DefaultImageRegionStore(long tileCacheSize) {
		this(DEFAULT_THUMBNAIL_WIDTH, tileCacheSize);
	}
	

	/**
	 * Similar to paintRegion, but wait until all the tiles have arrived (or abort if it is taking too long)
	 *
	 * @param server
	 * @param g
	 * @param clipShapeVisible
	 * @param zPosition
	 * @param tPosition
	 * @param downsampleFactor
	 * @param observer
	 * @param imageDisplay
	 * @param timeoutMilliseconds Timeout after which a request is made from the PathImageServer directly, rather than waiting for tile requests.
	 */
	@Override
	@SuppressWarnings("unchecked")
	public void paintRegionCompletely(ImageServer<BufferedImage> server, Graphics g, Shape clipShapeVisible, int zPosition, int tPosition, double downsampleFactor, ImageObserver observer, ImageRenderer imageDisplay, long timeoutMilliseconds) {

		// Loop through and create the image
		List<RequestedTile> workers = new ArrayList<>();
		BufferedImage imgTemp = null;

		for (RegionRequest request : ImageRegionStoreHelpers.getTilesToRequest(server, clipShapeVisible, downsampleFactor, zPosition, tPosition, null)) {

			Future<BufferedImage> future = requestImageTile(server, request);

			// If we have an image, paint it & record coordinates
			if (future.isDone() && future.resultNow() instanceof BufferedImage img) {
				if (imageDisplay != null) {
					imgTemp = imageDisplay.applyTransforms(img, imgTemp);
					g.drawImage(imgTemp, request.getX(), request.getY(), request.getWidth(), request.getHeight(), observer);
				} else
					g.drawImage(img, request.getX(), request.getY(), request.getWidth(), request.getHeight(), observer);
			} else {
				// If we've a tile worker, prepare for requesting its results soon...
				workers.add(new RequestedTile(request, future));
			}

		}

		// Loop through any workers now, drawing their tiles too
		for (var worker : workers) {
			BufferedImage imgTile = null;
			try {
				imgTile = worker.future().get(timeoutMilliseconds, TimeUnit.MILLISECONDS);
			} catch (CancellationException e) {
				logger.debug("Repaint skipped...");
				continue;
			} catch (InterruptedException e) {
				logger.debug("Tile request interrupted in 'paintRegionCompletely': {}", e.getLocalizedMessage());
				return;
			} catch (ExecutionException e) {
				logger.error("Execution exception in 'paintRegionCompletely'", e);
				return;
			} catch (TimeoutException e) {
				// If we timed out, try reading directly
				logger.warn("Timed out requesting region ({} ms)... {}", timeoutMilliseconds, worker.request());
			}
			if (imgTile == null)
				continue;
			RegionRequest request = worker.request();
			if (imageDisplay != null) {
				imgTemp = imageDisplay.applyTransforms(imgTile, imgTemp);
				g.drawImage(imgTemp, request.getX(), request.getY(), request.getWidth(), request.getHeight(), observer);
			} else
				g.drawImage(imgTile, request.getX(), request.getY(), request.getWidth(), request.getHeight(), observer);
		}

	}

	private record RequestedTile(RegionRequest request, Future<BufferedImage> future) {}


	@Override
	public boolean paintRegion(ImageServer<BufferedImage> server, Graphics g, Shape clipShapeVisible, int zPosition, int tPosition, double downsampleFactor, BufferedImage imgThumbnail, ImageObserver observer, ImageRenderer imageDisplay) {
		return paintRegionInternal(server, g, clipShapeVisible, zPosition, tPosition, downsampleFactor, imgThumbnail, observer, imageDisplay);
	}


	private boolean paintRegionInternal(ImageServer<BufferedImage> server, Graphics g, Shape clipShapeVisible, int zPosition, int tPosition, double downsampleFactor, BufferedImage imgThumbnail, ImageObserver observer, ImageRenderer imageDisplay) {

		boolean isComplete = true;

		// Check if we have all the regions required for this request
		List<RegionRequest> requests = ImageRegionStoreHelpers.getTilesToRequest(server, clipShapeVisible, downsampleFactor, zPosition, tPosition, null);
//		requests.forEach(r -> requestImageTile(server, r));
//		requestAllTiles(server, requests);

		// If we should be painting recursively, ending up with the thumbnail, do so
		if (imgThumbnail != null) {
			Rectangle missingBounds = null;
			for (RegionRequest request : requests) {
				// Load the image
				BufferedImage img = requestTile(server, request);
				if (img == null) {// && !mapCache.containsKey(request)) {
					if (missingBounds == null)
						missingBounds = AwtTools.getBounds(request);
					else
						missingBounds = missingBounds.union(AwtTools.getBounds(request));
				}
			}

			// If we are missing regions, try (recursively) to repaint at a lower resolution
			if (missingBounds != null) {
				double[] preferredDownsamples = server.getPreferredDownsamples();
				Arrays.sort(preferredDownsamples);
				double nextDownsample = -1; // -1 is the flag that indicates that we don't have a lower resolution to go to
				for (double d : preferredDownsamples) {
					if (d > Math.max(downsampleFactor, 1)) {
						nextDownsample = d;
						break;
					}
				}
				// Get the next downsample level if we can
				if (nextDownsample > 0)
//					paintRegion(server, g, clipShapeVisible, zPosition, tPosition, nextDownsample, imgThumbnail, observer, imageDisplay);
					paintRegionInternal(server, g, missingBounds, zPosition, tPosition, nextDownsample, imgThumbnail, observer, imageDisplay);
				else {
					// The best we can do is paint the thumbnail
					if (imageDisplay != null) {
                        imgThumbnail = imageDisplay.applyTransforms(imgThumbnail, null);
					}
					g.drawImage(imgThumbnail, 0, 0, server.getWidth(), server.getHeight(), observer);
				}
			}
		}

		// If we're compositing channels, it's worthwhile to cache RGB tiles for so long as the ImageDisplay remains constant
//		boolean useDisplayCache = imageDisplay != null && !server.isRGB() && server.nChannels() > 1;
		boolean useDisplayCache = server != null && !server.isRGB() && server.getMetadata().getChannelType() != ChannelType.CLASSIFICATION && (server.nChannels() > 1 || server.getPixelType() != PixelType.UINT8);
		long displayTimestamp = imageDisplay == null ? 0L : imageDisplay.getLastChangeTimestamp();
		String displayCachePath = null;
		if (useDisplayCache) {
			if (imageDisplay == null)
				displayCachePath = "RGB::" + server.getPath();
			else
				displayCachePath = server.getPath() + imageDisplay.getUniqueID();
		}

		// Loop through and draw whatever tiles we've got
		BufferedImage imgTemp = null;
		for (RegionRequest request : requests) {
			// Load the image
			BufferedImage img = getIfPresent(request);

			// If there is no image tile, try to get a lower-resolution version to draw -
			// this can actually paint over previously-available regions, but they will be repainted again when this region's request comes through
			if (img == null) {
				isComplete = false;
				continue;
			}

			// If we have an image, paint it & record coordinates
			// Apply any required color transformations
			if (imageDisplay != null || useDisplayCache) {
				// We can abort now - we know the display has changed, additional painting is futile...
				if (imageDisplay != null && displayTimestamp != imageDisplay.getLastChangeTimestamp())
					return false;
				if (useDisplayCache) {
					// Apply transforms, creating & caching new temp images
					RegionRequest requestCache = RegionRequest.createInstance(displayCachePath, request.getDownsample(), request);
					var imgTile = img;
					imgTemp = getCache().computeIfAbsent(requestCache, r -> toRGB(imgTile, imageDisplay));
				} else {
					// Apply transforms, trying to reuse temp image
					// Note: this assumes pixels can't be transparent
					if (imgTemp != null && (imgTemp.getWidth() != img.getWidth() || imgTemp.getHeight() != img.getHeight()))
						imgTemp = imageDisplay.applyTransforms(img, null);
					else
						imgTemp = imageDisplay.applyTransforms(img, imgTemp);
				}
				img = imgTemp;
			}
			g.drawImage(img, request.getX(), request.getY(), request.getWidth(), request.getHeight(), observer);
			if (DEBUG_TILES) {
				g.setColor(Color.RED);
				g.drawRect(request.getX(), request.getY(), request.getWidth(), request.getHeight());				
			}
		}
		return isComplete;
	}

	private static BufferedImage toRGB(BufferedImage img, ImageRenderer renderer) {
		if (renderer != null)
			return renderer.applyTransforms(img, null);
		else {
			BufferedImage imgTemp = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
			Graphics2D g2d = imgTemp.createGraphics();
			g2d.drawImage(img, 0, 0, null);
			g2d.dispose();
			return imgTemp;
		}
	}


	@Override
	public void close() {
		super.close();
	}


}
