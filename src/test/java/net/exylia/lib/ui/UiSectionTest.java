package net.exylia.lib.ui;

import net.exylia.lib.item.Item;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a paginated list divides its rows and picks how to draw them.
 *
 * <p>Arithmetic and lookup only, which is deliberately all a section is: the
 * part that can be wrong without a server is the part worth testing without
 * one.
 */
class UiSectionTest {

    private static UiItem item(String material) {
        return UiItem.of(Item.of(material).build()).build();
    }

    private static UiSection section(List<Integer> slots, Map<String, UiItem> templates) {
        return new UiSection("kits", slots, templates, null, null, null);
    }

    private static UiSection ofSize(int slots) {
        return section(java.util.stream.IntStream.range(0, slots).boxed().toList(),
                Map.of(UiSection.DEFAULT, item("STONE")));
    }

    @Test
    @DisplayName("rows are divided into pages, rounding up")
    void pageCount() {
        UiSection list = ofSize(21);

        assertEquals(1, list.pagesFor(0), "an empty list still has a page to show");
        assertEquals(1, list.pagesFor(1));
        assertEquals(1, list.pagesFor(21));
        assertEquals(2, list.pagesFor(22));
        assertEquals(5, list.pagesFor(100));
    }

    @Test
    @DisplayName("a list with no slots does not divide by zero")
    void noSlots() {
        UiSection list = section(List.of(), Map.of(UiSection.DEFAULT, item("STONE")));

        assertEquals(1, list.pagesFor(50));
        assertEquals(0, list.perPage());
    }

    @Test
    @DisplayName("a row asks for a template by name")
    void namedTemplates() {
        UiSection list = section(List.of(0, 1),
                Map.of(UiSection.DEFAULT, item("STONE"),
                        "selected", item("DIAMOND"),
                        "no_permissions", item("BARRIER")));

        assertTrue(list.hasTemplate("selected"));
        assertEquals("DIAMOND", materialOf(list.template("selected")));
        assertEquals("BARRIER", materialOf(list.template("no_permissions")));
    }

    @Test
    @DisplayName("a row that asks for nothing gets the ordinary template")
    void defaultTemplate() {
        UiSection list = section(List.of(0),
                Map.of(UiSection.DEFAULT, item("STONE"), "selected", item("DIAMOND")));

        assertEquals("STONE", materialOf(list.template(null)));
    }

    @Test
    @DisplayName("a template name nobody declared draws the ordinary row")
    void unknownTemplateFallsBack() {
        // Easier to notice than an empty slot, and far easier to recover from:
        // a plugin renaming a template does not blank a leaderboard.
        UiSection list = section(List.of(0),
                Map.of(UiSection.DEFAULT, item("STONE"), "selected", item("DIAMOND")));

        assertEquals("STONE", materialOf(list.template("invented_by_a_plugin")));
        assertFalse(list.hasTemplate("invented_by_a_plugin"));
    }

    @Test
    @DisplayName("a section with no template at all is allowed, for rows that bring their own")
    void sectionWithoutTemplates() {
        // ExyliaSandBox's kit room lists the stacks it has stored. No template
        // could describe an arbitrary saved item, and writing one out to
        // configuration to read it back would be silly.
        UiSection list = section(List.of(0, 1), Map.of());

        assertFalse(list.hasTemplates());
        assertNull(list.template(null));
        assertEquals(2, list.perPage());
    }

    @Test
    @DisplayName("the slots and templates cannot be changed after the fact")
    void defensivelyCopied() {
        List<Integer> slots = new java.util.ArrayList<>(List.of(0, 1));
        UiSection list = section(slots, Map.of(UiSection.DEFAULT, item("STONE")));

        slots.add(2);

        assertEquals(2, list.perPage());
    }

    @Test
    @DisplayName("the same template instance is handed back, not a copy")
    void templatesAreShared() {
        UiItem template = item("STONE");
        UiSection list = section(List.of(0), Map.of(UiSection.DEFAULT, template));

        // Twenty rows drawn from one template must not be twenty definitions.
        assertSame(template, list.template(null));
        assertSame(template, list.template("anything"));
    }

    private static String materialOf(UiItem item) {
        return ((net.exylia.lib.item.Source.OfMaterial) item.item().source()).raw();
    }
}
