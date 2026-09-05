package net.exylia.lib.nametag.internal;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.nametag.NametagStyle;
import net.exylia.lib.nametag.PluginNametags;
import net.kyori.adventure.text.format.NamedTextColor;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a viewer's client is actually told.
 *
 * <p>The promises here are about packets not sent as much as packets sent: a
 * colour that did not change costs nothing, a team is created once and added to
 * afterwards, and two viewers looking at the same player are never told the
 * same thing just because it is the same player.
 */
class NametagTest {

    private static final NametagStyle GREEN = NametagStyle.of(NamedTextColor.GREEN);
    private static final NametagStyle RED = NametagStyle.of(NamedTextColor.RED);

    private World world;
    private FakePlayer alice;
    private FakePlayer bob;
    private FakePlayer carol;
    private RecordingSink sink;
    private Plugin plugin;
    private PluginNametags tags;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        world = FakeServer.newWorld("world");
        alice = new FakePlayer("Alice").at(new Location(world, 0, 64, 0));
        bob = new FakePlayer("Bob").at(new Location(world, 0, 64, 0));
        carol = new FakePlayer("Carol").at(new Location(world, 0, 64, 0));
        FakeServer.online(alice.player(), bob.player(), carol.player());

        sink = new RecordingSink();
        NametagRuntime.install(sink);
        plugin = FakeServer.newPlugin("Clans");
        tags = NametagRuntime.of(plugin);
    }

    @AfterEach
    void tearDown() {
        NametagRuntime.shutdown();
        FakeServer.reset();
    }

    @Test
    @DisplayName("painting sends one team and one flags refresh")
    void paintsOnce() {
        tags.paint(alice.player(), bob.player(), GREEN);

        assertEquals(
                List.of("create:Alice:" + GREEN.teamName() + ":Bob", "flags:Alice:Bob"),
                sink.calls());
        assertEquals(GREEN, tags.styleOf(alice.player(), bob.player()));
    }

    @Test
    @DisplayName("painting the same style twice sends nothing the second time")
    void repaintIsFree() {
        tags.paint(alice.player(), bob.player(), GREEN);
        sink.clear();

        tags.paint(alice.player(), bob.player(), GREEN);

        assertEquals(List.of(), sink.calls(),
                "a colour that did not change is a packet nobody needed");
    }

    @Test
    @DisplayName("a second player in the same style joins the team instead of making one")
    void reusesTheTeam() {
        tags.paint(alice.player(), bob.player(), GREEN);
        sink.clear();

        tags.paint(alice.player(), carol.player(), GREEN);

        assertEquals(List.of("add:Alice:" + GREEN.teamName() + ":Carol"), sink.calls("add"));
        assertEquals(List.of(), sink.calls("create"));
    }

    @Test
    @DisplayName("changing colour leaves the old team before joining the new one")
    void recolourLeavesFirst() {
        tags.paint(alice.player(), bob.player(), GREEN);
        sink.clear();

        tags.paint(alice.player(), bob.player(), RED);

        assertEquals(List.of("delteam:Alice:" + GREEN.teamName()), sink.calls("delteam"));
        assertEquals(List.of("create:Alice:" + RED.teamName() + ":Bob"), sink.calls("create"));
        assertEquals(RED, tags.styleOf(alice.player(), bob.player()));
    }

    @Test
    @DisplayName("leaving a shared team rebuilds it with whoever is still in it")
    void leavingSharedTeamRebuildsIt() {
        tags.paint(alice.player(), List.of(bob.player(), carol.player()), GREEN);
        sink.clear();

        tags.reset(alice.player(), bob.player());

        assertEquals(
                List.of("delteam:Alice:" + GREEN.teamName(),
                        "create:Alice:" + GREEN.teamName() + ":Carol",
                        "flags:Alice:Bob"),
                sink.calls());
        assertEquals(List.of(), sink.calls("remove"),
                "a single-member removal disconnects a client that moved them elsewhere");
        assertEquals(GREEN, tags.styleOf(alice.player(), carol.player()));
    }

    @Test
    @DisplayName("two viewers can see the same player differently")
    void perViewer() {
        tags.paint(alice.player(), carol.player(), GREEN);
        tags.paint(bob.player(), carol.player(), RED);

        assertEquals(GREEN, tags.styleOf(alice.player(), carol.player()));
        assertEquals(RED, tags.styleOf(bob.player(), carol.player()));
    }

    @Test
    @DisplayName("styles that differ only by glow share a team")
    void glowIsNotPartOfTheTeam() {
        // Glow rides on entity flags, so splitting the team for it would send
        // the client two teams that draw the name identically.
        assertEquals(GREEN.teamName(), GREEN.withGlow().teamName());
        assertNotEquals(GREEN.teamName(), GREEN.showingInvisible().teamName());
        assertNotEquals(GREEN.teamName(), RED.teamName());
    }

    @Test
    @DisplayName("a glow without a colour sends no team at all")
    void glowOnlySendsNoTeam() {
        // The point of the style: a server whose names belong to a tab plugin
        // can still outline a player without claiming them into a team.
        tags.paint(alice.player(), bob.player(), NametagStyle.glowOnly());

        assertNull(NametagStyle.glowOnly().teamName());
        assertEquals(List.of("flags:Alice:Bob"), sink.calls());
        assertTrue(NametagRuntime.state()
                .isGlowing(alice.player().getUniqueId(), bob.player().getUniqueId()));
    }

    @Test
    @DisplayName("resetting a glow without a colour removes no team either")
    void glowOnlyResets() {
        tags.paint(alice.player(), bob.player(), NametagStyle.glowOnly());
        sink.clear();

        tags.reset(alice.player(), bob.player());

        assertEquals(List.of("flags:Alice:Bob"), sink.calls());
        assertFalse(NametagRuntime.state().anyGlowing(alice.player().getUniqueId()));
    }

    @Test
    @DisplayName("a colour painted over a glow leaves no team behind")
    void glowOnlyGivesWayToAColour() {
        tags.paint(alice.player(), bob.player(), NametagStyle.glowOnly());
        sink.clear();

        tags.paint(alice.player(), bob.player(), GREEN);
        tags.reset(alice.player(), bob.player());

        assertEquals(
                List.of("create:Alice:" + GREEN.teamName() + ":Bob", "flags:Alice:Bob",
                        "delteam:Alice:" + GREEN.teamName(), "flags:Alice:Bob"),
                sink.calls());
    }

    @Test
    @DisplayName("glow is remembered per viewer, so the rewrite knows who to outline")
    void glowIsTracked() {
        tags.paint(alice.player(), bob.player(), GREEN.withGlow());

        State state = NametagRuntime.state();
        assertTrue(state.anyGlowing(alice.player().getUniqueId()));
        assertTrue(state.isGlowing(alice.player().getUniqueId(), bob.player().getUniqueId()));
        // Nobody asked for Bob to see a glow.
        assertFalse(state.anyGlowing(bob.player().getUniqueId()));
    }

    @Test
    @DisplayName("dropping a glow stops the rewrite, so the outline goes away")
    void glowIsDropped() {
        tags.paint(alice.player(), bob.player(), GREEN.withGlow());

        tags.paint(alice.player(), bob.player(), GREEN);

        assertFalse(NametagRuntime.state().anyGlowing(alice.player().getUniqueId()),
                "an outline left in the index would be redrawn on the next packet");
    }

    @Test
    @DisplayName("resetting takes the player out of the team and refreshes their flags")
    void resets() {
        tags.paint(alice.player(), bob.player(), GREEN.withGlow());
        sink.clear();

        tags.reset(alice.player(), bob.player());

        assertEquals(
                List.of("delteam:Alice:" + GREEN.teamName(), "flags:Alice:Bob"),
                sink.calls());
        assertNull(tags.styleOf(alice.player(), bob.player()));
        assertFalse(NametagRuntime.state().anyGlowing(alice.player().getUniqueId()));
    }

    @Test
    @DisplayName("a plugin cannot reset what another plugin painted")
    void resetIsScopedToItsPlugin() {
        PluginNametags other = NametagRuntime.of(FakeServer.newPlugin("Game"));
        other.paint(alice.player(), bob.player(), RED);
        sink.clear();

        tags.reset(alice.player(), bob.player());

        assertEquals(List.of(), sink.calls(),
                "a game must not silently undo a clan's colour");
        assertEquals(RED, other.styleOf(alice.player(), bob.player()));
    }

    @Test
    @DisplayName("resetting everywhere finds every viewer who was shown a player")
    void resetEverywhere() {
        tags.paint(alice.player(), carol.player(), GREEN);
        tags.paint(bob.player(), carol.player(), GREEN);
        sink.clear();

        tags.resetEverywhere(carol.player());

        // Order is not promised: the viewers come out of a set.
        assertEquals(
                java.util.Set.of("delteam:Alice:" + GREEN.teamName(),
                        "delteam:Bob:" + GREEN.teamName()),
                java.util.Set.copyOf(sink.calls("delteam")));
    }

    @Test
    @DisplayName("painting a group paints everyone for everyone but themselves")
    void paintEachOther() {
        tags.paintEachOther(List.of(alice.player(), bob.player()), GREEN);

        assertEquals(
                List.of("create:Alice:" + GREEN.teamName() + ":Bob",
                        "create:Bob:" + GREEN.teamName() + ":Alice"),
                sink.calls("create"));
        assertNull(tags.styleOf(alice.player(), alice.player()),
                "a player does not see their own nametag");
    }

    @Test
    @DisplayName("disabling the owning plugin puts everything it painted back")
    void releasePutsEverythingBack() {
        tags.paint(alice.player(), bob.player(), GREEN);
        tags.paint(bob.player(), alice.player(), GREEN);
        sink.clear();

        NametagRuntime.release(plugin.getName());

        assertEquals(2, sink.calls("delteam").size());
        assertEquals(0, NametagRuntime.state().tracked());
    }

    @Test
    @DisplayName("a player who leaves is forgotten as viewer and as target")
    void forgetsPlayerWhoLeft() {
        tags.paint(alice.player(), bob.player(), GREEN.withGlow());
        tags.paint(bob.player(), alice.player(), GREEN);

        NametagRuntime.forget(bob.player());

        assertNull(tags.styleOf(alice.player(), bob.player()),
                "a colour kept for somebody who left would be reapplied to the next "
                        + "player given that id");
        assertFalse(NametagRuntime.state().anyGlowing(alice.player().getUniqueId()));
        assertEquals(0, NametagRuntime.state().tracked());
    }

    @Test
    @DisplayName("a send that throws does not escape to the caller")
    void brokenSendIsContained() {
        sink.broken = true;

        tags.paint(alice.player(), bob.player(), GREEN);

        sink.broken = false;
        assertEquals(GREEN, tags.styleOf(alice.player(), bob.player()));
    }

    @Test
    @DisplayName("without PacketEvents every call does nothing rather than failing")
    void unsupportedIsSilent() {
        NametagRuntime.install(null);
        PluginNametags none = NametagRuntime.of(plugin);

        none.paint(alice.player(), bob.player(), GREEN);
        none.reset(alice.player(), bob.player());

        assertFalse(NametagRuntime.isSupported());
        assertNull(none.styleOf(alice.player(), bob.player()));
    }
}
