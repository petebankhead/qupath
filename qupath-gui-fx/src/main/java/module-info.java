import qupath.lib.gui.localization.QuPathResourceBundleProviderImpl;

module qupath.gui.fx {

    requires java.base;
    requires java.desktop;
	requires java.datatransfer;
	requires java.prefs;
	requires java.scripting;
	requires java.net.http;
	
	requires transitive javafx.base;
	requires transitive javafx.controls;
	requires transitive javafx.graphics;
	requires javafx.swing;
	requires javafx.web;
	
	requires transitive qupath.core.processing;
	
	requires transitive org.controlsfx.controls;
	requires jfxtras.menu;
	requires org.slf4j;
    requires qupath.fx;

	uses qupath.lib.gui.localization.spi.QuPathResourceBundleProvider;
	provides qupath.lib.gui.localization.spi.QuPathResourceBundleProvider with qupath.lib.gui.localization.QuPathResourceBundleProviderImpl;

	requires com.google.common;
	requires org.apache.commons.text;
	requires org.apache.commons.math4.legacy;
	requires org.apache.commons.statistics.distribution;
	requires org.commonmark;
	requires com.google.gson;
	requires org.kordamp.ikonli.javafx;
	requires ch.qos.logback.classic;
	requires ch.qos.logback.core;
	requires org.locationtech.jts;

	exports qupath.lib.display;
	exports qupath.lib.gui;
	exports qupath.lib.gui.commands;
	exports qupath.lib.gui.dialogs;
	exports qupath.lib.gui.extensions;
	exports qupath.lib.gui.images.servers;
	exports qupath.lib.gui.images.stores;
	exports qupath.lib.gui.logging;
	exports qupath.lib.gui.panes;
	exports qupath.lib.gui.prefs;
	exports qupath.lib.gui.scripting;
	exports qupath.lib.gui.tma;
	exports qupath.lib.gui.tools;
	exports qupath.lib.gui.viewer;
	exports qupath.lib.gui.viewer.tools;
	exports qupath.lib.gui.viewer.recording;
	exports qupath.lib.gui.viewer.overlays;
	exports qupath.lib.gui.actions;
	exports qupath.lib.gui.actions.annotations;
	exports qupath.lib.gui.localization;
	exports qupath.lib.gui.charts;
	exports qupath.lib.gui.prefs.annotations;
	exports qupath.lib.gui.viewer.tools.handlers;
    exports qupath.lib.gui.scripting.languages;
	exports qupath.lib.gui.localization.spi;

	uses qupath.lib.gui.extensions.QuPathExtension;
	
}