package net.exylia.lib.item.internal;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Lore nothing coloured is body text, never the client's dark purple. */
class LoreColourTest {

    private static final TextColor LETTERS = TextColor.fromHexString("#e7cfff");

    @Test
    void aLineThatIsOnlyAValueIsDrawnInLetters() {
        List<Component> lore = ItemRenderer.lore(List.of(" %description%"), null,
                Map.of("description", "Snaps into a T-pose."), Set.of());
        assertEquals(LETTERS, lore.get(0).color());
    }

    @Test
    void aColouredLineKeepsItsColour() {
        List<Component> lore = ItemRenderer.lore(List.of("{info}{icon:click} Click to open"), null, Map.of(), Set.of());
        assertEquals(TextColor.fromHexString("#59a4ff"), firstColour(lore.get(0)));
    }

    @Test
    void aValueWithItsOwnColourKeepsIt() {
        List<Component> lore = ItemRenderer.lore(List.of("%state%"), null,
                Map.of("state", "{success}Enabled"), Set.of("state"));
        assertEquals(TextColor.fromHexString("#8fffc1"), firstColour(lore.get(0)));
    }

    private static TextColor firstColour(Component component) {
        if (component instanceof net.kyori.adventure.text.TextComponent text && !text.content().isBlank()) {
            return component.color();
        }
        for (Component child : component.children()) {
            TextColor colour = firstColour(child);
            if (colour != null) return colour;
        }
        return component.color();
    }
}
