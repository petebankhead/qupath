package qupath.lib.classifiers.pixel;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.color.ColorModelFactory;
import qupath.lib.common.GeneralTools;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerBuilder;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.images.servers.PixelType;
import qupath.lib.regions.RegionRequest;

/**
 * A pixel classifier implementation that loads data from an existing image.
 *
 * @since v0.8.0
 */
public class LoadingPixelClassifier implements PixelClassifier {

    private static final Logger logger = LoggerFactory.getLogger(LoadingPixelClassifier.class);

    private final ImageToBuilder<BufferedImage> imageToBuilder;
    private final PixelClassifierMetadata metadata;

    private final Cache<ImageData<BufferedImage>, ImageServer<BufferedImage>> cache = CacheBuilder.newBuilder()
            .weakKeys()
            .build();

    private transient volatile ColorModel colorModel;



    private LoadingPixelClassifier(ImageToBuilder<BufferedImage> imageToBuilder, PixelClassifierMetadata metadata) {
        this.imageToBuilder = imageToBuilder;
        this.metadata = metadata;
    }

    @Override
    public boolean supportsImage(ImageData<BufferedImage> imageData) {
        return getBuilder(imageData).isPresent();
    }

    @Override
    public BufferedImage applyClassification(ImageData<BufferedImage> imageData, RegionRequest request) throws IOException {
        var builder = getBuilder(imageData);
        if (builder.isEmpty()) {
            logger.debug("No URI found, cannot return {}", request);
            return null;
        }
        ImageServer<BufferedImage> server;
        try {
            server = cache.get(imageData, () -> builder.get().build());
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException ioe)
                throw ioe;
            else
                throw new IOException(e);
        }
        var img = server.readRegion(request.updatePath(server.getPath()));
        if (img == null) {
            logger.debug("Classification is null for {}", request);
            return null;
        }
        var cm = getColorModel();
        if (cm == null)
            return img;
        else
           return new BufferedImage(cm, img.getRaster(), img.isAlphaPremultiplied(), null);
    }

    private ImageServer<BufferedImage> createClassifierServer(URI uri) throws IOException {
        return ImageServers.buildServer(uri);
    }

    private Optional<ImageServerBuilder.ServerBuilder<BufferedImage>> getBuilder(ImageData<BufferedImage> imageData) {
        if (imageData == null)
            return Optional.empty();
        var builder = imageToBuilder.apply(imageData);
        return Optional.ofNullable(builder);
    }

    private ColorModel getColorModel() {
        if (colorModel == null) {
            synchronized (this) {
                if (colorModel == null)
                    colorModel = createColorModel();
            }
        }
        return colorModel;
    }

    private ColorModel createColorModel() {
        return switch (getMetadata().getOutputType()) {
            case PROBABILITY, MULTICLASS_PROBABILITY -> createProbabilityColorModel();
            case CLASSIFICATION -> createClassificationColorModel();
            default -> null;
        };
    }

    protected ColorModel createClassificationColorModel() {
        return ColorModelFactory.getIndexedClassificationColorModel(metadata.getClassificationLabels());
    }

    protected ColorModel createProbabilityColorModel() {
        if (getMetadata().getOutputPixelType() == PixelType.UINT8) {
            return ColorModelFactory.getProbabilityColorModel8Bit(metadata.getOutputChannels());
        } else {
            return ColorModelFactory.getProbabilityColorModel32Bit(metadata.getOutputChannels());
        }
    }

    @Override
    public PixelClassifierMetadata getMetadata() {
        return metadata;
    }

    public interface ImageToBuilder<T> extends Function<ImageData<T>, ImageServerBuilder.ServerBuilder<T>> {}

    public static class ImageToBuilderByPath implements ImageToBuilder<BufferedImage> {

        private final Path dir;
        private final String prefix;
        private final String postfix;
        private final String[] args;

        private ImageToBuilderByPath(Path dir, String prefix, String postfix, String... args) {
            Objects.requireNonNull(dir, "Directory must not be null!");
            this.dir = dir;
            this.prefix = prefix;
            this.postfix = postfix;
            this.args = args;
        }

        @Override
        public ImageServerBuilder.ServerBuilder<BufferedImage> apply(ImageData<BufferedImage> imageData) {
            var name = imageData.getServer().getMetadata().getName();
            var nameUpdated = updateNameWithPrefixAndPostfix(name);
            try {
                var firstPath = Files.list(dir)
                        .filter(p -> nameUpdated.equals(p.getFileName().toString()))
                        .findFirst();
                if (firstPath.isPresent())
                    return createBuilder(firstPath.get());
                var nameUpdatedWithoutExt = updateNameWithPrefixAndPostfix(GeneralTools.stripExtension(name));
                var alternativePath = Files.list(dir)
                        .filter(p -> nameUpdatedWithoutExt.equals(p.getFileName().toString()))
                        .findFirst();
                if (alternativePath.isPresent())
                    return createBuilder(alternativePath.get());
                throw new RuntimeException("No image found for " + imageData);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        private ImageServerBuilder.ServerBuilder<BufferedImage> createBuilder(Path path) throws IOException {
            var support = ImageServers.getImageSupport(path.toUri(), args);
            if (support != null)
                return support.getBuilders().getFirst();
            else
                return null;
        }

        private String updateNameWithPrefixAndPostfix(String name) {
            String updated = name;
            if (prefix != null)
                updated = prefix + name;
            if (postfix != null)
                updated = name + postfix;
            return updated;
        }


    }

}
