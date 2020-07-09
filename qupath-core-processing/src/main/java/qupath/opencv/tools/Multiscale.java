/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2020 QuPath developers, The University of Edinburgh
 * %%
 * QuPath is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * QuPath is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License 
 * along with QuPath.  If not, see <https://www.gnu.org/licenses/>.
 * #L%
 */


package qupath.opencv.tools;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.bytedeco.javacpp.PointerScope;
import org.bytedeco.javacpp.indexer.FloatIndexer;
import org.bytedeco.javacpp.indexer.UByteIndexer;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.index.ItemVisitor;
import org.locationtech.jts.index.quadtree.Quadtree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.common.GeneralTools;
import qupath.lib.geom.Point2;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ColorTransforms.ColorTransform;
import qupath.lib.images.servers.ColorTransforms;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.images.servers.PixelType;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;
import qupath.opencv.ops.ImageOps;
import qupath.opencv.tools.MultiscaleFeatures.Hessian;

/**
 * Class to assist multiscale detection in 2D or 3D.
 * 
 * <p>
 * <b>Warning!</b> This class is very rough and unfinished.
 * 
 * <p>
 * TODO: Add blob detection
 * <p>
 * TODO: Consider adding edge detection
 * 
 * @author Pete Bankhead
 */
public class Multiscale {
	
	private final static Logger logger = LoggerFactory.getLogger(Multiscale.class);
	
	
	public static class RidgeDetector {

		private static Logger logger = LoggerFactory.getLogger(RidgeDetector.class);

		private ImageData<BufferedImage> imageData;
		private ColorTransform transform;

		private double angleThreshold = 15; // Controls curvature, defined in terms of degrees (for ease of interpretation)

		private double distanceThreshold = 5.0; // Controls continuity
		private double dotProductThreshold = Math.cos(Math.toRadians(angleThreshold));
		private int minPoints = 5;
		private double sigmaStart = 1.0;
		private double sigmaScale = 1.5;
		private int nSigmas = 1;
		private double noiseThreshold = 5;
//		private boolean doSort = false;
		private boolean do3D = true;
		private boolean doSqrt = false;

		public RidgeDetector(ImageData<BufferedImage> imageData) {
			this.imageData = imageData;
		}
		
		public RidgeDetector channel(int channel) {
			this.transform = ColorTransforms.createChannelExtractor(channel);
			return this;
		}
		
		public RidgeDetector channel(String channel) {
			this.transform = ColorTransforms.createChannelExtractor(channel);
			return this;
		}
		
		public RidgeDetector channel(ColorTransform transform) {
			this.transform = transform;
			return this;
		}
		
		public RidgeDetector do3D(boolean do3D) {
			this.do3D = do3D;
			return this;
		}
		
		/**
		 * Detect at a single scale.
		 * @param sigma Gaussian sigma for smoothing.
		 * @return
		 */
		public RidgeDetector scales(double sigma) {
			return scales(sigma, 1);
		}
		
		public RidgeDetector scales(double sigmaStart, int nScales) {
			return scales(sigmaStart, sigmaScale, nScales);
		}
		
		public RidgeDetector noiseThreshold(double k) {
			this.noiseThreshold = k;
			return this;
		}
		
		/**
		 * Detect at one or more scales.
		 * @param sigmaStart
		 * @param sigmaScale
		 * @param nScales
		 * @return
		 */
		public RidgeDetector scales(double sigmaStart, double sigmaScale, int nScales) {
			this.sigmaStart = sigmaStart;
			this.sigmaScale = sigmaScale;
			this.nSigmas = nScales;
			return this;
		}

		public List<Ridge> detect(RegionRequest request) throws IOException {
			try (var scope = new PointerScope()) {
				return detectRidges(request);
			}
		}


		List<Ridge> detectRidges(RegionRequest request) throws IOException {

			// Extract & preprocess regions
			var server = imageData.getServer();
			var op = transform == null ? ImageOps.buildImageDataOp() : ImageOps.buildImageDataOp(transform);
			if (doSqrt)
				op = op.appendOps(ImageOps.Core.ensureType(PixelType.FLOAT32), ImageOps.Core.sqrt());
			else
				op = op.appendOps(ImageOps.Core.ensureType(PixelType.FLOAT32));

			// If no region is specified, use the entire image at full resolution
			if (request == null) {
				logger.warn("No region specified - will attempt ridge detection for the full image");
				request = RegionRequest.createInstance(
						server.getPath(),
						1,
						0, 0, server.getWidth(), server.getHeight(),
						0, 0
						);
			}

			List<Mat> inputMats;
			if (do3D) {
				inputMats = new ArrayList<>();
				for (int z = 0; z < server.nZSlices(); z++) {
					var temp = request.updateZ(z);
					inputMats.add(op.apply(imageData, temp));
				}
			} else {
				inputMats = Collections.singletonList(op.apply(imageData, request));
			}

			// Estimate the noise standard deviation
			// This can be used as a sanity check/minimum threshold
			double noiseStdDev = inputMats.parallelStream().mapToDouble(m -> Noise.estimateNoise(m, 3.0)).average().orElse(0);

			var accumulator = new ScaleAccumulator2D();

			double sigma = sigmaStart;
			for (int i = 0; i < nSigmas; i++) {
				logger.debug("Building scale {}", sigma);
				double sigmaMicrons = sigma * server.getPixelCalibration().getAveragedPixelSize().doubleValue();
				var builder = new MultiscaleFeatures.MultiscaleResultsBuilder()
						.pixelCalibration(server.getPixelCalibration(), 1)
						.sigmaXY(sigmaMicrons)
						.retainHessian(true)
						.hessianEigenvalues(true)
						.gaussianSmoothed(true);

				if (inputMats.size() > 1) {
					var results = builder.sigmaZ(sigmaMicrons)
							.build(inputMats);

					// Update noise estimate for this smoothing level
					// Note: For anisotropic pixels (or non-independent noise), this may be rather dubious
					var k0 = OpenCVTools.getGaussianDerivKernel(sigma, 0, false);
					var cal = server.getPixelCalibration();
					Mat k0z;
					if (cal != null && cal.hasZSpacingMicrons())
						k0z = OpenCVTools.getGaussianDerivKernel(sigmaMicrons / cal.getZSpacingMicrons(), 0, false);
					else
						k0z = OpenCVTools.getGaussianDerivKernel(sigma, 0, false);
					var k2 = OpenCVTools.getGaussianDerivKernel(sigma, 2, false);
					double noiseEstimate = Noise.updateNoiseEstimateSeparable(noiseStdDev, k0, k0z, k2);

					
					accumulate(accumulator, sigmaMicrons, results.stream().map(r -> r.getHessian()).collect(Collectors.toList()), noiseEstimate * noiseThreshold);

				} else {
					var results = builder.build(inputMats.get(0));
					var hessian = results.getHessian();
					
					// Update the noise estimate for this smoothing level
					var k0 = OpenCVTools.getGaussianDerivKernel(sigma, 0, false);
					var k2 = OpenCVTools.getGaussianDerivKernel(sigma, 2, false);
					double noiseEstimate = Noise.updateNoiseEstimateSeparable(noiseStdDev, k0, k2);

					
					accumulate(accumulator, sigmaMicrons, hessian, noiseEstimate * noiseThreshold);
				}
				sigma *= sigmaScale;
			}
			
			/*
			 * Required input:
			 *  - Binary ridge mat (2D/3D)
			 *  
			 * Useful input:
			 *  - Scale information (informative for possible interpolation)
			 *  - Strength information (useful to add a measurement for later filtering)
			 *  - Ridge orientation mat (eigenvectors - although may be estimated from connected components)
			 */
			
			// Thin ridge image
			
			// Identify end points
			
			// Initialize ridges by tracing connected components until reaching an end point or a bridge
			
			// Optionally attempt to connect end points to connect gaps
			
			
			Mat matBinary = accumulator.ridgeMask;
			Mat matStrength = accumulator.ridgeStrength;
			Mat matScale = accumulator.ridgeScale;
			Mat matEigenvector = accumulator.ridgeEigenvector;
			
//			OpenCVTools.matToImagePlus(matBinary, "Binary").show();
			
			var ridgePoints = traceLines(matBinary);

//			OpenCVTools.matToImagePlus(matBinary, "Binary after").show();

			FloatIndexer idxStrength = matStrength.createIndexer();
			FloatIndexer idxScale = matScale.createIndexer();
			FloatIndexer idxEigenvector = matEigenvector.createIndexer();
			List<Ridge> ridges = new ArrayList<>();
			for (var points : ridgePoints) {
				ridges.add(new Ridge(points.stream().map(p ->  {
					float strength = idxStrength.get(p.i, p.j, p.k);
					float scale = idxScale.get(p.i, p.j, p.k);
					float eigY = idxEigenvector.get(p.i, p.j, 0, p.k);
					float eigX = idxEigenvector.get(p.i, p.j, 1, p.k);
					float eigZ = do3D ? idxEigenvector.get(p.i, p.j, 2, p.k) : Float.NaN;
					return RidgePoint.create(p, strength, scale, eigX, eigY, eigZ);
				}).collect(Collectors.toList())));
			}
			
			boolean doMerge = true;
//			doMerge = false;
			
			if (doMerge)
				return mergeRidges(ridges, 25, 1.5, server.getPixelCalibration());
			else
				return ridges;
		}
		
		
		static void accumulate(ScaleAccumulator2D accumulator, double scale, Hessian hessian, double threshold) {
		    var eigenvalues = hessian.getEigenvalues(false).get(1);
		    var eigenvectors = hessian.getEigenvectors(false).get(0);
		    // Use the highest eigenvalue as a measure of ridge strength, and the 'other' eigenvector as an assessment of ridge direction
		    accumulator.addScale(scale, opencv_core.multiply(eigenvalues, -1.0).asMat(), eigenvectors, threshold);
		}

		static void accumulate(ScaleAccumulator2D accumulator, double scale, List<Hessian> hessianMatrices, double threshold) {
		    var ridgeStrengths = new ArrayList<Mat>();
		    var eigenvectors = new ArrayList<Mat>();
		    for (var hessian : hessianMatrices) {
		        ridgeStrengths.add(opencv_core.multiply(hessian.getEigenvalues(false).get(1), -1.0).asMat());
		        eigenvectors.add(OpenCVTools.mergeChannels(hessian.getEigenvectors(false), null));
		    }
		    var strength = OpenCVTools.mergeChannels(ridgeStrengths, null);

		    accumulator.addScale(scale, strength, concatenateDimension(eigenvectors), threshold);
		}
		
		static Mat concatenateDimension(List<Mat> mats) {
		    if (mats.isEmpty())
		        return new Mat();
		    if (mats.size() == 1)
		        return mats.get(0);
		    var first = mats.get(0);
		    int height = first.rows();
		    int width = first.cols();
		    int channels = first.channels();
		    int[] dims = new int[] {height, width, channels, mats.size()};
		    var mat = new Mat(dims, opencv_core.CV_32F);
		    FloatIndexer idx = (FloatIndexer)mat.createIndexer();
		    int s = 0;
		    long[] inds = new long[4];
		    for (var m : mats) {
		        inds[3] = s;
		        FloatIndexer idx2 = m.createIndexer();
		        for (int y = 0; y < height; y++) {
		            inds[0] = y;
		            for (int x = 0; x < width; x++) {
		                inds[1] = x;
		                for (int c = 0; c < channels; c++) {
		                    inds[2] = c;
		                    idx.put(inds, idx2.get(y, x, c));
		                }
		            }
		        }
		        idx2.close();
		        s++;
		    }
		    idx.close();
		    return mat;
		}

	}
	
	
	static List<Ridge> mergeRidges(Collection<Ridge> ridges, double angleThreshold, double distanceThreshold, PixelCalibration cal) {
		logger.warn("Merge ridges not yet fully implemented!");
		
		double dotProductThreshold = Math.cos(Math.toRadians(angleThreshold));
		
		// TODO: Attempt to merge ridges
		var input = new ArrayList<>(ridges);
		Collections.sort(input, Comparator.comparingInt(Ridge::nPoints).reversed());
		
		var output = new ArrayList<Ridge>();
		
		var cache = new SpatialCache();
		var ridgeMap = new HashMap<RidgePoint, Ridge>();
		for (var ridge : input) {
			if (ridge.nPoints() == 0)
				continue;
			var startPoint = ridge.getStart(true);
			var endPoint = ridge.getEnd(true);
			ridgeMap.put(startPoint, ridge);
			ridgeMap.put(endPoint, ridge);
			cache.insert(startPoint);
			cache.insert(endPoint);
		}
		
		// Loop through ridges in descending order of length
		var stillToMerge = new HashSet<>(input);
		for (var ridge : input) {
			if (!stillToMerge.contains(ridge))
				continue;
			
			stillToMerge.remove(ridge);
			cache.remove(ridge.getStart(true));
			cache.remove(ridge.getEnd(true));
			
			boolean changes = true;
			while (changes) {
				changes = false;
				var startPoint = ridge.getStart(true);
				
				var candidates = cache.query(startPoint, distanceThreshold, cal);
				candidates.sort(Comparator.comparingDouble(p -> p.distanceSq(startPoint, cal)));
				for (var c : candidates) {
					double dot = startPoint.eigenvectorDotProduct(c);
					if (dot < -dotProductThreshold && startPoint.displacementEigenvectorDotProduct(c, cal) > dotProductThreshold) {
						var ridge2 = ridgeMap.get(c);
						if (c != ridge2.getEnd(true))
							Collections.reverse(ridge2.points);
						ridge.points.addAll(0, ridge2.points);
						changes = true;
						cache.remove(ridge2.getStart(true));
						cache.remove(ridge2.getEnd(true));
						stillToMerge.remove(ridge2);
						break;
					}
				}
				if (changes)
					continue;
				
				var endPoint = ridge.getEnd(true);
				candidates = cache.query(endPoint, distanceThreshold, cal);
				for (var c : candidates) {
					double dot = endPoint.eigenvectorDotProduct(c);
					if (dot < -dotProductThreshold && endPoint.displacementEigenvectorDotProduct(c, cal) > dotProductThreshold) {
						var ridge2 = ridgeMap.get(c);
						if (c != ridge2.getEnd(true))
							Collections.reverse(ridge2.points);
						ridge.points.addAll(ridge2.points);
						cache.remove(ridge2.getStart(true));
						cache.remove(ridge2.getEnd(true));
						stillToMerge.remove(ridge2);
						changes = true;
						break;
					}
				}
			}
			output.add(ridge);
		}
		
		return output;
	}
	
	
	
	/**
	 * Trace thinned lines in a binary image.
	 * @param matBinary
	 * @return
	 */
	static List<List<SimpleIndex>> traceLines(Mat matBinary) {
		
		UByteIndexer idx = matBinary.createIndexer();
		Thinning.thin(idx);
		Thinning.countNeighbors(idx);
		
//		var imp = OpenCVTools.matToImagePlus("Binary", matBinary);
//		imp.setDimensions(1, imp.getStackSize(), 1);
//		imp.setDisplayRange(0, 5);
//		imp.show();
		
		// Remove branches
		replaceInRange(idx, 4, Integer.MAX_VALUE, 0);
		
		// Find isolated & end points
		Thinning.countNeighbors(idx);

		
//		imp = OpenCVTools.matToImagePlus("Binary without branches", matBinary);
//		imp.setDimensions(1, imp.getStackSize(), 1);
//		imp.setDisplayRange(0, 5);
//		imp.show();

		
//		OpenCVTools.matToImagePlus(matBinary, "Binary COUNTED").show();

		// Trace between end points
		boolean removeIsolatedPixels = true;
//		Deque<SimpleIndex> endPoints = new ArrayDeque<>(findPixels(idx, removeIsolatedPixels ? 2 : 1, 3));
		Set<SimpleIndex> endPoints = new LinkedHashSet<>(findPixels(idx, removeIsolatedPixels ? 2 : 1, 3));
		List<List<SimpleIndex>> ridges = new ArrayList<>();
		while (!endPoints.isEmpty()) {
			var start = endPoints.iterator().next();
			var list = traceLine(idx, start);
			endPoints.removeAll(list);
//			if (list.size() > 10)
				ridges.add(list);
		}
		idx.close();
		
		return ridges;
	}
	
	/**
	 * Trace a line using 8/26 connectivity in a binary image.
	 * This assumes that the image has been thinned.
	 * The contents of the image will be modified in-place (specifically, pixels set to 0 as they are 
	 * identified).
	 * @param idx
	 * @param startPoint
	 * @return
	 */
	static List<SimpleIndex> traceLine(UByteIndexer idx, SimpleIndex startPoint) {
		List<SimpleIndex> points = new ArrayList<>();
		SimpleIndex p = startPoint;
		long iMax = idx.size(0);
		long jMax = idx.size(1);
		long kMax = idx.size(2);
		while (p != null) {
			points.add(p);
			// Set pixel to 0 (so it isn't found again)
			idx.put(p.i, p.j, p.k, 0);
			// Look for another neighbor
			p = getNeighbor(idx, p.i, p.j, p.k, iMax, jMax, kMax);
		}
		return points;
	}
	
	private static SimpleIndex getNeighbor(UByteIndexer idx, long i, long j, long k, long iMax, long jMax, long kMax) {
		for (long kk = Math.max(0, k-1); kk <= Math.min(k+1, kMax-1); kk++) {
			for (long ii = Math.max(0, i-1); ii <= Math.min(i+1, iMax-1); ii++) {
				for (long jj = Math.max(0, j-1); jj <= Math.min(j+1, jMax-1); jj++) {
//					if (kk == ii && kk == jj)
//						continue;
					if (idx.get(ii, jj, kk) != (byte)0)
						return new SimpleIndex(ii, jj, kk);
				}
			}
		}
		return null;
	}
	
	
	private static class SimpleIndex {
		
		public final long i, j, k;
		
		SimpleIndex(long i, long j, long k) {
			this.i = i;
			this.j = j;
			this.k = k;
		}
		
		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + (int) (i ^ (i >>> 32));
			result = prime * result + (int) (j ^ (j >>> 32));
			result = prime * result + (int) (k ^ (k >>> 32));
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			SimpleIndex other = (SimpleIndex) obj;
			if (i != other.i)
				return false;
			if (j != other.j)
				return false;
			if (k != other.k)
				return false;
			return true;
		}
				
	}
	
	private static List<SimpleIndex> findPixels(UByteIndexer idx, int minValue, int maxValue) {
		var list = new ArrayList<SimpleIndex>();
		long iMax = idx.size(0);
		long jMax = idx.size(1);
		long kMax = idx.size(2);
		for (long k = 0; k < kMax; k++) {
			for (long i = 0; i < iMax; i++) {
				for (long j = 0; j < jMax; j++) {
					int val = idx.get(i, j, k);
					if (val >= minValue && val < maxValue)
						list.add(new SimpleIndex(i, j, k));
				}
			}
		}
		return list;
	}
	
	
	/**
	 * Replace pixel values falling in a specified range with another value.
	 * @param idx byte image indexer
	 * @param minValue minimum value (inclusive) to replace
	 * @param maxValue maximum value (exclusive) to replace
	 * @param replacement new pixel value to use
	 */
	private static void replaceInRange(UByteIndexer idx, int minValue, int maxValue, int replacement) {
		long iMax = idx.size(0);
		long jMax = idx.size(1);
		long kMax = idx.size(2);
		for (long k = 0; k < kMax; k++) {
			for (long i = 0; i < iMax; i++) {
				for (long j = 0; j < jMax; j++) {
					int val = idx.get(i, j, k);
					if (val >= minValue && val < maxValue)
						idx.put(i, j, k, replacement);
				}
			}
		}
	}
	
	
	



	/**
	 * Trace 1D ridges in a 2D or 3D image.
	 * 
	 * @param ridgeStrength
	 * @param strengthThreshold
	 * @param ridgeEigenvector
	 * @param ridgeMask optional uint8 mask; if present, ridges are not permitted within zero pixels
	 * @return
	 */
	static Mat traceRidges(Mat ridgeStrength, double strengthThreshold, Mat ridgeEigenvector, Mat ridgeMask) {

		FloatIndexer idxStrength = ridgeStrength.createIndexer();
		FloatIndexer idxEigenvector = ridgeEigenvector.createIndexer();
		UByteIndexer idxMask = ridgeMask == null ? null : ridgeMask.createIndexer();

		int height = ridgeStrength.rows();
		int width = ridgeStrength.cols();
		boolean do3D = ridgeStrength.channels() > 1;
		int sizeZ = do3D ? ridgeStrength.channels() : 1; // Channels may be repurposed as a z-dimension

		var matBinary = new Mat(height, width, opencv_core.CV_8UC(sizeZ), Scalar.ZERO);
		UByteIndexer idxBinary = matBinary.createIndexer();

		assert ridgeEigenvector.isContinuous();

		long[] inds = new long[4];
		for (long z = 0; z < sizeZ; z++) {
			inds[3] = z;
			for (long y = 1; y < height - 1; y++) {
				inds[0] = y;
				for (long x = 1; x < width - 1; x++) {
					inds[1] = x;

					if (idxMask != null && idxMask.get(y, x, z) == 0)
						continue;

					float strength = idxStrength.get(y, x, z);
					if (strength <= strengthThreshold)
						continue;

					float eigX, eigY, eigZ;

					if (do3D) {
						// TODO: Fix requirements for eigenvectors in 3D!
						// Should be able to determine appropriate vectors by taking cross product of vector along ridge?
						inds[2] = 3;
						eigX = idxEigenvector.get(inds);
						inds[2] += 1;
						eigY = idxEigenvector.get(inds);
						inds[2] += 1;
						eigZ = idxEigenvector.get(inds);
						if (strength <= Interpolation.interp3D(idxStrength, y + eigY, x + eigX, GeneralTools.clipValue(z + eigZ, 0, sizeZ-1))
								|| strength <= Interpolation.interp3D(idxStrength, y - eigY, x - eigX, GeneralTools.clipValue(z - eigZ, 0, sizeZ-1)))
							continue;

						inds[2] = 6;
						eigX = idxEigenvector.get(inds);
						inds[2] += 1;
						eigY = idxEigenvector.get(inds);
						inds[2] += 1;
						eigZ = idxEigenvector.get(inds);
						if (strength <= Interpolation.interp3D(idxStrength, y + eigY, x + eigX, GeneralTools.clipValue(z + eigZ, 0, sizeZ-1))
								|| strength <= Interpolation.interp3D(idxStrength, y - eigY, x - eigX, GeneralTools.clipValue(z - eigZ, 0, sizeZ-1)))
							continue;

						// For the future...
						inds[2] = 0;
						eigX = idxEigenvector.get(inds);
						inds[2] += 1;
						eigY = idxEigenvector.get(inds);
						inds[2] += 1;
						eigZ = idxEigenvector.get(inds);
					} else {
						eigX = idxEigenvector.get(y, x, 0);
						eigY = idxEigenvector.get(y, x, 1);
						eigZ = 0f;

						if (strength <= Interpolation.interp2D(idxStrength, y - eigX, x + eigY) || strength <= Interpolation.interp2D(idxStrength, y + eigX, x - eigY))
							continue;
					}

					idxBinary.put(y, x, z, 255);
				}
			}
		}

		idxBinary.close();
		idxStrength.close();
		idxEigenvector.close();

		return matBinary;
	}



	static class RidgePoint {

		/**
		 * x-coordinate for the pixel
		 */
		int x;

		/**
		 * y-coordinate for the pixel
		 */
		int y;

		/**
		 * z-slice for the pixel (or 0)
		 */
		int z;

		/**
		 * Ridge strength
		 */
		double strength;

		/**
		 * Ridge scale - may be used to estimate ridge width
		 */
		float scale;

		/**
		 * x-component of eigenvector describing potential ridge orientation.
		 * This typically corresponds to the eigenvalue with the smallest absolute value.
		 */
		float eigX;
		/**
		 * y-component of eigenvector describing potential ridge orientation.
		 * This typically corresponds to the eigenvalue with the smallest absolute value.
		 */
		float eigY;
		/**
		 * z-component of eigenvector describing potential ridge orientation.
		 * This typically corresponds to the eigenvalue with the smallest absolute value.
		 */
		float eigZ;
		
		
		static RidgePoint create(SimpleIndex ind, float strength, float scale, float eigX, float eigY, float eigZ) {
			var p = new RidgePoint();
			p.x = (int)ind.j;
			p.y = (int)ind.i;
			p.z = (int)ind.k;
			p.strength = strength;
			p.scale = scale;
			p.eigX = eigX;
			p.eigY = eigY;
			p.eigZ = eigZ;
			return p;
		}
		
		// TODO: Consider if this should be immutable!
		private void flipEigenvector() {
			eigX = -eigX;
			eigY = -eigY;
			eigZ = -eigZ;
		}

		double distanceSq(RidgePoint p, PixelCalibration cal) {
			double dx = p.x - x;
			double dy = p.y - y;
			double dz = p.z - z;
			if (cal != null && cal.hasPixelSizeMicrons()) {
				dx *= cal.getPixelWidthMicrons();
				dy *= cal.getPixelHeightMicrons();
				if (dz != 0)
					dz *= cal.getZSpacingMicrons();
			}
			if (Double.isFinite(dz))
				return dx * dx + dy * dy + dz * dz;
			else
				return dx * dx + dy * dy;
		}

		double distance(RidgePoint p, PixelCalibration cal) {
			return Math.sqrt(distanceSq(p, cal));
		}

		/**
		 * Compute dot product of eigenvectors corresponding to max eigenvalue
		 * @param p
		 * @return
		 */
		double eigenvectorDotProduct(RidgePoint p) {
			if (Double.isFinite(eigZ))
				return eigX * p.eigX + eigY * p.eigY + eigZ * p.eigZ;
			else
				return eigX * p.eigX + eigY * p.eigY;
		}
		
		/**
		 * Compute dot product between the eigenvectors of this point, and the displacement 
		 * vector from this point to point p (normalized to unit length).
		 * The purpose of this is to determine if the orientations match.
		 * @param p
		 * @param cal
		 * @return
		 */
		double displacementEigenvectorDotProduct(RidgePoint p, PixelCalibration cal) {
			double dx = x - p.x;
			double dy = y - p.y;
			double dz = z - p.z;
			if (cal != null && cal.hasPixelSizeMicrons()) {
				dx *= cal.getPixelWidthMicrons();
				dy *= cal.getPixelHeightMicrons();
				if (dz != 0)
					dz *= cal.getZSpacingMicrons();
			}
			if (dz > 0 && Double.isFinite(eigZ)) {
				double norm = Math.sqrt(dx*dx + dy*dy + dz*dz);
				dx /= norm;
				dy /= norm;
				dz /= norm;
				return eigX * dx + eigY * dy + eigZ * dz;
			} else {
				double norm = Math.sqrt(dx*dx + dy*dy);
				dx /= norm;
				dy /= norm;
				return eigX * dx + eigY * dy;
			}
		}

	}


	public static class Ridge {

		private List<RidgePoint> points = new ArrayList<>();

		private Ridge(Collection<RidgePoint> points) {
			this.points.addAll(points);
		}

		public LineString createLineString() {
			List<Coordinate> coords = new ArrayList<>();
			for (var p : points)
				coords.add(new Coordinate(p.x, p.y, p.z));
			return GeometryTools.getDefaultFactory().createLineString(coords.toArray(Coordinate[]::new));
		}

		/**
		 * Get the segments of this ridge visible on a specific plane
		 * @param plane
		 * @return
		 */
		public List<Ridge> getSegments(ImagePlane plane) {
			List<Ridge> segments = new ArrayList<>();
			List<RidgePoint> currentSegment = null;
			for (int i = 0; i < points.size(); i++) {
				var p = points.get(i);
				if (plane.getZ() == p.z) {
					if (currentSegment == null) {
						currentSegment = new ArrayList<>();
						if (i > 0) {
							currentSegment.add(points.get(i-1)); // TODO: Consider linking to half-way between points
						}
					}
					currentSegment.add(p);
				} else {
					if (currentSegment != null) {
						currentSegment.add(p); // TODO: Consider linking to half-way between points
						segments.add(new Ridge(currentSegment));
						currentSegment = null;
					}
				}
			}
			if (currentSegment != null) {
				segments.add(new Ridge(currentSegment));
			}
			return segments;
		}
		
		public RidgePoint getStart(boolean updateEigenvector) {
			if (points.isEmpty())
				return null;
			var p = points.get(0);
			if (updateEigenvector && points.size() > 1) {
				if (p.displacementEigenvectorDotProduct(points.get(1), null) > 0) {
					p.flipEigenvector();
				}
			}
			return p;
		}
		
		public RidgePoint getEnd(boolean updateEigenvector) {
			if (points.isEmpty())
				return null;
			var p = points.get(points.size()-1);
			if (updateEigenvector && points.size() > 1) {
				if (p.displacementEigenvectorDotProduct(points.get(points.size()-2), null) > 0) {
					p.flipEigenvector();
				}
			}
			return p;
		}
		
		
		public ROI createROI(ImagePlane plane) {
			if (plane == null) {
				int z = (int)Math.round(points.stream().mapToInt(p -> p.z).average().orElse(0));
				plane = ImagePlane.getPlane(z, 0);
			}
			if (points.size() == 1)
				return ROIs.createPointsROI(points.stream().map(c -> new Point2(c.x, c.y)).collect(Collectors.toList()), plane);
			return ROIs.createPolylineROI(points.stream().map(c -> new Point2(c.x, c.y)).collect(Collectors.toList()), plane);
		}

		public PathObject createDetection(ImagePlane plane) {
			var detection = PathObjects.createDetectionObject(createROI(plane));
			try (var ml = detection.getMeasurementList()) {
				ml.putMeasurement("Ridge strength", getMeanStrength());
			}
			return detection;
		}

		public PathObject createAnnotation(ImagePlane plane) {
			var annotation = PathObjects.createAnnotationObject(createROI(plane));
			try (var ml = annotation.getMeasurementList()) {
				ml.putMeasurement("Ridge strength", getMeanStrength());
			}
			return annotation;
		}

		public double getMeanStrength() {
			return points.stream().mapToDouble(p -> p.strength).average().getAsDouble();
		}
		
		public int nPoints() {
			return points.size();
		}
		
		public double length(PixelCalibration cal) {
			RidgePoint lastPoint = null;
			double distSum = 0;
			for (var p : points) {
				if (lastPoint != null) {
					distSum += p.distance(lastPoint, cal);
				}
				lastPoint = p;
			}
			return distSum;
		}
		
		public RidgePoint closestEndPoint(RidgePoint point, PixelCalibration cal) {
			double distSq0 = points.get(0).distanceSq(point, cal);
			double distSq1 = points.get(points.size()-1).distanceSq(point, cal);
			if (distSq0 <= distSq1)
				return points.get(0);
			return points.get(points.size()-1);
		}

	}


	static class SpatialCache {

		private ThreadLocal<Envelope> envelope = ThreadLocal.withInitial(() -> new Envelope());
		private ThreadLocal<ListVisitor<RidgePoint>> visitor = ThreadLocal.withInitial(() -> new ListVisitor<RidgePoint>());

		private Quadtree tree = new Quadtree();
		private Set<RidgePoint> contains = new HashSet<RidgePoint>();


		static SpatialCache buildCache(Collection<RidgePoint> points) {
			var cache = new SpatialCache();
			for (var p : points)
				cache.insert(p);
			return cache;
		}

		boolean isEmpty() {
			return contains.isEmpty();
		}

		RidgePoint getNextPoint(boolean highestStrength) {
			if (isEmpty())
				return null;
			if (highestStrength) {
				// A parallel stream is *much* slower
				double max = Double.NEGATIVE_INFINITY;
				RidgePoint maxPoint = null;
				for (var p : contains) {
					double strength = p.strength;
					if (strength > max) {
						maxPoint = p;
						max = strength;
					}
				}
				return maxPoint;
			} else
				return contains.iterator().next();
		}

		/**
		 * Get potential neighboring points
		 * @param point
		 * @param distance
		 * @return
		 */
		List<RidgePoint> query(RidgePoint point, double distance, PixelCalibration cal) {
			double expansion = cal == null || !cal.hasPixelSizeMicrons() ? distance : distance / Math.min(cal.getPixelWidthMicrons(), cal.getPixelHeightMicrons());
			var env = getEnvelope(point, expansion);
			var v = visitor.get();
			v.list.clear();
			tree.query(env, v);
			double distSq = distance*distance;
			v.list.removeIf(p -> point.distance(point, cal) > distSq);
			return v.list;
		}

		private Envelope getEnvelope(RidgePoint point, double expansion) {
			var env = envelope.get();
			env.init(point.x-expansion, point.x+expansion, point.y-expansion, point.y+expansion);
			return env;
		}

		void insert(RidgePoint point) {
			// TODO: Check, does the envelope need to be duplicated?
			if (contains.add(point))
				tree.insert(getEnvelope(point, 0), point);
		}

		boolean contains(RidgePoint point) {
			return contains.contains(point);
		}

		void remove(RidgePoint point) {
			if (contains.remove(point))
				tree.remove(getEnvelope(point, 0), point);
		}

		class ListVisitor<T> implements ItemVisitor {

			private List<T> list = new ArrayList<>();

			@Override
			public void visitItem(Object o) {
				list.add((T)o);
			}

		}

	}


	/**
	 * Keep track of maximum ridge response and associated (potential) ridge orientation for every pixel.
	 */
	static class ScaleAccumulator2D {

		Mat ridgeStrength;
		Mat ridgeEigenvector;
		Mat ridgeScale;
		Mat ridgeMask;

		void addScale(double scale, Mat matStrength, Mat matEigenvector, double strengthThreshold) {
			assert scale >= 0; //&& scale < 256
			// Update strength according to smoothing scale
			matStrength = opencv_core.multiply(matStrength, scale*scale).asMat();
			strengthThreshold *= scale * scale;
			if (ridgeStrength == null) {
				ridgeStrength = matStrength;
				ridgeEigenvector = matEigenvector;
				ridgeScale = new Mat(ridgeStrength.rows(), ridgeStrength.cols(), ridgeStrength.type());
				setTo(ridgeScale, (float)scale, null);
				
				ridgeMask = traceRidges(ridgeStrength, strengthThreshold, matEigenvector, null);
				
//				ridgeMask = computeRidgeMask(matStrength, strengthThreshold);
			} else {
				var mask = opencv_core.greaterThan(matStrength, ridgeStrength).asMat();
				//	            var matScale = new Mat(1, 1, opencv_core.CV_32F, Scalar.all(scale))
//				var maskRidgeMask = computeRidgeMask(matStrength, strengthThreshold);
				
				var maskRidgeMask = traceRidges(ridgeStrength, strengthThreshold, matEigenvector, mask);


				// Some methods need to be adjusted to handle 3D images
				matStrength.copyTo(ridgeStrength, mask);
				copyTo(matEigenvector, ridgeEigenvector, mask);
				maskRidgeMask.copyTo(ridgeMask, mask);
				setTo(ridgeScale, (float)scale, mask);
				//	            matScale.close()
				mask.close();
				matStrength.close();
			}

		}


		void setTo(Mat matDest, float value, Mat matMask) {
			FloatBuffer bufDest = (FloatBuffer)matDest.createBuffer();
			ByteBuffer bufMask = matMask == null ? null : (ByteBuffer)matMask.createBuffer();
			long n = java.util.stream.LongStream.of(matDest.createIndexer().sizes()).sum();
			for (int i = 0; i < n; i++) {
				if (matMask == null || bufMask.get(i) != (byte)0)
					bufDest.put(i, value);
			}
		}


		void copyTo(Mat matSource, Mat matDest, Mat matMask) {
			//	        if (matSource.channels() > 0) {
			//	            matSource.copyTo(matDest, matMask)
			//	            return
			//	        }
			FloatBuffer bufSource = (FloatBuffer)matSource.createBuffer();
			FloatBuffer bufDest = (FloatBuffer)matDest.createBuffer();
			ByteBuffer bufMask = (ByteBuffer)matMask.createBuffer();
			long n = java.util.stream.LongStream.of(matSource.createIndexer().sizes()).sum();
			//	        println 'LIMITS'
			//	        println bufSource.limit()
			//	        println bufDest.limit()
			//	        println bufMask.limit()
			for (int i = 0; i < n; i++) {
				if (bufMask.get(i) != (byte)0)
					bufDest.put(i, bufSource.get(i));
			}
		}


		Mat computeRidgeMask(Mat mat, double threshold) {
			if (threshold > 0) {
				return opencv_core.greaterThan(mat, threshold).asMat();
			} else {
				return opencv_core.lessThan(mat, threshold).asMat();
			}
		}

		List<RidgePoint> buildPoints(float strengthThreshold) {

			FloatIndexer idxStrength = ridgeStrength.createIndexer();
			FloatIndexer idxEigenvector = ridgeEigenvector.createIndexer();
			FloatIndexer idxScale = ridgeScale.createIndexer();
			UByteIndexer idxMask = ridgeMask.createIndexer();

			int height = ridgeStrength.rows();
			int width = ridgeStrength.cols();
			boolean do3D = ridgeStrength.channels() > 1;
			int sizeZ = do3D ? ridgeStrength.channels() : 1; // Channels may be repurposed as a z-dimension

			var matBinary = new Mat(height, width, opencv_core.CV_8UC(sizeZ), Scalar.ZERO);
			UByteIndexer idxBinary = matBinary.createIndexer();

			assert ridgeEigenvector.isContinuous();

			List<RidgePoint> points = new ArrayList<>();
			long[] inds = new long[4];
			for (long z = 0; z < sizeZ; z++) {
				inds[3] = z;
				for (long y = 1; y < height - 1; y++) {
					inds[0] = y;
					for (long x = 1; x < width - 1; x++) {
						inds[1] = x;

						if (idxMask.get(y, x, z) == 0)
							continue;

						float strength = idxStrength.get(y, x, z);
						if (strength <= strengthThreshold)
							continue;

						float scale = idxScale.get(y, x);
						float eigX, eigY, eigZ;

						if (do3D) {
							// TODO: Fix requirements for eigenvectors in 3D!
							// Should be able to determine appropriate vectors by taking cross product of vector along ridge?
							inds[2] = 3;
							eigX = idxEigenvector.get(inds);
							inds[2] += 1;
							eigY = idxEigenvector.get(inds);
							inds[2] += 1;
							eigZ = idxEigenvector.get(inds);
							if (strength <= Interpolation.interp3D(idxStrength, y + eigY, x + eigX, GeneralTools.clipValue(z + eigZ, 0, sizeZ-1))
									|| strength <= Interpolation.interp3D(idxStrength, y - eigY, x - eigX, GeneralTools.clipValue(z - eigZ, 0, sizeZ-1)))
								continue;

							inds[2] = 6;
							eigX = idxEigenvector.get(inds);
							inds[2] += 1;
							eigY = idxEigenvector.get(inds);
							inds[2] += 1;
							eigZ = idxEigenvector.get(inds);
							if (strength <= Interpolation.interp3D(idxStrength, y + eigY, x + eigX, GeneralTools.clipValue(z + eigZ, 0, sizeZ-1))
									|| strength <= Interpolation.interp3D(idxStrength, y - eigY, x - eigX, GeneralTools.clipValue(z - eigZ, 0, sizeZ-1)))
								continue;

							// For the future...
							inds[2] = 0;
							eigX = idxEigenvector.get(inds);
							inds[2] += 1;
							eigY = idxEigenvector.get(inds);
							inds[2] += 1;
							eigZ = idxEigenvector.get(inds);
						} else {
							eigX = idxEigenvector.get(y, x, 0);
							eigY = idxEigenvector.get(y, x, 1);
							eigZ = 0f;

							if (strength <= Interpolation.interp2D(idxStrength, y - eigX, x + eigY) || strength <= Interpolation.interp2D(idxStrength, y + eigX, x - eigY))
								continue;
						}

						idxBinary.put(y, x, z, 255);

						// TODO: Consider ensuring eigenvectors are normalized?
						var p = new RidgePoint();
						p.x = (int)x;
						p.y = (int)y;
						p.z = (int)z;
						p.strength = strength;
						p.scale = scale;
						p.eigX = eigX;
						p.eigY = eigY;
						p.eigZ = eigZ;
						points.add(p);
					}
				}
			}

			idxBinary.close();

			//	        opencv_ximgproc.thinning(matBinary, matBinary)
			//	        Display.showImage("Ridge mask", ridgeMask)
			//	        Display.showImage("Mask", ridgeMask)
			//	        Display.showImage("Ridges", matBinary);

			idxStrength.close();
			idxEigenvector.close();
			idxScale.close();

			matBinary.close();

			// TODO: Apply thinning before generating points
			// TODO: Initialize points to ridges based upon connected components (after branches removed)

			return points;
		}

	}
	

}
