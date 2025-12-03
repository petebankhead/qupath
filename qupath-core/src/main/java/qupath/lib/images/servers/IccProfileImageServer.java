package qupath.lib.images.servers;

import qupath.lib.awt.common.BufferedImageTools;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.awt.image.ColorConvertOp;
import java.io.IOException;

public class IccProfileImageServer extends TransformingImageServer<BufferedImage> {

    private transient ColorConvertOp op;

    IccProfileImageServer(ImageServer<BufferedImage> wrappedServer) {
        super(wrappedServer);
        var iccBytes = wrappedServer.getIccProfileBytes();
        if (iccBytes != null) {
            var source = ICC_Profile.getInstance(iccBytes);
            var dest = ICC_Profile.getInstance(ColorSpace.CS_sRGB);
            op = new ColorConvertOp(
                    new ICC_Profile[] {source, dest},
                    null);
        }
    }

    @Override
    public BufferedImage readRegion(double downsample, int x, int y, int width, int height, int z, int t) throws IOException {
        var img = super.readRegion(downsample, x, y, width, height, z, t);
        if (op != null) {
            // Don't want to modify in-place because this could mess with cached pixels
            // TODO: Check if it does - it might be safe because of defensive copying
            var img2 = BufferedImageTools.duplicate(img);
            op.filter(img.getRaster(), img.getRaster());
            return img2;
        } else {
            return img;
        }
    }

    @Override
    public String getServerType() {
        if (op == null)
            return getWrappedServer().getServerType();
        else
            return getWrappedServer().getServerType() + " (ICC profile)";
    }

    @Override
    protected ImageServerBuilder.ServerBuilder<BufferedImage> createServerBuilder() {
        return null;
    }

    @Override
    protected String createID() {
        return getWrappedServer().getPath() + " (ICC profile)";
    }
}
