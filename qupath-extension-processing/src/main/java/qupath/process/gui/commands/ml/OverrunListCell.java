package qupath.process.gui.commands.ml;

import javafx.scene.control.ListCell;
import javafx.scene.control.OverrunStyle;

/**
 * A simple {@link ListCell} that can use a specified {@link OverrunStyle}.
 * This can be useful if the string representation of a cell is very long.
 * @param <T>
 */
class  OverrunListCell<T> extends ListCell<T> {

    public OverrunListCell() {
        this(OverrunStyle.ELLIPSIS);
    }

    public OverrunListCell(OverrunStyle style) {
        super();
        setTextOverrun(style);
    }

}
