package net.exylia.lib.util.teleport;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.util.Cooldowns;
import net.exylia.lib.util.teleport.internal.TeleportRuntime;
import net.exylia.lib.util.teleport.internal.TpaBook;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a request promises: the right player moves, in the direction they were
 * asked about, and only while the request is still one.
 *
 * <p>The direction is the part worth testing hardest. A {@code /tpahere} that
 * moves the wrong player looks exactly like a working {@code /tpa} to the code
 * and exactly like a kidnapping to the person it happened to, so both ways
 * round are asserted separately rather than one being assumed from the other.
 */
class TeleportTpaTest {

    private Plugin plugin;
    private World world;
    private FakePlayer sender;
    private FakePlayer target;
    private PluginTeleports teleports;

    private Location senderPlace;
    private Location targetPlace;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        plugin = FakeServer.newPlugin("Practice");
        world = FakeServer.newWorld("lobby");
        FakeServer.worlds(world);

        senderPlace = new Location(world, 10, 64, 10, 0f, 0f);
        targetPlace = new Location(world, 500, 70, 500, 0f, 0f);

        sender = new FakePlayer("DiGround");
        sender.at(senderPlace);
        target = new FakePlayer("Exylia");
        target.at(targetPlace);
        FakeServer.online(sender.player(), target.player());

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

    // ------------------------------------------------------------- direction

    @Test
    @DisplayName("/tpa moves the sender to the target")
    void toTargetMovesTheSender() {
        assertEquals(TpaOutcome.SENT,
                teleports.request(sender.player(), target.player(), TeleportDirection.TO_TARGET));

        TpaAcceptance accepted = teleports.accept(target.player(), sender.player());
        assertTrue(accepted.isAccepted());
        accepted.teleport().orElseThrow().start();
        settle();

        assertEquals(1, sender.teleports().size(), "the sender was never moved");
        assertEquals(500.0, sender.teleports().get(0).getX(), 0.001,
                "the sender did not arrive at the target");
        assertTrue(target.teleports().isEmpty(),
                "the target moved, and answering a /tpa must cost them nothing");
    }

    @Test
    @DisplayName("/tpahere moves the target to the sender")
    void toSenderMovesTheTarget() {
        assertEquals(TpaOutcome.SENT,
                teleports.request(sender.player(), target.player(), TeleportDirection.TO_SENDER));

        TpaAcceptance accepted = teleports.accept(target.player(), sender.player());
        accepted.teleport().orElseThrow().start();
        settle();

        // The one who answers is the one who moves. Getting this backwards is
        // the classic bug and it is invisible from the accepting side.
        assertEquals(1, target.teleports().size(), "the target was never moved");
        assertEquals(10.0, target.teleports().get(0).getX(), 0.001,
                "the target did not arrive at the sender");
        assertTrue(sender.teleports().isEmpty(), "the sender moved on a /tpahere");
    }

    // --------------------------------------------------------------- refusals

    @Test
    @DisplayName("asking to be teleported to yourself is refused")
    void askingYourselfIsRefused() {
        assertEquals(TpaOutcome.SELF,
                teleports.request(sender.player(), sender.player(), TeleportDirection.TO_TARGET));
        assertEquals(0, TpaBook.countFor(sender.player().getUniqueId()));
    }

    @Test
    @DisplayName("the same request twice is not filed twice")
    void aDuplicateIsRefused() {
        assertEquals(TpaOutcome.SENT,
                teleports.request(sender.player(), target.player(), TeleportDirection.TO_TARGET));
        assertEquals(TpaOutcome.ALREADY_PENDING,
                teleports.request(sender.player(), target.player(), TeleportDirection.TO_TARGET));

        assertEquals(1, teleports.pendingFor(target.player()).size(),
                "one sender filed two live requests against one target");
    }

    @Test
    @DisplayName("a target already at their limit is refused")
    void aFullTargetIsRefused() {
        // Two at most, so the anti-spam limit is reachable without standing up
        // nine players to prove a number.
        teleports.using(new TeleportSettings(0.0, true, true, 5, 32, 300, 0.5,
                3, 30, 60, 2, 16));

        FakePlayer first = onlineAt("First", new Location(world, 1, 64, 1));
        FakePlayer second = onlineAt("Second", new Location(world, 2, 64, 2));
        FakePlayer third = onlineAt("Third", new Location(world, 3, 64, 3));

        assertEquals(TpaOutcome.SENT,
                teleports.request(first.player(), target.player(), TeleportDirection.TO_TARGET));
        assertEquals(TpaOutcome.SENT,
                teleports.request(second.player(), target.player(), TeleportDirection.TO_TARGET));

        // Without the limit one player can bury another's list until the
        // request they actually wanted is unfindable, at no cost to the sender.
        assertEquals(TpaOutcome.TARGET_BUSY,
                teleports.request(third.player(), target.player(), TeleportDirection.TO_TARGET));
        assertEquals(2, teleports.pendingFor(target.player()).size());
    }

    @Test
    @DisplayName("accepting a request nobody sent says there is none")
    void acceptingNothingSaysSo() {
        TpaAcceptance accepted = teleports.accept(target.player(), sender.player());

        assertEquals(TpaOutcome.NO_REQUEST, accepted.outcome());
        assertTrue(accepted.teleport().isEmpty());
        assertTrue(sender.teleports().isEmpty());
    }

    // ----------------------------------------------------------------- expiry

    @Test
    @DisplayName("a request that ran out cannot be accepted, and says why")
    void anExpiredRequestIsNotAcceptable() {
        // Filed directly with an expiry already behind us, because the module
        // reads the clock rather than counting down: there is no timer here to
        // wind forward, only a moment to compare against.
        TpaBook.put(new TeleportRequestTicket(
                sender.player().getUniqueId(), target.player().getUniqueId(),
                TeleportDirection.TO_TARGET, Instant.now().minusSeconds(1), plugin));

        TpaAcceptance accepted = teleports.accept(target.player(), sender.player());

        // "There was one, and it ran out" and "there is no request from that
        // person" send the player to check two different things.
        assertEquals(TpaOutcome.EXPIRED, accepted.outcome());
        assertTrue(accepted.teleport().isEmpty());
        assertTrue(sender.teleports().isEmpty(), "an expired request still moved somebody");
    }

    @Test
    @DisplayName("an expired request is not listed as waiting")
    void anExpiredRequestIsNotListed() {
        TpaBook.put(new TeleportRequestTicket(
                sender.player().getUniqueId(), target.player().getUniqueId(),
                TeleportDirection.TO_TARGET, Instant.now().minusSeconds(1), plugin));

        assertTrue(teleports.pendingFor(target.player()).isEmpty());
        assertTrue(teleports.pendingFor(target.player(), sender.player()).isEmpty());
    }

    @Test
    @DisplayName("expireStale reports how many it dropped")
    void expireStaleCounts() {
        FakePlayer other = onlineAt("Other", new Location(world, 4, 64, 4));
        teleports.request(other.player(), target.player(), TeleportDirection.TO_TARGET);
        // Filed last on purpose: sending a request counts what the target is
        // sitting on, and counting is a read, and a read drops what has run
        // out. Adding the stale one first would leave nothing for this to find.
        TpaBook.put(new TeleportRequestTicket(
                sender.player().getUniqueId(), target.player().getUniqueId(),
                TeleportDirection.TO_TARGET, Instant.now().minusSeconds(1), plugin));

        assertEquals(1, teleports.expireStale(), "only the expired one should be dropped");
        assertEquals(1, teleports.pendingFor(target.player()).size());
    }

    // ------------------------------------------------------------- withdrawal

    @Test
    @DisplayName("denying removes the request")
    void denyingRemovesIt() {
        teleports.request(sender.player(), target.player(), TeleportDirection.TO_TARGET);

        assertEquals(TpaOutcome.DENIED, teleports.deny(target.player(), sender.player()));

        assertTrue(teleports.pendingFor(target.player()).isEmpty());
        assertEquals(TpaOutcome.NO_REQUEST,
                teleports.accept(target.player(), sender.player()).outcome());
    }

    @Test
    @DisplayName("the sender can withdraw their own request")
    void cancellingRemovesIt() {
        teleports.request(sender.player(), target.player(), TeleportDirection.TO_TARGET);

        assertEquals(TpaOutcome.CANCELLED, teleports.cancel(sender.player(), target.player()));
        assertTrue(teleports.pendingFor(target.player()).isEmpty());
    }

    @Test
    @DisplayName("accepting one twice does not move the player twice")
    void acceptingIsOnce() {
        teleports.request(sender.player(), target.player(), TeleportDirection.TO_TARGET);

        teleports.accept(target.player(), sender.player()).teleport().orElseThrow().start();
        settle();

        assertEquals(TpaOutcome.NO_REQUEST,
                teleports.accept(target.player(), sender.player()).outcome());
        assertEquals(1, sender.teleports().size(), "one request moved them twice");
    }

    // ---------------------------------------------------------------- leaving

    @Test
    @DisplayName("quitting clears the requests a player is on either side of")
    void quittingClearsBothRoles() {
        FakePlayer other = onlineAt("Other", new Location(world, 5, 64, 5));

        // One where they were asked, one where they did the asking.
        teleports.request(sender.player(), target.player(), TeleportDirection.TO_TARGET);
        teleports.request(other.player(), sender.player(), TeleportDirection.TO_TARGET);
        assertEquals(1, teleports.pendingFor(target.player()).size());
        assertEquals(1, teleports.pendingFor(sender.player()).size());

        sender.disconnect();
        FakeServer.dispatch(new org.bukkit.event.player.PlayerQuitEvent(
                sender.player(), net.kyori.adventure.text.Component.empty(),
                org.bukkit.event.player.PlayerQuitEvent.QuitReason.DISCONNECTED));

        // Leaving only the ones they sent would let somebody accept a visit
        // from a person who is not on the server.
        assertTrue(teleports.pendingFor(target.player()).isEmpty(),
                "a request from a player who left survived them");
        assertTrue(teleports.pendingFor(sender.player()).isEmpty(),
                "a request waiting for a player who left survived them");
    }

    // ---------------------------------------------------- the unstarted request

    @Test
    @DisplayName("the request from accept is not started")
    void acceptDoesNotStartIt() {
        teleports.request(sender.player(), target.player(), TeleportDirection.TO_TARGET);

        teleports.accept(target.player(), sender.player());
        settle();

        // The module has no idea whether this server makes people stand still
        // first or charges a cooldown; answering that on the caller's behalf
        // would be wrong on most servers.
        assertTrue(sender.teleports().isEmpty(),
                "accepting moved the player before the caller had described it");
    }

    @Test
    @DisplayName("the request from accept takes a warmup and honours it")
    void theAcceptedRequestTakesAWarmup() {
        teleports.request(sender.player(), target.player(), TeleportDirection.TO_TARGET);

        TeleportHandle handle = teleports.accept(target.player(), sender.player())
                .teleport().orElseThrow()
                .warmup(1.0)
                .start();

        FakeServer.tick(15);
        assertTrue(sender.teleports().isEmpty(), "moved before the countdown finished");
        assertFalse(handle.isDone());

        FakeServer.tick(10);
        assertEquals(TeleportResult.SUCCESS, resultOf(handle));
        assertEquals(1, sender.teleports().size());
    }

    @Test
    @DisplayName("an accepted request carries the tpa cause")
    void theAcceptedRequestSaysWhy() {
        FakeServer.deliverEvents();
        TeleportTest.Recorder recorder = new TeleportTest.Recorder();
        org.bukkit.Bukkit.getPluginManager().registerEvents(recorder, plugin);

        teleports.request(sender.player(), target.player(), TeleportDirection.TO_TARGET);
        teleports.accept(target.player(), sender.player()).teleport().orElseThrow().start();
        settle();

        assertEquals(TeleportCause.TPA, recorder.seen().cause());
    }

    // ---------------------------------------------------------------- helpers

    private FakePlayer onlineAt(String name, Location where) {
        FakePlayer added = new FakePlayer(name);
        added.at(where);
        java.util.List<org.bukkit.entity.Player> everybody =
                new java.util.ArrayList<>(org.bukkit.Bukkit.getOnlinePlayers());
        everybody.add(added.player());
        FakeServer.online(everybody.toArray(org.bukkit.entity.Player[]::new));
        return added;
    }

    private static TeleportResult resultOf(TeleportHandle handle) {
        assertTrue(handle.isDone(), "the teleport never completed");
        return handle.future().join();
    }

    private static void settle() {
        FakeServer.tick(3);
    }
}
