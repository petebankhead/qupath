package qupath.opencv.ml.pixel;

import qupath.lib.lazy.interfaces.LazyNumericValue;
import qupath.lib.objects.PathObject;

import java.util.List;

public interface PixelClassificationMeasurementManager {

    Number getCachedMeasurementValue(PathObject pathObject, String name);

    Number getMeasurementValue(PathObject pathObject, String name);

    List<String> getMeasurementNames();

    default List<LazyNumericValue<PathObject>> getCachedMeasurements() {
        return List.of();
    }

    default List<LazyNumericValue<PathObject>> getMeasurements() {
        return List.of();
    }

}
