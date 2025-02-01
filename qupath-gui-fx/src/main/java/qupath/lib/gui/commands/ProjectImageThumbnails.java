package qupath.lib.gui.commands;

import qupath.lib.display.ChannelDisplayInfo;
import qupath.lib.display.ImageDisplay;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.WrappedBufferedImageServer;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * Helper class for generating thumbnails for images in a QuPath project.
 */
class ProjectImageThumbnails {

    private static int thumbnailWidth = 1000;
    private static int thumbnailHeight = 600;

    static BufferedImage getThumbnailRGB(ImageServer<BufferedImage> server, ImageDisplay imageDisplay) throws IOException {
        var img = server.getDefaultThumbnail(server.nZSlices()/2, 0);
        // Try to write RGB images directly
        if (imageDisplay == null && (server.isRGB() || img.getType() == BufferedImage.TYPE_BYTE_GRAY)) {
            return resizeForThumbnail(img);
        }
        // Try with display transforms
        if (imageDisplay == null) {
            // By wrapping the thumbnail, we avoid slow z-stack/time series requests & determine brightness & contrast just from one plane
            var wrappedServer = new WrappedBufferedImageServer("Dummy", img, server.getMetadata().getChannels());
            imageDisplay = ImageDisplay.create(new ImageData<>(wrappedServer));
        }
        for (ChannelDisplayInfo info : imageDisplay.selectedChannels()) {
            imageDisplay.autoSetDisplayRange(info);
        }
        return resizeForThumbnail(
                imageDisplay.applyTransforms(img, null)
        );
    }

    /**
     * Resize an image so that its dimensions fit inside thumbnailWidth x thumbnailHeight.
     *
     * Note: this assumes the image can be drawn to a Graphics object.
     *
     * @param imgThumbnail
     * @return
     */
    static BufferedImage resizeForThumbnail(BufferedImage imgThumbnail) {
        double scale = Math.min((double)thumbnailWidth / imgThumbnail.getWidth(),
                (double)thumbnailHeight / imgThumbnail.getHeight()
        );
        if (scale > 1)
            return imgThumbnail;
        int w = (int)(imgThumbnail.getWidth() * scale);
        int h = (int)(imgThumbnail.getHeight() * scale);

        BufferedImage imgThumbnail2 = new BufferedImage(w, h, imgThumbnail.getType());
        Graphics2D g2d = imgThumbnail2.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.drawImage(imgThumbnail, 0, 0, imgThumbnail2.getWidth(), imgThumbnail2.getHeight(), null);
        g2d.dispose();
        return imgThumbnail2;
    }

}
