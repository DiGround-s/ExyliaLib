package net.exylia.lib.player;

import net.exylia.lib.player.internal.PlayerRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The directory, with no server at all.
 *
 * <p>Every tier that needs one is guarded, so what is left is the part with
 * the branching in it: the cache, its casing, the negative cache and the
 * collapsing. A test that stood a server up would be testing Bukkit's user
 * cache instead.
 */
class ExyliaPlayersTest {

    private static final UUID DRAK = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeEach
    void clearDirectory() {
        PlayerRuntime.shutdown();
    }

    @Test
    @DisplayName("an address needs an id and a name")
    void requiresBoth() {
        assertThrows(IllegalArgumentException.class, () -> new ExyliaPlayer(null, "Drak", null));
        assertThrows(IllegalArgumentException.class, () -> new ExyliaPlayer(DRAK, "", null));
        assertThrows(IllegalArgumentException.class, () -> new ExyliaPlayer(DRAK, "  ", null));
    }

    @Test
    @DisplayName("two addresses for the same id are the same player")
    void identityIsTheIdAlone() {
        ExyliaPlayer before = new ExyliaPlayer(DRAK, "Drak", null);
        ExyliaPlayer renamed = new ExyliaPlayer(DRAK, "DrakNew", "lobby");
        assertEquals(before, renamed);
        assertEquals(before.hashCode(), renamed.hashCode());
        assertTrue(before.is(renamed));
        assertFalse(before.is(new ExyliaPlayer(OTHER, "Drak", null)));
    }

    @Test
    @DisplayName("a remembered name resolves once its player has left")
    void remembersAfterTheyLeave() {
        ExyliaPlayers.remember(DRAK, "Drak");
        ExyliaPlayer found = ExyliaPlayers.cached("Drak");
        assertNotNull(found);
        assertEquals(DRAK, found.id());
        assertEquals("Drak", found.name());
    }

    @Test
    @DisplayName("a name is looked up however it was typed, and answers in its own casing")
    void nameLookupIgnoresCasing() {
        ExyliaPlayers.remember(DRAK, "Drak");
        ExyliaPlayer found = ExyliaPlayers.cached("dRaK");
        assertNotNull(found);
        assertEquals(DRAK, found.id());
        // The casing the player actually uses, not the casing that was typed:
        // it is what goes into a message and into an item name.
        assertEquals("Drak", found.name());
    }

    @Test
    @DisplayName("an id resolves to the name it last answered to")
    void idResolvesToName() {
        ExyliaPlayers.remember(DRAK, "Drak");
        assertEquals("Drak", ExyliaPlayers.nameOf(DRAK));
        assertEquals("Drak", ExyliaPlayers.nameOr(DRAK, "?"));
        assertNull(ExyliaPlayers.nameOf(OTHER));
        assertEquals("?", ExyliaPlayers.nameOr(OTHER, "?"));
    }

    @Test
    @DisplayName("a uuid typed as text is read as one")
    void acceptsAUuidAsText() {
        ExyliaPlayers.remember(DRAK, "Drak");
        ExyliaPlayer found = ExyliaPlayers.cached(DRAK.toString());
        assertNotNull(found);
        assertEquals("Drak", found.name());
    }

    @Test
    @DisplayName("a name nobody knows is null, not an exception")
    void unknownNameIsNull() {
        assertNull(ExyliaPlayers.cached("Nobody"));
        assertNull(ExyliaPlayers.cached(""));
        assertNull(ExyliaPlayers.cached("   "));
        assertNull(ExyliaPlayers.cached(UUID.randomUUID().toString()));
    }

    @Test
    @DisplayName("a later name for the same id replaces the earlier one")
    void renameReplaces() {
        ExyliaPlayers.remember(DRAK, "Drak");
        ExyliaPlayers.remember(DRAK, "DrakNew");
        assertEquals("DrakNew", ExyliaPlayers.nameOf(DRAK));
        ExyliaPlayer byNew = ExyliaPlayers.cached("DrakNew");
        assertNotNull(byNew);
        assertEquals(DRAK, byNew.id());
        // The old name still points at them. Bukkit's own user cache behaves
        // the same way, and a staff member typing the name they remember is
        // better served by an answer than by "not found".
        ExyliaPlayer byOld = ExyliaPlayers.cached("Drak");
        assertNotNull(byOld);
        assertEquals(DRAK, byOld.id());
    }

    @Test
    @DisplayName("resolving what the directory knows never leaves the thread")
    void resolveAnswersFromMemory() {
        ExyliaPlayers.remember(DRAK, "Drak");
        Optional<ExyliaPlayer> found = ExyliaPlayers.resolve("Drak").getNow(null);
        assertNotNull(found, "a cached name must complete immediately");
        assertTrue(found.isPresent());
        assertEquals(DRAK, found.get().id());
    }

    @Test
    @DisplayName("with no bridge and no Mojang, an unknown name resolves to nobody")
    void resolveWithoutABridge() {
        Optional<ExyliaPlayer> found = ExyliaPlayers.resolve("Nobody").getNow(null);
        assertNotNull(found, "every tier is unavailable, so the answer is already known");
        assertTrue(found.isEmpty());
    }

    @Test
    @DisplayName("a name that resolved to nobody is not asked about again")
    void negativeCacheHolds() {
        ExyliaPlayers.resolve("Nobody").getNow(null);
        // Same future object: the second call reads the negative cache rather
        // than starting a second lookup. Without it, a typo in a command cost
        // a proxy round trip and a Mojang request per press of enter.
        Optional<ExyliaPlayer> again = ExyliaPlayers.resolve("nobody").getNow(null);
        assertNotNull(again);
        assertTrue(again.isEmpty());
    }

    @Test
    @DisplayName("a name learned after being unknown stops being unknown")
    void rememberClearsTheNegativeCache() {
        ExyliaPlayers.resolve("Drak").getNow(null);
        ExyliaPlayers.remember(DRAK, "Drak");
        ExyliaPlayer found = ExyliaPlayers.cached("Drak");
        assertNotNull(found, "joining must beat the negative cache");
        assertEquals(DRAK, found.id());
    }

    @Test
    @DisplayName("an address with no server is not on the network")
    void networkStateIsHonest() {
        ExyliaPlayer nowhere = new ExyliaPlayer(DRAK, "Drak", null);
        assertFalse(nowhere.isOnNetwork());
        assertTrue(nowhere.serverName().isEmpty());
        ExyliaPlayer somewhere = new ExyliaPlayer(DRAK, "Drak", "lobby");
        assertTrue(somewhere.isOnNetwork());
        assertEquals("lobby", somewhere.serverName().orElseThrow());
        // A blank server name is the proxy saying "between servers", which is
        // not a server to send anything to.
        assertFalse(new ExyliaPlayer(DRAK, "Drak", "").isOnNetwork());
    }

    @Test
    @DisplayName("a target holds what was typed until somebody asks")
    void targetDefersTheLookup() {
        PlayerTarget target = new PlayerTarget("  Drak  ");
        assertEquals("Drak", target.name());
        assertNull(target.cached());
        ExyliaPlayers.remember(DRAK, "Drak");
        assertEquals(DRAK, target.cached().id());
        assertThrows(IllegalArgumentException.class, () -> new PlayerTarget(" "));
    }

    @Test
    @DisplayName("the directory is not offered as tab completions")
    void suggestionsAreBounded() {
        ExyliaPlayers.remember(DRAK, "Drak");
        // Only this server's players and the network's are suggestions. A
        // name somebody once typed into a command is not one, and the user
        // cache of an old server is a hundred thousand of them.
        assertTrue(ExyliaPlayers.names().isEmpty());
    }

    @Test
    @DisplayName("an address built from a player is remembered by building it")
    void buildingRemembers() {
        ExyliaPlayer built = ExyliaPlayers.of(DRAK, "Drak");
        assertSame(DRAK, built.id());
        assertEquals(DRAK, ExyliaPlayers.cached("Drak").id());
    }
}
