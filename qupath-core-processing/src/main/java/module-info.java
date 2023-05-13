module qupath.core.processing {
	
    requires java.base;
    requires java.desktop;
    
    requires transitive qupath.core;

    requires org.slf4j;
    requires transitive ij;
    requires transitive org.bytedeco.opencv;
    requires io.github.qupath.bioimageio.spec;
    requires com.google.gson;
    requires com.google.common;
    requires org.locationtech.jts;
    requires java.scripting;
    requires org.apache.commons.math4.legacy;

    exports qupath.imagej.detect.cells;
    exports qupath.imagej.detect.dearray;
    exports qupath.imagej.detect.tissue;
    exports qupath.imagej.images.servers;
    exports qupath.imagej.images.writers;
    exports qupath.imagej.processing;
    exports qupath.imagej.superpixels;
    exports qupath.imagej.tools;
    exports qupath.lib.algorithms;
    exports qupath.lib.analysis.algorithms;
    exports qupath.lib.analysis.features;
    exports qupath.lib.plugins.objects;
    exports qupath.lib.scripting;
    exports qupath.opencv;
    exports qupath.opencv.features;
    exports qupath.opencv.tools;
    exports qupath.lib.scripting.languages;
    exports qupath.opencv.ops;
    exports qupath.opencv.ml.pixel;
    exports qupath.opencv.ml;
    exports qupath.lib.analysis.heatmaps;
    exports qupath.opencv.ml.objects.features;
    exports qupath.opencv.ml.objects;

    provides qupath.lib.images.writers.ImageWriter with
    	qupath.imagej.images.writers.TiffWriterIJ,
    	qupath.imagej.images.writers.ZipWriterIJ;
    
    provides qupath.lib.images.servers.ImageServerBuilder with
    	qupath.imagej.images.servers.ImageJServerBuilder;

}