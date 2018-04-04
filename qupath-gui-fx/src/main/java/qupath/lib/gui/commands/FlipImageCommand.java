/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package qupath.lib.gui.commands;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.text.TextAlignment;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.commands.interfaces.PathCommand;
import qupath.lib.gui.helpers.PanelToolsFX;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.objects.TMACoreObject;
import qupath.lib.objects.hierarchy.TMAGrid;
import qupath.lib.roi.interfaces.ROI;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Very basic rotation slider.
 * 
 * Doesn't permit image to rotate beyond 90 degrees.
 * 
 * @author Pete Bankhead
 *
 */
public class FlipImageCommand implements PathCommand {

	final private static Logger logger = LoggerFactory.getLogger(FlipImageCommand.class);

	final private QuPathGUI qupath;

	private Stage dialog;
	private CheckBox flipHorizontal;
	private CheckBox flipVertical;

	public FlipImageCommand(QuPathGUI qupath) {
		this.qupath = qupath;
	}

	private void makeDialog() {
		dialog = new Stage();
		dialog.initOwner(qupath.getStage());
		dialog.setTitle("Flip view");

		BorderPane pane = new BorderPane();

		final Label label = new Label("Choose Flip Direction");
		label.setTextAlignment(TextAlignment.CENTER);
		QuPathViewer viewerTemp = qupath.getViewer();
		flipHorizontal = new CheckBox("Flip Horizontally");
		flipVertical = new CheckBox("Flip Vertically");
		QuPathViewer viewer = qupath.getViewer();
		// Set defaults
		flipHorizontal.setSelected(viewer.isFlipHorizontal());
		flipVertical.setSelected(viewer.isFlipVertical());

		flipHorizontal.selectedProperty().addListener((v, o, n) -> {
			if (viewer == null)
				return;
			boolean isFlipHorizontal = flipHorizontal.selectedProperty().getValue();
			boolean isFlipVertical = flipVertical.selectedProperty().getValue();
			viewer.setFlipped(isFlipHorizontal, isFlipVertical);
			viewer.getImageData().setProperty("flipHorizontal",isFlipHorizontal );
			viewer.getImageData().setProperty("flipVertical",isFlipVertical );
		});

		flipVertical.selectedProperty().addListener((v, o, n) -> {
			if (viewer == null)
				return;
			boolean isFlipHorizontal = flipHorizontal.selectedProperty().getValue();
			boolean isFlipVertical = flipVertical.selectedProperty().getValue();
			viewer.setFlipped(isFlipHorizontal, isFlipVertical);
			viewer.getImageData().setProperty("flipHorizontal",isFlipHorizontal );
			viewer.getImageData().setProperty("flipVertical",isFlipVertical );
		});

		GridPane panelButtons = PanelToolsFX.createColumnGridControls(
				flipHorizontal,
				flipVertical
				);
		panelButtons.setPrefWidth(300);
		

		pane.setTop(label);
		pane.setBottom(panelButtons);
		pane.setPadding(new Insets(10, 10, 10, 10));

		Scene scene = new Scene(pane);
		dialog.setScene(scene);
//		dialog.sizeToScene();
		dialog.setResizable(false);
//		dialog.pack();
//		dialog.setLocationRelativeTo(qupath.getViewer());

//		dialog.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
	}

	@Override
	public void run() {
		if (dialog != null && dialog.isShowing())
			return;
//		if (dialog == null)
			makeDialog();
		dialog.show();
	}
}