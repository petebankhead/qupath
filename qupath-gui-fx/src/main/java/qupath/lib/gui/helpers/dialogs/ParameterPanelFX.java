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

package qupath.lib.gui.helpers.dialogs;

import java.awt.GridBagConstraints;
import java.text.NumberFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Locale.Category;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Vector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import qupath.lib.common.GeneralTools;
import qupath.lib.plugins.parameters.BooleanParameter;
import qupath.lib.plugins.parameters.ChoiceParameter;
import qupath.lib.plugins.parameters.DoubleParameter;
import qupath.lib.plugins.parameters.EmptyParameter;
import qupath.lib.plugins.parameters.IntParameter;
import qupath.lib.plugins.parameters.NumericParameter;
import qupath.lib.plugins.parameters.Parameter;
import qupath.lib.plugins.parameters.ParameterChangeListener;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.plugins.parameters.StringParameter;

/**
 * A panel for displaying a list of parameters suitably to aid with creating JavaFX GUIs.
 * 
 * @author Pete Bankhead
 *
 */
public class ParameterPanelFX {

	private Vector<ParameterChangeListener> listeners = new Vector<>();

	private final static Logger logger = LoggerFactory.getLogger(ParameterPanelFX.class);
	
	private static int DEFAULT_NUMERIC_TEXT_COLS = 6;
	
	private GridPane pane;
	private ParameterList params;
	private Map<Parameter<?>, Node> map = new HashMap<>();
	
	// TODO: Check if font needs to be set everywhere?  For now it's just for titles...
	private static Font font = Font.font("Arial");
	
	/**
	 * Create a ParameterPanelFX.
	 * 
	 * @param params
	 */
	public ParameterPanelFX(final ParameterList params) {
		this(params, new GridPane());
	}
	
	/**
	 * Create a ParameterPanelFX using a specified GridPane.
	 * 
	 * @param params
	 * @param gridPane
	 */
	private ParameterPanelFX(final ParameterList params, final GridPane gridPane) {
		super();
		this.params = params;
		this.pane = gridPane == null ? new GridPane() : gridPane;
		initialize();
//		pane.setMinSize(pane.getPrefWidth(), pane.getPrefHeight());
		pane.setVgap(4);
		pane.setHgap(4);
//		pane.setMaxSize(pane.getPrefWidth(), pane.getPrefHeight());
//		System.err.println("Resizable: " + pane.isResizable());
	}
	
	public ParameterList getParameters() {
		return params;
	}
	
	public Pane getPane() {
		return pane;
	}
	
	private void initialize() {
//		for (Parameter<?> p : params.getParameterList()) {
		for (Entry<String, Parameter<?>> entry : params.getParameters().entrySet()) {
			Parameter<?> p = entry.getValue();
			// Don't show hidden parameters
			if (p.isHidden())
				continue;
			if (p instanceof DoubleParameter)
				addDoubleParameter((DoubleParameter)p);
			else if (p instanceof IntParameter)
				addIntParameter((IntParameter)p);
			else if (p instanceof StringParameter)
				addStringParameter((StringParameter)p);
			else if (p instanceof EmptyParameter)
				addEmptyParameter((EmptyParameter)p);
			else if (p instanceof ChoiceParameter)
				addChoiceParameter((ChoiceParameter)p);
			else if (p instanceof BooleanParameter) {
				addBooleanParameter((BooleanParameter)p);
			}
		}
	}
	
	
	public void addParameterChangeListener(ParameterChangeListener listener) {
		listeners.add(listener);
	}

	public void removeParameterChangeListener(ParameterChangeListener listener) {
		listeners.remove(listener);
	}
	
	
	private String getKey(Parameter<?> param) {
		// This is a rather clumsy workaround as the keys are not currently stored here...
		for (Entry<String, Parameter<?>> entry : params.getParameters().entrySet()) {
			if (entry.getValue().equals(param))
				return entry.getKey();
		}
		return null;
	}
	
	
	private void fireParameterChangedEvent(Parameter<?> param, boolean isAdjusting) {
		String key = getKey(param);
		if (key != null) {
			for (ParameterChangeListener listener: listeners) {
				listener.parameterChanged(params, key, isAdjusting);
			}
		}
	}

	private void addBooleanParameter(BooleanParameter param) {
		addCheckBoxParameter(param);
	}
	
	private void addDoubleParameter(DoubleParameter param) {
		if (param.hasLowerAndUpperBounds())
			addSliderParameter(param);
		else
			addNumericTextField(param);
	}

	private void addIntParameter(IntParameter param) {
		if (param.hasLowerAndUpperBounds())
			addSliderParameter(param);
		else
			addNumericTextField(param);
	}
	
	private void addNumericTextField(NumericParameter<? extends Number> param) {
		TextField tf = getTextField(param, DEFAULT_NUMERIC_TEXT_COLS);
		if (param.getUnit() != null) {
			Pane panel = new FlowPane();
			panel.getChildren().add(tf);
			Label label = new Label(param.getUnit());
			label.setPadding(new Insets(0, 0, 0, 4));
			panel.getChildren().add(label);
			addParamComponent(param, param.getPrompt(), panel);
		} else {
			Pane panel = new Pane();
			panel.getChildren().add(tf);
			addParamComponent(param, param.getPrompt(), panel);
		}
	}

	private void addStringParameter(StringParameter param) {
		addParamComponent(param, param.getPrompt(), getTextField(param, 25));
	}

	private void addEmptyParameter(EmptyParameter param) {
		Label label = new Label(param.getPrompt());
//		Text label = new Text(param.getPrompt());
		if (param.isTitle()) {
			// Cannot change font weight for default font (at least on macOS...) - need to change the font that's used
			label.setFont(Font.font(font.getFamily(), FontWeight.BOLD, font.getSize()));
//			label.setStyle("-fx-font-weight: bold;");
//			label.setStyle("-fx-font-size: 100%");
//			label.setEffect(new DropShadow());
			if (!map.isEmpty())
				label.setPadding(new Insets(10, 0, 0, 0));
		}
		addParamComponent(param, null, label);
	}

	private void addChoiceParameter(ChoiceParameter<Object> param) {
		ComboBox<Object> combo = new ComboBox<>();
		combo.getItems().setAll(param.getChoices());
		combo.getSelectionModel().select(param.getValueOrDefault());
		combo.setOnAction(e -> {
			if (param.setValue(combo.getSelectionModel().getSelectedItem()))
				fireParameterChangedEvent(param, false);
		});
		combo.setMaxWidth(Double.MAX_VALUE);
		addParamComponent(param, param.getPrompt(), combo);
	}
	
	private void addCheckBoxParameter(BooleanParameter param) {
		CheckBox cb = new CheckBox(param.getPrompt());
		cb.setSelected(param.getValueOrDefault());
		cb.selectedProperty().addListener((v, o, n) -> {
			if (param.setValue(cb.isSelected()))
				fireParameterChangedEvent(param, false);
		});
		addParamComponent(param, null, cb);
	}
	
	private void addSliderParameter(IntParameter param) {
		int min = (int)param.getLowerBound();
		int max = (int)(param.getUpperBound() + .5);
		Slider slider = new Slider(min, max, param.getValueOrDefault());
		TextField tf = new TextField();
		tf.setEditable(false);
		tf.setText(""+slider.getValue());
		tf.setPrefColumnCount(DEFAULT_NUMERIC_TEXT_COLS);
		ParameterSliderChangeListener listener = new ParameterSliderChangeListener(slider, param, tf);
		slider.valueProperty().addListener((v, o, n) -> listener.handleSliderUpdate());
//		slider.setMinimumSize(new Dimension(slider.getPreferredSize().width, slider.getMinimumSize().height));
		BorderPane panel = new BorderPane();
		panel.setCenter(slider);
		panel.setRight(tf);
		addParamComponent(param, param.getPrompt(), panel);
	}
	
	
	private void addSliderParameter(DoubleParameter param) {
//		double lower = param.getLowerBound();
//		double higher = param.getUpperBound();
//		int sliderMax;
//		// Choose a sensible increment
//		if (higher - lower > 1000)
//			sliderMax = (int)Math.round((higher - lower));
//		else if (higher - lower > 100)
//			sliderMax = (int)Math.round((higher - lower) * 10);
//		else
//			sliderMax = (int)Math.round((higher - lower) * 100);
		final Slider slider = new Slider(param.getLowerBound(), param.getUpperBound(),  param.getValueOrDefault());
		TextField tf = new TextField();
		tf.setPrefColumnCount(DEFAULT_NUMERIC_TEXT_COLS);
		setTextFieldFromNumber(tf, param.getValueOrDefault(), param.getUnit());
		tf.setEditable(false);
		ParameterSliderChangeListener listener = new ParameterSliderChangeListener(slider, param, tf);
		slider.valueProperty().addListener((v, o, n) -> listener.handleSliderUpdate());
//		slider.setMinimumSize(new Dimension(slider.getPreferredSize().width, slider.getMinimumSize().height));
		BorderPane panel = new BorderPane();
		panel.setCenter(slider);
		panel.setRight(tf);
		addParamComponent(param, param.getPrompt(), panel);
	}
	
	
	protected static void setTextFieldFromNumber(TextField text, Number value, String unit) {
		String s;
		if (value == null)
			s = "";
		else  {
			if ((value instanceof Double) || (value instanceof Float)) {
				double v = value.doubleValue();
				if (v == Math.round(v))
					s = String.format("%.0f", v);
				else
					s = GeneralTools.formatNumber(v, 4);
//					s = String.format("%.4f", v);
			} else
				s = String.format("%d", value.longValue());
			if (unit != null)
				s += (" " + unit);
		}
		// Only set the text if it's different - avoids some exceptions due to the complex interplay between listeners...
		if (!text.getText().equals(s))
			text.setText(s);
	}
	
	
	protected TextField getTextField(Parameter<?> param, int cols) {
		TextField tf = new TextField();
		Object defaultVal = param.getValueOrDefault();
		if (defaultVal instanceof Number)
			tf.setText(NumberFormat.getInstance().format(defaultVal));
		else if (defaultVal != null)
			tf.setText(defaultVal.toString());
		
		if (cols > 0)
			tf.setPrefColumnCount(cols);
//		tf.addActionListener(new ParameterActionListener(tf, param));
		
		tf.textProperty().addListener((v, o, n) -> {
			if (n != null && param.setStringLastValue(Locale.getDefault(Category.FORMAT), n)) {
				fireParameterChangedEvent(param, false);
			}
		});
		// onKeyTyped wasn't causing property synchronisation of parameter values
//		tf.setOnKeyTyped(e -> {
//			if (param.setStringLastValue(tf.getText()))
//				fireParameterChangedEvent(param, false);
//		});
		return tf;
	}
	
	GridBagConstraints constraints = new GridBagConstraints();
	
	// GridBagLayout version... TODO: Update for JavaFX
	private void addParamComponent(Parameter<?> parameter, String text, Node component) {
		
		map.put(parameter, component);
		String help = parameter.getHelpText();
		Tooltip tooltip = help == null ? null : new Tooltip(help);
		if (tooltip != null) {
			Tooltip.install(component, tooltip);
			// May not be necessary...?
			if (component instanceof Parent) {
				for (Node child : ((Parent)component).getChildrenUnmodifiable()) {
					if (child instanceof Control) {
						((Control)child).setTooltip(tooltip);
					} 
				}
			}
		}
		if (constraints == null) {
			constraints = new GridBagConstraints();
			constraints.gridwidth = 1;
			constraints.gridheight = 1;
			constraints.gridy = 0;
		}
		constraints.gridy++;
		constraints.gridx = 0;
		if (text == null) {
			constraints.gridwidth = 2;
			constraints.weightx = 1.0;
			constraints.anchor = GridBagConstraints.CENTER;
			add(component, constraints);
		} else {
			constraints.weightx = 0;
			constraints.gridwidth = 1;
			constraints.anchor = GridBagConstraints.EAST;
			Label label = new Label(text);
			if (tooltip != null)
				label.setTooltip(tooltip);
//			label.setMinWidth(label.getPrefWidth());
//			FlowPane pane = new FlowPane();
//			pane.getChildren().add(label)
			add(label, constraints);
			label.setLabelFor(component);
			constraints.gridx++;
			constraints.weightx = 1.0;
			constraints.anchor = GridBagConstraints.WEST;
//			component.setBorder(BorderFactory.createLineBorder(Color.RED, 2));
			add(component, constraints);			
		}
	}
	
	
	
	private void add(Node component, GridBagConstraints constraints) {
//		if (component instanceof Pane)
//			((Pane)component).setPadding(new Insets(2, 2, 2, 2));
//		else if (component instanceof Control)
//			((Control)component).setPadding(new Insets(2, 2, 2, 2));
		
//		logger.trace("Adding {} at {}, {}, {}, {}", component, constraints.gridx, constraints.gridy, constraints.gridwidth, constraints.gridheight);
		
		// Optional code to expand width...
//		System.err.println((constraints.gridx + constraints.gridwidth) + ".... " + constraints.gridx + ", " + constraints.gridwidth + ":  " + component);
//		if (component instanceof Pane) {
//			((Pane)component).setMaxWidth(Double.MAX_VALUE);
//			GridPane.setHgrow(component, Priority.ALWAYS);
//		}
		pane.add(component, constraints.gridx, constraints.gridy, constraints.gridwidth, constraints.gridheight);
	}
	
	
	
	public boolean getParameterEnabled(String key) {
		return getParameterEnabled(params.getParameters().get(key));
	}
	
	public boolean getParameterEnabled(Parameter<?> param) {
		Node comp = map.get(param);
		return comp != null && !comp.isDisabled();
	}
	
	public void setParameterEnabled(String key, boolean enabled) {
		setParameterEnabled(params.getParameters().get(key), enabled);
	}
	
	
	public void setParameterEnabled(Parameter<?> param, boolean enabled) {
		Node comp = map.get(param);
		if (comp != null)
			setEnabledRecursively(comp, enabled);
	}
	
	private void setEnabledRecursively(Node comp, boolean enabled) {
		comp.setDisable(!enabled);
	}
	
	
	
	
	class ParameterSliderChangeListener {
		
		private Slider slider;
		private NumericParameter<?> param;
		private TextField text;
		
		private boolean sliderChanging = false;
		private boolean textChanging = false;
		
		public ParameterSliderChangeListener(Slider slider, NumericParameter<?> param, TextField text) {
			this.slider = slider;
			this.param = param;
			this.text = text;
			
			this.text.setEditable(true);
			this.text.textProperty().addListener((v, o, n) -> {
				handleTextUpdate();
			});
		}

		public void handleSliderUpdate() {
			if (textChanging)
				return;
			sliderChanging = true;
			double val = slider.getValue();
			if (param.setDoubleLastValue(val)) {
				if (text != null) {
					setTextFieldFromNumber(text, param.getValueOrDefault(), param.getUnit());
				}
				fireParameterChangedEvent(param, slider.isValueChanging());
			}
			sliderChanging = false;
		}
		
		void handleTextUpdate() {
			if (sliderChanging)
				return;
			String s = text.getText();
			if (s == null || s.trim().length() == 0)
				return;
//			System.out.println("Text: " + s);
			try {
				String unit = param.getUnit();
				if (unit != null)
					s = s.toLowerCase().replace(unit.toLowerCase(), "").trim();
				if (s.length() == 0)
					return;
				double val = NumberFormat.getInstance().parse(s).doubleValue();
//				double val = Double.parseDouble(s);
				double previousValue = param.getValueOrDefault().doubleValue();
				if (Double.isNaN(val) || val == previousValue)
					return;
				
//				double index = (val - param.getLowerBound()) / (param.getUpperBound() - param.getLowerBound()) * slider.getMax();
//				if (index < 0)
//					index = 0;
//				if (index > slider.getMax())
//					index = slider.getMax();
				
				textChanging = true;
				param.setDoubleLastValue(val);
				slider.setValue(val);
				fireParameterChangedEvent(param, slider.isValueChanging());
				textChanging = false;
			} catch (Exception e) {
				logger.debug("Cannot parse number from {} - will keep default of {}", s, param.getValueOrDefault());
//				e.printStackTrace();
			} finally {
				textChanging = false;
			};
		}
		
	}
	
	
	
	/**
	 * Set a numeric parameter value (either int or double).
	 * 
	 * The reason for using this method rather than setting the parameter value directly is that it ensures that
	 * any displayed components (text fields, sliders...) are updated accordingly.
	 * 
	 * @param key
	 * @param value
	 * @return
	 */
	public boolean setNumericParameterValue(final String key, Number value) {
		// Try to get a component to set
		Parameter<?> parameterOrig = params.getParameters().get(key);
		if (parameterOrig == null || !(parameterOrig instanceof NumericParameter)) {
			logger.warn("Unable to set parameter {} with value {} - no numeric parameter found with that key", key, value);
			return false;			
		}
		NumericParameter<?> parameter = (NumericParameter<?>)parameterOrig;
		Node component = map.get(parameter);
		// Occurs with hidden parameters
		if (component == null) {
			parameter.setDoubleLastValue(value.doubleValue());
			return true;
		}
		if (component instanceof Parent) {
			for (Node comp : ((Parent)component).getChildrenUnmodifiable()) {
				if (comp instanceof TextField) {
					// Only change the text if necessary
					TextField textField = (TextField)comp;
					setTextFieldFromNumber(textField, value, parameter.getUnit());
					return true;
				}
			}
		}
		logger.warn("Unable to set parameter {} with value {} - no component found", key, value);		
		return false;
	}
	
	
	
	/**
	 * Set the minimum and maximum value for a numeric parameter.
	 * 
	 * If the parameter is being displayed with a slider, the slider range will also be updated accordingly.
	 * 
	 * @param key
	 * @param minValue
	 * @param maxValue
	 * @return
	 */
	public boolean setNumericParameterValueRange(final String key, double minValue, double maxValue) {
		// Try to get a component to set
		Parameter<?> parameterOrig = params.getParameters().get(key);
		if (parameterOrig == null || !(parameterOrig instanceof NumericParameter)) {
			logger.warn("Unable to set range for {} - no numeric parameter found with that key", key);
			return false;			
		}
		NumericParameter<?> parameter = (NumericParameter<?>)parameterOrig;
		// Occurs with hidden parameters
		try {
			parameter.setRange(minValue, maxValue);
			Node component = map.get(parameter);
			if (component instanceof Parent) {
				for (Node comp : ((Parent)component).getChildrenUnmodifiable()) {
					if (comp instanceof Slider) {
						// Only change the text if necessary
						Slider slider = (Slider)comp;
							slider.setMin(minValue);
							slider.setMax(maxValue);
							return true;
					}
				}
			}
		} catch (Exception e) {
			logger.warn("Unable to set range for {}: {}", parameter, e.getLocalizedMessage());							
		}
		return false;
	}
	
	
	
	public static void main(String[] args) {
		
		new JFXPanel();
		if (!Platform.isFxApplicationThread()) {
			Platform.runLater(() -> main(args));
			return;
		}
		
		Stage frame = new Stage();
		frame.setTitle("Testing parameter panel");
		int k = 0;
		final ParameterList params = new ParameterList().
				addEmptyParameter(Integer.toString(k++), "Parameter list", true).
				addEmptyParameter(Integer.toString(k++), "Here is a list of parameters that I am testing out", false).
				addIntParameter(Integer.toString(k++), "Enter an int", 5, "px").
				addDoubleParameter(Integer.toString(k++), "Enter a double", 5.2, "microns").
				addDoubleParameter(Integer.toString(k++), "Enter a double in range", 5.2, null, 1, 10).
				addIntParameter(Integer.toString(k++), "Enter an int in range", 5, null, 1, 10).
				addStringParameter(Integer.toString(k++), "Enter a string", "Default here").
				addChoiceParameter(Integer.toString(k++), "Choose a choice", "Two", new String[]{"One", "Two", "Three"}).
				addChoiceParameter(Integer.toString(k++), "Choose a number choice", Integer.valueOf(2), new Integer[]{1, 2, 3}).
				addBooleanParameter(Integer.toString(k++), "Check me out", true);
		
		
		
		BorderPane borderPane = new BorderPane();
		ParameterPanelFX panel = new ParameterPanelFX(params);
		
		final TextArea textArea = new TextArea();
		for (Parameter<?> p : params.getParameters().values()) {
			textArea.setText(textArea.getText() + (p + "\n"));
		}
		panel.addParameterChangeListener(new ParameterChangeListener() {
			@Override
			public void parameterChanged(ParameterList params, String key, boolean isAdjusting) {
				textArea.setText("");
				for (Parameter<?> p : params.getParameters().values())
					textArea.setText(textArea.getText() + (p + "\n"));
			}
		});
		
		borderPane.setCenter(panel.getPane());
		borderPane.setBottom(textArea);
		
		frame.setScene(new Scene(borderPane));
		frame.show();
	}
	
	
}
