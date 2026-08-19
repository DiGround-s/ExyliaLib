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

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a teleport promises: the player arrives, or is told why not, and
 * nothing is left running behind them.
 *
 * <p>The countdown is the part worth testing hardest. Everything else is a
 * teleport; the countdown is six different things that can end it, several of
 * which can happen in the same tick.
 */
class TeleportTest {

    private Plugin plugin;
    private World world;
    private FakePlayer player;
    private PluginTeleports teleports;
    private Location origin;
    private Location destination;

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

        // What ExyliaLib does at startup: these listeners are what cancel a
        // countdown when the player moves, is hit or leaves.
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

    // --------------------------------------------------------------- instant

    @Test
    @DisplayName("a teleport with no countdown moves the player and reports success")
    void instantMoves() {
        TeleportHandle handle = teleports.to(player.player(), destination).start();
        settle();

        assertEquals(1, player.teleports().size(), "the player was never moved");
        assertEquals(0.0, player.teleports().get(0).getX(), 0.001);
        assertTrue(handle.isDone());
        assertEquals(TeleportResult.SUCCESS, resultOf(handle));
    }

    @Test
    @DisplayName("a finished teleport leaves nothing registered")
    void instantLeavesNothingBehind() {
        teleports.to(player.player(), destination).start();
        settle();

        assertEquals(0, Teleports.active(), "a finished teleport is not a running one");
        assertEquals(0, FakeServer.liveRepeatingTasks());
    }

    @Test
    @DisplayName("a player who already left is not teleported")
    void offlinePlayerIsNotMoved() {
        player.disconnect();

        TeleportHandle handle = teleports.to(player.player(), destination).start();

        assertTrue(player.teleports().isEmpty());
        assertEquals(TeleportResult.PLAYER_LEFT, resultOf(handle));
    }

    // -------------------------------------------------------------- countdown

    @Test
    @DisplayName("a countdown does not move the player until it elapses")
    void warmupWaits() {
        TeleportHandle handle = teleports.to(player.player(), destination)
                .warmup(1.0)
                .start();

        FakeServer.tick(15);
        assertTrue(player.teleports().isEmpty(), "moved before the countdown finished");
        assertFalse(handle.isDone());

        FakeServer.tick(10);
        assertEquals(1, player.teleports().size(), "never moved after the countdown finished");
        assertEquals(TeleportResult.SUCCESS, resultOf(handle));
    }

    @Test
    @DisplayName("a countdown reports how much is left")
    void warmupReportsRemaining() {
        double[] last = {-1};
        teleports.to(player.player(), destination)
                .warmup(1.0)
                .onTick(remaining -> last[0] = remaining)
                .start();

        // Reported once immediately, or the first frame of a countdown only
        // appears a quarter of a second in.
        assertEquals(1.0, last[0], 0.001);

        FakeServer.tick(6);
        assertEquals(0.75, last[0], 0.001);
    }

    @Test
    @DisplayName("a finished countdown leaves no repeating task behind")
    void warmupCleansUpItsTimer() {
        teleports.to(player.player(), destination).warmup(1.0).start();
        FakeServer.tick(25);

        assertEquals(0, FakeServer.liveRepeatingTasks(), "the countdown timer outlived it");
        assertEquals(0, Teleports.active());
    }

    // ----------------------------------------------------------- interruption

    @Test
    @DisplayName("moving during a countdown cancels it, and the player stays put")
    void movingCancels() {
        TeleportHandle handle = teleports.to(player.player(), destination)
                .warmup(2.0)
                .cancelOnMove()
                .start();

        FakeServer.tick(5);
        walkTo(new Location(world, 101, 64, 200));

        assertEquals(TeleportResult.CANCELLED_ON_MOVE, resultOf(handle));

        FakeServer.tick(50);
        assertTrue(player.teleports().isEmpty(), "a cancelled teleport still moved them");
        assertEquals(0, FakeServer.liveRepeatingTasks());
    }

    @Test
    @DisplayName("looking around during a countdown does not cancel it")
    void turningTheHeadDoesNotCancel() {
        TeleportHandle handle = teleports.to(player.player(), destination)
                .warmup(1.0)
                .cancelOnMove()
                .start();

        // Same block, different direction and a little sub-block drift: this is
        // what standing still actually sends. Cancelling on it would make a
        // countdown impossible to survive.
        walkTo(new Location(world, 100.3, 64, 200.2, 180f, 45f));
        FakeServer.tick(25);

        assertEquals(TeleportResult.SUCCESS, resultOf(handle));
        assertEquals(1, player.teleports().size());
    }

    @Test
    @DisplayName("moving does not cancel when the flag is off")
    void movingIsAllowedWhenAsked() {
        TeleportHandle handle = teleports.to(player.player(), destination)
                .warmup(1.0)
                .cancelOnMove(false)
                .start();

        walkTo(new Location(world, 110, 64, 210));
        FakeServer.tick(25);

        assertEquals(TeleportResult.SUCCESS, resultOf(handle));
    }

    @Test
    @DisplayName("damage during a countdown cancels it when the flag is on")
    void damageCancels() {
        TeleportHandle handle = teleports.to(player.player(), destination)
                .warmup(2.0)
                .cancelOnDamage()
                .start();

        FakeServer.tick(5);
        hit();

        assertEquals(TeleportResult.CANCELLED_ON_DAMAGE, resultOf(handle));
        assertTrue(player.teleports().isEmpty());
    }

    @Test
    @DisplayName("damage does not cancel when the flag is off")
    void damageIsAllowedWhenAsked() {
        TeleportHandle handle = teleports.to(player.player(), destination)
                .warmup(1.0)
                .cancelOnDamage(false)
                .start();

        hit();
        assertFalse(handle.isDone(), "being hit called it off after it was told not to");

        FakeServer.tick(25);
        assertEquals(TeleportResult.SUCCESS, resultOf(handle));
        assertEquals(1, player.teleports().size());
    }

    @Test
    @DisplayName("quitting during a countdown ends it and leaves no live task")
    void quittingEndsIt() {
        TeleportHandle handle = teleports.to(player.player(), destination)
                .warmup(3.0)
                .start();

        FakeServer.tick(5);
        player.disconnect();
        FakeServer.dispatch(new org.bukkit.event.player.PlayerQuitEvent(
                player.player(), net.kyori.adventure.text.Component.empty(),
                org.bukkit.event.player.PlayerQuitEvent.QuitReason.DISCONNECTED));

        assertEquals(TeleportResult.PLAYER_LEFT, resultOf(handle));
        assertEquals(0, FakeServer.liveRepeatingTasks(),
                "a countdown for a player who left kept ticking");
        assertEquals(0, Teleports.active());
        assertTrue(player.teleports().isEmpty());
    }

    @Test
    @DisplayName("cancelling by hand ends it")
    void cancellingByHandEndsIt() {
        TeleportHandle handle = teleports.to(player.player(), destination)
                .warmup(3.0)
                .start();

        handle.cancel();

        assertEquals(TeleportResult.CANCELLED_MANUALLY, resultOf(handle));
        assertEquals(0, FakeServer.liveRepeatingTasks());
    }

    @Test
    @DisplayName("a second teleport cancels the first rather than stacking")
    void oneCountdownPerPlayer() {
        TeleportHandle first = teleports.to(player.player(), destination).warmup(3.0).start();
        TeleportHandle second = teleports.to(player.player(),
                new Location(world, 50, 70, 50)).warmup(3.0).start();

        // Two countdowns would both fire, and the player would arrive at the
        // first destination only to be dragged to the second.
        assertTrue(first.isDone(), "the first must give way");
        assertFalse(second.isDone());
        assertEquals(1, Teleports.active());
    }

    // ----------------------------------------------------------- the cooldown

    @Test
    @DisplayName("a running cooldown refuses the teleport without moving anybody")
    void cooldownRefuses() {
        Cooldowns.start(player.player(), "warp", Duration.ofSeconds(30));

        TeleportHandle handle = teleports.to(player.player(), destination)
                .cooldown("warp", 30.0)
                .start();

        assertEquals(TeleportResult.ON_COOLDOWN, resultOf(handle));
        assertTrue(player.teleports().isEmpty(), "a refused teleport still moved them");
    }

    @Test
    @DisplayName("a successful teleport keeps the cooldown it claimed")
    void successKeepsTheCooldown() {
        teleports.to(player.player(), destination)
                .cooldown("warp", 30.0)
                .start();
        settle();

        assertTrue(Cooldowns.isActive(player.player(), "warp"),
                "a teleport that happened must still be charged for");
    }

    @Test
    @DisplayName("a cancelled countdown gives back the cooldown it claimed")
    void cancellingRefundsTheCooldown() {
        TeleportHandle handle = teleports.to(player.player(), destination)
                .warmup(3.0)
                .cooldown("warp", 30.0)
                .start();
        assertTrue(Cooldowns.isActive(player.player(), "warp"),
                "the cooldown is claimed when the countdown starts");

        FakeServer.tick(5);
        walkTo(new Location(world, 105, 64, 200));

        assertEquals(TeleportResult.CANCELLED_ON_MOVE, resultOf(handle));
        // The player never got the teleport. Charging them for one they did not
        // receive is a bug, and on a long warp cooldown it is the difference
        // between a cancelled teleport and a lost one.
        assertFalse(Cooldowns.isActive(player.player(), "warp"),
                "a cancelled teleport charged the player anyway");
    }

    @Test
    @DisplayName("a vetoed teleport gives back the cooldown it claimed")
    void vetoRefundsTheCooldown() {
        FakeServer.deliverEvents();
        org.bukkit.Bukkit.getPluginManager().registerEvents(new Vetoer(), plugin);

        TeleportHandle handle = teleports.to(player.player(), destination)
                .cooldown("warp", 30.0)
                .start();
        settle();

        assertEquals(TeleportResult.CANCELLED_BY_EVENT, resultOf(handle));
        // Same rule as a cancelled countdown, and for the same reason: the
        // player never arrived. Which of the two refusals it was is our
        // bookkeeping, not something they should pay a half-hour cooldown for.
        assertFalse(Cooldowns.isActive(player.player(), "warp"),
                "a vetoed teleport charged the player anyway");
    }

    @Test
    @DisplayName("a teleport that found nowhere safe gives back the cooldown")
    void noSafeLocationRefundsTheCooldown() {
        TeleportHandle handle = teleports.to(player.player(), destination)
                .safe()
                .cooldown("warp", 30.0)
                .start();
        settle();

        assertEquals(TeleportResult.NO_SAFE_LOCATION, resultOf(handle),
                "the fake world has no blocks, so nowhere is provably safe");
        assertFalse(Cooldowns.isActive(player.player(), "warp"),
                "a teleport that never happened charged the player anyway");
    }

    // --------------------------------------------------------------- the event

    @Test
    @DisplayName("another plugin can veto a teleport")
    void theEventCanCancelIt() {
        FakeServer.deliverEvents();
        org.bukkit.Bukkit.getPluginManager().registerEvents(new Vetoer(), plugin);

        TeleportHandle handle = teleports.to(player.player(), destination).start();
        settle();

        assertEquals(TeleportResult.CANCELLED_BY_EVENT, resultOf(handle));
        assertTrue(player.teleports().isEmpty(), "a vetoed teleport still moved them");
    }

    @Test
    @DisplayName("another plugin can send them somewhere else instead")
    void theEventCanRedirect() {
        FakeServer.deliverEvents();
        org.bukkit.Bukkit.getPluginManager().registerEvents(
                new Redirector(new Location(world, 500, 80, 500)), plugin);

        TeleportHandle handle = teleports.to(player.player(), destination).start();
        settle();

        assertEquals(TeleportResult.SUCCESS, resultOf(handle));
        assertEquals(500.0, player.teleports().get(0).getX(), 0.001,
                "the redirect was ignored");
    }

    @Test
    @DisplayName("the event carries why and who asked")
    void theEventSaysWhyAndWho() {
        FakeServer.deliverEvents();
        Recorder recorder = new Recorder();
        org.bukkit.Bukkit.getPluginManager().registerEvents(recorder, plugin);

        teleports.to(player.player(), destination).cause(TeleportCause.BACK).start();
        settle();

        assertEquals(TeleportCause.BACK, recorder.seen.cause());
        assertEquals(plugin, recorder.seen.requester());
    }

    // ------------------------------------------------------- unusable places

    @Test
    @DisplayName("an unreadable stored string fails rather than throwing")
    void garbageStringFails() {
        TeleportHandle handle = teleports.to(player.player(), "not a location").start();

        assertEquals(TeleportResult.FAILED, resultOf(handle));
        assertTrue(player.teleports().isEmpty());
    }

    @Test
    @DisplayName("a place in a world this server does not have is reported, not thrown")
    void unknownWorldIsReported() {
        TeleportHandle handle = teleports.to(player.player(), "nether,1,2,3,0,0").start();

        assertEquals(TeleportResult.WORLD_NOT_FOUND, resultOf(handle));
    }

    @Test
    @DisplayName("a place on another server says so")
    void otherServerIsReported() {
        TeleportHandle handle = teleports.to(player.player(),
                "practice-1,lobby,1,2,3,0,0").start();

        assertEquals(TeleportResult.CROSS_SERVER_UNAVAILABLE, resultOf(handle));
    }

    @Test
    @DisplayName("a stored local place teleports normally")
    void storedLocalPlaceWorks() {
        TeleportHandle handle = teleports.to(player.player(), "lobby,5,70,6,0,0").start();
        settle();

        assertEquals(TeleportResult.SUCCESS, resultOf(handle));
        assertEquals(5.0, player.teleports().get(0).getX(), 0.001);
    }

    // -------------------------------------------------------------- lifecycle

    @Test
    @DisplayName("releasing a plugin ends its countdowns and leaves no live task")
    void releasingEndsItsCountdowns() {
        TeleportHandle handle = teleports.to(player.player(), destination)
                .warmup(5.0)
                .start();

        Teleports.release(plugin.getName());

        assertTrue(handle.isDone(), "a disabled plugin left a countdown running");
        assertEquals(TeleportResult.CANCELLED_MANUALLY, resultOf(handle));
        assertEquals(0, FakeServer.liveRepeatingTasks(),
                "a countdown outlived the plugin that owned it");
        assertEquals(0, Teleports.active());
        assertTrue(player.teleports().isEmpty());
    }

    @Test
    @DisplayName("releasing one plugin leaves another plugin's countdown alone")
    void releasingIsPerPlugin() {
        Plugin other = FakeServer.newPlugin("Survival");
        FakePlayer someoneElse = new FakePlayer("Other");
        someoneElse.at(new Location(world, 10, 64, 10));
        FakeServer.online(player.player(), someoneElse.player());

        TeleportHandle mine = teleports.to(player.player(), destination).warmup(5.0).start();
        TeleportHandle theirs = Teleports.of(other)
                .to(someoneElse.player(), destination).warmup(5.0).start();

        Teleports.release(plugin.getName());

        assertTrue(mine.isDone());
        assertFalse(theirs.isDone(), "another plugin's countdown was taken down with it");

        Teleports.release(other.getName());
    }

    @Test
    @DisplayName("isWarmingUp answers for the player who is counting down")
    void warmingUpIsVisible() {
        assertFalse(Teleports.isWarmingUp(player.player()));

        TeleportHandle handle = teleports.to(player.player(), destination).warmup(3.0).start();
        assertTrue(Teleports.isWarmingUp(player.player()));

        handle.cancel();
        assertFalse(Teleports.isWarmingUp(player.player()));
    }

    @Test
    @DisplayName("cancelWarmup ends the countdown that player is in")
    void cancelWarmupEndsIt() {
        TeleportHandle handle = teleports.to(player.player(), destination).warmup(3.0).start();

        teleports.cancelWarmup(player.player());

        assertEquals(TeleportResult.CANCELLED_MANUALLY, resultOf(handle));
    }

    @Test
    @DisplayName("toAll moves everybody with no countdown")
    void toAllMovesEverybody() {
        FakePlayer someoneElse = new FakePlayer("Other");
        someoneElse.at(new Location(world, 10, 64, 10));
        FakeServer.online(player.player(), someoneElse.player());

        java.util.concurrent.CompletableFuture<Void> all =
                teleports.toAll(List.of(player.player(), someoneElse.player()), destination);
        settle();
        all.join();

        assertEquals(1, player.teleports().size());
        assertEquals(1, someoneElse.teleports().size());
    }

    // ---------------------------------------------------------------- helpers

    /**
     * The result, insisting it is already there.
     *
     * <p>Never a blocking join: a teleport that fails to complete is a real bug
     * this suite must catch, and joining on it would turn that bug into a suite
     * that hangs rather than one that goes red.
     */
    private static TeleportResult resultOf(TeleportHandle handle) {
        assertTrue(handle.isDone(), "the teleport never completed");
        return handle.future().join();
    }

    /**
     * Lets the queued work run.
     *
     * <p>A teleport hops onto the thread owning the player rather than moving
     * them inline, so on a real server it lands a tick later. The fake
     * scheduler only runs what it was given when the clock is turned, which is
     * what makes that hop visible here instead of being hidden by a main thread
     * that happens to be the caller.
     */
    private static void settle() {
        FakeServer.tick(3);
    }

    /** What the server sends when a player walks. */
    private void walkTo(Location to) {
        Location from = player.player().getLocation();
        player.at(to);
        FakeServer.dispatch(new org.bukkit.event.player.PlayerMoveEvent(
                player.player(), from, to));
    }

    /**
     * What the server sends when a player is hit.
     *
     * <p>The damage source is a proxy rather than a real one: building the real
     * one resolves a damage type through Paper's registry, which only exists
     * inside a running server. The module never reads it — it reads who was
     * hit — so a stand-in is enough and the alternative is no test at all.
     */
    private void hit() {
        org.bukkit.damage.DamageSource source = (org.bukkit.damage.DamageSource)
                java.lang.reflect.Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[]{org.bukkit.damage.DamageSource.class},
                        (self, method, args) -> FakeServer.defaultValue(method.getReturnType()));
        FakeServer.dispatch(new org.bukkit.event.entity.EntityDamageEvent(
                player.player(),
                org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                source, 2.0));
    }

    /** A plugin that refuses every teleport, like a combat-log check would. */
    public static final class Vetoer implements org.bukkit.event.Listener {
        @org.bukkit.event.EventHandler
        public void onTeleport(ExyliaTeleportEvent event) {
            event.setCancelled(true);
        }
    }

    /** A plugin that sends players somewhere else, like a region would. */
    public static final class Redirector implements org.bukkit.event.Listener {
        private final Location instead;

        Redirector(Location instead) {
            this.instead = instead;
        }

        @org.bukkit.event.EventHandler
        public void onTeleport(ExyliaTeleportEvent event) {
            event.setTo(instead);
        }
    }

    /** A plugin that only watches. */
    public static final class Recorder implements org.bukkit.event.Listener {
        private ExyliaTeleportEvent seen;

        @org.bukkit.event.EventHandler
        public void onTeleport(ExyliaTeleportEvent event) {
            seen = event;
        }

        /** The last teleport announced, for the suites in this package. */
        public ExyliaTeleportEvent seen() {
            return seen;
        }
    }
}
