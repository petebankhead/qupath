package qupath.lib.lazy.factories;

import qupath.lib.lazy.interfaces.LazyValue;
import qupath.lib.lazy.objects.PathObjectLazyValues;
import qupath.lib.objects.PathObject;

import java.util.List;

public class ImageNameValueFactory implements PathObjectValueFactory {

    @Override
    public List<LazyValue<PathObject, ?>> createValues(PathObjectListWrapper wrapper) {
        var imageData = wrapper.getImageData();
        if (imageData == null)
            return List.of();
        else
           return List.of(PathObjectLazyValues.createImageNameMeasurement(imageData));
    }

}
