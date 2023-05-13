module qupath.core {
    requires java.base;
    requires java.desktop;
    requires java.scripting;
	
    requires org.slf4j;
    requires org.locationtech.jts;
    requires com.google.gson;
    requires ij;
    requires org.apache.commons.statistics.distribution;
    requires info.picocli;
    requires com.google.common;
//    requires commons.math3;

    exports qupath.lib.analysis;
    exports qupath.lib.analysis.images;
    exports qupath.lib.analysis.stats;
    exports qupath.lib.analysis.stats.survival;
    exports qupath.lib.awt.common;
    exports qupath.lib.classifiers;
    exports qupath.lib.classifiers.object;
    exports qupath.lib.classifiers.pixel;
    exports qupath.lib.color;
    exports qupath.lib.common;
    exports qupath.lib.geom;
    exports qupath.lib.images;
    exports qupath.lib.images.servers;
    exports qupath.lib.images.writers;
    exports qupath.lib.io;
    exports qupath.lib.measurements;
    exports qupath.lib.objects;
    exports qupath.lib.objects.classes;
    exports qupath.lib.objects.hierarchy;
    exports qupath.lib.objects.hierarchy.events;
    exports qupath.lib.plugins;
    exports qupath.lib.plugins.parameters;
    exports qupath.lib.plugins.workflow;
    exports qupath.lib.projects;
    exports qupath.lib.regions;
    exports qupath.lib.roi;
    exports qupath.lib.roi.interfaces;
}