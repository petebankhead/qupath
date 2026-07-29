package qupath.process.gui.commands.ml.op;

import ij.plugin.filter.RankFilters;
import ij.process.FloatProcessor;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.bytedeco.javacpp.indexer.FloatIndexer;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Scalar;
import qupath.imagej.processing.Watershed;
import qupath.imagej.tools.IJTools;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.images.servers.PixelType;
import qupath.lib.objects.PathObjectTools;
import qupath.lib.regions.Padding;
import qupath.lib.regions.RegionRequest;
import qupath.opencv.ops.ImageDataOp;
import qupath.opencv.ops.ImageOp;
import qupath.opencv.ops.ImageOps;
import qupath.opencv.tools.OpenCVTools;

public class DetectionFeaturesDataOpBuilder implements ImageDataOpBuilder {

    @Override
    public ImageDataOp build(ImageData<BufferedImage> imageData, PixelCalibration resolution) {
        // TODO: Make features, expansion and sigma all customizable
        var features = PathObjectTools.getAvailableFeatures(imageData.getHierarchy().getDetectionObjects());
        return new DetectionFeaturesDataOp(features, 5.0, 1.0, null);
    }

    @Override
    public boolean supportsImage(ImageData<BufferedImage> imageData, PixelCalibration resolution) {
        return ImageDataOpBuilder.super.supportsImage(imageData, resolution);
    }

    @Override
    public String getName() {
        return "Detection features";
    }

    @Override
    public boolean canCustomize(ImageData<BufferedImage> imageData) {
        return ImageDataOpBuilder.super.canCustomize(imageData);
    }

    @Override
    public boolean doCustomize(ImageData<BufferedImage> imageData) {
        return ImageDataOpBuilder.super.doCustomize(imageData);
    }

    @Override
    public String toString() {
        return getName();
    }

    private static class DetectionFeaturesDataOp implements ImageDataOp {

        private final List<String> measurements;
        private final double expansion;
        private final double sigma;
        private final ImageOp postprocessing;

        private DetectionFeaturesDataOp(Collection<String> measurements, double expansion, double sigma, ImageOp postprocessing) {
            this.measurements = List.copyOf(measurements);
            this.expansion = expansion;
            this.sigma = sigma;
            this.postprocessing = postprocessing;
        }

        @Override
        public Mat apply(ImageData<BufferedImage> imageData, RegionRequest request) {

            // TODO: Add padding
            int pad = 0;
            if (sigma > 0) {
                pad += (int)(Math.ceil(sigma * 4));
            }
            if (expansion > 0) {
                pad += (int)(Math.ceil(expansion) + 1);
            }
            var padding = Padding.symmetric(pad);
            if (postprocessing != null) {
                padding = padding.add(postprocessing.getPadding());
            }

            int w = (int)Math.round(request.getWidth() / request.getDownsample()) + padding.getXSum();
            int h = (int)Math.round(request.getHeight() / request.getDownsample()) + padding.getYSum();
            int c = measurements.size();

            double downsample = request.getDownsample();
            double xOrigin = -(request.getX() / downsample - padding.getX1());
            double yOrigin = -(request.getY() / downsample - padding.getY1());

            Mat output = new Mat(h, w, opencv_core.CV_32FC(c), Scalar.ZERO);

            var detections = List.copyOf(imageData.getHierarchy().getAllDetectionsForRegion(request));
            if (detections.isEmpty()) {
                return output;
            }

            // Create labeled image.
            // We use ImageJ so it's easier to apply watershed expansion afterwards.
            FloatProcessor fpLabels = new FloatProcessor(w, h);
            float[][] features = new float[detections.size()][];
            for (int i = 0; i < detections.size(); i++) {
                var detection = detections.get(i);
                var roi = detection.getROI();
                if (roi == null)
                    continue;
                fpLabels.setValue(i+1);
                fpLabels.fill(IJTools.convertToIJRoi(roi, xOrigin, yOrigin, downsample));
                float[] f = new float[measurements.size()];
                for (int j = 0; j < c; j++) {
                    double val = detection.getMeasurementList().getOrDefault(measurements.get(j), Double.NaN);
                    if (Double.isFinite(val)) {
                        f[j] = (float)val;
                    }
                }
                features[i] = f;
            }

            // Expand
            if (expansion > 0) {
                Watershed.watershedExpandLabels(fpLabels, expansion, false);
                // Watershed expansion gives 1-pixel gaps, which we want to fill in
                var fpDilated = fpLabels.duplicate();
                new RankFilters().rank(fpDilated, 1.0, RankFilters.MAX);
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        if (fpLabels.getf(x, y) == 0f && fpDilated.getf(x, y) > 0f)
                            fpLabels.setf(x, y, fpDilated.getf(x, y));
                    }
                }
            }


            // Generate our feature images
            try (FloatIndexer idx = output.createIndexer()) {
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int label = (int)fpLabels.getf(x, y);
                        if (label == 0)
                            continue;
                        float[] f = features[label-1];
                        idx.put(y, x, f);
                    }
                }
            }

            if (sigma > 0)
                OpenCVTools.gaussianFilter(output, sigma);

            if (postprocessing != null) {
                return ImageOps.stripPadding(postprocessing.apply(output), padding);
            } else {
                return ImageOps.stripPadding(output, padding);
            }
        }

        @Override
        public boolean supportsImage(ImageData<BufferedImage> imageData) {
            return true;
        }

        @Override
        public List<ImageChannel> getChannels(ImageData<BufferedImage> imageData) {
            return ImageChannel.getChannelList(measurements.stream().map(this::measurementToChannelName).toArray(String[]::new));
        }

        private String measurementToChannelName(String measurement) {
            if (sigma <= 0 && expansion <= 0)
                return measurement;
            if (sigma > 0) {
                if (expansion > 0) {
                    return measurement + " (expand=" + expansion + ", sigma=" + sigma + ")";
                } else {
                    return measurement + " (sigma=" + sigma + ")";
                }
            } else if (expansion > 0) {
                return measurement + " (expand=" + expansion + ")";
            } else {
                return measurement;
            }
        }

        @Override
        public ImageDataOp appendOps(ImageOp... ops) {
            if (ops.length == 0)
                return this;
            List<ImageOp> sequential = new ArrayList<>();
            if (postprocessing != null)
                sequential.add(postprocessing);
            sequential.addAll(Arrays.asList(ops));
            return new DetectionFeaturesDataOp(
                    measurements,
                    expansion,
                    sigma,
                    ImageOps.Core.sequential(sequential)
            );
        }

        @Override
        public PixelType getOutputType(PixelType inputType) {
            return PixelType.FLOAT32;
        }

        @Override
        public Collection<URI> getURIs() throws IOException {
            return List.of();
        }

        @Override
        public boolean updateURIs(Map<URI, URI> replacements) {
            return false;
        }
    }

}
