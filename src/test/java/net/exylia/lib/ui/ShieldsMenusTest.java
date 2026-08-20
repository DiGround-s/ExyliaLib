package net.exylia.lib.ui;

import net.exylia.lib.FakeServer;
import net.exylia.lib.action.ActionResult;
import net.exylia.lib.action.Actions;
import net.exylia.lib.action.PluginActions;
import net.exylia.lib.ui.internal.MenuLoader;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The menus a migrated plugin actually ships, loaded as written.
 *
 * <p>These are the real files out of ExyliaShields rather than an excerpt. A
 * button that does nothing is the failure this catches, and it is invisible in
 * a test that writes its own YAML: the file that works is never the one the
 * plugin ships.
 */
class ShieldsMenusTest {

    /** Every action the four files name, without the namespace. */
    private static final List<String> REGISTERED = List.of(
            "open_slots", "create_slot", "open_editor", "open_color_picker",
            "open_browser", "import_design", "pick_color", "add_pattern",
            "remove_layer", "toggle_preview", "reset_slot", "delete_slot",
            "select_slot");

    private Plugin plugin;
    private PluginActions actions;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Actions.releaseAll();
        plugin = FakeServer.newPlugin("Shields", null);
        actions = Actions.of(plugin, "shield");
        for (String id : REGISTERED) {
            actions.registerSync(id, (context, args) -> ActionResult.success());
        }
        // What Menus.of does for a real plugin: next_page and the rest belong
        // to the library, but an action id is namespaced, so they are
        // registered under the plugin that owns the menu.
        net.exylia.lib.ui.internal.BuiltInActions.register(actions);
    }

    @AfterEach
    void tearDown() {
        Actions.releaseAll();
        FakeServer.reset();
    }

    private UiDefinition load(String name) {
        YamlConfiguration config = new YamlConfiguration();
        try (InputStream stream = getClass().getResourceAsStream("/shields/" + name + ".yml")) {
            assertNotNull(stream, name + ".yml is on the test classpath");
            config.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (Exception invalid) {
            throw new IllegalStateException(name + ".yml did not load", invalid);
        }
        return MenuLoader.load("shield:" + name, config, actions::template, UiSounds.DEFAULTS);
    }

    @Test
    @DisplayName("every shipped menu loads")
    void everyMenuLoads() {
        for (String name : List.of("slots", "design_editor", "color_picker", "community_browser")) {
            assertNotNull(load(name), name + " loads");
        }
    }

    @Test
    @DisplayName("the buttons of the slots menu are bound to something")
    void slotsButtonsAreBound() {
        UiDefinition menu = load("slots");
        // create=4, browser=47, import=51, as the file writes them.
        for (int slot : List.of(4, 47, 51)) {
            UiItem button = menu.items().get(slot);
            assertNotNull(button, "slot " + slot + " is drawn");
            assertFalse(button.bindings().isEmpty(),
                    "slot " + slot + " does something when clicked");
            assertFalse(button.bindings().forClick(ClickKind.LEFT).isEmpty(),
                    "slot " + slot + " responds to a left click");
        }
    }

    @Test
    @DisplayName("a row of the slots list opens the editor")
    void slotRowsAreBound() {
        UiDefinition menu = load("slots");
        UiSection section = menu.section();
        assertNotNull(section, "the slots menu is a paginated list");

        UiItem row = section.templates().get(UiSection.DEFAULT);
        assertNotNull(row, "the list has a row template");
        assertFalse(row.bindings().forClick(ClickKind.LEFT).isEmpty(),
                "left-clicking a slot edits it");
        assertFalse(row.bindings().forClick(ClickKind.RIGHT).isEmpty(),
                "right-clicking a slot wears it");
    }

    @Test
    @DisplayName("a navigation arrow pages its list without being told to")
    void navigationArrowsPageWithoutActions() {
        // How every paginated menu in the ecosystem is written: the arrow is
        // declared under "navigation" with a slot and an icon and nothing else.
        // Commons paged by slot, so no file ever named an action here, and a
        // library that needs one leaves every arrow in the ecosystem inert.
        UiDefinition menu = load("design_editor");

        for (String id : List.of("patterns", "layers")) {
            UiSection section = menu.sections().get(id);
            assertNotNull(section, id + " is a section of the editor");

            assertNotNull(section.previous(), id + " has a previous arrow");
            assertFalse(section.previous().item().bindings().forClick(ClickKind.LEFT).isEmpty(),
                    id + ": the previous arrow pages the list");

            assertNotNull(section.next(), id + " has a next arrow");
            assertFalse(section.next().item().bindings().forClick(ClickKind.LEFT).isEmpty(),
                    id + ": the next arrow pages the list");
        }
    }

    @Test
    @DisplayName("the buttons of the editor are bound to something")
    void editorButtonsAreBound() {
        UiDefinition menu = load("design_editor");
        boolean anyBound = false;
        for (UiItem button : menu.items().values()) {
            if (!button.bindings().isEmpty()) {
                anyBound = true;
                break;
            }
        }
        assertTrue(anyBound, "the editor has buttons that do something");
    }
}
