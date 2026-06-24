package qupath.process.gui.tools.wand;

import org.bytedeco.javacpp.indexer.UByteIndexer;
import org.bytedeco.opencv.opencv_core.Mat;
import qupath.lib.analysis.images.SimpleImage;

/**
 * Wrap a mask so it can be reused as a SimpleImage for contour tracing.
 */
class TraceableMask implements SimpleImage {

    private final int width, height;
    private final UByteIndexer indexer;
    private final int pad;

    /**
     * Constructor.
     *
     * @param mat the Mat to wrap (should not be closed externally)
     * @param pad optional padding value; the width and height are reduced by 2 x pad,
     *            and the x and y coordinates are offset by the pad value.
     *            When using OpenCV's flood fill, pad is usually 1; otherwise, it may be 0.
     */
    TraceableMask(Mat mat, int pad) {
        // The mask is padded by 1 pixel on all sides (for floodFill),
        // so for tracing we remove this padding
        if (pad < 0)
            throw new IllegalArgumentException("Padding must be >= 0, not " + pad);
        this.pad = pad;
        this.width = mat.cols() - pad * 2;
        this.height = mat.rows() - pad * 2;
        this.indexer = mat.createIndexer();
    }

    @Override
    public float getValue(int x, int y) {
        return indexer.get(y + pad, x + pad);
    }

    @Override
    public int getWidth() {
        return width;
    }

    @Override
    public int getHeight() {
        return height;
    }

}
