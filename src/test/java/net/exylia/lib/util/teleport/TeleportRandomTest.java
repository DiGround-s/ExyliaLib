package net.exylia.lib.util.teleport;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.util.Cooldowns;
import net.exylia.lib.util.teleport.internal.TeleportRuntime;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dropping somebody somewhere in the world.
 *
 * <p>The fake world hands back no blocks, so every candidate the search picks
 * is unsurvivable — which is exactly the case worth pinning down, because it is
 * the one where the wrong answer is a player in the ocean rather than a message.
 *
 * <p>The other half is <em>when</em> the search runs. Every attempt loads a
 * chunk, and doing that for a player who is about to walk out of their
 * countdown is chunk generation nobody asked for.
 */
class TeleportRandomTest {

    private Plugin plugin;
    private World world;
    private FakePlayer player;
    private PluginTeleports teleports;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        plugin = FakeServer.newPlugin("Practice");
        world = FakeServer.newWorld("wild");
        FakeServer.worlds(world);

        player = new FakePlayer("DiGround");
        player.at(new Location(world, 0, 64, 0));
        FakeServer.online(player.player());

        TeleportRuntime.resetForTests();
        TeleportRuntime.init(plugin);
        teleports = Teleports.of(plugin);
    }

    @AfterEach
    void tearDown() {
        Teleports.releaseAll();
        TeleportRuntime.resetForTests();
        Cooldowns.clearEverything();
        FakeServer.reset();
    }

    @Test
    @DisplayName("running out of attempts moves nobody and says nowhere was safe")
    void exhaustingTheAttemptsMovesNobody() {
        TeleportHandle handle = teleports
                .random(player.player(), RandomArea.around(spawn(), 100, 1_000))
                .start();
        settle();

        // Refused rather than dropped into whatever the search kept finding: a
        // random teleport that lands a player in lava is worse than one that
        // did not happen.
        assertEquals(TeleportResult.NO_SAFE_LOCATION, resultOf(handle));
        assertTrue(player.teleports().isEmpty(), "a failed search still moved them");
    }

    @Test
    @DisplayName("a failed search gives back the cooldown it claimed")
    void aFailedSearchRefundsTheCooldown() {
        teleports.random(player.player(), RandomArea.around(spawn(), 100, 1_000))
                .cooldown("rtp", 300.0)
                .start();
        settle();

        assertFalse(Cooldowns.isActive(player.player(), "rtp"),
                "a random teleport that never happened charged the player anyway");
    }

    @Test
    @DisplayName("the countdown runs before any chunk is asked for")
    void theCountdownRunsBeforeTheSearch() {
        TeleportHandle handle = teleports
                .random(player.player(), RandomArea.around(spawn(), 100, 1_000))
                .warmup(1.0)
                .start();

        FakeServer.tick(15);
        assertFalse(handle.isDone(), "the search answered before the countdown had finished");

        FakeServer.tick(10);
        assertEquals(TeleportResult.NO_SAFE_LOCATION, resultOf(handle));
    }

    @Test
    @DisplayName("walking out of the countdown means no search happens at all")
    void cancellingSkipsTheSearchEntirely() {
        TeleportHandle handle = teleports
                .random(player.player(), RandomArea.around(spawn(), 100, 1_000))
                .warmup(2.0)
                .cancelOnMove()
                .start();

        FakeServer.tick(5);
        walkTo(new Location(world, 1, 64, 0));

        assertEquals(TeleportResult.CANCELLED_ON_MOVE, resultOf(handle));
        assertTrue(player.teleports().isEmpty());
    }

    @Test
    @DisplayName("a random teleport carries the random cause")
    void itSaysWhy() {
        // The cause is set when the request is built rather than when it lands,
        // so it is readable even on one that never reaches the event.
        TeleportHandle handle = teleports
                .random(player.player(), RandomArea.around(spawn(), 100, 1_000))
                .start();
        settle();

        assertEquals(TeleportCause.RANDOM, handle.cause());
    }

    // ---------------------------------------------------------------- helpers

    private Location spawn() {
        return new Location(world, 0, 64, 0);
    }

    private static TeleportResult resultOf(TeleportHandle handle) {
        assertTrue(handle.isDone(), "the teleport never completed");
        return handle.future().join();
    }

    private static void settle() {
        FakeServer.tick(3);
    }

    private void walkTo(Location to) {
        Location from = player.player().getLocation();
        player.at(to);
        FakeServer.dispatch(new org.bukkit.event.player.PlayerMoveEvent(
                player.player(), from, to));
    }
}
