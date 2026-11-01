package net.exylia.lib.stage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A long label is broken into lines rather than shrunk to a speck; a short one stays on one line. */
class StageButtonFitTest {

    @Test
    void aLongLabelIsBrokenIntoReadableLines() {
        StageButton.Fit one = StageButton.fit("Revela una casilla primero", 1.0, 0.5, 0.75);
        assertTrue(one.breaks().size() >= 1, "broken");
        double single = 1.0 * 0.86 / (26 * 0.18);
        assertTrue(one.scale() > single * 1.8, "at least nearly twice the size of one line: " + one.scale());
        assertTrue(one.scale() * 0.25 * (one.breaks().size() + 1) <= 0.5, "fits the plate's height");
    }

    @Test
    void theBreaksLandOnTheChosenSpacesAndKeepTheColours() {
        net.kyori.adventure.text.Component label = net.kyori.adventure.text.Component.text("Revela una ")
                .color(net.kyori.adventure.text.format.NamedTextColor.GRAY)
                .append(net.kyori.adventure.text.Component.text("casilla primero"));
        net.kyori.adventure.text.Component shown = StageButton.broken(label, List.of(2, 3));
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(shown);
        assertEquals("Revela una\ncasilla\nprimero", plain);
        assertEquals(net.kyori.adventure.text.format.NamedTextColor.GRAY, shown.color());
    }

    @Test
    void aShortLabelStaysOnOneLine() {
        assertEquals(List.of(), StageButton.fit("CASH OUT", 1.0, 0.5, 0.75).breaks());
        assertEquals(List.of(), StageButton.fit("Again", 0.8, 0.24, 0.5).breaks());
    }
}
