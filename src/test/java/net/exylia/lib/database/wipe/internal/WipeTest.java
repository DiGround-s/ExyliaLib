package net.exylia.lib.database.wipe.internal;

import net.exylia.lib.FakeServer;
import net.exylia.lib.database.Column;
import net.exylia.lib.database.DataKind;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Owner;
import net.exylia.lib.database.Repository;
import net.exylia.lib.database.Table;
import net.exylia.lib.database.TestDatabases;
import net.exylia.lib.database.transfer.Transfers;
import net.exylia.lib.task.Tasks;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wipes and player resets against a real H2 database: what a preset picks,
 * what an owner delete removes, and what it must never touch.
 */
class WipeTest {

    private static final AtomicInteger DATABASE = new AtomicInteger();

    @Table(value = "wipe_homes", data = DataKind.PROGRESS)
    record Home(@Id String id, @Owner @Column UUID player, @Column String name) {
    }

    @Table(value = "wipe_unlocks", data = DataKind.ACCOUNT)
    record Unlock(@Id @Owner String player, @Column int count) {
    }

    @Table("wipe_notes")
    record Note(@Id String id, @Column UUID player) {
    }

    @Table(value = "wipe_warps", data = DataKind.SETUP)
    record Warp(@Id String id, @Column String world) {
    }

    /** Shaped like the library's shared tables: every plugin's rows side by side. */
    @Table(value = "wipe_shared", data = DataKind.HISTORY)
    record Shared(@Id String id, @Column String plugin, @Owner @Column UUID player) {
    }

    private Plugin plugin;
    private Repository<Home> homes;
    private Repository<Unlock> unlocks;
    private Repository<Note> notes;
    private Repository<Warp> warps;
    private final UUID steve = UUID.randomUUID();
    private final UUID alex = UUID.randomUUID();

    @TempDir
    Path folder;

    @BeforeAll
    static void server() {
        FakeServer.install();
    }

    @BeforeEach
    void open() {
        FakeServer.reset();
        FakeServer.runAsyncForReal();
        plugin = FakeServer.newPlugin("Survival");
        TestDatabases.memory(plugin, "wipe" + DATABASE.incrementAndGet());
        Transfers.init(plugin);
        homes = Databases.of(plugin).repository(Home.class);
        unlocks = Databases.of(plugin).repository(Unlock.class);
        notes = Databases.of(plugin).repository(Note.class);
        warps = Databases.of(plugin).repository(Warp.class);
        join(homes.saveAll(List.of(new Home("a", steve, "base"), new Home("b", alex, "farm"))));
        join(unlocks.saveAll(List.of(new Unlock(steve.toString(), 3), new Unlock(alex.toString(), 1))));
        join(notes.saveAll(List.of(new Note("n", steve))));
        join(warps.saveAll(List.of(new Warp("spawn", "world"))));
    }

    @AfterEach
    void close() {
        WipeRuntime.release();
        Transfers.releaseAll();
        Databases.releaseAll();
        Tasks.releaseAll();
        FakeServer.reset();
    }

    private static <T> T join(CompletableFuture<T> future) {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private WipeTable table(Repository<?> repository) {
        return WipeTable.of("Survival", Databases.find("Survival").location(), repository);
    }

    // ------------------------------------------------------------ declarations

    @Test
    @DisplayName("a table reads its kind and owner; an unannotated one is unclassified")
    void declarationsAreRead() {
        WipeTable home = table(homes);
        assertEquals(DataKind.PROGRESS, home.kind());
        assertEquals("player", home.owner());
        assertFalse(home.ownerText());
        assertTrue(table(unlocks).ownerText(), "a String owner stores the uuid as text");
        assertEquals(DataKind.UNSPECIFIED, table(notes).kind());
        assertNull(table(notes).owner());
    }

    @Test
    @DisplayName("two views of one table are one entry; disagreeing kinds leave it unclassified")
    void mergeDedupes() {
        WipeTable a = new WipeTable("A", "A", "h2|db", "stats", DataKind.PROGRESS, null, false, null, null);
        WipeTable b = new WipeTable("B", "B", "h2|db", "STATS", DataKind.PROGRESS, null, false, null, null);
        WipeTable elsewhere = new WipeTable("C", "C", "h2|other", "stats", DataKind.PROGRESS, null, false, null, null);
        assertEquals(2, WipeRuntime.merge(List.of(b, a, elsewhere), warning -> { }).size(),
                "one per physical table: same location once, another database separately");

        List<String> warnings = new ArrayList<>();
        WipeTable clash = new WipeTable("B", "B", "h2|db", "stats", DataKind.HISTORY, null, false, null, null);
        List<WipeTable> merged = WipeRuntime.merge(List.of(a, clash), warnings::add);
        assertEquals(1, merged.size());
        assertEquals(DataKind.UNSPECIFIED, merged.get(0).kind());
        assertEquals(1, warnings.size());

        WipeTable tenantA = new WipeTable("A", "A", "h2|db", "exylia_logs", DataKind.HISTORY, null, false, "A", null);
        WipeTable tenantB = new WipeTable("B", "B", "h2|db", "exylia_logs", DataKind.HISTORY, null, false, "B", null);
        assertEquals(2, WipeRuntime.merge(List.of(tenantA, tenantB), warning -> { }).size(),
                "a shared library table holds each plugin's rows apart");
    }

    // ------------------------------------------------------------ presets

    @Test
    @DisplayName("presets turn on their kinds and never unclassified or setup tables")
    void presetsResolveToKinds() {
        WipeRequest season = new WipeRequest(WipeRequest.Preset.SEASON, null, null, null, null, null);
        assertEquals(java.util.EnumSet.of(DataKind.PROGRESS, DataKind.WORLD, DataKind.CACHE), season.kinds());
        assertFalse(season.toggle(DataKind.UNSPECIFIED), "an unclassified table is never in a season");
        assertFalse(season.toggle(DataKind.SETUP));
        assertTrue(season.toggle(DataKind.HISTORY));

        season.tables(List.of(table(homes), table(notes), table(warps), table(unlocks)));
        assertEquals(List.of("wipe_homes"), season.targets().stream().map(WipeTable::table).toList());

        WipeRequest players = new WipeRequest(WipeRequest.Preset.PLAYERS, null, null, null, null, null);
        assertEquals(java.util.EnumSet.of(DataKind.PROGRESS, DataKind.CACHE), players.kinds());
        assertFalse(players.toggle(DataKind.WORLD));

        WipeRequest one = new WipeRequest(WipeRequest.Preset.PLUGIN, null, null, null, "Survival", null);
        one.tables(List.of(table(homes), table(notes), table(warps)));
        assertEquals(3, one.targets().size(), "one plugin takes every kind, unclassified and setup too");
        one.toggle("Survival");
        assertTrue(one.targets().isEmpty(), "a plugin left out loses nothing");
    }

    @Test
    @DisplayName("a player reset only reaches tables with an owner")
    void playerResetNeedsAnOwner() {
        WipeRequest reset = WipeRequest.player(steve, "Steve", null, List.of("@account"));
        reset.tables(List.of(table(homes), table(unlocks), table(notes)));
        assertEquals(List.of("wipe_unlocks"), reset.targets().stream().map(WipeTable::table).toList());
        assertEquals(1, WipePanels.skipped(reset), "the table without an owner is counted as skipped");
        assertThrows(IllegalArgumentException.class,
                () -> WipeRequest.player(steve, "Steve", null, List.of("@setup")));
    }

    @Test
    @DisplayName("the command's words: plugins, presets, kinds and the old table syntax")
    void commandWords() {
        assertEquals(WipeRequest.Preset.SEASON, WipeRequest.parse("all", "@season").preset());
        WipeRequest kind = WipeRequest.parse("Survival", "@progress");
        assertEquals("Survival", kind.only());
        assertEquals(java.util.EnumSet.of(DataKind.PROGRESS), kind.kinds());
        assertNull(WipeRequest.parse("Survival", "wipe_homes"), "a plugin and a table is the old path");
        assertNull(WipeRequest.parse("Survival", "all"), "a plugin and all is the old path");
        WipeRequest everything = WipeRequest.parse("*", "all");
        assertEquals(java.util.EnumSet.allOf(DataKind.class), everything.kinds());
        assertNull(everything.only());
        assertEquals("wipe_homes", WipeRequest.parse("all", "wipe_homes").table());
        assertThrows(IllegalArgumentException.class, () -> WipeRequest.parse("all", "@nope"));
    }

    // ------------------------------------------------------------ deleting

    @Test
    @DisplayName("an owner delete removes that player's rows and nobody else's")
    void ownerDelete() {
        assertEquals(1L, WipeRuntime.count(table(homes), steve));
        assertEquals(1L, WipeRuntime.delete(table(homes), steve));
        assertEquals(1L, WipeRuntime.delete(table(unlocks), steve), "a text owner column matches too");

        assertTrue(join(homes.find("b")).isPresent(), "alex keeps his home");
        assertTrue(join(homes.find("a")).isEmpty());
        assertTrue(join(unlocks.find(alex.toString())).isPresent());
        assertTrue(join(unlocks.find(steve.toString())).isEmpty());
    }

    @Test
    @DisplayName("a shared table loses only the wiping plugin's rows")
    void tenantDelete() {
        Repository<Shared> shared = Databases.of(plugin).repository(Shared.class);
        join(shared.saveAll(List.of(new Shared("1", "Survival", steve), new Shared("2", "Practice", steve))));
        assertNull(table(shared).tenant(), "only the library's own tables are shared by plugin column");
        WipeTable own = table(shared);
        // As the library lists its own shared tables: one entry per plugin, its rows only.
        WipeTable entry = new WipeTable("Survival", "Survival", own.location(), own.table(), own.kind(),
                own.owner(), false, "Survival", shared);

        assertEquals(1L, WipeRuntime.delete(entry, null));
        assertTrue(join(shared.find("2")).isPresent(), "another plugin's rows stay");
    }

    @Test
    @DisplayName("a season wipe backs up, empties its kinds, freezes them and leaves the rest")
    void seasonWipe() {
        WipeRequest season = new WipeRequest(WipeRequest.Preset.SEASON, null, null, null, null, null);
        join(WipeRuntime.plan(season));
        WipeTable home = season.tables().stream().filter(t -> t.table().equals("wipe_homes")).findFirst().orElseThrow();
        assertEquals(2L, season.count(home));

        CompletableFuture<WipeRuntime.Outcome> done = new CompletableFuture<>();
        WipeRuntime.run(season, console(), folder, done::complete);
        WipeRuntime.Outcome outcome = join(done);

        assertNull(outcome.failure(), String.valueOf(outcome.failure()));
        assertEquals(2L, outcome.rows());
        assertEquals(1, outcome.backups().size(), "one dump for the one plugin");
        assertTrue(Files.exists(outcome.backups().get(0)));
        assertEquals(0L, join(homes.count()));
        assertEquals(2L, join(unlocks.count()), "an account table survives a season");
        assertEquals(1L, join(notes.count()), "an unclassified table is never touched by a preset");
        assertEquals(1L, join(warps.count()), "setup survives");
        assertTrue(homes.frozen(), "the emptied table ignores writes until the restart");
        assertFalse(unlocks.frozen());
        assertTrue(WipeRuntime.serverLocked());
        assertTrue(WipePanels.result(season, outcome).contains("Survival"));
    }

    @Test
    @DisplayName("a failed backup cancels the wipe before anything is deleted")
    void failedBackupCancels() throws IOException {
        Path blocked = folder.resolve("file");
        Files.writeString(blocked, "not a folder");
        WipeRequest season = new WipeRequest(WipeRequest.Preset.SEASON, null, null, null, null, null);
        CompletableFuture<WipeRuntime.Outcome> done = new CompletableFuture<>();
        WipeRuntime.run(season, console(), blocked.resolve("dumps"), done::complete);

        assertNotNull(join(done).failure());
        assertEquals(2L, join(homes.count()));
        assertFalse(homes.frozen());
    }

    // ------------------------------------------------------------ discovery

    @Test
    @DisplayName("a jar scan finds the @Table records and nothing else")
    void jarScan() throws IOException {
        Path jar = folder.resolve("plugin.jar");
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Class<?> type : List.of(Home.class, Warp.class, WipeTest.class)) {
                String name = type.getName().replace('.', '/') + ".class";
                zip.putNextEntry(new ZipEntry(name));
                try (InputStream in = type.getClassLoader().getResourceAsStream(name)) {
                    in.transferTo(zip);
                }
                zip.closeEntry();
            }
        }
        List<Class<?>> found = WipeRuntime.tableRecords(jar, getClass().getClassLoader());
        assertEquals(2, found.size(), "got " + found);
        assertTrue(found.contains(Home.class) && found.contains(Warp.class));
    }

    private static CommandSender console() {
        return FakeServer.consoleSender();
    }
}
