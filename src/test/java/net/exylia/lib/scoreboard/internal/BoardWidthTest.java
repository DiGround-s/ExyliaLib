package net.exylia.lib.scoreboard.internal;

import net.exylia.lib.text.Centering;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoardWidthTest {

    private static final List<String> LINES = List.of("<center>TODAY", "", "STATISTICS HEADER", "Ping: %ping%");
    private static final int[] SOURCES = {0, 1, 2, 3};

    private static Component[] lines(String ping) {
        return new Component[]{Component.text("TODAY"), Component.empty(),
                Component.text("STATISTICS HEADER"), Component.text("Ping: " + ping)};
    }

    private static int widest(Component[] shown) {
        int widest = 0;
        for (Component line : shown) {
            widest = Math.max(widest, Centering.pixelWidth(line));
        }
        return widest;
    }

    @Test
    @DisplayName("the board is as wide as its widest fixed text, and a centred line sits in the middle")
    void centresWithinTheFixedWidth() {
        BoardWidth width = new BoardWidth(LINES);
        assertEquals(Centering.pixelWidth(Component.text("STATISTICS HEADER")), width.base());
        Component[] shown = width.layout(lines("9"), SOURCES, 0);
        String today = PlainTextComponentSerializer.plainText().serialize(shown[0]);
        assertTrue(today.startsWith("    ") && today.endsWith("TODAY"), today);
        assertEquals(width.base(), widest(shown), 4);
    }

    @Test
    @DisplayName("a value that widens the board keeps that width until it has been shorter for a while")
    void holdsItsWidth() {
        BoardWidth width = new BoardWidth(LINES);
        String wide = "1234567890123456789";
        int grown = widest(width.layout(lines(wide), SOURCES, 0));
        assertTrue(grown > width.base());
        // Back to a short value: the blank line keeps the board as wide as it was.
        assertEquals(grown, widest(width.layout(lines("9"), SOURCES, 1_000)), 4);
        // Long enough after: it shrinks back to its fixed width.
        assertEquals(width.base(), widest(width.layout(lines("9"), SOURCES, 1_000 + BoardWidth.SHRINK_AFTER_MS)), 4);
    }
}
