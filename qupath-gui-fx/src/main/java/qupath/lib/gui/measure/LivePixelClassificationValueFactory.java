package qupath.lib.gui.measure;

import qupath.lib.images.cache.ImageCache;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.lazy.interfaces.LazyValue;
import qupath.lib.lazy.objects.PathObjectLazyValues;
import qupath.lib.objects.PathObject;
import qupath.opencv.ml.pixel.AnotherMeasurementManager;
import qupath.opencv.ml.pixel.DefaultPixelClassificationMeasurementManager;
import qupath.opencv.ml.pixel.PixelClassificationMeasurementManager;

import java.util.ArrayList;
import java.util.List;

class LivePixelClassificationValueFactory implements PathObjectValueFactory {


    @Override
    public List<LazyValue<PathObject, ?>> createValues(PathObjectListWrapper wrapper) {

        List<LazyValue<PathObject, ?>> measurements = new ArrayList<>();

        if (wrapper.containsAnnotations() || wrapper.containsTMACores() || wrapper.containsRoot()) {
            var pixelClassifier = ObservableMeasurementTableData.getPixelLayer(wrapper.getImageData());
            if (pixelClassifier != null) {
                if (pixelClassifier.getMetadata().getChannelType() == ImageServerMetadata.ChannelType.CLASSIFICATION || pixelClassifier.getMetadata().getChannelType() == ImageServerMetadata.ChannelType.PROBABILITY) {
                    PixelClassificationMeasurementManager pixelManager = new AnotherMeasurementManager(ImageCache.getSharedInstance(), pixelClassifier);
                    measurements.addAll(pixelManager.getCachedMeasurements());
                }
            }
        }

        return measurements;
    }
}
