/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * Copyright (C) 2018 - 2020 QuPath developers, The University of Edinburgh
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

package qupath.imagej.gui;

import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.Menus;
import ij.Prefs;
import ij.gui.ImageWindow;
import ij.gui.Overlay;
import ij.gui.Roi;
import javafx.application.Platform;
import javafx.beans.property.StringProperty;
import javafx.geometry.Orientation;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuButton;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import java.awt.Color;
import java.awt.Frame;
import java.awt.Rectangle;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import javax.swing.SwingUtilities;

import org.controlsfx.control.action.Action;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.fx.dialogs.FileChoosers;
import qupath.fx.prefs.controlsfx.PropertyItemBuilder;
import qupath.imagej.detect.cells.PositiveCellDetection;
import qupath.imagej.detect.cells.SubcellularDetection;
import qupath.imagej.detect.cells.WatershedCellDetection;
import qupath.imagej.detect.cells.WatershedCellMembraneDetection;
import qupath.imagej.detect.dearray.TMADearrayerPluginIJ;
import qupath.imagej.detect.tissue.PositivePixelCounterIJ;
import qupath.imagej.detect.tissue.SimpleTissueDetection2;
import qupath.imagej.superpixels.DoGSuperpixelsPlugin;
import qupath.imagej.superpixels.SLICSuperpixelsPlugin;
import qupath.imagej.tools.IJTools;
import qupath.lib.awt.common.AwtTools;
import qupath.lib.color.ColorToolsAwt;
import qupath.lib.common.GeneralTools;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.actions.ActionTools;
import qupath.lib.gui.actions.annotations.ActionConfig;
import qupath.lib.gui.actions.annotations.ActionIcon;
import qupath.lib.gui.actions.annotations.ActionMenu;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.localization.QuPathResources;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.prefs.SystemMenuBar;
import qupath.lib.gui.tools.ColorToolsFX;
import qupath.lib.gui.tools.IconFactory.PathIcons;
import qupath.lib.gui.viewer.OverlayOptions;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.gui.viewer.DragDropImportListener.DropHandler;
import qupath.lib.images.PathImage;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathCellObject;
import qupath.lib.objects.PathDetectionObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectTools;
import qupath.lib.objects.TMACoreObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.PathPlugin;
import qupath.lib.plugins.objects.TileClassificationsToAnnotationsPlugin;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;
import qupathj.QuPath_Send_Overlay_to_QuPath;
import qupathj.QuPath_Send_ROI_to_QuPath;

/**
 * QuPath extension &amp; associated static helper methods used to support integration of ImageJ with QuPath.
 * 
 * @author Pete Bankhead
 *
 */
public class IJExtension implements QuPathExtension {
	
	private static final Logger logger = LoggerFactory.getLogger(IJExtension.class);
	
	// Path to ImageJ - used to determine plugins directory
	private static StringProperty imageJPath = null;

	/**
	 * It is necessary to block MenuBars created with AWT on macOS, otherwise shortcuts
	 * can be fired twice and menus confused.
	 * But using the same strategy on Windows causes ImageJ menus not to display.
	 * So we need to handle both cases (and make it possible to override).
	 */
	private static final boolean blockAwtMenuBars = "true".equals(System.getProperty("qupath.block.awt.menubars",
			GeneralTools.isMac() ? "true" : "false"));
	private static final AwtMenuBarBlocker menuBarBlocker = new AwtMenuBarBlocker();

	static {
		// Try to default to the most likely ImageJ path on a Mac
		if (GeneralTools.isMac() && new File("/Applications/ImageJ/").isDirectory())
			imageJPath = PathPrefs.createPersistentPreference("ijPath", "/Applications/ImageJ");
		else
			imageJPath = PathPrefs.createPersistentPreference("ijPath", null);
	}
	
	/**
	 * Set the path for a local ImageJ installation, if required. This can be used to help load external ImageJ plugins.
	 * @param path
	 */
	public static void setImageJPath(final String path) {
		imageJPath.set(path);
	}
	
	/**
	 * Get the path for a local ImageJ installation, if set.
	 * @return 
	 */
	public static String getImageJPath() {
		return imageJPath.get();
	}
	
	/**
	 * Property representing the path to a local ImageJ installation, or null if no path has been set.
	 * @return
	 */
	public static StringProperty imageJPathProperty() {
		return imageJPath;
	}
	

	/**
	 * Get an instance of ImageJ, or start one, for interactive use (with GUI displayed).
	 * 
	 * @return an ImageJ instance, or null if ImageJ could not be started
	 */
	public static synchronized ImageJ getImageJInstance() {
		if (SwingUtilities.isEventDispatchThread())
			return getImageJInstanceOnEDT();
		
		var ij = IJ.getInstance();
		if (ij != null) {
			ensurePluginsInstalled(ij);
			return ij;
		}
		
		// Try getting ImageJ without resorting to the EDT?
		return getImageJInstanceOnEDT();
	}

	private static Set<ImageJ> installedPlugins = Collections.newSetFromMap(new WeakHashMap<>());
	
	/**
	 * Ensure we have installed the necessary plugins.
	 * We might not if ImageJ has been launched elsewhere.
	 * @param imageJ the ImageJ instance for which the plugins should be installed.
	 */
	private static synchronized void ensurePluginsInstalled(ImageJ imageJ) {
		if (installedPlugins.contains(imageJ))
			return;
		if (!Platform.isFxApplicationThread() && !SwingUtilities.isEventDispatchThread()) {
			SwingUtilities.invokeLater(() -> ensurePluginsInstalled(imageJ));
			return;
		}
		logger.info("Installing QuPath plugins for ImageJ");
		Menus.installPlugin(QuPath_Send_ROI_to_QuPath.class.getName(), Menus.PLUGINS_MENU, "Send ROI to QuPath", "", imageJ);
		Menus.installPlugin(QuPath_Send_Overlay_to_QuPath.class.getName(), Menus.PLUGINS_MENU, "Send Overlay to QuPath", "", imageJ);
		Menus.installPlugin(QuPath_Send_Overlay_to_QuPath.class.getName() + "(\"manager\")", Menus.PLUGINS_MENU, "Send RoiManager ROIs to QuPath", "", imageJ);
		installedPlugins.add(imageJ);
	}


	private static synchronized ImageJ getImageJInstanceOnEDT() {
		ImageJ ijTemp = IJ.getInstance();
		Prefs.setThreads(1); // Turn off ImageJ's multithreading, since we do our own
		if (ijTemp == null) {
			logger.info("Creating a new standalone ImageJ instance...");
			try {
				// See http://rsb.info.nih.gov/ij/docs/menus/plugins.html for setting the plugins directory
				String ijPath = getImageJPath();
				if (ijPath != null)
					System.getProperties().setProperty("plugins.dir", ijPath);
				ijTemp = new ImageJ(ImageJ.STANDALONE);
			} catch (Exception e) {
				// There may be an error (e.g. on OSX when attempting to install an ApplicationListener), but one we can safely ignore -
				// so don't print a full stack trace & try to get an instance again
				logger.warn(e.getLocalizedMessage());
				ijTemp = IJ.getInstance();
			}
			if (ijTemp == null) {
				logger.error("Unable to start ImageJ");
				return null;
			}
		
			// ImageJ doesn't necessarily behave well when it is closed but windows are left open -
			// so here ensure that all remaining displayed images are closed
			final ImageJ ij = ijTemp;
			ij.exitWhenQuitting(false);
			var windowListener = new ImageJWindowListener(ij);
			ij.addWindowListener(windowListener);

			// Attempt to block the AWT menu bar when ImageJ is not in focus.
			// Also try to work around a macOS issue where ImageJ's menubar and QuPath's don't work nicely together,
			// by ensuring that any system menubar request by QuPath is (temporarily) overridden.
			if (blockAwtMenuBars)
				menuBarBlocker.startBlocking();
			if (ij.isShowing())
				SystemMenuBar.setOverrideSystemMenuBar(true);

			logger.debug("Created ImageJ instance: {}", ijTemp);
		}
		
		// Make sure we have QuPath's custom plugins installed
		ensurePluginsInstalled(ijTemp);
		
		return ijTemp;
	}


	private static class ImageJWindowListener extends WindowAdapter {

		private final ImageJ ij;

		private ImageJWindowListener(ImageJ ij) {
			this.ij = ij;
		}

		@Override
		public void windowClosing(WindowEvent e) {
			ij.requestFocus();
			for (Frame frame : Frame.getFrames()) {
				// Close any images we have open
				if (frame instanceof ImageWindow) {
					ImageWindow win = (ImageWindow) frame;
					ImagePlus imp = win.getImagePlus();
					if (imp != null)
						imp.setIJMenuBar(false);
					win.setVisible(false);
					if (imp != null) {
						// Save message still appears...
						imp.changes = false;
						// Initially tried to close, but then ImageJ hung
						// Flush was ok, unless it was selected to save changes - in which case that didn't work out
						//							imp.flush();
						//							imp.close();
						//								imp.flush();
					} else
						win.dispose();
				}
			}
			SystemMenuBar.setOverrideSystemMenuBar(false);
		}

	}
	
	
	/**
	 * Extract a region of interest from an image as an ImageJ ImagePlus.
	 * @param server the image
	 * @param pathROI
	 * @param request
	 * @param setROI true if a ROI should be converted to the closest matching ImageJ {@code Roi} &amp; set on the image, false otherwise
	 * @return an {@link ImagePlus} wrapped in a {@link PathImage} to give additional calibration information
	 * @throws IOException 
	 */
	public static PathImage<ImagePlus> extractROI(ImageServer<BufferedImage> server, ROI pathROI, RegionRequest request, boolean setROI) throws IOException {
		setROI = setROI && (pathROI != null);
		// Ensure the ROI bounds & ensure it fits within the image
		Rectangle bounds = AwtTools.getBounds(request);
		if (bounds != null)
			bounds = bounds.intersection(new Rectangle(0, 0, server.getWidth(), server.getHeight()));
		if (bounds == null) {
			return null;
		}
	
		PathImage<ImagePlus> pathImage = IJTools.convertToImagePlus(server, request);
		if (pathImage == null || pathImage.getImage() == null)
			return null;
	
	
	
		if (setROI) {
			ImagePlus imp = pathImage.getImage();
//			if (!(pathROI instanceof RectangleROI)) {
				Roi roi = IJTools.convertToIJRoi(pathROI, pathImage);
				imp.setRoi(roi);
//			}
		}
		return pathImage;
	}

	/**
	 * Similar to {@link #extractROI(ImageServer, ROI, RegionRequest, boolean)}, except that the title of the ImagePlus is set according to the parent object type (which is used to get the ROI).
	 * Specifically, if a TMA core is passed as a parent, then the core name will be included in the title.
	 * 
	 * @param server
	 * @param pathObject
	 * @param request
	 * @param setROI
	 * @return
	 * @throws IOException 
	 * 
	 * @see #extractROI(ImageServer, ROI, RegionRequest, boolean)
	 */
	public static PathImage<ImagePlus> extractROI(ImageServer<BufferedImage> server, PathObject pathObject, RegionRequest request, boolean setROI) throws IOException {
		PathImage<ImagePlus> pathImage = extractROI(server, pathObject.getROI(), request, setROI);
		IJTools.setTitleFromObject(pathImage, pathObject);
		return pathImage;
	}
	

	/**
	 * Extract an image region as an ImagePlus, optionally setting ImageJ Rois corresponding to QuPath objects.
	 * 
	 * @param server server from which pixels should be requested
	 * @param pathObject the primary object, which may have its ROI set on the image
	 * @param hierarchy object hierarchy containing objects whose ROIs should be added to the ImagePlus overlay
	 * @param request the region being requested
	 * @param setROI if true, the ROI of the pathObject will be set on the image as the 'main' ROI (i.e. not an overlay)
	 * @param options options determining which kinds of objects will have ROIs added, to match with the display in the QuPath viewer
	 * @return
	 * @throws IOException
	 */
	public static PathImage<ImagePlus> extractROIWithOverlay(ImageServer<BufferedImage> server, PathObject pathObject, PathObjectHierarchy hierarchy, RegionRequest request, boolean setROI, OverlayOptions options) throws IOException {
		ROI pathROI;
		if (pathObject == null || !pathObject.hasROI()) {
			pathROI = ROIs.createRectangleROI(0, 0, server.getWidth(), server.getHeight(), ImagePlane.getDefaultPlane());
			//			logger.error("No ROI found to extract!");
			//			return null;
		} else
			pathROI = pathObject.getROI();

		// Extract the image
		PathImage<ImagePlus> pathImage = extractROI(server, pathROI, request, setROI);
		if (pathImage == null)
			return pathImage;

		// Add the overlay
		if (hierarchy != null) {
			ImagePlus imp = pathImage.getImage();
			var regionPredicate = PathObjectTools.createImageRegionPredicate(request);
			Overlay overlay = extractOverlay(hierarchy, request, options, p -> p != pathObject && regionPredicate.test(p));
			if (overlay.size() > 0) {
				imp.setOverlay(overlay);
			}
		}
		IJTools.setTitleFromObject(pathImage, pathObject);

		return pathImage;
	}

	
	/**
	 * Extract an ImageJ overlay for the specified region.
	 * @param hierarchy
	 * @param request
	 * @param options options to control which objects are being displayed
	 * @param filter optional additional filter used to determine which objects will be included (may be used in combination with options)
	 * @return
	 */
	public static Overlay extractOverlay(PathObjectHierarchy hierarchy, RegionRequest request, OverlayOptions options, Predicate<PathObject> filter) {
		Overlay overlay = new Overlay();
		
		double downsample = request.getDownsample();
		double xOrigin = -request.getX() / downsample;
		double yOrigin = -request.getY() / downsample;
		
		// TODO: Permit filling/unfilling ROIs
		for (PathObject child : hierarchy.getObjectsForRegion(PathObject.class, request, null)) {
			if (filter != null && !filter.test(child))
				continue;
			
			if (child.hasROI()) {
				
				// Check if this is displayed - skip it not
				if (options != null && 
						((child instanceof PathDetectionObject && !options.getShowDetections()) ||
						(child instanceof PathAnnotationObject && !options.getShowAnnotations()) ||
						(child instanceof TMACoreObject && !options.getShowTMAGrid())))
					continue;
				
				boolean isCell = child instanceof PathCellObject;
				
				Color color = ColorToolsAwt.getCachedColor(ColorToolsFX.getDisplayedColorARGB(child));
				boolean showOuterRoi = !isCell || (options == null || options.getShowCellBoundaries());
				boolean showNucleusRoi = isCell && (options == null || options.getShowCellNuclei());
				if (showOuterRoi) {
					Roi roi = IJTools.convertToIJRoi(child.getROI(), xOrigin, yOrigin, downsample);
					roi.setStrokeColor(color);
					roi.setName(child.getDisplayedName());
					//						roi.setStrokeWidth(2);
					overlay.add(roi);
				}
				if (showNucleusRoi) {
					ROI nucleus = ((PathCellObject)child).getNucleusROI();
					if (nucleus == null)
						continue;
					Roi roi = IJTools.convertToIJRoi(((PathCellObject)child).getNucleusROI(), xOrigin, yOrigin, downsample);
					roi.setStrokeColor(color);
					roi.setName(child.getDisplayedName() + " - nucleus");
					overlay.add(roi);
				}
			}
		}
		return overlay;
	}
	
	
	/**
	 * Commands to install with the ImageJ extension.
	 */
	@SuppressWarnings("javadoc")
	public static class IJExtensionCommands {
		
		private QuPathGUI qupath;

		@ActionMenu(value = {"Menu.Analyze", "Menu.Analyze.Tiles"})
		public final Action SEP_0 = ActionTools.createSeparator();

		@ActionMenu(value = {"Menu.Analyze", "Menu.Analyze.Tiles"})
		@ActionConfig("Action.ImageJ.superpixelsSLIC")
		public final Action actionSLIC;
		
		@ActionMenu(value = {"Menu.Analyze", "Menu.Analyze.Tiles"})
		@ActionConfig("Action.ImageJ.superpixelsDoG")
		public final Action actionDoG;
		
		@ActionMenu(value = {"Menu.Analyze", "Menu.Analyze.Tiles"})
		public final Action SEP_1 = ActionTools.createSeparator();
		
		@ActionMenu(value = {"Menu.Analyze", "Menu.Analyze.Tiles"})
		@ActionConfig("Action.ImageJ.tilesToAnnotations")
		public final Action actionTiles;
		
		
		@ActionMenu(value = {"Menu.Analyze", "Menu.Analyze.CellDetection"})
		@ActionConfig("Action.ImageJ.cellDetection")
		public final Action actionCellDetection;

		@ActionMenu(value = {"Menu.Analyze", "Menu.Analyze.CellDetection"})
		@ActionConfig("Action.ImageJ.positiveCellDetection")
		public final Action actionPositiveCellDetection;
		
		@ActionMenu(value = {"Menu.Analyze", "Menu.Analyze.CellDetection"})
		public final Action SEP_2 = ActionTools.createSeparator();

		@ActionMenu(value = {"Menu.Analyze", "Menu.Analyze.CellDetection"})
		@ActionConfig("Action.ImageJ.subcellularDetection")
		@Deprecated
		public final Action actionSubcellularDetection;

		@ActionMenu("Menu.Analyze")
		public final Action SEP_2B = ActionTools.createSeparator();
		
		@ActionMenu(value = {"Menu.Analyze", "Menu.deprecated"})
		@ActionConfig("Action.ImageJ.pixelCount")
		@Deprecated
		public final Action actionPixelCount;
		
//		@ActionMenu("TMA>")				
//		@ActionDescription("Identify cores and grid arrangement of a tissue microarray.")
//		public final Action actionTMADearray;
		
		
		@ActionMenu(value = {"Menu.Analyze", "Menu.deprecated"})
		@ActionConfig("Action.ImageJ.simpleTissueDetection")
		@Deprecated
		public final Action actionSimpleTissueDetection;
		
		@ActionMenu(value = {"Menu.Analyze", "Menu.deprecated"})
		@ActionConfig("Action.ImageJ.cellAndMembraneDetection")
		@Deprecated
		public final Action actionCellMembraneDetection;

		
		@ActionIcon(PathIcons.EXTRACT_REGION)
		@ActionMenu(value = {"Menu.Extensions", "ImageJ>"})
		@ActionConfig("Action.ImageJ.extractRegion")
		public final Action actionExtractRegion;
				
		@ActionIcon(PathIcons.SCREENSHOT)
		@ActionMenu(value = {"Menu.Extensions", "ImageJ>"})
		@ActionConfig("Action.ImageJ.extractRegionSnapshot")
		public final Action actionSnapshot;
		
		@ActionMenu(value = {"Menu.Extensions", "ImageJ>"})
		@ActionConfig("Action.ImageJ.importRois")
		public final Action actionImportROIs;

		@ActionMenu(value = {"Menu.Extensions", "ImageJ>"})
		public final Action SEP_3 = ActionTools.createSeparator();
		
		@ActionMenu(value = {"Menu.Extensions", "ImageJ>"})
		@ActionConfig("Action.ImageJ.setPluginsDirectory")
		public final Action actionPlugins = ActionTools.createAction(() -> promptToSetPluginsDirectory());
		
		@ActionMenu(value = {"Menu.Extensions", "ImageJ>"})
		public final Action SEP_4 = ActionTools.createSeparator();

		@ActionMenu(value = {"Menu.Extensions", "ImageJ>"})
		@ActionConfig("Action.ImageJ.macroRunner")
		public final Action actionMacroRunner;
				
		IJExtensionCommands(QuPathGUI qupath) {
			
			this.qupath = qupath;
			
			// Experimental brush tool turned off for now
			ExtractRegionCommand commandExtractRegionCustom = new ExtractRegionCommand(qupath);
			actionExtractRegion = qupath.createImageDataAction(imageData -> commandExtractRegionCustom.run());

			var screenshotCommand = new ScreenshotCommand(qupath);
			actionSnapshot = ActionTools.createAction(screenshotCommand);		
			
			actionMacroRunner = createPluginAction(new ImageJMacroRunner(qupath));
			
			actionSLIC = createPluginAction(SLICSuperpixelsPlugin.class);
			actionDoG = createPluginAction(DoGSuperpixelsPlugin.class);
			actionTiles = createPluginAction(TileClassificationsToAnnotationsPlugin.class);
			
			actionPixelCount = createPluginAction(PositivePixelCounterIJ.class);
			
//			actionTMADearray = qupath.createPluginAction("TMA dearrayer", TMADearrayerPluginIJ.class, null);
			
			actionSimpleTissueDetection = createPluginAction(SimpleTissueDetection2.class);
			
			actionCellDetection = createPluginAction(WatershedCellDetection.class);
			actionPositiveCellDetection = createPluginAction(PositiveCellDetection.class);
			actionCellMembraneDetection = createPluginAction(WatershedCellMembraneDetection.class);
			actionSubcellularDetection = createPluginAction(SubcellularDetection.class);
			
			var importRoisCommand = new ImportRoisCommand(qupath);
			actionImportROIs = qupath.createImageDataAction(imageData -> importRoisCommand.run());
		}
		
		private Action createPluginAction(Class<? extends PathPlugin> pluginClass) {
			return qupath.createPluginAction(null, pluginClass, null);
		}
		
		private Action createPluginAction(PathPlugin<BufferedImage> plugin) {
			return qupath.createPluginAction(null, plugin, null);
		}
		
		
	}
		
	static void promptToSetPluginsDirectory() {
		String path = getImageJPath();
		File dir = FileChoosers.promptForDirectory("Set ImageJ plugins directory", path == null ? null : new File(path));
		if (dir != null && dir.isDirectory())
			setImageJPath(dir.getAbsolutePath());
	}
	
	
	private boolean extensionInstalled = false;
	
	/**
	 * 
	 * Add some commands written using ImageJ to QuPath.
	 * 
	 * TODO: Clean up this code... it's incredibly messy!
	 * 
	 * @param qupath
	 */
	private void addQuPathCommands(final QuPathGUI qupath) {
				
		// Add a preference to set the ImageJ path
		var item = new PropertyItemBuilder<>(imageJPath, String.class)
				.propertyType(PropertyItemBuilder.PropertyType.DIRECTORY)
						.name("ImageJ plugins directory")
								.category("ImageJ")
										.description("Set the path to the 'plugins' directory of an existing ImageJ installation")
				.build();
		qupath.getPreferencePane().getPropertySheet().getItems().add(item);
		
		var commands = new IJExtensionCommands(qupath);
		qupath.installActions(ActionTools.getAnnotatedActions(commands));
		
		// Add buttons to toolbar
		var toolbar = qupath.getToolBar();
		toolbar.getItems().add(new Separator(Orientation.VERTICAL));
		
		try {
			ImageView imageView = new ImageView(getImageJIcon(QuPathGUI.TOOLBAR_ICON_SIZE, QuPathGUI.TOOLBAR_ICON_SIZE));
			MenuButton btnImageJ = new MenuButton();
			btnImageJ.setGraphic(imageView);
			btnImageJ.setTooltip(new Tooltip("ImageJ commands"));
			btnImageJ.getItems().addAll(
					ActionTools.createMenuItem(commands.actionExtractRegion),
					ActionTools.createMenuItem(commands.actionSnapshot)
			);
			toolbar.getItems().add(btnImageJ);
		} catch (Exception e) {
			logger.error("Error adding toolbar buttons", e);
		}
				
		// It's awkward, but we handle TMA dearraying separately so we can ensure it falls at the top of the list
		Menu menuTMA = qupath.getMenu("TMA", true);
		
		// TODO: Switch to use @ActionConfig
		var actionTMADearray = qupath.createPluginAction(QuPathResources.getString("Action.ImageJ.tmaDearrayer"), TMADearrayerPluginIJ.class, null);
		actionTMADearray.setLongText(QuPathResources.getString("Action.ImageJ.tmaDearrayer.description"));
		menuTMA.getItems().add(0,
						ActionTools.createMenuItem(actionTMADearray)
				);
		
		qupath.getDefaultDragDropListener().addFileDropHandler(new ImageJDropHandler(qupath));
		
	}
	
	
	static class ImageJDropHandler implements DropHandler<File> {
		
		private QuPathGUI qupath;
		
		private ImageJDropHandler(QuPathGUI qupath) {
			this.qupath = qupath;
		}

		@Override
		public boolean handleDrop(QuPathViewer viewer, List<File> list) {
			if (list.size() == 1) {
				if (handleMacro(list.get(0)))
					return true;
			}
			return handleRois(viewer, list);
		}
		
		private boolean handleRois(QuPathViewer viewer, List<File> files) {
			var imageData = viewer == null ? null : viewer.getImageData();
			if (imageData == null)
				return false;
			
			var roiFiles = files.stream().filter(f -> IJTools.containsImageJRois(f)).toList();
			if (roiFiles.isEmpty())
				return false;
			
			var pathObjects = files.stream().flatMap(f -> IJTools.readImageJRois(f).stream())
					.map(r -> IJTools.convertToAnnotation(r, 1.0, null))
					.toList();
			
			imageData.getHierarchy().addObjects(pathObjects);
			imageData.getHierarchy().getSelectionModel().selectObjects(pathObjects);
			
			return true;
		}
		
		
		private boolean handleMacro(File file) {
			// TODO: Handle embedding useful running info within ImageJ macro comments
			if (file.getName().toLowerCase().endsWith(".ijm")) {
				String macro;
				try {
					macro = GeneralTools.readFileAsString(file.getAbsolutePath());
					qupath.runPlugin(new ImageJMacroRunner(qupath), macro, true);
					return true;
				} catch (Exception e) {
					Dialogs.showErrorMessage("Error opening ImageJ macro", e);
					logger.error(e.getMessage(), e);
				}
			}
			return false;
		}
		
		
	}
	

	
	/**
	 * Try to read the ImageJ icon from its jar.
	 * 
	 * @param width
	 * @param height
	 * @return
	 */
	public static Image getImageJIcon(final int width, final int height) {
		try {
			URL url = ImageJ.class.getClassLoader().getResource("microscope.gif");
			return new Image(url.toString(), width, height, true, true);
		} catch (Exception e) {
			logger.error("Unable to load ImageJ icon!", e);
		}	
		return null;
	}
	
	

	@Override
	public void installExtension(QuPathGUI qupath) {
		
		if (extensionInstalled)
			return;
		
		extensionInstalled = true;

		Prefs.setThreads(1); // We always want a single thread, due to QuPath's multithreading
		addQuPathCommands(qupath);
	}


	@Override
	public String getName() {
		return QuPathResources.getString("Extension.ImageJ");
	}


	@Override
	public String getDescription() {
		return QuPathResources.getString("Extension.ImageJ.description");
	}
	
	/**
	 * Returns the version stored within this jar, because it is matched to the QuPath version.
	 */
	@Override
	public Version getQuPathVersion() {
		return getVersion();
	}


}
