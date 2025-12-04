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

/**
 * ICC profile wrapper that reads the profile from an image, or throws an exception if no profile is available.
 * @since v0.7.0
 */
public class EmbeddedIccProfileProfileWrapper implements IccProfileWrapper {

    @Override
    public ICC_Profile getProfile(ImageServer<?> imageServer) throws IOException {
        if (imageServer instanceof IccProfileReader reader) {
            var bytes = reader.getIccProfileBytes();
            if (bytes != null)
                return ICC_Profile.getInstance(bytes);
        }
        throw new IOException("No ICC profile found for " + imageServer);
    }

}
