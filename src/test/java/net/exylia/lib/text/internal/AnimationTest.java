package net.exylia.lib.text.internal;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AnimationTest {

    private static final String RAW = "<animation:scroll:#000000:#ffffff:1s>ABCD</animation>";

    @Test
    @DisplayName("the frame comes from the clock and loops over the duration")
    void framesFollowTheClock() {
        assertEquals("<animation:scroll:#000000:#ffffff:1s:@0>ABCD</animation>", Animation.frame(RAW, 0));
        assertEquals("<animation:scroll:#000000:#ffffff:1s:@5>ABCD</animation>", Animation.frame(RAW, 250));
        assertEquals(Animation.frame(RAW, 0), Animation.frame(RAW, 1000));
        assertEquals("plain", Animation.frame("plain", 0));
    }

    @Test
    @DisplayName("a frame paints every letter, moves with time and leaves no label")
    void paintsAndMoves() {
        Component first = TextEngine.parseUncached(Animation.frame(RAW, 0));
        Component later = TextEngine.parseUncached(Animation.frame(RAW, 250));
        assertEquals("ABCD", PlainTextComponentSerializer.plainText().serialize(first));
        List<TextColor> a = colours(first);
        assertEquals(4, a.size());
        assertNotEquals(a, colours(later));
        assertNull(first.style().font());
    }

    @Test
    @DisplayName("pulse paints the whole text one colour")
    void pulseIsOneColour() {
        Component pulse = TextEngine.parseUncached("<animation:pulse:#000000:#ffffff:1s:@5>ABC</animation>");
        assertEquals(1, colours(pulse).stream().distinct().count());
    }

    private static List<TextColor> colours(Component component) {
        List<TextColor> out = new ArrayList<>();
        collect(component, null, out);
        return out;
    }

    private static void collect(Component component, TextColor inherited, List<TextColor> out) {
        TextColor colour = component.color() != null ? component.color() : inherited;
        if (component instanceof TextComponent text) {
            text.content().codePoints().forEach(c -> out.add(colour));
        }
        for (Component child : component.children()) {
            collect(child, colour, out);
        }
    }
}
