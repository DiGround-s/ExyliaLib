package net.exylia.lib.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class HoverPaletteTest {

    @BeforeEach
    void setUp() {
        Colors.apply(new Palette());
    }

    private static Component hoverOf(Component c) {
        if (c.hoverEvent() != null) return (Component) c.hoverEvent().value();
        for (Component child : c.children()) {
            Component found = hoverOf(child);
            if (found != null) return found;
        }
        return null;
    }

    @Test
    void paletteTokenInHoverIsAColour() {
        for (String raw : new String[]{
                "<hover:show_text:'{letters}Opens the editor'>x</hover>",
                "<hover:show_text:\"<gradient:{primary}:{secondary_light}>Opens the editor</gradient>\">x</hover>",
                "[center]{letters}a\n[center]<click:open_url:'https://a.b/c'><hover:show_text:'{letters}Opens the editor'>{success}&l[OPEN]</hover></click>"}) {
            Component hover = hoverOf(Text.of(raw).build());
            assertNotNull(hover, raw);
            assertEquals("Opens the editor", PlainTextComponentSerializer.plainText().serialize(hover), raw);
        }
    }
}
