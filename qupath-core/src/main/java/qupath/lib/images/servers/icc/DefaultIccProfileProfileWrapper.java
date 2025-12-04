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

import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.io.IOException;

/**
 * ICC profile wrapper that uses a default profile available within Java.
 * <p>
 * Currently, this supports {@code "sRGB"} and {@code "linear_rgb"} (case-insensitive), and is intended for
 * use as the target profile.
 * @since v0.7.0
 */
public class DefaultIccProfileProfileWrapper implements IccProfileWrapper {

    private final String name;

    public DefaultIccProfileProfileWrapper(String name) {
        this.name = name;
    }

    @Override
    public ICC_Profile getProfile(ImageServer<?> imageServer) throws IOException {
        return switch (name.toLowerCase()) {
            case "srgb" -> ICC_Profile.getInstance(ICC_ColorSpace.CS_sRGB);
            case "linear", "linear_rgb" -> ICC_Profile.getInstance(ICC_ColorSpace.CS_LINEAR_RGB);
            default -> throw new IOException("Unexpected value: " + name.toLowerCase());
        };
    }

}
