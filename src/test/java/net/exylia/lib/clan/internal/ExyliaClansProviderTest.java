package net.exylia.lib.clan.internal;

import net.exylia.exyliaClans.api.ClansAPI;
import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.clan.Clan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether ExyliaLib still reads ExyliaClans.
 *
 * <p>ExyliaClans used to be integrated through ExyliaCommons, whose provider
 * reached for {@code net.exylia.exyliaclans.api.ExyliaClansAPI} — a class that
 * no longer exists in ExyliaClans. The integration was dead and nothing said
 * so, because a provider that cannot find its plugin looks exactly like a
 * plugin that is not installed.
 *
 * <p>This test stands up ExyliaClans' current API shape (see
 * {@code src/test/java/net/exylia/exyliaClans/}) and asserts the provider reads
 * it, so the same silence cannot happen twice.
 */
class ExyliaClansProviderTest {

    private ExyliaClansProvider provider;

    private FakePlayer leader;
    private FakePlayer member;
    private FakePlayer outsider;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        ClansAPI.reset();

        leader = new FakePlayer("Leader");
        member = new FakePlayer("Member");
        outsider = new FakePlayer("Outsider");
        FakeServer.online(leader.player(), member.player());
        FakeServer.plugins(FakeServer.newPlugin("ExyliaClans"));

        ClansAPI.add(new net.exylia.exyliaClans.database.Clan(
                "red-id", "Red", id(leader), 250.5), id(member));
        ClansAPI.add(new net.exylia.exyliaClans.database.Clan(
                "blue-id", "Blue", id(outsider), 0), new UUID[0]);
        ClansAPI.ally("red-id", "blue-id");

        provider = ExyliaClansProvider.tryCreate();
    }

    @AfterEach
    void tearDown() {
        ClansAPI.reset();
        FakeServer.reset();
    }

    @Test
    @DisplayName("the provider is active when ExyliaClans is running")
    void detected() {
        assertTrue(provider.enabled());
        assertEquals("ExyliaClans", provider.name());
    }

    @Test
    @DisplayName("a player's clan comes back with the fields ExyliaClans stores")
    void readsAClan() {
        Optional<Clan> clan = provider.clanOf(id(leader));
        assertTrue(clan.isPresent());

        Clan red = clan.orElseThrow();
        assertEquals("red-id", red.id());
        assertEquals("Red", red.name());
        assertEquals("Red", red.tag());
        assertEquals(250.5, red.balance());
        assertEquals("ExyliaClans", red.provider());
        assertTrue(red.isLeader(id(leader)));
        assertTrue(red.isMember(id(member)));
        assertFalse(red.isMember(id(outsider)));
        assertEquals(2, red.memberCount());
        assertEquals(2, red.onlineCount());
    }

    @Test
    @DisplayName("a player with no clan is not an error")
    void aPlayerWithoutAClan() {
        UUID nobody = UUID.randomUUID();
        assertTrue(provider.clanOf(nobody).isEmpty());
        assertFalse(provider.hasClan(nobody));
        assertTrue(provider.onlineMembersOf(nobody).isEmpty());
    }

    @Test
    @DisplayName("lookups by name and by id both resolve")
    void lookups() {
        assertEquals("red-id", provider.byTag("Red").orElseThrow().id());
        assertEquals("red-id", provider.byId("red-id").orElseThrow().id());
        assertEquals("blue-id", provider.byId("Blue").orElseThrow().id());
        assertEquals(2, provider.all().size());
        assertTrue(provider.byTag("Green").isEmpty());
    }

    @Test
    @DisplayName("membership answers come from the plugin, not from our snapshot")
    void membership() {
        assertTrue(provider.hasClan(id(leader)));
        assertTrue(provider.areInSameClan(id(leader), id(member)));
        assertFalse(provider.areInSameClan(id(leader), id(outsider)));
        assertEquals(java.util.List.of(id(leader), id(member)),
                java.util.List.copyOf(provider.onlineMembersOf(id(leader))));
    }

    @Test
    @DisplayName("an alliance is asked of the plugin, both ways round")
    void alliances() {
        assertTrue(provider.areAllied(id(leader), id(outsider)));
        assertTrue(provider.areAllied(id(outsider), id(leader)));
        assertEquals(java.util.List.of("blue-id"),
                java.util.List.copyOf(provider.alliesOf("red-id")));
        assertEquals(java.util.List.of("red-id"),
                java.util.List.copyOf(provider.alliesOf("blue-id")));
    }

    @Test
    @DisplayName("a rivalry recorded on one side counts from either side")
    void rivalries() {
        assertTrue(provider.rivalsOf("red-id").isEmpty());

        ClansAPI.rival("blue-id", "red-id");

        assertTrue(provider.areRivals(id(leader), id(outsider)));
        assertTrue(provider.areRivals(id(outsider), id(leader)));
        assertEquals(java.util.List.of("red-id"),
                java.util.List.copyOf(provider.rivalsOf("blue-id")));
    }

    @Test
    @DisplayName("no relation with a player who has no clan")
    void relationsNeedTwoClans() {
        UUID nobody = UUID.randomUUID();
        assertFalse(provider.areAllied(id(leader), nobody));
        assertFalse(provider.areRivals(id(leader), nobody));
        assertFalse(provider.areInSameClan(id(leader), nobody));
    }

    private static UUID id(FakePlayer player) {
        return player.player().getUniqueId();
    }
}
