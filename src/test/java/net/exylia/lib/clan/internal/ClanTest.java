package net.exylia.lib.clan.internal;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.clan.Clan;
import net.exylia.lib.clan.ClanBridge;
import net.exylia.lib.clan.Clans;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the module asks a clan provider to do.
 */
class ClanTest {

    private FakeProvider provider;
    private FakePlayer alpha;
    private FakePlayer beta;
    private FakePlayer lone;

    private UUID redId;
    private UUID blueId;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        World world = FakeServer.newWorld("world");

        provider = new FakeProvider("TestProvider");
        ClanRuntime.install(provider);

        alpha = new FakePlayer("Alpha");
        beta = new FakePlayer("Beta");
        lone = new FakePlayer("Lone");
        FakeServer.online(alpha.player(), beta.player(), lone.player());

        redId = UUID.randomUUID();
        blueId = UUID.randomUUID();

        Clan red = Clan.builder(redId.toString())
                .name("Red").tag("RED")
                .leaders(List.of(alpha.player().getUniqueId()))
                .members(List.of(beta.player().getUniqueId()))
                .allies(List.of(blueId.toString()))
                .build();
        Clan blue = Clan.builder(blueId.toString())
                .name("Blue").tag("BLUE")
                .leaders(List.of(UUID.randomUUID()))
                .allies(List.of(redId.toString()))
                .build();

        provider.add(alpha.player().getUniqueId(), red);
        provider.add(beta.player().getUniqueId(), red);
        provider.addClan(blue);
        provider.addClan(red);
    }

    @AfterEach
    void tearDown() {
        ClanRuntime.shutdown();
        FakeServer.reset();
    }

    // ------------------------------------------------------------------
    // Basic lookups
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a player's clan is found")
    void clanIsFound() {
        Optional<Clan> found = Clans.clanOf(alpha.player());
        assertTrue(found.isPresent());
        assertEquals("RED", found.get().tag());
    }

    @Test
    @DisplayName("a player without a clan returns empty")
    void noClanReturnsEmpty() {
        assertFalse(Clans.clanOf(lone.player()).isPresent());
    }

    @Test
    @DisplayName("a clan can be found by tag")
    void byTag() {
        assertTrue(Clans.byTag("RED").isPresent());
        assertEquals("Red", Clans.byTag("RED").get().name());
    }

    // ------------------------------------------------------------------
    // Members and ranks
    // ------------------------------------------------------------------

    @Test
    @DisplayName("all members include leaders, moderators and regular members")
    void allMembers() {
        Clan red = Clans.clanOf(alpha.player()).get();
        assertEquals(Set.of(alpha.player().getUniqueId(), beta.player().getUniqueId()),
                red.allMembers());
    }

    @Test
    @DisplayName("a leader is recognised")
    void leaderIsRecognised() {
        Clan red = Clans.clanOf(alpha.player()).get();
        assertTrue(red.isLeader(alpha.player().getUniqueId()));
        assertFalse(red.isLeader(beta.player().getUniqueId()));
    }

    @Test
    @DisplayName("a member is recognised")
    void memberIsRecognised() {
        Clan red = Clans.clanOf(alpha.player()).get();
        assertTrue(red.isMember(beta.player().getUniqueId()));
        assertFalse(red.isMember(lone.player().getUniqueId()));
    }

    // ------------------------------------------------------------------
    // Relationships
    // ------------------------------------------------------------------

    @Test
    @DisplayName("alliances are symmetric: each side sees the other")
    void alliancesAreSymmetric() {
        Clan red = Clans.clanOf(alpha.player()).get();
        Clan blue = Clans.byTag("BLUE").get();

        assertTrue(red.alliedWith(blue));
        assertTrue(blue.alliedWith(red));
    }

    @Test
    @DisplayName("a player without a clan is never allied with anyone")
    void noClanNeverAllied() {
        assertFalse(Clans.areAllied(lone.player(), alpha.player()));
    }

    @Test
    @DisplayName("rivals are symmetric")
    void rivalsAreSymmetric() {
        Clan red = Clan.builder("R")
                .name("Red").tag("RED").members(List.of(alpha.player().getUniqueId()))
                .rivals(List.of("B")).build();
        Clan blue = Clan.builder("B")
                .name("Blue").tag("BLUE").members(List.of(beta.player().getUniqueId()))
                .rivals(List.of("R")).build();

        assertTrue(red.rivalOf(blue));
        assertTrue(blue.rivalOf(red));
    }

    // ------------------------------------------------------------------
    // Caching
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the same player asked twice hits the cache once")
    void cachingHitsOnce() {
        Clans.clanOf(alpha.player());
        Clans.clanOf(alpha.player());

        assertEquals(1, provider.lookupCount.get());
    }

    @Test
    @DisplayName("clearing the cache forces a fresh lookup")
    void invalidateClearsCache() {
        Clans.clanOf(alpha.player());
        Clans.invalidate();
        Clans.clanOf(alpha.player());

        assertEquals(2, provider.lookupCount.get());
    }

    @Test
    @DisplayName("forgetting a player drops their cache entry")
    void forgetDropsCache() {
        Clans.clanOf(alpha.player());
        ClanRuntime.forget(alpha.player().getUniqueId());
        Clans.clanOf(alpha.player());

        assertEquals(2, provider.lookupCount.get());
    }

    @Test
    @DisplayName("a player who has no clan is not re-asked every time either")
    void absentPlayersAreCachedToo() {
        Clans.clanOf(lone.player());
        Clans.clanOf(lone.player());

        assertEquals(1, provider.lookupCount.get());
    }

    // ------------------------------------------------------------------
    // No provider
    // ------------------------------------------------------------------

    @Test
    @DisplayName("without a provider everything returns empty")
    void withoutProvider() {
        ClanRuntime.shutdown();

        assertFalse(Clans.isSupported());
        assertFalse(Clans.clanOf(alpha.player()).isPresent());
        assertFalse(Clans.hasClan(alpha.player()));
        assertTrue(Clans.all().isEmpty());
        assertEquals("", Clans.providerName());
    }

    // ------------------------------------------------------------------
    // Bridge registration
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a registered bridge becomes the active provider")
    void bridgeBecomesProvider() {
        ClanBridge bridge = new ClanBridge() {
            @Override public String name() { return "TestBridge"; }
            @Override public boolean available() { return true; }
            @Override public ClanBridge.Snapshot of(UUID player) {
                return player.equals(alpha.player().getUniqueId())
                        ? ClanBridge.Snapshot.of("R", "Red", "RED",
                        Set.of(alpha.player().getUniqueId()), Set.of(beta.player().getUniqueId()))
                        : null;
            }
            @Override public ClanBridge.Snapshot byTag(String tag) { return null; }
            @Override public ClanBridge.Snapshot byId(String id) { return null; }
            @Override public java.util.Collection<ClanBridge.Snapshot> all() { return List.of(); }
            @Override public boolean hasClan(UUID player) { return false; }
            @Override public java.util.Set<String> alliesOf(String clanId) { return Set.of(); }
            @Override public java.util.Set<String> rivalsOf(String clanId) { return Set.of(); }
            @Override public boolean sameClan(UUID player, UUID other) { return false; }
        };

        Clans.registerBridge(bridge, 10);

        assertTrue(Clans.isSupported());
        assertEquals("TestBridge", Clans.providerName());
        assertTrue(Clans.clanOf(alpha.player()).isPresent());
    }
}
