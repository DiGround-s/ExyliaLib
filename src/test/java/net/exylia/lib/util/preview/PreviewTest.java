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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a preview promises: the player comes back, sees it alone, and does not
 * fall &mdash; and shows nothing at all until a server owner says where.
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
    private Location stage;

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
        stage = new Location(world, 20.5, 70, -40.5, 180f, 0f);
        previews = Previews.of(plugin).using(new PreviewSettings().at(stage));
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
    @DisplayName("the player is moved to the stage the server owner set")
    void theStageIsWhereItWasConfigured() {
        previews.show(viewer.player(), effect);

        List<Location> moves = viewer.teleports();
        assertFalse(moves.isEmpty(), "the player was never moved");
        assertEquals(stage.getX(), moves.get(0).getX(), 0.001);
        assertEquals(stage.getY(), moves.get(0).getY(), 0.001);
        assertEquals(stage.getZ(), moves.get(0).getZ(), 0.001);
    }

    @Test
    @DisplayName("the player faces the way the stage was set facing")
    void facingIsTheStages() {
        previews.show(viewer.player(), effect);

        // The owner aimed the stage at whatever they built behind it, so that
        // is where the effect belongs, not wherever the player happened to look.
        assertEquals(180f, viewer.teleports().get(0).getYaw(), 0.01f);
    }

    // ------------------------------------------------------------- no stage

    @Test
    @DisplayName("nothing is shown, and nobody is moved, without a stage")
    void noStageMeansNoPreview() {
        previews.using(new PreviewSettings());

        assertFalse(previews.available(), "an admin has to set the location first");
        assertNull(previews.show(viewer.player(), effect));
        assertTrue(viewer.teleports().isEmpty(), "a player must not be moved to nowhere");
        assertFalse(viewer.isFrozen());
    }

    @Test
    @DisplayName("a stage in a world that is not loaded is no stage at all")
    void anUnloadedWorldIsNotAStage() {
        previews.using(new PreviewSettings("-,deleted,0.0,70.0,0.0,0.0,0.0", 5.0, 4, 20, 600));

        assertFalse(previews.available());
        assertNull(previews.show(viewer.player(), effect));
    }

    @Test
    @DisplayName("a configured stage is available")
    void aConfiguredStageIsAvailable() {
        assertTrue(previews.available());
        assertNotNull(previews.show(viewer.player(), effect));
    }

    @Test
    @DisplayName("a stage written by a command reads back as the same place")
    void theStageSurvivesBeingStored() {
        // What setpreviewlocation writes and config.yml holds is text, so the
        // round trip is what decides whether the stage an admin stood on is the
        // one players are put on.
        Location read = new PreviewSettings().at(stage).stage();

        assertNotNull(read);
        assertEquals(stage.getWorld(), read.getWorld());
        assertEquals(stage.getX(), read.getX(), 0.001);
        assertEquals(stage.getY(), read.getY(), 0.001);
        assertEquals(stage.getZ(), read.getZ(), 0.001);
        assertEquals(stage.getYaw(), read.getYaw(), 0.01f);
        assertEquals(stage.getPitch(), read.getPitch(), 0.01f);
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
    @DisplayName("the stage is the world it was configured in")
    void theStageKeepsItsWorld() {
        previews.show(viewer.player(), effect);

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

    // ------------------------------------------------------------------ lock

    @Test
    @DisplayName("a player mid-preview can do nothing, and everything afterwards")
    void actionsAreBlockedWhilePreviewing() {
        Preview preview = previews.show(viewer.player(), effect);

        var drop = new org.bukkit.event.player.PlayerDropItemEvent(viewer.player(), null);
        FakeServer.dispatch(drop);
        assertTrue(drop.isCancelled(), "a drop from the stage lands a thousand blocks down");

        preview.end();
        var after = new org.bukkit.event.player.PlayerDropItemEvent(viewer.player(), null);
        FakeServer.dispatch(after);
        assertFalse(after.isCancelled(), "the lock must go with the preview");
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
        // second to finish would return the player to the stage.
        assertNotNull(first);
        assertNotNull(second);
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
    @DisplayName("two players share the one stage without meeting")
    void twoPreviewsShareTheStage() {
        FakePlayer other = new FakePlayer("Other");
        other.at(new Location(world, 100, 64, 200));
        FakeServer.online(viewer.player(), other.player());

        previews.show(viewer.player(), effect);
        previews.show(other.player(), effect);

        Location a = viewer.teleports().get(0);
        Location b = other.teleports().get(0);
        assertEquals(a.getX(), b.getX(), 0.001, "there is only one stage");
        assertEquals(a.getZ(), b.getZ(), 0.001);
        // Which is only safe because neither can see the other, nor the other's
        // effect: every step of a preview is sent to its own viewer alone.
        assertFalse(viewer.hidden().isEmpty(), "the other player must be hidden");
        assertFalse(other.hidden().isEmpty());
    }

    // ------------------------------------------------------------------ after

    @Test
    @DisplayName("what was meant to happen afterwards still happens when interrupted")
    void theCallbackAlwaysRuns() {
        boolean[] reopened = {false};

        Preview preview = previews.show(viewer.player(), effect, () -> reopened[0] = true);
        assertNotNull(preview);
        preview.end();
        FakeServer.tick(3);

        // A menu that opened a preview has to be reopened whether the preview
        // finished or was cut short.
        assertTrue(reopened[0], "the menu would never come back");
    }
}
