package qupath.opencv.ml.pixel.measurements;

import qupath.lib.lazy.interfaces.LazyNumericValue;
import qupath.lib.objects.PathObject;

import java.util.List;

public interface PixelMeasurements {

    List<LazyNumericValue<PathObject>> getCachedMeasurements();

    List<LazyNumericValue<PathObject>> getMeasurements();

}
