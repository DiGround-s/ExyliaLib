package net.exylia.lib.client.internal;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.client.ClientBrand;
import net.exylia.lib.client.ClientTeam;
import net.exylia.lib.client.PluginTeams;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a team promises a game that lasts.
 *
 * <p>The push API already draws markers; a team exists for the questions a
 * push cannot answer — who is on it, what happens when somebody joins a second
 * one, and what is left behind when nobody cleans up. Those are what is tested
 * here, through the packets a client would have received.
 */
class ClientTeamTest {

    private World world;
    private FakePlayer alice;
    private FakePlayer bob;
    private FakePlayer carol;
    private FakeLink link;
    private Plugin plugin;
    private PluginTeams teams;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        world = FakeServer.newWorld("world");
        alice = new FakePlayer("Alice").at(new Location(world, 0, 64, 0));
        bob = new FakePlayer("Bob").at(new Location(world, 0, 64, 0));
        carol = new FakePlayer("Carol").at(new Location(world, 0, 64, 0));
        FakeServer.online(alice.player(), bob.player(), carol.player());

        link = FakeLink.full(ClientBrand.LUNAR)
                .owning(alice.player(), bob.player(), carol.player());
        ClientRegistry.install(List.of(link));

        plugin = FakeServer.newPlugin("Game");
        teams = ClientRuntime.teamsOf(plugin);
    }

    @AfterEach
    void tearDown() {
        ClientRuntime.shutdown();
        FakeServer.reset();
    }

    @Test
    @DisplayName("adding a member draws the whole team for everyone in it")
    void drawsEveryMember() {
        ClientTeam team = teams.create();
        team.add(alice.player());
        link.clear();

        team.add(bob.player());

        // Both see each other, and neither is told about themselves.
        assertEquals(
                List.of("markers:Alice:Bob", "markers:Bob:Alice"),
                link.calls("markers"));
    }

    @Test
    @DisplayName("a player joining a second team leaves the first")
    void onePlayerOneTeam() {
        ClientTeam red = teams.create(List.of(alice.player(), bob.player()));
        ClientTeam blue = teams.create();
        link.clear();

        blue.add(bob.player());

        assertFalse(red.has(bob.player().getUniqueId()),
                "the old team must not keep a player who joined another");
        assertTrue(blue.has(bob.player().getUniqueId()));
        assertSame(blue, teams.of(bob.player()));
        // Alice is alone now and must be told, or she keeps a marker on Bob.
        assertTrue(link.calls("markers").contains("markers:Alice:"),
                "the team a player left has to be re-drawn, got " + link.calls("markers"));
    }

    @Test
    @DisplayName("removing a member clears their markers and re-draws the rest")
    void removingClears() {
        ClientTeam team = teams.create(List.of(alice.player(), bob.player(), carol.player()));
        link.clear();

        team.remove(bob.player());

        assertEquals(List.of("clearmarkers:Bob"), link.calls("clearmarkers"));
        assertEquals(
                List.of("markers:Alice:Carol", "markers:Carol:Alice"),
                link.calls("markers"));
        assertNull(teams.of(bob.player()));
    }

    @Test
    @DisplayName("deleting a team clears every member")
    void deleteClearsEveryone() {
        ClientTeam team = teams.create(List.of(alice.player(), bob.player()));
        link.clear();

        team.delete();

        assertEquals(List.of("clearmarkers:Alice", "clearmarkers:Bob"),
                link.calls("clearmarkers"));
        assertFalse(team.alive());
        assertNull(teams.of(alice.player()));
        assertNull(teams.find(team.id()));
    }

    @Test
    @DisplayName("deleting twice is not an error and sends nothing twice")
    void deleteIsIdempotent() {
        ClientTeam team = teams.create(List.of(alice.player()));
        team.delete();
        link.clear();

        team.delete();

        assertEquals(List.of(), link.calls());
    }

    @Test
    @DisplayName("a member who logs out is dropped and their teammates re-drawn")
    void forgetsPlayerWhoLeft() {
        ClientTeam team = teams.create(List.of(alice.player(), bob.player()));
        link.clear();

        ClientRuntime.forget(bob.player());

        assertFalse(team.has(bob.player().getUniqueId()));
        // Alice still has a marker pointing at somebody who is gone.
        assertEquals(List.of("markers:Alice:"), link.calls("markers"));
    }

    @Test
    @DisplayName("an offline member is never reported and never drawn")
    void dropsOfflineMembers() {
        ClientTeam team = teams.create(List.of(alice.player(), bob.player()));
        // Somebody who left without the module being told: the team must still
        // shrink, or it holds a player who cannot see anything.
        FakeServer.online(alice.player(), carol.player());
        link.clear();

        assertEquals(1, team.size());
        assertEquals(List.of(alice.player()), List.copyOf(team.members()));
        // Not merely filtered out of the answer: dropped, or the set grows for
        // as long as the team lives and never shrinks again.
        assertFalse(team.has(bob.player().getUniqueId()),
                "an offline member has to leave the team, not just the reply");
        assertNull(teams.of(bob.player()));
    }

    @Test
    @DisplayName("disabling the owning plugin deletes its teams")
    void releaseDeletesTeams() {
        ClientTeam team = teams.create(List.of(alice.player(), bob.player()));
        link.clear();

        ClientRuntime.release(plugin.getName());

        assertFalse(team.alive());
        assertEquals(List.of("clearmarkers:Alice", "clearmarkers:Bob"),
                link.calls("clearmarkers"));
        assertEquals(0, TeamRegistry.tracked());
    }

    @Test
    @DisplayName("a plugin only sees the teams it created")
    void teamsAreScopedToTheirPlugin() {
        ClientTeam mine = teams.create(List.of(alice.player()));
        PluginTeams other = ClientRuntime.teamsOf(FakeServer.newPlugin("Other"));
        other.create(List.of(bob.player()));

        assertEquals(List.of(mine), List.copyOf(teams.all()));
        assertEquals(1, other.all().size());
    }

    @Test
    @DisplayName("a player is found across plugins, because they are in one team")
    void lookupCrossesPlugins() {
        PluginTeams other = ClientRuntime.teamsOf(FakeServer.newPlugin("Other"));
        ClientTeam theirs = other.create(List.of(alice.player()));

        // The question is "which team is this player in", not "which of mine".
        assertSame(theirs, teams.of(alice.player()));
    }

    @Test
    @DisplayName("re-sending after a reconnect draws the team again")
    void resendRedraws() {
        ClientTeam team = teams.create(List.of(alice.player(), bob.player()));
        link.clear();

        ClientRuntime.resend(alice.player(), false);

        assertEquals(
                List.of("markers:Alice:Bob", "markers:Bob:Alice"),
                link.calls("markers"));
        assertTrue(team.alive());
    }

    @Test
    @DisplayName("a broken integration does not take the team down with it")
    void brokenIntegrationIsContained() {
        ClientTeam team = teams.create(List.of(alice.player()));
        link.broken = true;

        team.add(bob.player());

        link.broken = false;
        assertTrue(team.has(bob.player().getUniqueId()),
                "the team is the library's own state and must survive their bug");
        assertEquals(2, team.size());
    }
}
