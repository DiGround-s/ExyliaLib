package net.exylia.lib.screen.internal;

import net.exylia.lib.screen.Node;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Text in a grid cell (zero width, growing) wraps to the cell's share of the row, not a word a line. */
class GridWrapTest {

    @Test
    void cellTextWrapsToItsShare() {
        String words = "Jump as far as you can. Your score is the jumps you make.";
        Node row = Node.row().width(360);
        for (int i = 0; i < 3; i++) row.add(Node.column(Node.text(words).wrap(Double.MAX_VALUE)).width(0).grow(1));
        Layout.Wrapped wrapped = Layout.lay(Node.column(row), 400, 225).stream()
                .filter(placed -> placed.wrapped() != null).findFirst().orElseThrow().wrapped();
        // 120 px a cell holds several words a line: 2-4 lines, never one per word (12)
        assertTrue(wrapped.lines() <= 4, "lines " + wrapped.lines());
        assertTrue(wrapped.width() > 60, "width " + wrapped.width());
    }
}
