package net.exylia.lib.settings.internal;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.TestDatabases;
import net.exylia.lib.settings.Broadcasts;
import net.exylia.lib.settings.Setting;
import net.exylia.lib.settings.Settings;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Text;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Player settings: read from memory, stored only when they differ from the
 * default, shared by the network unless per server, and the menu's layouts.
 */
class SettingsTest {

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path folder;

    private static final AtomicInteger DATABASE = new AtomicInteger();

    private Plugin plugin;
    private FakePlayer steve;

    @BeforeAll
    static void install() {
        FakeServer.install();
        FakeServer.packageMainResources("lang");
        net.exylia.lib.settings.internal.SettingsMenu.anchor(FakeServer.class);
    }

    @BeforeEach
    void open() {
        FakeServer.reset();
        FakeServer.runAsyncForReal();
        steve = new FakePlayer("Steve");
        FakeServer.online(steve.player());
        plugin = FakeServer.newPlugin("Shop");
        TestDatabases.memory(plugin, "settings" + DATABASE.incrementAndGet());
    }

    @AfterEach
    void close() {
        SettingsRuntime.releaseAll();
        Databases.releaseAll();
        Tasks.releaseAll();
        FakeServer.reset();
    }

    private static void waitLoaded(SettingsRuntime.PluginStore store, UUID player) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!store.isLoaded(player)) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("never loaded");
            Thread.onSpinWait();
        }
    }

    private static void settle(SettingsRuntime.PluginStore store, UUID player) {
        try {
            store.writesForTests(player).get(30, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Setting trades() {
        return Setting.toggle("trades", true).build();
    }

    private SettingsRuntime.PluginStore store(Plugin owner, String server) {
        SettingsRuntime.PluginStore store = SettingsRuntime.store(owner);
        store.serverIdForTests(server);
        return store;
    }

    @Test
    @DisplayName("a changed value survives a relog, and setting it back to the default deletes the row")
    void survivesRelog() {
        SettingsRuntime.PluginStore store = store(plugin, "lobby");
        Settings.register(plugin, trades());
        UUID id = steve.player().getUniqueId();
        waitLoaded(store, id);

        assertTrue(Settings.enabled(steve.player(), plugin, "trades"));
        assertTrue(Settings.toggle(steve.player(), plugin, "trades"));
        assertFalse(Settings.enabled(steve.player(), plugin, "trades"));
        settle(store, id);

        SettingsRuntime.left(id);
        assertTrue(Settings.enabled(steve.player(), plugin, "trades"), "a player not read yet has the default");
        store.openForTests(id);
        waitLoaded(store, id);
        assertFalse(Settings.enabled(steve.player(), plugin, "trades"));

        assertTrue(Settings.set(steve.player(), plugin, "trades", "true"));
        settle(store, id);
        long rows = Databases.of(plugin).repository(StoredSetting.class).count().join();
        assertEquals(0, rows, "the default is never stored");
    }

    @Test
    @DisplayName("a network value follows the player to another server; a per-server one does not")
    void perServer() {
        SettingsRuntime.PluginStore lobby = store(plugin, "lobby");
        Settings.register(plugin, trades());
        Settings.register(plugin, Setting.number("view", 8, 2, 16, 1).perServer().build());
        UUID id = steve.player().getUniqueId();
        waitLoaded(lobby, id);
        Settings.set(steve.player(), plugin, "trades", "false");
        Settings.set(steve.player(), plugin, "view", "12");
        settle(lobby, id);
        SettingsRuntime.left(id);

        Plugin survival = FakeServer.newPlugin("Shop");
        SettingsRuntime.PluginStore other = store(survival, "survival");
        Settings.register(survival, trades());
        Settings.register(survival, Setting.number("view", 8, 2, 16, 1).perServer().build());
        waitLoaded(other, id);

        assertFalse(Settings.enabled(steve.player(), survival, "trades"));
        assertEquals(8, Settings.number(steve.player(), survival, "view"));
    }

    @Test
    @DisplayName("a value set while the read was out is not undone by the read")
    void writeBeforeLoadWins() {
        SettingsRuntime.PluginStore store = store(plugin, "lobby");
        Settings.register(plugin, trades());
        UUID id = steve.player().getUniqueId();
        waitLoaded(store, id);
        Settings.set(steve.player(), plugin, "trades", "false");
        settle(store, id);

        SettingsRuntime.left(id);
        store.openForTests(id);
        Settings.set(steve.player(), plugin, "trades", "true");
        waitLoaded(store, id);
        assertTrue(Settings.enabled(steve.player(), plugin, "trades"));
    }

    @Test
    @DisplayName("a value set for a player who is away is there on their next join")
    void offlineSet() {
        SettingsRuntime.PluginStore store = store(plugin, "lobby");
        Settings.register(plugin, trades());
        UUID id = steve.player().getUniqueId();
        waitLoaded(store, id);
        SettingsRuntime.left(id);

        assertTrue(Settings.set(id, plugin, "trades", "false"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (Databases.of(plugin).repository(StoredSetting.class).count().join() == 0) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("never written");
            Thread.onSpinWait();
        }
        store.openForTests(id);
        waitLoaded(store, id);
        assertFalse(Settings.enabled(steve.player(), plugin, "trades"));
    }

    @Test
    @DisplayName("a setting kept by the plugin is read and written through its store")
    void customStore() {
        AtomicReference<String> kept = new AtomicReference<>("false");
        Settings.register(plugin, Setting.toggle("tpa", true).store(new Setting.Store() {
            @Override
            public String get(Player player) {
                return kept.get();
            }

            @Override
            public void set(Player player, String value) {
                kept.set(value);
            }
        }).build());

        assertFalse(Settings.enabled(steve.player(), plugin, "tpa"));
        assertFalse(Settings.set(steve.player().getUniqueId(), plugin, "tpa", "true"), "the plugin's own store needs the player");
        assertTrue(Settings.toggle(steve.player(), plugin, "tpa"));
        assertEquals("true", kept.get());
    }

    @Test
    @DisplayName("without the permission the setting reads its default")
    void permission() {
        AtomicReference<String> kept = new AtomicReference<>("false");
        Settings.register(plugin, Setting.toggle("autosell", true).permission("shop.autosell")
                .store(new Setting.Store() {
                    @Override
                    public String get(Player player) {
                        return kept.get();
                    }

                    @Override
                    public void set(Player player, String value) {
                        kept.set(value);
                    }
                }).build());
        assertTrue(Settings.enabled(steve.player(), plugin, "autosell"));
        steve.grant("shop.autosell");
        assertFalse(Settings.enabled(steve.player(), plugin, "autosell"));
    }

    @Test
    @DisplayName("values are checked: choices wrap, numbers are clamped, nonsense is refused")
    void values() {
        Setting choice = Setting.choice("mode", "all", "all", "friends", "none").build();
        assertEquals("none", choice.next("all", -1));
        assertEquals("all", choice.next("none", 1));
        assertNull(choice.normalise("everyone"));

        Setting number = Setting.number("view", 8, 2, 16, 4).build();
        assertEquals("16", number.next("14", 1));
        assertEquals("2", number.normalise("-5"));
        assertNull(number.normalise("far"));

        assertNull(Setting.toggle("x", true).build().normalise("yes"));
        assertThrows(IllegalArgumentException.class, () -> Setting.choice("y", "maybe", "on", "off").build());
        assertThrows(IllegalArgumentException.class, () -> Setting.toggle("Bad Key", true));
        assertFalse(Settings.set(steve.player(), plugin, "unknown", "true"));
        assertFalse(Settings.set(UUID.randomUUID(), plugin, "unknown", "true"));
    }

    @Test
    @DisplayName("a broadcast reaches only the players who left its channel on")
    void broadcastsFilter() {
        FakePlayer alex = new FakePlayer("Alex");
        FakeServer.online(steve.player(), alex.player());
        SettingsRuntime.PluginStore store = store(plugin, "lobby");
        Broadcasts.Channel deaths = Broadcasts.channel(plugin, "deaths", "SKELETON_SKULL", "Deaths");
        waitLoaded(store, steve.player().getUniqueId());
        waitLoaded(store, alex.player().getUniqueId());
        Settings.set(alex.player(), plugin, "deaths", "false");

        Broadcasts.send(deaths, Text.of("Somebody died"));

        assertEquals(1, steve.messages().size());
        assertTrue(alex.messages().isEmpty());
        assertTrue(store.hasAnnouncements());
        assertTrue(store.categories().isEmpty(), "channels are not regular settings");
    }

    @Test
    @DisplayName("every screen compiles into the library's menus with nothing reported")
    void screensCompile() {
        List<String> messages = net.exylia.lib.debug.DebugCapture.start();
        try {
            Plugin library = FakeServer.newPlugin("ExyliaLib", folder.resolve("lib").toFile());
            SettingsMenu.init(library, "exylialib");
            var menus = net.exylia.lib.ui.Menus.of(library, "exylialib");
            SettingsMenu.load(menus);
            assertTrue(menus.definition(SettingsMenu.ROOT).isPresent());
            assertTrue(menus.definition(SettingsMenu.LIST).isPresent());
            assertTrue(menus.definition(SettingsMenu.HUB + 7).isPresent());
            assertTrue(menus.definition(SettingsMenu.PAGE + 1).isPresent());
            assertTrue(messages.isEmpty(), messages::toString);
        } finally {
            SettingsMenu.release();
            net.exylia.lib.ui.Menus.releaseAll();
            net.exylia.lib.action.Actions.releaseAll();
            net.exylia.lib.debug.DebugCapture.stop();
        }
    }

    @Test
    @DisplayName("the screens land in the library's folder, and a reload keeps the owner's edits")
    void screensAreFiles() throws Exception {
        Plugin library = FakeServer.newPlugin("ExyliaLib", folder.resolve("lib").toFile());
        try {
            SettingsMenu.init(library, "exylialib");
            var menus = net.exylia.lib.ui.Menus.of(library, "exylialib");
            SettingsMenu.load(menus);
            java.io.File root = new java.io.File(library.getDataFolder(), "lang/en/menus/settings/settings.yml");
            assertTrue(root.isFile());

            YamlConfiguration edited = YamlConfiguration.loadConfiguration(root);
            edited.set("items.settings.name", "{accent}&lMINE");
            edited.set("items.announcements", null);
            edited.save(root);
            menus.unload();
            SettingsMenu.load(menus);

            YamlConfiguration after = YamlConfiguration.loadConfiguration(root);
            assertEquals("{accent}&lMINE", after.getString("items.settings.name"), "an edit survives");
            assertEquals(27, SettingsMenu.read(library, SettingsMenu.ROOT).getInt("size"));
        } finally {
            SettingsMenu.release();
            net.exylia.lib.ui.Menus.releaseAll();
            net.exylia.lib.action.Actions.releaseAll();
        }
    }

    @Test
    @DisplayName("every screen compiles, and cards are centred on the suite's grid")
    void screens() {
        Plugin library = FakeServer.newPlugin("ExyliaLib", folder.resolve("lib").toFile());
        YamlConfiguration hubFile = SettingsMenu.read(library, "settings_hub");
        YamlConfiguration pageFile = SettingsMenu.read(library, "settings_page");
        assertEquals("0-35", SettingsMenu.read(library, SettingsMenu.LIST).getString("pagination.slots"));
        for (int count = 1; count <= SettingsMenu.ROW; count++) {
            YamlConfiguration hub = SettingsMenu.centred(hubFile, 18, count);
            assertEquals(count, hub.getString("pagination.slots").split(",").length);
            assertEquals(4, hub.getInt("items.header.slot"));
            assertEquals(45, hub.getInt("size"));
        }
        assertEquals("11,13,15", SettingsMenu.centred(pageFile, 9, 3).getString("pagination.slots"));
        assertEquals("10,12,14,16", SettingsMenu.centred(pageFile, 9, 4).getString("pagination.slots"));
        assertEquals(SettingsMenu.LIST, SettingsMenu.layout(SettingsMenu.PAGE, 8));
        assertEquals(SettingsMenu.LIST, SettingsMenu.layout(SettingsMenu.PAGE, 0));
        assertEquals(List.of(4), SettingsMenu.columns(1));
    }
}
