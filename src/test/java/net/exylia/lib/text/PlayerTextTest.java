package net.exylia.lib.text;

import net.exylia.lib.text.internal.TextEngine;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Text a player typed never turns into a button, whichever way it travels. */
class PlayerTextTest {

    private static final String CLICK = "<click:run_command:/op me>x</click>";

    private static boolean clickable(Component component) {
        if (component.clickEvent() != null) {
            return true;
        }
        for (Component child : component.children()) {
            if (clickable(child)) {
                return true;
            }
        }
        return false;
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    @DisplayName("strip leaves nothing a second parse can bring back")
    void stripIsFinal() {
        assertEquals(CLICK, Text.of("\\" + CLICK).plain(),
                "plain() unescapes, which is why it is not a sanitiser");
        assertEquals("x", Text.strip("\\" + CLICK));
        assertEquals("a < b", Text.strip("a < b"));
        assertEquals("hi", Text.strip("&chi"));
    }

    @Test
    @DisplayName("escape keeps the text as typed and inert")
    void escapeReadsAsTyped() {
        for (String typed : List.of(CLICK, "\\" + CLICK, "&chi {primary}", "a\\b", "a\\b<c", "\\\\<x>", "\\&c")) {
            Component parsed = Text.component(Text.escape(typed));
            assertEquals(typed, plain(parsed));
            assertNull(parsed.clickEvent());
        }
    }

    @Test
    @DisplayName("a placeholder value keeps its colours and loses its click")
    void restrictedValues() {
        Component value = TextEngine.parseRestricted("<red>" + CLICK, false);
        assertEquals(false, clickable(value));
        assertEquals("red", plain(TextEngine.parseRestricted("<red>red", false)), "colours still parse");
        assertEquals("<hover:show_text:'x'>y", plain(TextEngine.parseRestricted("<hover:show_text:'x'>y", false)));
        Component colored = Text.of("Tag: %tag%").withColored("%tag%", "<red>" + CLICK).build();
        assertEquals(false, clickable(colored));
    }
}
