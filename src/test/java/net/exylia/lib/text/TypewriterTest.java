package net.exylia.lib.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypewriterTest {

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void writesLettersInOrderKeepingRowsAndStyle() {
        Typewriter reveal = Typewriter.of(Component.text("Hello ").color(NamedTextColor.RED).append(Component.text("world there")), 60);
        assertTrue(plain(reveal.whole()).contains("\n"), "wrapped once, up front");
        String first = plain(reveal.frame(3));
        assertTrue(first.startsWith("Hel "), first);
        assertEquals(plain(reveal.whole()).split("\n").length, first.split("\n", -1).length, "rows from the first letter");
        assertEquals(reveal.whole(), reveal.frame(reveal.length()));
    }

    @Test
    void anEmptyLineIsDoneAtOnce() {
        assertEquals(0, Typewriter.of(Component.empty(), 200).length());
    }
}
