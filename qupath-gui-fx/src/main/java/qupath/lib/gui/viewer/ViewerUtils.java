package qupath.lib.gui.viewer;

import javafx.beans.Observable;
import javafx.util.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.common.ColorTools;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerMetadata;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.awt.image.ByteLookupTable;
import java.awt.image.LookupOp;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * Static functions to support {@link QuPathViewer}.
 * Extracted into a separate class to try to make {@link QuPathViewer} more
 * manageable.
 */
class ViewerUtils {

    private static final Logger logger = LoggerFactory.getLogger(ViewerUtils.class);

    static int getMeanBrightnessRGB(final BufferedImage img, int x, int y, int w, int h) {
        if (img == null)
            return 0;
        double sum = 0;
        if (w < 0)
            w = img.getWidth();
        if (h < 0)
            h = img.getHeight();
        int[] pixels = new int[w * h];
        img.getRGB(x, y, w, h, pixels, 0, w);
        double scale = 1. / (3. * w * h); // To convert to mean
        for (int c : pixels) {
            int r = (c & ColorTools.MASK_RED) >> 16;
            int g = (c & ColorTools.MASK_GREEN) >> 8;
            int b = c & ColorTools.MASK_BLUE;
            sum += (r + g + b) * scale;
        }
        // Convert to mean brightness
        return (int)(sum + .5);
    }

    /**
    * Update the channel colors, as stored in the server metadata, to match the specified colors.
    * This is used in the fix for
    * <a href="https://github.com/qupath/qupath/issues/843">https://github.com/qupath/qupath/issues/843</a>
    * @param server
    * @param colors
    * @return
    * @throws IllegalArgumentException if the number of colors does not match the number of channels
    */
    static boolean updateServerChannels(ImageServer<BufferedImage> server, List<Integer> colors) throws IllegalArgumentException {
        var channels = server.getMetadata().getChannels();
        if (channels.size() != colors.size())
            throw new IllegalArgumentException(String.format("Number of channels (%d) does not match the number of colors (%d)!", channels.size(), colors.size()));
        var serverChannelColors = channels.stream().map(ImageChannel::getColor).toList();
        if (colors.equals(serverChannelColors))
            return false;
        channels = new ArrayList<>(channels);
        int n = 0;
        for (int i = 0; i < channels.size(); i++) {
            var channel = channels.get(i);
            var color = colors.get(i);
            if (!Objects.equals(channel.getColor(), color)) {
                channels.set(i, ImageChannel.getInstance(channel.getName(), color));
                n++;
            }
        }
        if (n == 0)
            return false; // Shouldn't happen
        var newMetadata = new ImageServerMetadata.Builder(server.getMetadata())
            .channels(channels)
            .build();
        server.setMetadata(newMetadata);
        if (n == 1)
            logger.info("Updating server metadata for 1 channel");
        else
            logger.info("Updating server metadata for {} channels", n);
        return true;
    }

    /**
	 * Check if two ImageServers are compatible in terms of display settings, i.e. having the same number, type and names for channels.
	 * @param currentServer
	 * @param tempServer
	 * @return true if the servers are compatible, false otherwise
	 */
	static boolean serversCompatible(ImageServer<BufferedImage> currentServer, ImageServer<BufferedImage> tempServer) {
		if (Objects.equals(currentServer, tempServer))
			return true;
		if (currentServer == null || tempServer == null)
			return false;
		if (tempServer.nChannels() == currentServer.nChannels() && tempServer.getPixelType() == currentServer.getPixelType()) {
			var tempNames = tempServer.getMetadata().getChannels().stream().map(ImageChannel::getName).toList();
			var currentNames = currentServer.getMetadata().getChannels().stream().map(ImageChannel::getName).toList();
			return tempNames.equals(currentNames);
		}
		return false;
	}

    static Subscription subscribeObservables(Runnable runnable, Observable... observables) {
        Subscription subscription = Subscription.EMPTY;
        for (var observable : observables) {
            subscription = subscription.and(observable.subscribe(runnable));
        }
        return subscription;
    }

    /**
     * Create an RGB BufferedImage suitable for caching the image used for painting.
     * @param w
     * @param h
     * @return
     */
    static BufferedImage createBufferedImage(final int w, final int h) {
        return new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
    }

    /**
     * Create a <code>LookupOp</code> that applies a gamma transform to an 8-bit image.
     *
     * @param gamma
     * @return
     */
    static LookupOp createGammaOp(double gamma) {
        byte[] lut = new byte[256];
        for (int i = 0; i < 256; i++) {
            double val = Math.pow(i/255.0, gamma) * 255;
            lut[i] = (byte)ColorTools.do8BitRangeCheck(val);
        }
        return new LookupOp(new ByteLookupTable(0, lut), null);
    }

    /**
     * Attempt to read an ICC profile from a TIFF image or stream.
     * This depends on ImageIO; in general, it should work with Java 9
     * (if an ICC profile is included in the TIFF) but not earlier versions.
     *
     * @param input an input of the kind that <code>ImageIO.createImageInputStream</code> can handle (e.g. a <code>File</code>)
     * @return an ICC profile if one is found, otherwise null.
     */
    static ICC_Profile readICC(Object input) {
        try (ImageInputStream stream = ImageIO.createImageInputStream(input)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (readers == null) {
                logger.debug("No readers found to extract ICC profile from {}", input);
                return null;
            }
            Class<?> clsTiffDir = Class.forName("javax.imageio.plugins.tiff.TIFFDirectory");
            Class<?> clsTiffField = Class.forName("javax.imageio.plugins.tiff.TIFFField");
            Method mCreateFromMetadata = clsTiffDir.getMethod("createFromMetadata", IIOMetadata.class);
            Method mGetTiffField = clsTiffDir.getMethod("getTIFFField", int.class);
            Method mGetAsBytes = clsTiffField.getMethod("getAsBytes");
            while (readers.hasNext()) {
                ImageReader reader = readers.next();
                stream.reset();
                reader.setInput(stream);
                Object tiffDir = mCreateFromMetadata.invoke(null, reader.getImageMetadata(0));
                Object tiffField = mGetTiffField.invoke(tiffDir, 34675);
                byte[] bytes = (byte[])mGetAsBytes.invoke(tiffField);
                return ICC_Profile.getInstance(bytes);
            }
        } catch (Exception e) {
            logger.warn("Unable to read ICC profile: {}", e.getMessage());
        }
        return null;
    }
}
