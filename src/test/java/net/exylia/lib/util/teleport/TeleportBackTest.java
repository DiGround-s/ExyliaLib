package net.exylia.lib.util.teleport;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.util.Cooldowns;
import net.exylia.lib.util.teleport.internal.BackHistory;
import net.exylia.lib.util.teleport.internal.TeleportRuntime;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an undo promises: the place the player actually came from, once, and
 * only when they really got there.
 *
 * <p>The hardest part is not going back — it is what happens to the entry when
 * the going back does not happen. A stack that spends an entry on a teleport
 * the player never received hands them a {@code /back} that skips a place, and
 * nothing about it looks broken until somebody counts.
 *
 * <p>Time is moved rather than waited for. {@link BackHistory} takes a clock
 * exactly so a test can age an entry past its limit without sleeping through
 * half an hour of it.
 */
class TeleportBackTest {

    private Plugin plugin;
    private World world;
    private FakePlayer player;
    private PluginTeleports teleports;
    private Location origin;
    private Location destination;

    /** What the module reads as "now", so age is testable. */
    private final AtomicLong clock = new AtomicLong(1_000_000L);

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        plugin = FakeServer.newPlugin("Practice");
        world = FakeServer.newWorld("lobby");
        FakeServer.worlds(world);

        origin = new Location(world, 100, 64, 200, 90f, 0f);
        destination = new Location(world, 0, 70, 0, 0f, 0f);

        player = new FakePlayer("DiGround");
        player.at(origin);
        FakeServer.online(player.player());

        TeleportRuntime.resetForTests();
        TeleportRuntime.init(plugin);
        BackHistory.setClock(clock::get);
        teleports = Teleports.of(plugin);
    }

    @AfterEach
    void tearDown() {
        BackHistory.resetClock();
        Teleports.releaseAll();
        TeleportRuntime.resetForTests();
        Cooldowns.clearEverything();
        FakeServer.reset();
    }

    // -------------------------------------------------------------- recording

    @Test
    @DisplayName("a teleport records where the player came from")
    void aTeleportRecordsTheOrigin() {
        teleports.to(player.player(), destination).start();
        settle();

        Optional<ExyliaLocation> recorded = teleports.lastLocationOf(player.player());
        assertTrue(recorded.isPresent(), "nothing was recorded for a teleport that happened");
        assertEquals(100.0, recorded.orElseThrow().x(), 0.001);
        assertEquals(200.0, recorded.orElseThrow().z(), 0.001);
    }

    @Test
    @DisplayName("going back returns the player to where they were")
    void backReturnsThem() {
        teleports.to(player.player(), destination).start();
        settle();

        TeleportHandle handle = teleports.back(player.player()).start();
        settle();

        assertEquals(TeleportResult.SUCCESS, resultOf(handle));
        Location landed = player.teleports().get(1);
        assertEquals(100.0, landed.getX(), 0.001, "the player did not come back to the origin");
        assertEquals(200.0, landed.getZ(), 0.001);
    }

    @Test
    @DisplayName("a teleport that never happened records nothing")
    void aRefusedTeleportRecordsNothing() {
        FakeServer.deliverEvents();
        org.bukkit.Bukkit.getPluginManager().registerEvents(new TeleportTest.Vetoer(), plugin);

        TeleportHandle handle = teleports.to(player.player(), destination).start();
        settle();

        assertEquals(TeleportResult.CANCELLED_BY_EVENT, resultOf(handle));
        // A vetoed teleport left the player where they were, so recording it
        // would hand them an undo for a move they never made — and push the
        // place they genuinely came from off the end of a bounded stack.
        assertTrue(teleports.lastLocationOf(player.player()).isEmpty(),
                "a teleport that moved nobody was recorded anyway");
    }

    // ----------------------------------------------------------- the two bounds

    @Test
    @DisplayName("the stack never grows past the configured size")
    void theStackIsBounded() {
        teleports.using(new TeleportSettings(0.0, true, true, 5, 32, 300, 0.5,
                3, 30, 60, 8, 16));

        for (int step = 1; step <= 10; step++) {
            player.at(new Location(world, step * 10, 64, step * 10));
            teleports.to(player.player(), new Location(world, step, 70, step)).start();
            settle();
        }

        // An unbounded deque per player is a leak with a nicer name.
        assertEquals(3, BackHistory.sizeOf(player.player().getUniqueId()),
                "the history grew past the size the owner configured");
    }

    @Test
    @DisplayName("bouncing between two places does not grow the stack")
    void pingPongingDoesNotGrowIt() {
        teleports.to(player.player(), destination).start();
        settle();
        assertEquals(1, BackHistory.sizeOf(player.player().getUniqueId()));

        // Going back pops one entry and arriving pushes one, so a player
        // bouncing between two places forever holds exactly one.
        teleports.back(player.player()).start();
        settle();
        assertEquals(1, BackHistory.sizeOf(player.player().getUniqueId()));

        teleports.back(player.player()).start();
        settle();
        assertEquals(1, BackHistory.sizeOf(player.player().getUniqueId()),
                "two undos in a row grew the history they were undoing");
    }

    @Test
    @DisplayName("a place older than the configured age is not offered")
    void aStaleEntryIsNotOffered() {
        teleports.to(player.player(), destination).start();
        settle();
        assertTrue(teleports.lastLocationOf(player.player()).isPresent());

        // Thirty-one minutes, with the default limit of thirty. A place a
        // player left half an hour ago is not somewhere they meant to come
        // back to: offering it turns an undo into a surprise.
        clock.addAndGet(Duration.ofMinutes(31).toMillis());

        assertTrue(teleports.lastLocationOf(player.player()).isEmpty(),
                "a place older than the limit was still offered");

        TeleportHandle handle = teleports.back(player.player()).start();
        assertEquals(TeleportResult.NOTHING_TO_GO_BACK_TO, resultOf(handle));
    }

    // ------------------------------------------------------------- nothing there

    @Test
    @DisplayName("a first /back of a session says there is nowhere to go")
    void nothingRecordedSaysSo() {
        TeleportHandle handle = teleports.back(player.player()).start();

        assertEquals(TeleportResult.NOTHING_TO_GO_BACK_TO, resultOf(handle));
        assertTrue(player.teleports().isEmpty(), "a /back with nowhere to go moved them anyway");
    }

    @Test
    @DisplayName("forgetting the history leaves nothing to go back to")
    void forgettingClearsIt() {
        teleports.to(player.player(), destination).start();
        settle();
        assertTrue(teleports.lastLocationOf(player.player()).isPresent());

        teleports.forgetHistory(player.player());

        assertTrue(teleports.lastLocationOf(player.player()).isEmpty());
    }

    @Test
    @DisplayName("quitting forgets everywhere that player had been")
    void quittingForgetsIt() {
        teleports.to(player.player(), destination).start();
        settle();
        assertTrue(teleports.lastLocationOf(player.player()).isPresent());

        player.disconnect();
        FakeServer.dispatch(new org.bukkit.event.player.PlayerQuitEvent(
                player.player(), net.kyori.adventure.text.Component.empty(),
                org.bukkit.event.player.PlayerQuitEvent.QuitReason.DISCONNECTED));

        assertEquals(0, BackHistory.sizeOf(player.player().getUniqueId()),
                "a player who left kept their history");
    }

    // ----------------------------------------------- the entry comes back

    @Test
    @DisplayName("a /back that is cancelled puts the entry back")
    void aCancelledBackIsRefunded() {
        teleports.to(player.player(), destination).start();
        settle();
        player.at(destination);

        TeleportHandle handle = teleports.back(player.player())
                .warmup(2.0)
                .cancelOnMove()
                .start();
        // The entry is spent the moment the request is built, which is what
        // stops two points growing the stack.
        assertEquals(0, BackHistory.sizeOf(player.player().getUniqueId()));

        FakeServer.tick(5);
        walkTo(new Location(world, 1, 70, 0));

        assertEquals(TeleportResult.CANCELLED_ON_MOVE, resultOf(handle));
        // An undo the player never received is not one they should have spent.
        // The same rule the cooldown refund follows, and it is the one thing
        // about /back that is invisible until somebody counts their history.
        assertEquals(1, BackHistory.sizeOf(player.player().getUniqueId()),
                "a cancelled /back spent the entry anyway");
        assertEquals(100.0, teleports.lastLocationOf(player.player()).orElseThrow().x(), 0.001);
    }

    @Test
    @DisplayName("a /back refused by a cooldown puts the entry back")
    void aRefusedBackIsRefunded() {
        teleports.to(player.player(), destination).start();
        settle();
        Cooldowns.start(player.player(), "back", Duration.ofSeconds(30));

        TeleportHandle handle = teleports.back(player.player())
                .cooldown("back", 30.0)
                .start();

        assertEquals(TeleportResult.ON_COOLDOWN, resultOf(handle));
        // Refused before it started is exactly as much of a back-teleport the
        // player never received as one cancelled halfway through.
        assertEquals(1, BackHistory.sizeOf(player.player().getUniqueId()),
                "a /back refused by a cooldown spent the entry anyway");
    }

    @Test
    @DisplayName("a /back vetoed by another plugin puts the entry back")
    void aVetoedBackIsRefunded() {
        teleports.to(player.player(), destination).start();
        settle();

        FakeServer.deliverEvents();
        org.bukkit.Bukkit.getPluginManager().registerEvents(new TeleportTest.Vetoer(), plugin);

        TeleportHandle handle = teleports.back(player.player()).start();
        settle();

        assertEquals(TeleportResult.CANCELLED_BY_EVENT, resultOf(handle));
        assertEquals(1, BackHistory.sizeOf(player.player().getUniqueId()),
                "a vetoed /back spent the entry anyway");
    }

    @Test
    @DisplayName("a caller's own callback throwing does not cost them the entry")
    void aThrowingCallbackDoesNotEatTheEntry() {
        teleports.to(player.player(), destination).start();
        settle();

        FakeServer.deliverEvents();
        org.bukkit.Bukkit.getPluginManager().registerEvents(new TeleportTest.Vetoer(), plugin);

        // The module's bookkeeping runs first and separately guarded, so a
        // consumer whose handler throws cannot take the refund with it.
        teleports.back(player.player())
                .then(result -> {
                    throw new IllegalStateException("the consumer's own bug");
                })
                .start();
        settle();

        assertEquals(1, BackHistory.sizeOf(player.player().getUniqueId()),
                "a throwing consumer callback cost the player their undo");
    }

    // ---------------------------------------------------------------- helpers

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

    @Test
    @DisplayName("a place with no world recorded for it is simply not recorded")
    void aWorldlessOriginIsNotRecorded() {
        player.at(new Location(null, 1, 2, 3));

        TeleportHandle handle = teleports.to(player.player(), destination).start();
        settle();

        assertEquals(TeleportResult.SUCCESS, resultOf(handle));
        // Nothing here can stop a teleport that already happened, and a place
        // with no world is not worth a line in the console every time.
        assertFalse(teleports.lastLocationOf(player.player()).isPresent());
    }
}
