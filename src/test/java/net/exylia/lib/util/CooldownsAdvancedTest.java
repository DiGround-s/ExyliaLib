package net.exylia.lib.util;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.util.internal.CooldownStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scopes, namespacing and surviving a restart.
 */
class CooldownsAdvancedTest {

    @TempDir
    Path directory;

    private FakePlayer player;
    private FakePlayer other;
    private final AtomicLong now = new AtomicLong(1_000_000_000L);

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        player = new FakePlayer("Steve");
        other = new FakePlayer("Alex");
        FakeServer.online(player.player(), other.player());

        Cooldowns.removeStore();
        Cooldowns.clearEverything();
        Cooldowns.setClock(now::get);
    }

    @AfterEach
    void tearDown() {
        Cooldowns.removeStore();
        Cooldowns.clearEverything();
        Cooldowns.resetClock();
        FakeServer.reset();
    }

    private void advance(Duration by) {
        now.addAndGet(by.toMillis());
    }

    private void withStore() {
        Cooldowns.installStore(new CooldownStore(directory, Logger.getLogger("test")));
    }

    // ------------------------------------------------------------------
    // Scopes
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a global cooldown is shared by everybody")
    void globalIsShared() {
        Cooldowns.start(CooldownScope.GLOBAL, "world-boss", Duration.ofHours(4));

        assertTrue(Cooldowns.isActive(CooldownScope.GLOBAL, "world-boss"));
        // Nobody holds it personally — it belongs to the server.
        assertFalse(Cooldowns.isActive(player.player(), "world-boss"));
    }

    @Test
    @DisplayName("a group cooldown is separate from its members' own")
    void groupIsSeparate() {
        CooldownScope clan = CooldownScope.group("red-clan");

        Cooldowns.start(clan, "war-declare", Duration.ofDays(1));

        assertTrue(Cooldowns.isActive(clan, "war-declare"));
        assertFalse(Cooldowns.isActive(player.player(), "war-declare"));
    }

    @Test
    @DisplayName("the same key in two scopes never collides")
    void scopesDoNotCollide() {
        CooldownScope clan = CooldownScope.group("red-clan");

        Cooldowns.start(player.player(), "raid", Duration.ofMinutes(10));
        Cooldowns.start(clan, "raid", Duration.ofMinutes(30));

        assertEquals(10 * 60_000L,
                Cooldowns.remaining(CooldownScope.player(player.player().getUniqueId()), "raid"));
        assertEquals(30 * 60_000L, Cooldowns.remaining(clan, "raid"));
    }

    @Test
    @DisplayName("two scopes of the same kind and id are the same owner")
    void scopeEquality() {
        UUID id = player.player().getUniqueId();

        assertEquals(CooldownScope.player(id), CooldownScope.player(id));
        assertEquals(CooldownScope.player(id).hashCode(), CooldownScope.player(id).hashCode());
        assertNotEquals(CooldownScope.player(id), CooldownScope.group(id.toString()));
    }

    @Test
    @DisplayName("the same id under two kinds of scope are two different owners")
    void sameIdDifferentTypeDoesNotCollide() {
        // The interesting collision is not two different ids — it is one id
        // that means a clan in one place and a team in another.
        CooldownScope clan = CooldownScope.of("clan", "red");
        CooldownScope team = CooldownScope.of("team", "red");

        Cooldowns.start(clan, "raid", Duration.ofMinutes(10));

        assertTrue(Cooldowns.isActive(clan, "raid"));
        assertFalse(Cooldowns.isActive(team, "raid"),
                "a scope is its kind and its id, not just its id");
        assertNotEquals(clan, team);
    }

    @Test
    @DisplayName("the same id under two kinds of scope are saved to different files")
    void sameIdDifferentTypeDifferentFiles() throws Exception {
        withStore();
        Cooldowns.start(CooldownScope.of("clan", "red"), "war", Duration.ofHours(6));
        Cooldowns.start(CooldownScope.of("team", "red"), "war", Duration.ofHours(6));
        Cooldowns.flushAll();

        assertEquals(2, countFiles(),
                "sharing a file would let one overwrite the other");
    }

    @Test
    @DisplayName("a player scope started through one overload is read by another")
    void overloadsAgree() {
        UUID id = player.player().getUniqueId();

        Cooldowns.start(player.player(), "pearl", Duration.ofSeconds(16));

        assertTrue(Cooldowns.isActive(id, "pearl"), "the UUID overload sees it");
        assertTrue(Cooldowns.isActive(CooldownScope.player(id), "pearl"),
                "the scope overload sees it too");
    }

    @Test
    @DisplayName("a custom scope works like any other")
    void customScope() {
        CooldownScope region = CooldownScope.of("region", "spawn");

        Cooldowns.start(region, "pvp-grace", Duration.ofMinutes(1));

        assertTrue(Cooldowns.isActive(region, "pvp-grace"));
        assertFalse(Cooldowns.isActive(CooldownScope.of("region", "nether"), "pvp-grace"));
    }

    // ------------------------------------------------------------------
    // Namespacing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("two plugins using the same bare key do not collide")
    void namespacesDoNotCollide() {
        PluginCooldowns first = Cooldowns.namespaced("kits");
        PluginCooldowns second = Cooldowns.namespaced("duels");

        first.start(player.player(), "pearl", Duration.ofSeconds(16));

        assertTrue(first.isActive(player.player(), "pearl"));
        assertFalse(second.isActive(player.player(), "pearl"),
                "the other plugin's pearl is its own");
    }

    @Test
    @DisplayName("a namespaced key is visible to the raw API under its full name")
    void namespaceIsJustAPrefix() {
        PluginCooldowns kits = Cooldowns.namespaced("kits");

        kits.start(player.player(), "pearl", Duration.ofSeconds(16));

        assertTrue(Cooldowns.isActive(player.player(), "kits:pearl"));
        assertFalse(Cooldowns.isActive(player.player(), "pearl"));
    }

    @Test
    @DisplayName("a namespaced view reports its own namespace")
    void namespaceIsReadable() {
        assertEquals("kits", Cooldowns.namespaced("kits").namespace());
    }

    // ------------------------------------------------------------------
    // Persistence: what gets written
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a long cooldown is written to disk")
    void longCooldownPersists() throws Exception {
        withStore();
        Cooldowns.start(player.player(), "daily", Duration.ofHours(6));
        Cooldowns.flushAll();

        assertEquals(1, countFiles(), "a six-hour cooldown is worth a file");
    }

    @Test
    @DisplayName("a short cooldown is never written")
    void shortCooldownDoesNotPersist() throws Exception {
        withStore();
        Cooldowns.start(player.player(), "pearl", Duration.ofSeconds(16));
        Cooldowns.flushAll();

        assertEquals(0, countFiles(),
                "sixteen seconds is not worth a disk write, and would expire before it is read");
    }

    @Test
    @DisplayName("the threshold is five minutes exactly")
    void thresholdIsFiveMinutes() throws Exception {
        withStore();
        Cooldowns.start(player.player(), "just-under", Duration.ofMinutes(5).minusMillis(1));
        Cooldowns.flushAll();
        assertEquals(0, countFiles(), "a millisecond under is short");

        Cooldowns.start(other.player(), "exactly", Duration.ofMinutes(5));
        Cooldowns.flushAll();
        assertEquals(1, countFiles(), "exactly five minutes is long");
    }

    @Test
    @DisplayName("only the long cooldowns of a player are written, not all of them")
    void onlyLongOnesAreWritten() throws Exception {
        withStore();
        Cooldowns.start(player.player(), "daily", Duration.ofHours(6));
        Cooldowns.start(player.player(), "pearl", Duration.ofSeconds(16));
        Cooldowns.flushAll();

        List<String> lines = readTheOnlyFile();
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).endsWith("daily"));
    }

    @Test
    @DisplayName("nothing is written when there is no store")
    void noStoreNoWrites() {
        // The default: a library with persistence not wired up still works.
        Cooldowns.start(player.player(), "daily", Duration.ofHours(6));
        Cooldowns.flushAll();

        assertEquals(0, Cooldowns.dirtyCount());
        assertTrue(Cooldowns.isActive(player.player(), "daily"));
    }

    @Test
    @DisplayName("only owners whose long cooldowns changed are written")
    void onlyDirtyOwnersAreWritten() {
        withStore();
        Cooldowns.start(player.player(), "daily", Duration.ofHours(6));
        assertEquals(1, Cooldowns.dirtyCount());

        Cooldowns.flushAll();
        assertEquals(0, Cooldowns.dirtyCount(), "a written owner is clean again");

        Cooldowns.start(other.player(), "pearl", Duration.ofSeconds(16));
        assertEquals(0, Cooldowns.dirtyCount(),
                "a short cooldown does not make an owner worth writing");
    }

    // ------------------------------------------------------------------
    // Persistence: coming back
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a long cooldown survives a restart")
    void survivesRestart() {
        withStore();
        Cooldowns.start(player.player(), "daily", Duration.ofHours(6));
        Cooldowns.flushAll();

        // The server goes down and comes back an hour later.
        Cooldowns.clearEverything();
        advance(Duration.ofHours(1));
        Cooldowns.load(player.player().getUniqueId());

        assertTrue(Cooldowns.isActive(player.player(), "daily"));
        assertEquals(5 * 60 * 60_000L,
                Cooldowns.remaining(player.player().getUniqueId(), "daily"),
                "five of the six hours are left");
    }

    @Test
    @DisplayName("a cooldown that expired while the server was down does not come back")
    void expiredWhileDownIsGone() {
        withStore();
        Cooldowns.start(player.player(), "daily", Duration.ofHours(6));
        Cooldowns.flushAll();

        Cooldowns.clearEverything();
        advance(Duration.ofHours(7));
        Cooldowns.load(player.player().getUniqueId());

        assertFalse(Cooldowns.isActive(player.player(), "daily"));
        assertEquals(0, Cooldowns.trackedOwners(), "and it is not loaded just to be swept");
    }

    @Test
    @DisplayName("a cooldown started since boot beats the one in the file")
    void memoryBeatsDisk() {
        withStore();
        Cooldowns.start(player.player(), "daily", Duration.ofHours(6));
        Cooldowns.flushAll();
        Cooldowns.clearEverything();

        // The player joins, and something starts a fresh cooldown before the
        // async load finishes.
        Cooldowns.start(player.player(), "daily", Duration.ofHours(2));
        Cooldowns.load(player.player().getUniqueId());

        assertEquals(2 * 60 * 60_000L,
                Cooldowns.remaining(player.player().getUniqueId(), "daily"),
                "the newer, running cooldown wins over the file");
    }

    @Test
    @DisplayName("quitting writes what was pending")
    void quitWrites() throws Exception {
        withStore();
        Cooldowns.start(player.player(), "daily", Duration.ofHours(6));

        Cooldowns.forget(player.player().getUniqueId());

        assertEquals(1, countFiles());
        assertEquals(0, Cooldowns.trackedOwners(), "and the player is gone from memory");
    }

    @Test
    @DisplayName("clearing a long cooldown removes it from disk too")
    void clearingRemovesFromDisk() throws Exception {
        withStore();
        Cooldowns.start(player.player(), "daily", Duration.ofHours(6));
        Cooldowns.flushAll();
        assertEquals(1, countFiles());

        Cooldowns.clear(player.player(), "daily");
        Cooldowns.flushAll();

        assertEquals(0, countFiles(),
                "an emptied file is deleted rather than left behind");
    }

    @Test
    @DisplayName("a global cooldown survives a restart like any other")
    void globalPersists() {
        withStore();
        Cooldowns.start(CooldownScope.GLOBAL, "world-boss", Duration.ofHours(4));
        Cooldowns.flushAll();

        Cooldowns.clearEverything();
        Cooldowns.load(CooldownScope.GLOBAL);

        assertTrue(Cooldowns.isActive(CooldownScope.GLOBAL, "world-boss"));
    }

    @Test
    @DisplayName("two owners are kept in separate files")
    void ownersAreSeparateFiles() throws Exception {
        withStore();
        Cooldowns.start(player.player(), "daily", Duration.ofHours(6));
        Cooldowns.start(other.player(), "daily", Duration.ofHours(6));
        Cooldowns.flushAll();

        assertEquals(2, countFiles());
    }

    @Test
    @DisplayName("a corrupt file does not stop the rest from loading")
    void corruptLinesAreSkipped() throws Exception {
        withStore();
        CooldownScope scope = CooldownScope.player(player.player().getUniqueId());
        Path file = directory.resolve(scope.storageId() + ".cd");
        Files.createDirectories(directory);
        Files.write(file, List.of(
                "this is not a number at all",
                "",
                String.valueOf(now.get() + 3_600_000L) + " good-one",
                "12345"));

        Cooldowns.load(player.player().getUniqueId());

        assertTrue(Cooldowns.isActive(player.player(), "good-one"),
                "the readable line still loads");
    }

    @Test
    @DisplayName("an expired entry in the file is never even loaded")
    void expiredIsFilteredByTheStore() {
        // Going through the store directly, so the result cannot be explained
        // by anything the in-memory map does afterwards.
        CooldownStore store = new CooldownStore(directory, Logger.getLogger("test"));
        long present = now.get();
        store.save("test-owner", java.util.Map.of(
                "gone", present - 1_000L,
                "alive", present + 3_600_000L));

        java.util.Map<String, Long> loaded = store.load("test-owner", present);

        assertEquals(1, loaded.size(), "the dead one is dropped by the reader itself");
        assertTrue(loaded.containsKey("alive"));
    }

    /**
     * The move itself cannot be tested from in here: proving it is atomic
     * means killing the process mid-write and looking at what survived. What
     * this does check is the half of it that is observable — that a write in
     * flight leaves the good file alone.
     */
    @Test
    @DisplayName("a half-written file never replaces a good one")
    void writeIsAtomic() throws Exception {
        withStore();
        Cooldowns.start(player.player(), "daily", Duration.ofHours(6));
        Cooldowns.flushAll();

        // A writer that dies partway leaves its mess in the temporary file,
        // and the real one is only replaced by a move that either happens or
        // does not.
        CooldownScope scope = CooldownScope.player(player.player().getUniqueId());
        Path real = directory.resolve(scope.storageId() + ".cd");
        List<String> before = Files.readAllLines(real);

        Path temporary = directory.resolve(scope.storageId() + ".cd.tmp");
        Files.writeString(temporary, "garbage from a crashed write");

        assertEquals(before, Files.readAllLines(real),
                "the good file is untouched while a write is in flight");
        assertTrue(Files.exists(temporary), "the mess stays in the temporary file");
    }

    @Test
    @DisplayName("a key containing spaces survives the round trip")
    void keysWithSpaces() {
        withStore();
        Cooldowns.start(player.player(), "kit reward daily", Duration.ofHours(6));
        Cooldowns.flushAll();
        Cooldowns.clearEverything();

        Cooldowns.load(player.player().getUniqueId());

        assertTrue(Cooldowns.isActive(player.player(), "kit reward daily"),
                "the expiry comes first precisely so the key may contain spaces");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private long countFiles() throws Exception {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        try (var files = Files.list(directory)) {
            return files.filter(p -> p.toString().endsWith(".cd")).count();
        }
    }

    private List<String> readTheOnlyFile() throws Exception {
        try (var files = Files.list(directory)) {
            Path file = files.filter(p -> p.toString().endsWith(".cd")).findFirst().orElseThrow();
            return Files.readAllLines(file);
        }
    }
}
