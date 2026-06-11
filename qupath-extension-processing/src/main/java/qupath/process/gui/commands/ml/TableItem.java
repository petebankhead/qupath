package qupath.process.gui.commands.ml;

import java.util.Objects;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.value.ObservableValue;

/**
 * An item to display in a 2-column table.
 * This stores a key value pair, exposing them as observable values.
 * <p>
 * The key is always a string, while the value can be any object.
 * @param <T> the generic parameter of the value
 */
class TableItem<T> {

    private final ObservableValue<String> name;
    private final ObservableValue<T> value;

    public static <T> TableItem<T> create(String name, T value) {
        return new TableItem<>(
                new SimpleStringProperty(name),
                new SimpleObjectProperty<>(value)
        );
    }

    TableItem(String name) {
        this(name, null);
    }

    TableItem(String name, T value) {
        this(
                new SimpleStringProperty(name),
                new SimpleObjectProperty<>(value)
        );
    }

    TableItem(ObservableValue<String> name, ObservableValue<T> value) {
        Objects.requireNonNull(name, "Name must not be null");
        Objects.requireNonNull(value, "Value must not be null");
        this.name = name;
        this.value = value;
    }

    ObservableValue<String> nameProperty() {
        return name;
    }

    ObservableValue<T> valueProperty() {
        return value;
    }

    public static class StringItem extends TableItem<String> {

        /**
         * Create an item that has a null string value.
         * @param name item name
         * @return
         */
        public static StringItem create(String name) {
            return create(name, null);
        }

        public static StringItem create(String name, String value) {
            return new StringItem(
                    new SimpleStringProperty(name),
                    new SimpleStringProperty(value)
            );
        }

        private StringItem(ObservableValue<String> name, ObservableValue<String> value) {
            super(name, value);
        }

    }

    public static class NumberItem extends TableItem<Number> {

        private NumberItem(ObservableValue<String> name, ObservableValue<Number> value) {
            super(name, value);
        }

        public static NumberItem createEmpty(String name) {
            return new NumberItem(
                    new SimpleStringProperty(name),
                    new SimpleObjectProperty<>()
            );
        }

        public static NumberItem create(String name, double value) {
            return new NumberItem(
                    new SimpleStringProperty(name),
                    new SimpleDoubleProperty(value)
            );
        }

        public static NumberItem create(String name, long value) {
            return new NumberItem(
                    new SimpleStringProperty(name),
                    new SimpleLongProperty(value)
            );
        }

    }

    }
