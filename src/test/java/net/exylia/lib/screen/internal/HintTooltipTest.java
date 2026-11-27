package net.exylia.lib.screen.internal;

import net.exylia.lib.screen.Node;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A hint tooltip of tooltip-item is the item's name alone (1.678.0). */
class HintTooltipTest {

    @Test
    void aHintTooltipOfAnItemIsItsNameAlone() {
        Component name = Component.text("Sword");
        Node hint = LiveScreen.hintPanel(List.of(name, Component.text("lore line"), Component.text("more lore")));
        assertEquals(1, hint.children().size());
        assertEquals(name, hint.children().get(0).text());
    }
}
