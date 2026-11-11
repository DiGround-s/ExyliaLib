package net.exylia.lib.text.internal;

import net.exylia.lib.text.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TextEffectsTest {

    private final AtomicLong clock = new AtomicLong(0);

    @AfterEach
    void tearDown() {
        TextEffects.clock(null);
    }

    @Test
    @DisplayName("a leading flow moves the text's colours with the clock, its words untouched")
    void flowAnimatesThroughTheEngine() {
        TextEffects.clock(clock::get);
        String raw = "<flow><gradient:#000000:#ffffff>EXYLIA</gradient>";
        Component first = Text.component(raw);
        clock.set(250);
        Component later = Text.component(raw);

        assertEquals("EXYLIA", PlainTextComponentSerializer.plainText().serialize(first));
        assertNotEquals(colours(first), colours(later));
        clock.set(0);
        assertEquals(colours(first), colours(Text.of(raw).build()));
    }

    @Test
    @DisplayName("text without a leading effect, and MiniMessage's own scoped rainbow, are left alone")
    void othersAreLeftAlone() {
        assertNull(TextEffects.split("{primary}<flow>not leading"));
        assertNull(TextEffects.split("<rainbow>PINATA</rainbow> [5/10]"));
        assertNull(TextEffects.split("<flow:abc>typo stays"));
        assertEquals("Hi", TextEffects.split("<marquee:8,speed=3><shine>Hi").body());
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
