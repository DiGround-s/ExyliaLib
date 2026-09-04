package net.exylia.lib.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers painting a component one character at a time.
 *
 * <p>What fails silently here: a hover or a bold that is dropped while the
 * colour is applied, a gradient that counts a child's characters from zero
 * again, and a loop with a seam where the last colour jumps back to the
 * first instead of blending into it.
 */
class GradientsTest {

    private static final TextColor RED = TextColor.color(0xff0000);
    private static final TextColor BLUE = TextColor.color(0x0000ff);
    private static final TextColor GREEN = TextColor.color(0x00ff00);
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @Test
    @DisplayName("blend reaches both ends and meets in the middle")
    void blendEnds() {
        assertEquals(RED, Gradients.blend(RED, BLUE, 0));
        assertEquals(BLUE, Gradients.blend(RED, BLUE, 1));
        assertEquals(TextColor.color(0x800080), Gradients.blend(RED, BLUE, 0.5));
        assertEquals(RED, Gradients.blend(RED, BLUE, -3));
        assertEquals(BLUE, Gradients.blend(RED, BLUE, 7));
    }

    @Test
    @DisplayName("stops are spaced evenly along the run")
    void atSpacesStops() {
        List<TextColor> stops = List.of(RED, GREEN, BLUE);
        assertEquals(RED, Gradients.at(stops, 0));
        assertEquals(GREEN, Gradients.at(stops, 0.5));
        assertEquals(BLUE, Gradients.at(stops, 1));
        assertEquals(TextColor.color(0x808000), Gradients.at(stops, 0.25));
        assertEquals(RED, Gradients.at(List.of(RED), 0.9));
        assertThrows(IllegalArgumentException.class, () -> Gradients.at(List.of(), 0));
    }

    @Test
    @DisplayName("wrap loops without a seam")
    void wrapLoops() {
        List<TextColor> stops = List.of(RED, BLUE);
        assertEquals(RED, Gradients.wrap(stops, 0));
        assertEquals(BLUE, Gradients.wrap(stops, 0.5));
        assertEquals(RED, Gradients.wrap(stops, 1.0));
        assertEquals(Gradients.wrap(stops, 0.25), Gradients.wrap(stops, 1.25));
        assertEquals(Gradients.wrap(stops, 0.75), Gradients.wrap(stops, -0.25));
        // Three quarters of the way round is halfway from blue back to red.
        assertEquals(TextColor.color(0x800080), Gradients.wrap(stops, 0.75));
    }

    @Test
    @DisplayName("paint gives the first character the first stop and the last the last")
    void paintEnds() {
        List<Component> chars = flatten(Gradients.paint("abc", List.of(RED, BLUE)));
        assertEquals(3, chars.size());
        assertEquals(RED, chars.get(0).color());
        assertEquals(TextColor.color(0x800080), chars.get(1).color());
        assertEquals(BLUE, chars.get(2).color());
        assertEquals("abc", PLAIN.serialize(Gradients.paint("abc", List.of(RED, BLUE))));
    }

    @Test
    @DisplayName("apply keeps decorations and events and only changes colour")
    void applyKeepsStyle() {
        Component source = Component.text("Hi", NamedTextColor.WHITE)
                .decorate(TextDecoration.BOLD)
                .hoverEvent(HoverEvent.showText(Component.text("hover")));

        Component painted = Gradients.apply(source, List.of(RED, BLUE));

        assertEquals(TextDecoration.State.TRUE, painted.decoration(TextDecoration.BOLD));
        assertNotNull(painted.hoverEvent());
        assertEquals(null, painted.color());
        List<Component> chars = flatten(painted);
        assertEquals(RED, chars.get(0).color());
        assertEquals(BLUE, chars.get(1).color());
    }

    @Test
    @DisplayName("children continue the count instead of starting over")
    void childrenContinue() {
        Component source = Component.text("ab").append(Component.text("cd"));
        int[] seen = new int[1];
        List<Integer> indexes = new ArrayList<>();
        Gradients.apply(source, index -> {
            indexes.add(index);
            seen[0]++;
            return RED;
        });
        assertEquals(List.of(0, 1, 2, 3), indexes);
        assertEquals(4, Gradients.length(source));
    }

    @Test
    @DisplayName("a character outside the basic plane is one character")
    void supplementaryIsOne() {
        Component source = Component.text("a𝔞b"); // a 𝔞 b
        assertEquals(3, Gradients.length(source));
        assertEquals("a𝔞b", PLAIN.serialize(Gradients.apply(source, List.of(RED, BLUE))));
    }

    @Test
    @DisplayName("an empty component is returned unpainted")
    void emptyUnchanged() {
        Component empty = Component.empty();
        assertSame(empty.children(), Gradients.apply(empty, List.of(RED)).children());
        assertTrue(PLAIN.serialize(Gradients.apply(empty, List.of(RED))).isEmpty());
    }

    private static List<Component> flatten(Component component) {
        List<Component> out = new ArrayList<>();
        collect(component, out);
        return out;
    }

    private static void collect(Component component, List<Component> out) {
        if (component instanceof TextComponent text && !text.content().isEmpty()) {
            out.add(component);
        }
        for (Component child : component.children()) {
            collect(child, out);
        }
    }
}
