/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2025 QuPath developers, The University of Edinburgh
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

package qupath.lib.images.servers.icc;

import qupath.lib.images.servers.ImageServer;

import java.awt.color.ICC_Profile;
import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * JSON-serializable wrapper for a method that can produce an ICC profile for an {@link ImageServer}.
 * @since v0.7.0
 */
public interface IccProfileWrapper {

    /**
     * Get the ICC profile for a given {@link ImageServer}.
     * This may be read from the server directly, or obtained from another source.
     * @param imageServer the server for which an ICC profile is required
     * @return an ICC profile, or null if none could be found
     * @throws IOException if there is an exception when attempting to obtain the ICC profile
     */
    ICC_Profile getProfile(ImageServer<?> imageServer) throws IOException;

    /**
     * Get any URIs required for this wrapper.
     * This is useful if the ICC profile is stored in an external file, and the path might change.
     * @return a collection of required URIs, or an empty collection if none are required
     */
    default Collection<URI> getURIs() {
        return List.of();
    }

    /**
     * Update any URIs used by this wrapper.
     * This is useful if the ICC profile is stored in an external file, and the path has changed.
     * @param replacements map of URI replacements (original: replacement)
     * @return a new wrapper with the URIs updated, or the same wrapper if no updates are needed
     */
    default IccProfileWrapper updateURIs(Map<URI, URI> replacements) {
        return this;
    }

}
