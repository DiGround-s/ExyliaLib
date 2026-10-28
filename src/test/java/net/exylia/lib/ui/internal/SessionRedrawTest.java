package net.exylia.lib.ui.internal;

import net.exylia.lib.FakeServer;
import net.exylia.lib.ui.UiDefinition;
import net.exylia.lib.ui.UiEntry;
import net.exylia.lib.ui.UiSounds;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** What a redraw leaves alone, and what a reopened list says it is showing. */
class SessionRedrawTest {

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
    }

    @AfterEach
    void tearDown() {
        FakeServer.reset();
    }

    private static UiDefinition load(String yaml) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(yaml);
        } catch (Exception invalid) {
            throw new IllegalStateException("test yaml is not valid", invalid);
        }
        return MenuLoader.load("test:menu", config, id -> null, UiSounds.DEFAULTS);
    }

    /** A window that only stores what is put in it. */
    private static Inventory window(ItemStack[] contents) {
        return (Inventory) Proxy.newProxyInstance(Inventory.class.getClassLoader(),
                new Class<?>[] {Inventory.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getSize" -> contents.length;
                    case "getItem" -> contents[(int) args[0]];
                    case "setItem" -> {
                        contents[(int) args[0]] = (ItemStack) args[1];
                        yield null;
                    }
                    case "clear" -> {
                        java.util.Arrays.fill(contents, null);
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** An item with nothing behind it: {@code new ItemStack(...)} needs a real server. */
    private static ItemStack stack() {
        return new ItemStack() { };
    }

    @Test
    @DisplayName("a refresh keeps what the player put in an editable slot")
    void refreshKeepsEditableSlots() {
        UiDefinition menu = load("""
                title: Apply
                size: 27
                editable_slots: '11,15'
                """);
        ItemStack[] contents = new ItemStack[27];
        Session session = new Session(null, null, menu, null, window(contents), 1, Map.of());
        ItemStack book = stack();
        ItemStack sword = stack();
        contents[11] = book;
        contents[15] = sword;
        contents[4] = stack();

        session.refresh();

        assertSame(book, contents[11], "a redraw deleted the player's book");
        assertSame(sword, contents[15], "a redraw deleted the player's item");
        assertNull(contents[4], "the menu's own slots are still cleared and redrawn");
    }

    @Test
    @DisplayName("a list reopened on a remembered page is titled with that page")
    void reopenedTitleShowsRememberedPage() {
        UiDefinition menu = load("""
                title: 'List %current_page%/%total_pages%'
                size: 27
                pagination:
                  slots: '0-8'
                  item_template:
                    material: STONE
                """);
        List<UiEntry> rows = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            rows.add(UiEntry.of(i).build());
        }
        String section = menu.section().id();

        String reopened = PlainTextComponentSerializer.plainText().serialize(
                Session.title(menu, null, Map.of(), Map.of(section, rows), Map.of(section, 3)));
        String clamped = PlainTextComponentSerializer.plainText().serialize(
                Session.title(menu, null, Map.of(), Map.of(section, rows), Map.of(section, 9)));

        assertEquals("List 3/4", reopened);
        assertEquals("List 4/4", clamped, "a page past the end lands on the last, as the seed does");
    }
}
