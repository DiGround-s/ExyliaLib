package net.exylia.lib.modes.internal;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.modes.Mode;
import net.exylia.lib.modes.Modes;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The modes catalog: shared categories, order, release with the owner, live cards and the screens. */
class ModesTest {

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path folder;

    private Plugin tables;
    private Plugin events;
    private FakePlayer steve;

    @BeforeAll
    static void install() {
        FakeServer.install();
        FakeServer.packageMainResources("lang");
        ModesMenu.anchor(FakeServer.class);
        net.exylia.lib.settings.internal.SettingsMenu.anchor(FakeServer.class);
    }

    @BeforeEach
    void open() {
        FakeServer.reset();
        tables = FakeServer.newPlugin("Tables");
        events = FakeServer.newPlugin("Events");
        steve = new FakePlayer("Steve");
    }

    @AfterEach
    void close() {
        Modes.releaseAll();
        FakeServer.reset();
    }

    private static Mode mode(String id) {
        return Mode.of(id, player -> { }).category("games").name(id).build();
    }

    @Test
    @DisplayName("plugins share a category, which sorts by order; one nobody described comes last")
    void categories() {
        Modes.category(tables, "games", "OAK_SIGN", "Games", 20);
        Modes.category(events, "events", "BELL", "Events", 10);
        Modes.register(tables, mode("chess"));
        Modes.register(events, mode("checkers"));
        Modes.register(events, Mode.of("koth", player -> { }).category("events").build());
        Modes.register(events, Mode.of("spleef", player -> { }).category("Minigames").icon("SNOWBALL").build());

        List<Modes.Category> categories = Modes.categories();
        assertEquals(List.of("events", "games", "minigames"), categories.stream().map(Modes.Category::id).toList());
        assertEquals("SNOWBALL", categories.get(2).icon());
        assertEquals(List.of("checkers", "chess"), Modes.modes("games").stream().map(Mode::id).toList());
        assertEquals(List.of("koth", "checkers", "chess", "spleef"), Modes.modes().stream().map(Mode::id).toList());
    }

    @Test
    @DisplayName("an id registered again replaces the mode, and a disabled plugin takes only its own with it")
    void release() {
        Modes.category(tables, "games", "OAK_SIGN", "Games", 0);
        Modes.register(tables, mode("chess"));
        Modes.register(tables, Mode.of("chess", player -> { }).category("games").name("Chess 2").build());
        Modes.register(events, mode("chess"));
        assertEquals(2, Modes.modes().size());
        assertTrue(Modes.modes().stream().anyMatch(mode -> mode.name().equals("Chess 2")));

        Modes.release(tables);
        assertEquals(1, Modes.modes().size());
        assertEquals("games", Modes.categories().getFirst().name(), "the description went with its plugin");

        Modes.unregister(events, "chess");
        assertTrue(Modes.categories().isEmpty());
    }

    @Test
    @DisplayName("a card reads its state and players live, and offers a click only when it can be played")
    void cards() {
        AtomicBoolean open = new AtomicBoolean(true);
        AtomicInteger playing = new AtomicInteger(3);
        Mode chess = Mode.of("chess", player -> { }).description("Checkmate the king.")
                .open(open::get).players(playing::get).build();
        Mode locked = Mode.of("vip", player -> { }).permission("modes.vip").build();

        String lore = ModesMenu.lore(chess, chess.allowed(steve.player()), chess.isOpen());
        assertTrue(lore.contains("{success}Open") && lore.contains("{info}3") && lore.contains("Click to play"), lore);
        open.set(false);
        playing.set(5);
        lore = ModesMenu.lore(chess, true, chess.isOpen());
        assertTrue(lore.contains("{error}Closed") && lore.contains("{info}5") && !lore.contains("Click to play"), lore);

        assertFalse(locked.allowed(steve.player()));
        lore = ModesMenu.lore(locked, false, locked.isOpen());
        assertTrue(lore.contains("{error}Locked") && !lore.contains("Playing"), lore);
        steve.grant("modes.vip");
        assertTrue(locked.allowed(steve.player()));

        assertEquals(-1, ModesMenu.playing(List.of(locked)));
        assertEquals(5, ModesMenu.playing(List.of(locked, chess)));
    }

    @Test
    @DisplayName("every screen compiles; the hub centres its cards and a root list has no BACK")
    void screens() {
        List<String> messages = net.exylia.lib.debug.DebugCapture.start();
        try {
            Plugin library = FakeServer.newPlugin("ExyliaLib", folder.resolve("lib").toFile());
            ModesMenu.init(library, "exylialib");
            var menus = net.exylia.lib.ui.Menus.of(library, "exylialib");
            ModesMenu.load(menus);
            for (int count = 1; count <= ModesMenu.ROW; count++) {
                assertTrue(menus.definition(ModesMenu.HUB + count).isPresent());
            }
            assertTrue(menus.definition(ModesMenu.LIST).isPresent());
            assertTrue(menus.definition(ModesMenu.ROOT_LIST).isPresent());
            assertTrue(messages.isEmpty(), messages::toString);

            YamlConfiguration list = net.exylia.lib.settings.internal.SettingsMenu.read(library, ModesMenu.FOLDER,
                    ModesMenu.LIST);
            assertEquals(49, list.getInt("items.back.slot"));
            assertFalse(ModesMenu.withoutBack(list).contains("items.back"));
            assertEquals(ModesMenu.HUB + 3, ModesMenu.layout(3));
            assertEquals(ModesMenu.ROOT_LIST, ModesMenu.layout(8));
            assertEquals("20,22,24", net.exylia.lib.settings.internal.SettingsMenu.centred(
                    net.exylia.lib.settings.internal.SettingsMenu.read(library, ModesMenu.FOLDER, "modes"), 18, 3)
                    .getString("pagination.slots"));
        } finally {
            ModesMenu.release();
            net.exylia.lib.ui.Menus.releaseAll();
            net.exylia.lib.action.Actions.releaseAll();
            net.exylia.lib.debug.DebugCapture.stop();
        }
    }
}
