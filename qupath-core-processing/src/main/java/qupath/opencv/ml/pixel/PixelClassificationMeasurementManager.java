package qupath.opencv.ml.pixel;

import qupath.lib.objects.PathObject;

import java.util.List;

public interface PixelClassificationMeasurementManager {

    Number getCachedMeasurementValue(PathObject pathObject, String name);

    Number getMeasurementValue(PathObject pathObject, String name);

    List<String> getMeasurementNames();

}
