package net.exylia.lib.ui;

import net.exylia.lib.FakeServer;
import net.exylia.lib.action.ActionResult;
import net.exylia.lib.action.Actions;
import net.exylia.lib.action.PluginActions;
import net.exylia.lib.ui.internal.MenuLoader;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every configuration example in {@code docs/menus.md}, loaded for real.
 *
 * <p>Documentation that describes keys the loader does not read is worse than
 * none: somebody writes what it says, nothing happens, and the file looks
 * correct. These are the doc's own examples, so if a key is renamed or dropped
 * the doc fails with the code rather than quietly going stale.
 */
class DocumentedMenusTest {

    private PluginActions actions;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Actions.releaseAll();
        Plugin plugin = FakeServer.newPlugin("Docs", null);
        actions = Actions.of(plugin, "practice");
        for (String id : new String[] {"select", "adjust_priority", "open_details"}) {
            actions.registerSync(id, (context, args) -> ActionResult.success());
        }
    }

    private UiDefinition load(String yaml) {
        return MenuLoader.load("docs:test",
                YamlConfiguration.loadConfiguration(new java.io.StringReader(yaml)),
                actions::template, UiSounds.DEFAULTS);
    }

    @Test
    @DisplayName("the container example is read the way the doc says")
    void containers() {
        assertEquals(UiDefinition.UiKind.CHEST, load("""
                type: SIMPLE
                size: 54
                title: "Menu"
                """).kind());

        assertEquals(5, load("""
                type: HOPPER
                size: 54
                title: "Menu"
                """).size(), "a hopper is five slots whatever size says");
    }

    @Test
    @DisplayName("the paginated list example loads with its template and navigation")
    void pagination() {
        UiDefinition menu = load("""
                title: "Kits"
                size: 54
                pagination:
                  slots: '10-16,19-25,28-34'
                  item_template:
                    material: "%kit_icon%"
                    name: "{warning}&l%kit_name%"
                    actions:
                      - "practice:select %kit_id%"
                  navigation:
                    previous: { slot: 45, material: ARROW, actions: ['previous_page'] }
                    next:     { slot: 53, material: ARROW, actions: ['next_page'] }
                """);

        UiSection list = menu.section("main");
        assertNotNull(list, "the doc says pagination becomes a section called main");
        assertEquals(21, list.perPage());
        assertNotNull(list.template(null), "item_template is the default template");
    }

    @Test
    @DisplayName("the several-lists example gives each section its own pages")
    void sections() {
        UiDefinition menu = load("""
                title: "Leaderboard"
                size: 54
                sections:
                  players:
                    slots: "1-7,10-16,19-25,28-34"
                    player_template:
                      material: PLAYER_HEAD
                    navigation:
                      previous: { slot: 37, material: ARROW, actions: ['previous_page players'] }
                      next: { slot: 43, material: ARROW, actions: ['next_page players'] }
                  stat_types:
                    slots: "46-52"
                    not_selected_template:
                      material: PAPER
                    selected_template:
                      material: MAP
                """);

        assertNotNull(menu.section("players"));
        assertNotNull(menu.section("stat_types"));
        assertEquals(28, menu.section("players").perPage());
        assertEquals(7, menu.section("stat_types").perPage());

        UiSection types = menu.section("stat_types");
        assertNotNull(types.template("selected"), "selected_template is the 'selected' template");
        assertNotNull(types.template("not_selected"));
    }

    @Test
    @DisplayName("the three filler roles in the doc are all read")
    void fillers() {
        UiDefinition menu = load("""
                title: "Kits"
                size: 54
                pagination:
                  slots: "10-16"
                  item_template:
                    material: STONE
                filler:
                  global:
                    material: BLACK_STAINED_GLASS_PANE
                    hide_tooltip: true
                  pagination:
                    material: LIGHT_GRAY_STAINED_GLASS_PANE
                    name: "{muted}No kits available"
                  custom:
                    header:
                      slots: "0-8"
                      material: GRAY_STAINED_GLASS_PANE
                """);

        assertNotNull(menu.fillers().global(), "the background");
        assertNotNull(menu.fillers().pagination(), "what an empty list says");
        assertEquals(1, menu.fillers().custom().size(), "a panel with its own slots");
    }

    @Test
    @DisplayName("a slot that says what it depends on is read as dynamic")
    void dependsOn() {
        UiDefinition menu = load("""
                title: "Stats"
                size: 27
                items:
                  elo:
                    slot: 22
                    material: DIAMOND
                    name: "{letters}Rating: {highlight}%elo%"
                    depends-on:
                      - stats
                """);

        UiItem item = menu.items().get(22);
        assertNotNull(item, "slot 22 should hold the item");
        assertTrue(item.dependencies().contains("stats"));
        assertTrue(item.isDynamic());
    }

    @Test
    @DisplayName("the refresh block is read with all three of its settings")
    void refresh() {
        UiDefinition menu = load("""
                title: "Queue"
                size: 27
                refresh:
                  mode: SMART
                  interval: 20
                  click_delay: 4
                """);

        assertEquals(UiRefresh.Mode.SMART, menu.refresh().mode());
        assertEquals(20, menu.refresh().interval());
        assertEquals(4, menu.refresh().clickDelay());
    }

    @Test
    @DisplayName("both animation forms in the doc are read")
    void animations() {
        assertEquals("center_out", load("""
                title: "Menu"
                size: 27
                animation: center_out
                """).openAnimation().type());

        UiAnimationSpec longForm = load("""
                title: "Menu"
                size: 27
                animation:
                  type: rows_alternate
                  speed: 3
                """).openAnimation();
        assertEquals("rows_alternate", longForm.type());
        assertEquals(3, longForm.speed());
    }

    @Test
    @DisplayName("a condition on a slot is read")
    void conditions() {
        UiDefinition menu = load("""
                title: "Lobby"
                size: 27
                items:
                  join:
                    slot: 10
                    material: LIME_DYE
                    condition: "%lfc_state% == none"
                """);

        UiItem item = menu.items().get(10);
        assertNotNull(item);
        assertEquals("%lfc_state% == none", item.condition());
    }

    @Test
    @DisplayName("the per-click action syntax in the doc compiles to bindings")
    void clickBindings() {
        UiDefinition menu = load("""
                title: "Menu"
                size: 27
                items:
                  button:
                    slot: 13
                    material: STONE
                    actions:
                      - "left: practice:adjust_priority 1"
                      - "right: practice:adjust_priority -1"
                      - "left,right: practice:open_details"
                """);

        ClickBindings bindings = menu.items().get(13).bindings();
        assertNotNull(bindings.forClick(ClickKind.LEFT), "left is bound");
        assertNotNull(bindings.forClick(ClickKind.RIGHT), "right is bound");
    }

    @Test
    @DisplayName("the sound keys the doc shows are the ones the loader reads")
    void sounds() {
        // Every deployed file writes the flat form; the doc shows both, and
        // this is the pair that used to leave the whole ecosystem silent.
        UiDefinition flat = load("""
                title: "Menu"
                size: 27
                open_sounds:
                  - "ENTITY_EXPERIENCE_ORB_PICKUP|1.0|1.2"
                click_sounds:
                  - "UI_BUTTON_CLICK|1.0|1.5"
                """);
        assertNotNull(flat.sounds().open());
        assertNotNull(flat.sounds().click());

        UiDefinition block = load("""
                title: "Menu"
                size: 27
                sounds:
                  open: "BLOCK_BARREL_OPEN|0.6|1.4"
                  denied: ""
                """);
        assertNotNull(block.sounds().open());
        assertTrue(block.sounds().denied() == null || block.sounds().denied().isBlank(),
                "an empty string is silence, not a missing key");
    }

    @Test
    @DisplayName("the doc's own claim about editable slots holds")
    void editableSlots() {
        UiDefinition menu = load("""
                title: "Editor"
                size: 54
                editable_slots: "0-4,9-44"
                """);

        assertFalse(menu.inputSlots().isEmpty(), "the doc says these accept items");
        assertTrue(menu.inputSlots().contains(0));
        assertTrue(menu.inputSlots().contains(44));
        assertFalse(menu.inputSlots().contains(53));
    }
}
