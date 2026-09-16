/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * Copyright (C) 2018 - 2020 QuPath developers, The University of Edinburgh
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
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.cache.ImageCache;
import qupath.lib.images.servers.ImageServerProvider;

import java.awt.image.BufferedImage;

/**
 * Factory for creating an ImageRegionStore, or accessing the shared store.
 */
public class ImageRegionStoreFactory {
	
	private static final Logger logger = LoggerFactory.getLogger(ImageRegionStoreFactory.class);

	private static final DefaultImageRegionStore SHARED_INSTANCE = new DefaultImageRegionStore(ImageCache.getSharedInstance());

	static {
		initTileCacheSizeBytes(ImageCache.getSharedInstance());
		ImageServerProvider.setCache(ImageCache.getSharedInstance().getCache(), BufferedImage.class);
	}

	/**
	 * Get the shared region store instance.
	 * This is usually the only instance required throughout the whole QuPath application.
	 * @return the shared region store
	 * @since v0.8.0
	 */
	public static DefaultImageRegionStore getSharedInstance() {
		return SHARED_INSTANCE;
	}

	/**
	 * Create an {@link ImageRegionStore} with a specified tile cache size, in bytes.
	 * <p>
	 * Since v0.8.0, this usually isn't required; use instead {@link #getSharedInstance()}.
	 * @param tileCacheSizeBytes maximum number of bytes for storing pixel values
	 * @return
	 */
	public static DefaultImageRegionStore createImageRegionStore(final long tileCacheSizeBytes) {
		return new DefaultImageRegionStore(ImageCache.create(tileCacheSizeBytes));
	}
	
	
	/**
	 * Calculate the appropriate tile cache size based upon the user preferences.
	 */
	private static void initTileCacheSizeBytes(ImageCache cache) {
		// Try to compute a sensible value...
		Runtime rt = Runtime.getRuntime();
		long maxAvailable = rt.maxMemory(); // Max available memory
		if (maxAvailable == Long.MAX_VALUE) {
			logger.warn("No inherent maximum memory set - for caching purposes, will assume 64 GB");
			maxAvailable = 64L * 1024L * 1024L * 1024L;
		}
		double percentage = PathPrefs.tileCachePercentageProperty().get();
		if (percentage < 10) {
			logger.warn("At least 10% of available memory needs to be used for tile caching (you requested {}%)", percentage);
			percentage = 10;
		} else if (percentage > 90) {
			logger.warn("No more than 90% of available memory can be used for tile caching (you requested {}%)", percentage);
			percentage = 90;			
		}
		long tileCacheSize = Math.round(maxAvailable * (percentage / 100.0));
		logger.info(String.format("Setting tile cache size to %.2f MB (%.1f%% max memory)", tileCacheSize/(1024.*1024.), percentage));
		cache.setMaxSize(tileCacheSize);
	}
	
}
