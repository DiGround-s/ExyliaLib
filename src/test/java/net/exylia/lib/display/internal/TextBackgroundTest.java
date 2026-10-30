package net.exylia.lib.display.internal;

import net.exylia.lib.display.DisplayModel;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A text display's panel is always sent explicitly, never left to the client's default grey one. */
class TextBackgroundTest {

    private static final int DEFAULT_BACKGROUND = 0x04;

    @Test
    void anUnsetPanelIsTransparentAndExplicit() {
        DisplayModel model = DisplayModel.text(Component.text("label"));
        assertEquals(0, model.background());
        assertEquals(0, DisplayPackets.textFlags(model) & DEFAULT_BACKGROUND);
    }

    @Test
    void opaqueWhiteIsAColourNotTheClientDefault() {
        DisplayModel model = DisplayModel.text(Component.text("label")).background(0xFFFFFFFF);
        assertEquals(0xFFFFFFFF, model.background());
        assertEquals(0, DisplayPackets.textFlags(model) & DEFAULT_BACKGROUND);
    }
}
