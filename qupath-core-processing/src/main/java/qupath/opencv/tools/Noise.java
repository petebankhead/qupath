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

import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Methods that help when working with noisy images.
 * 
 * @author Pete Bankhead
 */
public class Noise {
	
	private final static Logger logger = LoggerFactory.getLogger(Noise.class);

	
	/**
	 * Update a Gaussian noise estimate after filtering with a specified kernel.
	 * This effectively multiples the input noise standard deviation by the L2 norm 
	 * of the kernel, which assumes that the noise is independent at every pixel;
	 * <p>
	 * Note: This method is <b>not</b> appropriate if the noise is not independent 
	 * at each pixel, e.g. if the input image has already been filtered.
	 * 
	 * @param noiseStdDev noise standard deviation for the original image
	 * @param kernel filter kernel
	 * @return the updated noise estimate
	 */
	public static double updateNoiseEstimate(double noiseStdDev, Mat kernel) {
		return noiseStdDev * opencv_core.norm(kernel);
	}
	
	/**
	 * Update a Gaussian noise estimate after filtering with one or more specified kernels.
	 * The kernels are assumed to be separable (1D), although this is not strictly enforced.
	 * <p>
	 * Note: This method is <b>not</b> appropriate if the noise is not independent 
	 * at each pixel, e.g. if the input image has already been filtered, or if the 
	 * kernels are applied along the same image dimensions.
	 * 
	 * @param noiseStdDev noise standard deviation for the original image
	 * @param kernels filter kernels
	 * @return the updated noise estimate
	 */
	public static double updateNoiseEstimateSeparable(double noiseStdDev, Mat... kernels) {
		for (var k : kernels) {
			noiseStdDev = updateNoiseEstimate(noiseStdDev, k);
		}
		return noiseStdDev;
	}
	
	

	/**
	 * Estimate the standard deviation of Gaussian noise in a 2D, single-channel image.
	 * This uses a simple algorithm:
	 * <ol>
	 * 	<li>Apply a high-pass filter to the image (horizontal and vertical differences)</li>
	 *  <li>Compute a robust estimate of the standard deviation, using k-sigma clipping</li>
	 * </ol>
	 * The output is an estimate of the noise standard deviation within the image.
	 * <p>
	 * Note: if a multi-channel image is provided, only the first channel will be used.
	 * 
	 * @param mat image containing Gaussian noise
	 * @param k k-value used for sigma clipping; suggested default  value is 3.0
	 * @return
	 * @throws IllegalArgumentException if the input mat has more than 1 channel.
	 */
	public static double estimateNoise(Mat mat, double k) {
		logger.trace("estimateNoise called with k={}", k);
	    var mat2 = new Mat();
	    var kernel = new Mat(new double[] {-1.0, 1.0, 0.0});
	    opencv_imgproc.sepFilter2D(mat, mat2, opencv_core.CV_32F, kernel, kernel);
	    kernel.release();
	    // Use k-clipped estimate of standard deviation
	    // Divide by two because the integer filters have scaled the estimate (in 2D)
	    return kClippedStdDev(mat2, k, 5) / 2.0;
	}
	
	
	/**
	 * Calculate the standard deviation of pixels in a single-channel image using k-sigma clipping.
	 * This iteratively computes the standard deviation while eliminating pixels more than {@code k * stdDev} from 
	 * the mean.
	 * 
	 * @param mat input mat
	 * @param k k-value used for sigma clipping; suggested default value is 3.0
	 * @param nIterations number of iterations to use when calculating the standard deviation; suggested default value is 5
	 * @return k-clipped standard deviation
	 * @throws IllegalArgumentException if the input mat has more than 1 channel.
	 */
	public static double kClippedStdDev(Mat mat, double k, int nIterations) {
		if (mat.channels() > 1)
			throw new IllegalArgumentException("kClippedStdDev supports only single-channel images, but input has " + mat.channels() + " channels!");
		logger.debug("kClippedStdDev called with k={}", k);
		// Use k-clipped estimate of standard deviation
	    var matMean = new Mat();
	    var matStdDev = new Mat();
	    opencv_core.meanStdDev(mat, matMean, matStdDev);
	    var matMask = new Mat();
	    var idx = matStdDev.createIndexer();
	    var idxMean = matMean.createIndexer();
	    double stdDev = idx.getDouble(0);
	    double mean = idxMean.getDouble(0);
		logger.trace("Standard deviation after 0 iterations: {} (mean = {})", stdDev, mean);
	    for (int i = 0; i < nIterations; i++) {
	        if (stdDev == 0)
	            return 0.0;
	        double threshold = stdDev * k;
	        double lowerBound = mean - threshold;
	        double upperBound = mean + threshold;
	        matMask.put(opencv_core.lessThan(mat, upperBound).mul(opencv_core.greaterThan(mat, lowerBound)));
	        opencv_core.meanStdDev(mat, matMean, matStdDev, matMask);
	        stdDev = idx.getDouble(0);
	        mean = idxMean.getDouble(0);
			logger.trace("Standard deviation after {} iteration(s): {} (mean = {})", i+1, stdDev, mean);
	    }
	    idx.close();
	    idxMean.close();
		return stdDev;
	}
	
	
}
