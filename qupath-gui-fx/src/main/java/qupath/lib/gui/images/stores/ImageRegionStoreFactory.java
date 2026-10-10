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
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.cache.ImageCache;

/**
 * Factory for creating an ImageRegionStore, or accessing the shared store.
 */
public class ImageRegionStoreFactory {
	
	private static final Logger logger = LoggerFactory.getLogger(ImageRegionStoreFactory.class);

	private static final DefaultImageRegionStore SHARED_INSTANCE = new DefaultImageRegionStore(ImageCache.getSharedInstance());

	static {
		initTileCacheSizeBytes(ImageCache.getSharedInstance());
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
	 * Calculate the appropriate tile cache size based upon the user preferences.
	 */
	private static void initTileCacheSizeBytes(ImageCache cache) {
		cache.setMaxSizeByPercent(PathPrefs.tileCachePercentageProperty().get());
	}
	
}
