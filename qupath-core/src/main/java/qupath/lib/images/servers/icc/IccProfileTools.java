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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.ColorConvertOp;
import java.awt.image.WritableRaster;
import java.io.IOException;

/**
 * Helper methods for working with ICC profiles.
 * @since v0.7.0
 */
public class IccProfileTools {

    private static final Logger logger = LoggerFactory.getLogger(IccProfileTools.class);

    /**
     * Argument to request that an embedded ICC profile is used as the source.
     * The destination may be specified or may be the default.
     */
    public static final String ICC_EMBED = "--icc-profile";

    /**
     * Argument key to specify the source ICC profile, if this is different from any embedded profile.
     */
    public static final String ICC_SOURCE = "--icc-profile-source=";

    /**
     * Argument key to specify the destination ICC profile. Default is sRGB.
     */
    public static final String ICC_DEST = "--icc-profile-dest=";

    public static WritableRaster applyInPlace(ColorConvertOp op, WritableRaster raster) {
        return op.filter(raster, raster);
    }

    public static ColorConvertOp parseIccProfileArgs(String[] args, byte[] embeddedProfileBytes)
            throws IllegalArgumentException, IOException {
        ICC_Profile source = null;
        ICC_Profile dest = null;


        for (String arg : args) {
            arg = arg.strip();
            if (arg.startsWith(ICC_SOURCE)) {
                String argTemp = arg.substring(ICC_SOURCE.length());
                logger.info("Requesting source ICC profile from {}", argTemp);
                source = parseIccProfileFromArg(argTemp);
            } else if (arg.startsWith(ICC_DEST)) {
                String argTemp = arg.substring(ICC_DEST.length());
                logger.info("Requesting dest ICC profile from {}", argTemp);
                dest = parseIccProfileFromArg(argTemp);
            } else if (arg.equalsIgnoreCase(ICC_EMBED)) {
                if (embeddedProfileBytes == null) {
                    logger.warn("No embedded ICC profile found");
                    return null;
                } else {
                    logger.info("Found embedded ICC profile ({} bytes)", embeddedProfileBytes.length);
                    source = ICC_Profile.getInstance(embeddedProfileBytes);
                }
            }
        }
        if (source == null) {
            if (dest == null) {
                logger.debug("No ICC profile requested");
                return null;
            } else {
                logger.warn("No source ICC profile found, cannot apply dest profile only");
                return null;
            }
        }
        if (dest == null) {
            logger.debug("Using default ICC destination CS_sRGB");
            dest = ICC_Profile.getInstance(ICC_ColorSpace.CS_sRGB);
        }
        return new ColorConvertOp(
                new ICC_Profile[] {
                        source, dest
                },
                null);
    }

    private static ICC_Profile parseIccProfileFromArg(String arg) throws IOException {
        if ("srgb".equalsIgnoreCase(arg))
            return ICC_Profile.getInstance(ICC_ColorSpace.CS_sRGB);
        else if ("linear".equalsIgnoreCase(arg))
            return ICC_Profile.getInstance(ICC_ColorSpace.CS_LINEAR_RGB);
        else
            return ICC_Profile.getInstance(arg);
    }


}
