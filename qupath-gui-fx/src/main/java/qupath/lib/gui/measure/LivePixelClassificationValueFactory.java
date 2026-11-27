package qupath.lib.gui.measure;

import qupath.lib.images.cache.ImageCache;
import qupath.lib.lazy.interfaces.LazyValue;
import qupath.lib.objects.PathObject;
import qupath.opencv.ml.pixel.measurements.DefaultPixelClassifierMeasurements;
import qupath.opencv.ml.pixel.measurements.PixelMeasurements;

import java.util.List;

class LivePixelClassificationValueFactory implements PathObjectValueFactory {


    @Override
    public List<LazyValue<PathObject, ?>> createValues(PathObjectListWrapper wrapper) {

        if (wrapper.containsAnnotations() || wrapper.containsTMACores() || wrapper.containsRoot()) {
            var pixelClassifier = ObservableMeasurementTableData.getPixelLayer(wrapper.getImageData());
            if (pixelClassifier != null) {
                PixelMeasurements pixelManager = new DefaultPixelClassifierMeasurements(ImageCache.getSharedInstance(), pixelClassifier);
                return List.copyOf(pixelManager.getCachedMeasurements());
            }
        }

        return List.of();
    }
}
