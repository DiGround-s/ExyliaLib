package net.exylia.lib.ui;

import net.exylia.lib.FakeServer;
import net.exylia.lib.action.ActionResult;
import net.exylia.lib.action.Actions;
import net.exylia.lib.action.PluginActions;
import net.exylia.lib.item.Source;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
        assertEquals("HONEYCOMB", assertInstanceOf(Source.OfMaterial.class, item.item().source()).raw());
        assertEquals(2, item.item().lore().size());
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

        // A pagination block is one section, and its name is not interesting.
        UiSection list = menu.section();
        assertNotNull(list);
        assertEquals(UiSection.MAIN, list.id());
        assertEquals(21, list.perPage());
        assertEquals(10, list.slots().get(0));
        assertEquals(34, list.slots().get(20));
        assertEquals(45, list.previous().slot());
        assertEquals(53, list.next().slot());
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
        // The prefix lives inside material, which is how all four hundred of
        // these are written across the ecosystem. The earlier parser looked for
        // texture:, head-url: and head: keys, which nobody has ever written.
        UiDefinition menu = load("""
                title: "Heads"
                size: 27
                items:
                  by_texture:
                    slot: 0
                    material: "basehead-abc123"
                  by_url:
                    slot: 1
                    material: "urlhead-https://textures.minecraft.net/texture/deadbeef"
                  by_name:
                    slot: 2
                    material: "playerhead-Notch"
                  by_row:
                    slot: 3
                    material: "playerhead-%member_name%"
                """);

        assertInstanceOf(Source.OfHead.class, menu.items().get(0).item().source());
        assertInstanceOf(Source.OfHead.class, menu.items().get(1).item().source());
        assertInstanceOf(Source.OfHead.class, menu.items().get(2).item().source());
        assertInstanceOf(Source.OfHeadTemplate.class, menu.items().get(3).item().source(),
                "whose head it is depends on the row");
        assertTrue(menu.items().get(3).isDynamic());
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

        assertNull(menu.items().get(0).item().appearance().glow());
        assertEquals("true", menu.items().get(1).item().appearance().glow());
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
    @DisplayName("an animation is a name, or a name with a speed")
    void animations() {
        // Every one of the 89 animated menus in the wild writes the short form.
        UiDefinition shortForm = load("""
                title: "Menu"
                size: 27
                animation: center_out
                """);
        assertEquals("center_out", shortForm.openAnimation().type());
        assertEquals(2, shortForm.openAnimation().speed(), "a sensible default pace");

        UiDefinition longForm = load("""
                title: "Menu"
                size: 27
                animation:
                  type: rows_alternate
                  speed: 3
                """);
        assertEquals("rows_alternate", longForm.openAnimation().type());
        assertEquals(3, longForm.openAnimation().speed());
    }

    @Test
    @DisplayName("a speed of zero would be a frame every tick, not a stalled menu")
    void animationSpeedIsAtLeastOne() {
        UiDefinition menu = load("""
                title: "Menu"
                size: 27
                animation:
                  type: center_out
                  speed: 0
                """);

        assertEquals(1, menu.openAnimation().speed());
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

    @Test
    @DisplayName("the sound spelling every deployed menu uses is read")
    void soundsAtTheRoot() {
        // 996 files write open_sounds and none write a sounds block. Reading
        // only the latter made every menu in the ecosystem silent.
        UiDefinition menu = load("""
                title: "Kits"
                size: 27
                open_sounds:
                  - "ENTITY_EXPERIENCE_ORB_PICKUP|1.0|1.2"
                click_sounds:
                  - "UI_BUTTON_CLICK|1.0|1.5"
                """);

        assertEquals("ENTITY_EXPERIENCE_ORB_PICKUP|1.0|1.2", menu.sounds().open());
        assertEquals("UI_BUTTON_CLICK|1.0|1.5", menu.sounds().click());
        assertEquals(UiSounds.DEFAULTS.close(), menu.sounds().close(),
                "what the file does not mention keeps the default");
    }

    @Test
    @DisplayName("an empty sound list is silence, not a missing key")
    void silence() {
        UiDefinition menu = load("""
                title: "Quiet"
                size: 27
                open_sounds: []
                """);

        assertNull(menu.sounds().open());
    }

    @Test
    @DisplayName("the tidier spelling wins where a file uses both")
    void soundsBlockWins() {
        UiDefinition menu = load("""
                title: "Kits"
                size: 27
                open_sounds:
                  - "ENTITY_EXPERIENCE_ORB_PICKUP|1.0|1.2"
                sounds:
                  open: "BLOCK_BARREL_OPEN|1|1"
                """);

        assertEquals("BLOCK_BARREL_OPEN|1|1", menu.sounds().open());
    }

    @Test
    @DisplayName("every type value the ecosystem writes lands on a chest")
    void everyRealTypeValue() {
        // The old type described the container and whether it paginated at the
        // same time. All five values in the wild mean a chest; what paginates
        // is decided by whether the file has a list.
        for (String type : new String[] {
                "SIMPLE", "PAGINATION", "MULTI_PAGINATION", "ITEM_INPUT", "STATIC"}) {
            UiDefinition menu = load("""
                    title: "Menu"
                    size: 27
                    type: %s
                    """.formatted(type));
            assertEquals(UiDefinition.UiKind.CHEST, menu.kind(), type + " should be a chest");
            assertEquals(27, menu.size(), type + " should keep its configured size");
        }
    }

    @Test
    @DisplayName("a real container keeps the size the server says it has")
    void realContainerTypes() {
        // Asked of Bukkit rather than written down. Writing them out by hand
        // got three wrong, and each would be an inventory of the wrong size,
        // which throws on open rather than looking slightly odd.
        assertEquals(5, sizeOf("HOPPER"), "hopper");
        assertEquals(9, sizeOf("DROPPER"), "dropper");
        assertEquals(3, sizeOf("ANVIL"), "anvil");
        assertEquals(4, sizeOf("SMITHING"), "a smithing table has four slots, not three");
        assertEquals(10, sizeOf("CRAFTING"), "a crafting window has ten, not nine");
        assertEquals(27, sizeOf("BARREL"), "a barrel is a fixed 27, whatever the file says");
        assertEquals(1, sizeOf("BEACON"), "beacon");
        assertEquals(2, sizeOf("ENCHANTING"), "enchanting");

        assertEquals(UiDefinition.UiKind.ANVIL, load("""
                title: "Anvil"
                type: ANVIL
                """).kind());
    }

    @Test
    @DisplayName("only a chest is resizable")
    void onlyChestsResize() {
        assertTrue(UiDefinition.UiKind.CHEST.isSizeConfigurable());
        assertFalse(UiDefinition.UiKind.BARREL.isSizeConfigurable(),
                "a barrel looks like a chest and is not");
        assertEquals(9, load("""
                title: "Small"
                size: 9
                """).size());
    }

    /** The real slot count of a container kind, as the file would ask for it. */
    private int sizeOf(String type) {
        return load("""
                title: "Menu"
                size: 54
                type: %s
                """.formatted(type)).size();
    }

    @Test
    @DisplayName("the three filler roles are kept apart")
    void fillerRoles() {
        // 826 menus write global, 499 write pagination, 6 write custom. The
        // pagination one is not another background: it is what a player sees
        // when a list is empty, and it usually says so.
        UiDefinition menu = load("""
                title: "Kits"
                size: 54
                filler:
                  global:
                    material: GRAY_STAINED_GLASS_PANE
                    hide_tooltip: true
                  pagination:
                    material: LIGHT_GRAY_STAINED_GLASS_PANE
                    name: "{muted}No kits available"
                  custom:
                    header:
                      material: BLACK_STAINED_GLASS_PANE
                      slots: "0-8"
                pagination:
                  slots: '10-16'
                  item_template:
                    material: STONE
                """);

        UiFillers fillers = menu.fillers();
        assertNotNull(fillers.global());
        assertNotNull(fillers.pagination());
        assertEquals("{muted}No kits available", fillers.pagination().item().name(),
                "the empty-list filler says why the list is empty");
        assertEquals(1, fillers.custom().size());
        assertEquals("header", fillers.custom().getFirst().id());
        assertEquals(9, fillers.custom().getFirst().slots().size());
    }

    /** What a filler is made of, for asserting which one covered a slot. */
    private static String materialOf(UiItem item) {
        return assertInstanceOf(Source.OfMaterial.class, item.item().source()).raw();
    }

    @Test
    @DisplayName("a slot a page button vacates goes back to the menu's background")
    void backgroundUnderAPageButton() {
        // What a hidden arrow leaves behind. ExyliaCommons drew its fillers
        // before its navigation, so a button it did not draw was already
        // covered; here the button is drawn last, so the slot has to be told
        // what to go back to.
        UiDefinition menu = load("""
                title: "Kits"
                size: 54
                filler:
                  global:
                    material: GRAY_STAINED_GLASS_PANE
                  custom:
                    footer:
                      material: BLACK_STAINED_GLASS_PANE
                      slots: "45-53"
                pagination:
                  slots: '10-16'
                  item_template:
                    material: STONE
                  navigation:
                    previous: { slot: 45, material: ARROW }
                    next: { slot: 53, material: ARROW }
                """);

        UiFillers fillers = menu.fillers();
        assertEquals("BLACK_STAINED_GLASS_PANE", materialOf(fillers.backgroundAt(45)),
                "a panel covering the slot wins over the background");
        assertEquals("GRAY_STAINED_GLASS_PANE", materialOf(fillers.backgroundAt(20)),
                "a slot no panel claims falls through to the background");
    }

    @Test
    @DisplayName("a menu that fills nothing leaves a vacated button slot empty")
    void backgroundWithoutFillers() {
        UiDefinition menu = load("""
                title: "Kits"
                size: 54
                pagination:
                  slots: '10-16'
                  item_template:
                    material: STONE
                  navigation:
                    next: { slot: 53, material: ARROW }
                """);

        assertNull(menu.fillers().backgroundAt(53),
                "nothing to fill with means the slot is emptied, not left showing the arrow");
    }

    @Test
    @DisplayName("a custom panel with nowhere to go is dropped rather than kept")
    void customPanelWithoutSlots() {
        UiDefinition menu = load("""
                title: "Kits"
                size: 27
                filler:
                  custom:
                    nowhere:
                      material: STONE
                """);

        assertTrue(menu.fillers().custom().isEmpty());
    }

    @Test
    @DisplayName("a menu with no filler block fills nothing")
    void noFillers() {
        UiDefinition menu = load("""
                title: "Bare"
                size: 27
                """);

        assertTrue(menu.fillers().isEmpty());
    }

    @Test
    @DisplayName("the refresh block 161 deployed menus declare is read")
    void refreshBlock() {
        UiDefinition menu = load("""
                title: "Arrows"
                size: 54
                refresh:
                  mode: ON_CLICK
                  click_delay: 4
                """);

        assertEquals(UiRefresh.Mode.ON_CLICK, menu.refresh().mode());
        assertEquals(4, menu.refresh().clickDelay());
        assertTrue(menu.refresh().isOnClick());
        assertFalse(menu.refresh().isTimed());
    }

    @Test
    @DisplayName("a smart refresh both ticks and answers clicks")
    void smartRefresh() {
        UiDefinition menu = load("""
                title: "Queue"
                size: 54
                refresh:
                  mode: SMART
                  interval: 20
                """);

        assertTrue(menu.refresh().isTimed());
        assertTrue(menu.refresh().isOnClick(), "SMART redraws the clicked slot too");
        assertEquals(20, menu.refresh().interval());
    }

    @Test
    @DisplayName("a menu that says nothing about refreshing does not")
    void noRefreshBlock() {
        UiDefinition menu = load("""
                title: "Static"
                size: 27
                """);

        assertEquals(UiRefresh.Mode.DISABLED, menu.refresh().mode());
        assertFalse(menu.refresh().isTimed());
        assertFalse(menu.refresh().isOnClick());
    }

    @Test
    @DisplayName("a mode nobody implemented does nothing rather than guessing")
    void unknownRefreshMode() {
        UiDefinition menu = load("""
                title: "Odd"
                size: 27
                refresh:
                  mode: WHENEVER
                """);

        assertEquals(UiRefresh.Mode.DISABLED, menu.refresh().mode());
    }

    @Test
    @DisplayName("several lists on one screen each keep their own slots and arrows")
    void sections() {
        // ExyliaPracticeCore, menus/player/leaderboard.yml: the players in the
        // middle and the stat to sort by along the bottom, paging separately.
        UiDefinition menu = load("""
                title: "Leaderboard"
                size: 54
                sections:
                  players:
                    slots: "1-7,10-16"
                    player_template:
                      material: "playerhead-%player_name%"
                      name: "{highlight}#%player_rank_position%"
                    navigation:
                      previous: { slot: 37, material: ARROW }
                      next: { slot: 43, material: ARROW }
                  stat_types:
                    slots: "46-52"
                    not_selected_template:
                      material: "%stat_material%"
                      name: "{muted}%stat_name%"
                    selected_template:
                      material: "%stat_material%"
                      name: "{success}%stat_name%"
                      glowing: true
                    navigation:
                      previous: { slot: 45, material: ARROW }
                      next: { slot: 53, material: ARROW }
                """);

        assertEquals(2, menu.sections().size());
        assertNull(menu.section(), "with two lists there is no single one");

        UiSection players = menu.section("players");
        assertNotNull(players);
        assertEquals(14, players.perPage());
        assertEquals(37, players.previous().slot());

        UiSection types = menu.section("stat_types");
        assertNotNull(types);
        assertEquals(7, types.perPage());
        assertEquals(45, types.previous().slot());
    }

    @Test
    @DisplayName("a row can be drawn several ways, named by the file")
    void namedTemplates() {
        UiDefinition menu = load("""
                title: "Effects"
                size: 54
                sections:
                  effects:
                    slots: "19-25"
                    no_permissions_template:
                      material: BARRIER
                    not_selected_template:
                      material: "%effect_icon%"
                    selected_template:
                      material: "%effect_icon%"
                      glowing: true
                """);

        UiSection effects = menu.section("effects");
        assertNotNull(effects);
        assertTrue(effects.hasTemplate("no_permissions"));
        assertTrue(effects.hasTemplate("not_selected"));
        assertTrue(effects.hasTemplate("selected"));
        assertEquals("true", effects.template("selected").item().appearance().glow());
        // A name the file does not declare draws the ordinary row rather than
        // leaving an empty slot, which is far easier to notice and recover from.
        assertNotNull(effects.template("invented_by_a_plugin"));
    }

    @Test
    @DisplayName("item_template is the ordinary row, whatever else the section declares")
    void defaultTemplate() {
        UiDefinition menu = load("""
                title: "Kits"
                size: 54
                sections:
                  kits:
                    slots: "10-16"
                    item_template:
                      material: STONE
                    selected_template:
                      material: DIAMOND
                """);

        UiSection kits = menu.section("kits");
        assertNotNull(kits);
        assertEquals("STONE", assertInstanceOf(Source.OfMaterial.class,
                kits.template(null).item().source()).raw());
    }

    @Test
    @DisplayName("a section with only named templates still has a default")
    void namedTemplatesWithoutAPlainOne() {
        // 31 of the 33 real sections do exactly this: selected_template and
        // not_selected_template, with no item_template between them. Refusing
        // them would refuse every leaderboard in the ecosystem.
        UiDefinition menu = load("""
                title: "Stats"
                size: 54
                sections:
                  types:
                    slots: "46-52"
                    not_selected_template:
                      material: PAPER
                    selected_template:
                      material: PAPER
                      glowing: true
                """);

        UiSection types = menu.section("types");
        assertNotNull(types);
        assertNotNull(types.template(null), "a row that names nothing still draws");
        assertEquals("PAPER", assertInstanceOf(Source.OfMaterial.class,
                types.template(null).item().source()).raw());
    }

    @Test
    @DisplayName("a section with slots and no template loads, for rows that bring their own")
    void sectionWithoutTemplate() {
        // ExyliaSandBox, menus/user/kitroom.yml: a section with slots and a
        // filler and nothing else, whose rows are the stacks the plugin stored.
        UiDefinition menu = load("""
                title: "Kit Room"
                size: 54
                sections:
                  items:
                    slots: "10-16"
                    filler:
                      material: AIR
                """);

        UiSection list = menu.section("items");
        assertNotNull(list);
        assertFalse(list.hasTemplates());
        assertEquals(7, list.perPage());
    }

    @Test
    @DisplayName("a section slot outside the menu is a broken file")
    void sectionSlotOutsideMenu() {
        assertThrows(IllegalArgumentException.class, () -> load("""
                title: "Broken"
                size: 27
                sections:
                  kits:
                    slots: "10-40"
                    item_template:
                      material: STONE
                """));
    }

    @Test
    @DisplayName("page buttons are indexed once, so a click looks them up rather than searching")
    void navigationIsIndexed() {
        UiDefinition menu = load("""
                title: "Leaderboard"
                size: 54
                sections:
                  players:
                    slots: "1-7"
                    item_template: { material: STONE }
                    navigation:
                      previous: { slot: 37, material: ARROW }
                      next: { slot: 43, material: ARROW }
                  types:
                    slots: "46-52"
                    item_template: { material: STONE }
                    navigation:
                      previous: { slot: 45, material: ARROW }
                      next: { slot: 53, material: ARROW }
                """);

        var navigation = menu.navigation();
        assertEquals(4, navigation.size());
        assertEquals("players", navigation.get(37).section());
        assertEquals(-1, navigation.get(37).step());
        assertEquals("types", navigation.get(53).section());
        assertEquals(1, navigation.get(53).step());
    }

    @Test
    @DisplayName("a click in a listed slot knows which list it landed in")
    void sectionAtSlot() {
        UiDefinition menu = load("""
                title: "Leaderboard"
                size: 54
                sections:
                  players:
                    slots: "1-7"
                    item_template: { material: STONE }
                  types:
                    slots: "46-52"
                    item_template: { material: STONE }
                """);

        assertEquals("players", menu.sectionAt(3).id());
        assertEquals("types", menu.sectionAt(50).id());
        assertNull(menu.sectionAt(22), "nothing is listed there");
    }
}
