package net.exylia.lib.util.preview;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.util.sequence.Sequence;
import net.exylia.lib.util.sequence.Sequences;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a preview promises: the player comes back, sees it alone, and does not
 * fall.
 *
 * <p>The restore is the part worth testing hardest. Everything else is
 * cosmetic next to leaving somebody flying and invulnerable a thousand blocks
 * above a lobby.
 */
class PreviewTest {

    private Plugin plugin;
    private World world;
    private FakePlayer viewer;
    private PluginPreviews previews;
    private Sequence effect;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        plugin = FakeServer.newPlugin("Practice");
        world = FakeServer.newWorld("lobby");
        FakeServer.worlds(world);

        viewer = new FakePlayer("DiGround");
        viewer.at(new Location(world, 100, 64, 200, 90f, 0f));
        FakeServer.online(viewer.player());

        // What ExyliaLib does at startup: the listeners are what end a preview
        // when the player quits, dies or is moved by somebody else.
        net.exylia.lib.util.preview.internal.PreviewRuntime.resetForTests();
        net.exylia.lib.util.preview.internal.PreviewRuntime.init(plugin);
        previews = Previews.of(plugin);
        effect = Sequences.of(plugin).compile(List.of("[PARTICLE] FLAME"));
    }

    @AfterEach
    void tearDown() {
        Previews.releaseAll();
        net.exylia.lib.util.preview.internal.PreviewRuntime.resetForTests();
        Sequences.releaseAll();
        FakeServer.reset();
    }

    // ------------------------------------------------------------------- lift

    @Test
    @DisplayName("the player is lifted somewhere with nothing in it")
    void theStageIsEmptySky() {
        previews.show(viewer.player(), effect);

        List<Location> moves = viewer.teleports();
        assertFalse(moves.isEmpty(), "the player was never lifted");
        assertTrue(moves.get(0).getY() >= 320,
                "the stage must be clear of anything built, got y=" + moves.get(0).getY());
    }

    @Test
    @DisplayName("the player keeps the direction they were facing")
    void facingIsKept() {
        previews.show(viewer.player(), effect);

        assertEquals(90f, viewer.teleports().get(0).getYaw(), 0.01f,
                "the effect must appear in front of them, not behind");
    }

    @Test
    @DisplayName("the player is held up rather than left to fall")
    void thePlayerDoesNotFall() {
        previews.show(viewer.player(), effect);

        assertTrue(viewer.isFrozen(),
                "a player in the sky with gravity is a player falling");
        assertTrue(viewer.isInvulnerable(), "and one who must not take damage");
    }

    @Test
    @DisplayName("the stage stays in the player's own world")
    void theStageIsInTheSameWorld() {
        previews.show(viewer.player(), effect);

        // Crossing worlds would change their sky and fire a world-change event
        // at every plugin for something the player did not do.
        assertEquals(world, viewer.teleports().get(0).getWorld());
    }

    // ---------------------------------------------------------------- restore

    @Test
    @DisplayName("ending puts the player back exactly where they were")
    void endingRestoresPosition() {
        Location before = viewer.player().getLocation().clone();

        Preview preview = previews.show(viewer.player(), effect);
        preview.end();

        Location after = viewer.player().getLocation();
        assertEquals(before.getX(), after.getX(), 0.001);
        assertEquals(before.getY(), after.getY(), 0.001);
        assertEquals(before.getZ(), after.getZ(), 0.001);
    }

    @Test
    @DisplayName("ending gives back gravity and takes away flight")
    void endingRestoresState() {
        Preview preview = previews.show(viewer.player(), effect);
        preview.end();

        assertFalse(viewer.isFrozen(), "a player left flying in a lobby is a bug report");
        assertFalse(viewer.isInvulnerable());
    }

    @Test
    @DisplayName("ending twice is harmless")
    void endingIsIdempotent() {
        Preview preview = previews.show(viewer.player(), effect);
        preview.end();
        int movesAfterFirst = viewer.teleports().size();

        preview.end();

        assertEquals(movesAfterFirst, viewer.teleports().size(),
                "the second end must not move them again");
        assertTrue(preview.isFinished());
    }

    @Test
    @DisplayName("the player is shown to everyone again afterwards")
    void endingUnhidesEverything() {
        FakePlayer bystander = new FakePlayer("Someone");
        bystander.at(new Location(world, 105, 64, 200));
        FakeServer.online(viewer.player(), bystander.player());

        Preview preview = previews.show(viewer.player(), effect);
        assertFalse(viewer.hidden().isEmpty(), "the stage must be empty of other players");

        preview.end();

        assertTrue(viewer.hidden().isEmpty(), "and they must all come back");
    }

    // ----------------------------------------------------------- interruption

    @Test
    @DisplayName("a player who quits is not teleported anywhere")
    void quittingDoesNotMoveThem() {
        previews.show(viewer.player(), effect);
        int movesWhileOnline = viewer.teleports().size();

        viewer.disconnect();
        FakeServer.dispatch(new org.bukkit.event.player.PlayerQuitEvent(
                viewer.player(), net.kyori.adventure.text.Component.empty(),
                org.bukkit.event.player.PlayerQuitEvent.QuitReason.DISCONNECTED));

        // Teleporting a player who is leaving throws, and the server saves them
        // wherever it has them.
        assertEquals(movesWhileOnline, viewer.teleports().size());
        assertFalse(Previews.isPreviewing(viewer.player()),
                "but the preview must be forgotten, or their next login is stuck");
    }

    @Test
    @DisplayName("a second preview for the same player replaces the first")
    void oneAtATimePerPlayer() {
        Preview first = previews.show(viewer.player(), effect);
        Preview second = previews.show(viewer.player(), effect);

        // Two overlapping previews would each remember an origin, and the
        // second to finish would return the player to a patch of empty sky.
        assertTrue(first.isFinished(), "the first must give way");
        assertFalse(second.isFinished());
        assertEquals(1, Previews.active());
    }

    @Test
    @DisplayName("disabling a plugin puts its players back")
    void disablingRestoresEveryone() {
        Location before = viewer.player().getLocation().clone();
        previews.show(viewer.player(), effect);

        Previews.release(plugin.getName());

        assertEquals(before.getY(), viewer.player().getLocation().getY(), 0.001,
                "a disabled plugin must not leave a player in the sky");
        assertFalse(viewer.isFrozen());
        assertEquals(0, Previews.active());
    }

    // ------------------------------------------------------------- two at once

    @Test
    @DisplayName("two players previewing at once get different patches of sky")
    void twoPreviewsDoNotShareAStage() {
        FakePlayer other = new FakePlayer("Other");
        other.at(new Location(world, 100, 64, 200));
        FakeServer.online(viewer.player(), other.player());

        previews.show(viewer.player(), effect);
        previews.show(other.player(), effect);

        Location a = viewer.teleports().get(0);
        Location b = other.teleports().get(0);
        // Standing in the same spot in a lobby is the normal case, so lifting
        // straight up would put them both in the same place.
        assertNotEquals(a.getX() + "," + a.getZ(), b.getX() + "," + b.getZ(),
                "each preview needs its own patch of sky");
        assertTrue(a.distanceSquared(b) > 100, "and far enough apart to not be seen");
    }

    @Test
    @DisplayName("a stage is given back when its preview ends")
    void stagesAreReused() {
        Preview first = previews.show(viewer.player(), effect);
        Location taken = viewer.teleports().get(0).clone();
        first.end();

        FakePlayer other = new FakePlayer("Other");
        other.at(new Location(world, 100, 64, 200));
        FakeServer.online(viewer.player(), other.player());
        previews.show(other.player(), effect);

        // Otherwise a busy server walks its stages further out forever.
        assertEquals(taken.getX(), other.teleports().get(0).getX(), 0.001);
        assertEquals(taken.getZ(), other.teleports().get(0).getZ(), 0.001);
    }

    // ------------------------------------------------------------------ after

    @Test
    @DisplayName("what was meant to happen afterwards still happens when interrupted")
    void theCallbackAlwaysRuns() {
        boolean[] reopened = {false};

        Preview preview = previews.show(viewer.player(), effect, () -> reopened[0] = true);
        preview.end();
        FakeServer.tick(3);

        // A menu that opened a preview has to be reopened whether the preview
        // finished or was cut short.
        assertTrue(reopened[0], "the menu would never come back");
    }
}
