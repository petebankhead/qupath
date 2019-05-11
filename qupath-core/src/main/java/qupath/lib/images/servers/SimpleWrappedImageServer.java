package qupath.lib.images.servers;

import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import java.util.List;

import com.google.gson.annotations.JsonAdapter;

import qupath.lib.regions.RegionRequest;

/**
 * An ImageServer that simply wraps around an existing ImageServer.
 * <p>
 * This may have no use...
 * 
 * @author Pete Bankhead
 *
 * @param <T>
 */
@Deprecated
class SimpleWrappedImageServer<T> implements ImageServer<T> {
	
	@JsonAdapter(ImageServers.ImageServerTypeAdapter.class)
	private ImageServer<T> server;
	
	protected SimpleWrappedImageServer(ImageServer<T> server) {
		this.server = server;
	}
	
	/**
	 * Get underlying ImageServer, i.e. the one that is being wrapped.
	 * 
	 * @return
	 */
	protected ImageServer<T> getWrappedServer() {
		return server;
	}

	@Override
	public T readBufferedImage(RegionRequest request) throws IOException {
		return server.readBufferedImage(request);
	}

	@Override
	public ImageServerMetadata getOriginalMetadata() {
		return server.getOriginalMetadata();
	}

	@Override
	public void close() throws Exception {
		server.close();
	}

	@Override
	public String getPath() {
		return server.getPath();
	}

	@Override
	public URI getURI() {
		return server.getURI();
	}

	@Override
	public String getShortServerName() {
		return server.getShortServerName();
	}

	@Override
	public double[] getPreferredDownsamples() {
		return server.getPreferredDownsamples();
	}

	@Override
	public int nResolutions() {
		return server.nResolutions();
	}

	@Override
	public double getPreferredDownsampleFactor(double requestedDownsample) {
		return server.getPreferredDownsampleFactor(requestedDownsample);
	}

	@Override
	public double getDownsampleForResolution(int level) {
		return server.getDownsampleForResolution(level);
	}

	@Override
	public int getWidth() {
		return server.getWidth();
	}

	@Override
	public int getHeight() {
		return server.getHeight();
	}

	@Override
	public int nChannels() {
		return server.nChannels();
	}

	@Override
	public boolean isRGB() {
		return server.isRGB();
	}

	@Override
	public int nZSlices() {
		return server.nZSlices();
	}

	@Override
	public int nTimepoints() {
		return server.nTimepoints();
	}

	@Override
	public double getZSpacingMicrons() {
		return server.getZSpacingMicrons();
	}

	@Override
	public double getPixelWidthMicrons() {
		return server.getPixelWidthMicrons();
	}

	@Override
	public double getPixelHeightMicrons() {
		return server.getPixelHeightMicrons();
	}

	@Override
	public double getAveragedPixelSizeMicrons() {
		return server.getAveragedPixelSizeMicrons();
	}

	@Override
	public boolean hasPixelSizeMicrons() {
		return server.hasPixelSizeMicrons();
	}

	@Override
	public T getCachedTile(TileRequest tile) {
		return server.getCachedTile(tile);
	}

	@Override
	public String getServerType() {
		return server.getServerType();
	}

	@Override
	public List<String> getSubImageList() {
		return server.getSubImageList();
	}

	@Override
	public ImageServer<T> openSubImage(String imageName) throws IOException {
		return server.openSubImage(imageName);
	}

	@Override
	public List<String> getAssociatedImageList() {
		return server.getAssociatedImageList();
	}

	@Override
	public T getAssociatedImage(String name) {
		return server.getAssociatedImage(name);
	}

	@Override
	public String getDisplayedImageName() {
		return server.getDisplayedImageName();
	}

	@Override
	public boolean isEmptyRegion(RegionRequest request) {
		return server.isEmptyRegion(request);
	}

	@Override
	public int getBitsPerPixel() {
		return server.getBitsPerPixel();
	}

	@Override
	public ImageChannel getChannel(int channel) {
		return server.getChannel(channel);
	}

	@Override
	public List<ImageChannel> getChannels() {
		return server.getChannels();
	}

	@Override
	public ImageServerMetadata getMetadata() {
		return server.getMetadata();
	}

	@Override
	public void setMetadata(ImageServerMetadata metadata) throws IllegalArgumentException {
		server.setMetadata(metadata);
	}

	@Override
	public T getDefaultThumbnail(int z, int t) throws IOException {
		return server.getDefaultThumbnail(z, t);
	}

	@Override
	public Collection<TileRequest> getAllTileRequests() {
		return server.getAllTileRequests();
	}

	@Override
	public TileRequest getTile(int level, int x, int y, int z, int t) {
		return server.getTile(level, x, y, z, t);
	}

	@Override
	public Collection<TileRequest> getTiles(RegionRequest request) {
		return server.getTiles(request);
	}

	@Override
	public Class<T> getImageClass() {
		return server.getImageClass();
	}

}