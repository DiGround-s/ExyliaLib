package net.exylia.lib.screen.internal;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkupWordsTest {

    /** A value written for lore breaks its lines with <nl>; on a screen it breaks them too and is measured as lines. */
    @Test
    void loreLineBreaksBecomeLines() {
        assertEquals("{info}a\nb\nc", MarkupRender.textOf("a<nl>b<br>c", Map.of("color", "{info}")));
        assertTrue(MarkupRender.words("one<nl>two", Map.of()).wrap() > 0, "a broken value is measured as lines");
        assertEquals(0, MarkupRender.words("one line", Map.of()).wrap());
    }
}
