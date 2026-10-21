package net.exylia.lib.text.internal;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A line with a colour per word must not reach the client hundreds of levels deep.
 */
class ShallowTest {

    @Test
    @DisplayName("a long line of unclosed colours is flattened and keeps its text and colours")
    void flattensDeepLines() {
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            raw.append(i % 2 == 0 ? "<#8a51c4>" : "<bold><#59a4ff>").append("w").append(i).append(' ');
        }
        Component deep = MiniMessage.miniMessage().deserialize(raw.toString());
        Component flat = TextEngine.shallow(deep);

        assertTrue(depth(flat) <= TextEngine.MAX_DEPTH, "depth " + depth(flat));
        PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();
        assertEquals(plain.serialize(deep), plain.serialize(flat));
        Component last = flat.children().get(flat.children().size() - 1);
        assertEquals("#59a4ff", last.color().asHexString());
        assertTrue(last.hasDecoration(net.kyori.adventure.text.format.TextDecoration.BOLD),
                "inherited bold must survive");
    }

    private static int depth(Component component) {
        int deepest = 0;
        for (Component child : component.children()) {
            deepest = Math.max(deepest, depth(child));
        }
        return deepest + 1;
    }
}
