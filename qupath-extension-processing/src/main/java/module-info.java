module qupath.extension.processing {
	
	requires java.base;
	requires java.desktop;
	
	requires javafx.controls;
	
	requires qupath.core;
    requires qupath.core.processing;
    requires qupath.gui.fx;

    requires org.slf4j;
    requires org.bytedeco.opencv;
	requires org.controlsfx.controls;
	requires org.locationtech.jts;
	requires qupath.fx;
	requires com.google.gson;
	requires org.apache.commons.math4.legacy;

	exports qupath.imagej.gui;
	
	provides qupath.lib.gui.extensions.QuPathExtension with 
		qupath.imagej.gui.IJExtension, qupath.process.gui.ProcessingExtension;
	
}