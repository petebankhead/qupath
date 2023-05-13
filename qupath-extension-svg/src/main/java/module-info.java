module qupath.extension.scripteditor {
	
    requires qupath.gui.fx;

    requires org.slf4j;
	requires org.jfree.svg;
	requires java.desktop;
    requires qupath.fx;

    provides qupath.lib.gui.extensions.QuPathExtension with
		qupath.lib.extension.svg.SvgExtension;

}