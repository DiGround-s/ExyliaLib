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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Menus written for ExyliaCommons, loaded unchanged.
 *
 * <p>The YAML in these tests is copied from real files in ExyliaPracticeCore.
 * There are hundreds of these across the ecosystem and none of them are going
 * to be migrated, so "does the old format still work" is not a nicety — it is
 * the feature.
 */
class MenuLoaderTest {

    private Plugin plugin;
    private PluginActions actions;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Actions.releaseAll();
        plugin = FakeServer.newPlugin("Practice", null);
        actions = Actions.of(plugin, "practice");
        for (String id : List.of("adjust_kit_priority", "set_kit_attribute",
                "reset_kit_attribute", "join_queue", "open_queue_categories",
                "toggle_setting", "party_kick_member")) {
            actions.registerSync(id, (context, args) -> ActionResult.success());
        }
    }

    @AfterEach
    void tearDown() {
        Actions.releaseAll();
        FakeServer.reset();
    }

    private UiDefinition load(String yaml) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(yaml);
        } catch (Exception invalid) {
            throw new IllegalStateException("test yaml is not valid", invalid);
        }
        return MenuLoader.load("practice:test", config, actions::template, UiSounds.DEFAULTS);
    }

    @Test
    @DisplayName("a plain menu loads with its title, size and items")
    void simpleMenu() {
        UiDefinition menu = load("""
                title: "{primary}&lSETTINGS"
                size: 27
                items:
                  party_invites:
                    slot: 10
                    material: "HONEYCOMB"
                    name: "{primary}&lParty Invitations"
                    lore:
                      - ""
                      - " {muted} {letters}Status: %party_invites_value%"
                    actions:
                      - "practice:toggle_setting party_invites"
                """);

        assertEquals("{primary}&lSETTINGS", menu.title());
        assertEquals(27, menu.size());
        assertEquals(UiDefinition.UiKind.CHEST, menu.kind());
        UiItem item = menu.items().get(10);
        assertNotNull(item);
        assertEquals("HONEYCOMB", item.material());
        assertEquals(2, item.lore().size());
        assertFalse(item.bindings().isEmpty());
    }

    @Test
    @DisplayName("left, right and shift prefixes bind to their own clicks")
    void clickPrefixes() {
        UiDefinition menu = load("""
                title: "Kit"
                size: 54
                items:
                  priority:
                    slot: 38
                    material: HOPPER
                    actions:
                      - "left: practice:adjust_kit_priority 1"
                      - "right: practice:adjust_kit_priority -1"
                      - "shift_left: practice:adjust_kit_priority 10"
                      - "shift_right: practice:adjust_kit_priority -10"
                """);

        ClickBindings bindings = menu.items().get(38).bindings();
        assertEquals(1, bindings.forClick(ClickKind.LEFT).size());
        assertEquals(1, bindings.forClick(ClickKind.RIGHT).size());
        assertEquals(1, bindings.forClick(ClickKind.SHIFT_LEFT).size());
        assertEquals(1, bindings.forClick(ClickKind.SHIFT_RIGHT).size());
        assertTrue(bindings.forClick(ClickKind.DROP).isEmpty(),
                "a click nobody bound does nothing");
    }

    @Test
    @DisplayName("an action's own namespace colon is not mistaken for a click prefix")
    void namespacedActionIsNotAPrefix() {
        UiDefinition menu = load("""
                title: "Queue"
                size: 27
                items:
                  back:
                    slot: 22
                    material: ARROW
                    actions:
                      - "practice:open_queue_categories"
                """);

        ClickBindings bindings = menu.items().get(22).bindings();
        // No prefix was written, so every click runs it.
        assertEquals(1, bindings.forClick(ClickKind.LEFT).size());
        assertEquals(1, bindings.forClick(ClickKind.RIGHT).size());
        assertEquals(1, bindings.forClick(ClickKind.DROP).size());
    }

    @Test
    @DisplayName("one line can bind several clicks at once")
    void combinedPrefix() {
        UiDefinition menu = load("""
                title: "Kit"
                size: 27
                items:
                  both:
                    slot: 4
                    material: PAPER
                    actions:
                      - "left,right: practice:join_queue boxing"
                """);

        ClickBindings bindings = menu.items().get(4).bindings();
        assertEquals(1, bindings.forClick(ClickKind.LEFT).size());
        assertEquals(1, bindings.forClick(ClickKind.RIGHT).size());
        assertTrue(bindings.forClick(ClickKind.MIDDLE).isEmpty());
    }

    @Test
    @DisplayName("pagination loads its slots, template and navigation")
    void pagination() {
        UiDefinition menu = load("""
                title: "Kits"
                size: 54
                pagination:
                  slots: '10-16,19-25,28-34'
                  item_template:
                    material: "%kit_icon%"
                    name: "{warning}&l%kit_name%"
                    amount: "%playing_count%"
                    actions:
                      - "practice:join_queue %kit_id%"
                  navigation:
                    previous:
                      slot: 45
                      material: ARROW
                      name: '{error}&l PREVIOUS'
                    next:
                      slot: 53
                      material: ARROW
                      name: '{success}&lNEXT '
                items:
                  back:
                    slot: 49
                    material: ARROW
                    actions:
                      - "practice:open_queue_categories"
                """);

        UiDefinition.Pagination pagination = menu.pagination();
        assertNotNull(pagination);
        assertEquals(21, pagination.perPage());
        assertEquals(10, pagination.slots().get(0));
        assertEquals(34, pagination.slots().get(20));
        assertEquals(45, pagination.previous().slot());
        assertEquals(53, pagination.next().slot());
        assertTrue(menu.isPaginated());
    }

    @Test
    @DisplayName("slot expressions accept every form menus are written in")
    void slotForms() {
        assertEquals(List.of(13), Slots.parse("13"));
        assertEquals(List.of(10, 11, 12), Slots.parse("10-12"));
        assertEquals(List.of(1, 5, 6, 7), Slots.parse("1,5-7"));
        assertEquals(List.of(1, 2), Slots.parse(" 1 , 2 "));
        // A range written twice is still one set of slots.
        assertEquals(List.of(3, 4), Slots.parse("3-4,3-4"));
    }

    @Test
    @DisplayName("a malformed slot is reported wherever it appears")
    void malformedSlotsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Slots.parse("abc"));
        assertThrows(IllegalArgumentException.class, () -> Slots.parse("5-x"));
        // Written backwards: silently losing a row is worse than saying so.
        assertThrows(IllegalArgumentException.class, () -> Slots.parse("9-3"));
    }

    @Test
    @DisplayName("a slot outside the menu is refused when it is loaded, not when it is opened")
    void slotsMustFitTheMenu() {
        assertThrows(IllegalArgumentException.class, () -> load("""
                title: "Small"
                size: 9
                items:
                  stray:
                    slot: 40
                    material: STONE
                """));
    }

    @Test
    @DisplayName("declaring both slot and slots is a mistake worth reporting")
    void slotAndSlotsTogetherAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> load("""
                title: "Menu"
                size: 27
                items:
                  confused:
                    slot: 1
                    slots: "2-3"
                    material: STONE
                """));
    }

    @Test
    @DisplayName("the old pagination type names still load, and still paginate")
    void legacyTypeNames() {
        UiDefinition menu = load("""
                title: "Kits"
                type: PAGINATION
                size: 54
                pagination:
                  slots: '10-12'
                  item_template:
                    material: PAPER
                """);

        assertEquals(UiDefinition.UiKind.CHEST, menu.kind());
        assertTrue(menu.isPaginated());
    }

    @Test
    @DisplayName("rows is the friendlier spelling of size")
    void rowsInsteadOfSize() {
        assertEquals(27, load("""
                title: "Menu"
                rows: 3
                """).size());
    }

    @Test
    @DisplayName("a size that is not a row count is refused")
    void invalidSizeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> load("""
                title: "Menu"
                size: 20
                """));
    }

    @Test
    @DisplayName("a hopper is five slots whatever the file says")
    void containerKindDecidesSize() {
        UiDefinition menu = load("""
                title: "Hopper"
                type: HOPPER
                size: 54
                """);

        assertEquals(UiDefinition.UiKind.HOPPER, menu.kind());
        assertEquals(5, menu.size());
    }

    @Test
    @DisplayName("heads load from a texture, a URL, a name, or a name decided per row")
    void heads() {
        UiDefinition menu = load("""
                title: "Heads"
                size: 27
                items:
                  by_texture:
                    slot: 0
                    material: PLAYER_HEAD
                    texture: "abc123"
                  by_url:
                    slot: 1
                    material: PLAYER_HEAD
                    head-url: "https://textures.minecraft.net/texture/deadbeef"
                  by_name:
                    slot: 2
                    material: PLAYER_HEAD
                    head: "Notch"
                  by_row:
                    slot: 3
                    material: PLAYER_HEAD
                    head: "%member_name%"
                """);

        assertNotNull(menu.items().get(0).head());
        assertNotNull(menu.items().get(1).head());
        assertNotNull(menu.items().get(2).head());
        assertNull(menu.items().get(3).head(), "whose head it is depends on the row");
        assertEquals("%member_name%", menu.items().get(3).headTemplate());
    }

    @Test
    @DisplayName("a flag can be turned off by writing either spelling")
    void flagsCanBeTurnedOff() {
        UiDefinition menu = load("""
                title: "Menu"
                size: 27
                items:
                  plain:
                    slot: 0
                    material: STONE
                  shiny:
                    slot: 1
                    material: STONE
                    glowing: true
                """);

        assertFalse(menu.items().get(0).glow());
        assertTrue(menu.items().get(1).glow());
    }

    @Test
    @DisplayName("a static menu is known to be static, so it is drawn once")
    void staticMenusAreRecognised() {
        UiDefinition simple = load("""
                title: "Static"
                size: 27
                items:
                  decoration:
                    slot: 0
                    material: BLACK_STAINED_GLASS_PANE
                    name: "&7"
                """);
        assertFalse(simple.isDynamic());

        UiDefinition dynamic = load("""
                title: "Live"
                size: 27
                items:
                  stats:
                    slot: 0
                    material: PAPER
                    name: "Wins: %player_wins%"
                """);
        assertTrue(dynamic.isDynamic());
    }

    @Test
    @DisplayName("sounds fall back to the defaults, and an empty value means silence")
    void sounds() {
        UiDefinition menu = load("""
                title: "Menu"
                size: 27
                sounds:
                  click: "BLOCK_NOTE_BLOCK_PLING|1|2"
                  open: ""
                """);

        assertEquals("BLOCK_NOTE_BLOCK_PLING|1|2", menu.sounds().click());
        assertNull(menu.sounds().open(), "an empty value silences that sound");
        assertEquals(UiSounds.DEFAULTS.close(), menu.sounds().close(),
                "anything not mentioned keeps the default");
    }

    @Test
    @DisplayName("an animation is a name, or a name with settings")
    void animations() {
        UiDefinition shortForm = load("""
                title: "Menu"
                size: 27
                animation: pulse
                """);
        assertEquals("pulse", shortForm.openAnimation().type());
        assertTrue(shortForm.openAnimation().loop());

        UiDefinition longForm = load("""
                title: "Menu"
                size: 27
                animation:
                  type: wave
                  speed: 3
                  direction: left-to-right
                  loop: false
                  stagger: 1
                """);
        assertEquals("wave", longForm.openAnimation().type());
        assertEquals(3, longForm.openAnimation().speed());
        assertEquals("left-to-right", longForm.openAnimation().direction());
        assertFalse(longForm.openAnimation().loop());
        assertEquals(1, longForm.openAnimation().stagger());
    }

    @Test
    @DisplayName("a mistyped action is a dead button and a reported problem, not a dead menu")
    void unknownActionsAreReportedButDoNotStopTheMenu() {
        List<String> problems = new ArrayList<>();
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString("""
                    title: "Menu"
                    size: 27
                    items:
                      working:
                        slot: 0
                        material: STONE
                        actions:
                          - "practice:join_queue boxing"
                      broken:
                        slot: 1
                        material: STONE
                        actions:
                          - "practice:no_such_action"
                    """);
        } catch (Exception invalid) {
            throw new IllegalStateException(invalid);
        }

        UiDefinition menu = MenuLoader.load("practice:test", config, actions::template,
                UiSounds.DEFAULTS, (where, problem) -> problems.add(where + ": " + problem));

        assertEquals(1, problems.size(), "the bad button is reported: " + problems);
        assertEquals(2, menu.items().size(), "the rest of the menu still works");
    }

    @Test
    @DisplayName("a row action decided by a placeholder is left to be resolved per row")
    void perRowActions() {
        UiDefinition menu = load("""
                title: "Members"
                size: 27
                items:
                  member:
                    slot: 0
                    material: PLAYER_HEAD
                    actions:
                      - "left: %kick_action%"
                """);

        assertEquals(1, menu.items().get(0).bindings().forClick(ClickKind.LEFT).size());
        assertTrue(menu.items().get(0).bindings().forClick(ClickKind.LEFT).get(0).isDynamic());
    }
}
