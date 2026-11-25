package qupath.opencv.ml.pixel.measurements;

import java.awt.image.WritableRaster;

interface PixelCounter {

    ClassifiedPixelCounts getCounts(WritableRaster raster, PixelClassifierCounts.MaskPredicate predicate);

}
