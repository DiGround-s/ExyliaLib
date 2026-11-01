package net.exylia.lib.respawn.internal;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import com.google.common.collect.ImmutableSet;
import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.respawn.RespawnContext;
import net.exylia.lib.respawn.RespawnHook;
import net.exylia.lib.respawn.Respawns;
import net.exylia.lib.util.teleport.Teleports;
import net.exylia.lib.util.teleport.internal.TeleportRuntime;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerRespawnEvent.RespawnReason;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Respawn rules and callbacks, through Paper's events and through the Folia watch. */
class RespawnTest {

    private Plugin library;
    private Plugin plugin;
    private World world;
    private FakePlayer player;
    private Location death;
    private Location vanilla;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        library = FakeServer.newPlugin("ExyliaLib");
        plugin = FakeServer.newPlugin("Practice");
        world = FakeServer.newWorld("world");
        FakeServer.worlds(world);
        death = new Location(world, 500, 64, 500);
        vanilla = new Location(world, 0, 64, 0);
        player = new FakePlayer("DiGround");
        player.at(death);
        FakeServer.online(player.player());
        TeleportRuntime.resetForTests();
        TeleportRuntime.init(library);
        RespawnRuntime.resetForTests();
        Respawns.release(plugin.getName());
    }

    @AfterEach
    void tearDown() {
        Respawns.release(plugin.getName());
        RespawnRuntime.resetForTests();
        Teleports.releaseAll();
        TeleportRuntime.resetForTests();
        FakeServer.reset();
    }

    // ------------------------------------------------------------------ Paper

    @Test
    @DisplayName("rules run in priority order, each seeing the last answer, and the last non-null wins")
    void priorityOrder() {
        paper();
        Location low = new Location(world, 1, 64, 1);
        Location high = new Location(world, 2, 64, 2);
        List<String> seen = new ArrayList<>();
        Respawns.of(plugin).location(EventPriority.HIGH, ctx -> {
            seen.add("high@" + ctx.target().getBlockX());
            return high;
        });
        Respawns.of(plugin).location(EventPriority.NORMAL, ctx -> {
            seen.add("normal@" + ctx.target().getBlockX());
            return null;
        });
        Respawns.of(plugin).location(EventPriority.LOW, ctx -> {
            seen.add("low@" + ctx.target().getBlockX());
            return low;
        });

        PlayerRespawnEvent event = respawn(RespawnReason.DEATH, false, false);
        RespawnRuntime.fireRules(event);

        assertEquals(List.of("low@0", "normal@1", "high@1"), seen);
        assertEquals(high, event.getRespawnLocation());
    }

    @Test
    @DisplayName("a rule answering null keeps the respawn where it was")
    void nullKeeps() {
        paper();
        Respawns.of(plugin).location(EventPriority.NORMAL, ctx -> null);
        PlayerRespawnEvent event = respawn(RespawnReason.DEATH, false, false);
        RespawnRuntime.fireRules(event);
        assertEquals(vanilla, event.getRespawnLocation());
    }

    @Test
    @DisplayName("rules see only deaths unless they opt in to other reasons")
    void reasons() {
        paper();
        AtomicInteger deathsOnly = new AtomicInteger();
        AtomicInteger portals = new AtomicInteger();
        Respawns.of(plugin).location(EventPriority.NORMAL, ctx -> {
            deathsOnly.incrementAndGet();
            return null;
        });
        Respawns.of(plugin).location(EventPriority.NORMAL, ctx -> {
            portals.incrementAndGet();
            assertNull(ctx.deathLocation(), "leaving the End is no death");
            return null;
        }, RespawnReason.END_PORTAL);

        RespawnRuntime.fireRules(respawn(RespawnReason.END_PORTAL, false, false));

        assertEquals(0, deathsOnly.get());
        assertEquals(1, portals.get());
    }

    @Test
    @DisplayName("Paper: the bed and anchor flags reach the rule")
    void paperBedFlag() {
        paper();
        List<Boolean> beds = new ArrayList<>();
        Respawns.of(plugin).location(EventPriority.NORMAL, ctx -> {
            beds.add(ctx.bedSpawn());
            return null;
        });
        RespawnRuntime.fireRules(respawn(RespawnReason.DEATH, false, false));
        RespawnRuntime.fireRules(respawn(RespawnReason.DEATH, true, false));
        RespawnRuntime.fireRules(respawn(RespawnReason.DEATH, false, true));
        assertEquals(List.of(false, true, true), beds);
    }

    @Test
    @DisplayName("Paper: callbacks run once, on the post-respawn event")
    void paperAfterOnPostRespawn() {
        paper();
        AtomicInteger ran = new AtomicInteger();
        Respawns.of(plugin).after(p -> ran.incrementAndGet());

        FakeServer.dispatch(respawn(RespawnReason.DEATH, false, false));
        assertEquals(0, ran.get(), "not before the player stands");
        FakeServer.dispatch(new PlayerPostRespawnEvent(player.player(), vanilla, false));
        assertEquals(1, ran.get());
        FakeServer.tick(3);
        assertEquals(1, ran.get(), "the tick fallback does not run them again");
    }

    @Test
    @DisplayName("without a post-respawn event, callbacks run a tick after the respawn")
    void afterFallback() {
        paper();
        AtomicInteger ran = new AtomicInteger();
        Respawns.of(plugin).after(p -> ran.incrementAndGet());
        FakeServer.dispatch(respawn(RespawnReason.END_PORTAL, false, false));
        FakeServer.tick(3);
        assertEquals(1, ran.get());
    }

    @Test
    @DisplayName("Paper: a death starts no watch")
    void paperDoesNotWatch() {
        paper();
        die();
        assertFalse(RespawnRuntime.polling());
        assertTrue(RespawnRuntime.PENDING.isEmpty());
    }

    // ------------------------------------------------------------------ Folia

    @Test
    @DisplayName("Folia: once the player stands, the rules move them and then the callbacks run")
    void foliaMovesThenRunsAfter() {
        folia();
        Location spawn = new Location(world, 50, 70, 50);
        List<RespawnContext> asked = new ArrayList<>();
        List<Location> standingAt = new ArrayList<>();
        Respawns.of(plugin).location(EventPriority.HIGH, ctx -> {
            asked.add(ctx);
            return spawn;
        });
        Respawns.of(plugin).after(p -> standingAt.add(p.getLocation().clone()));

        die();
        FakeServer.tick(10);
        assertTrue(asked.isEmpty(), "nothing while the respawn screen is open");

        standUp(vanilla);
        FakeServer.tick(10);

        assertEquals(1, asked.size());
        assertEquals(vanilla, asked.getFirst().target(), "the rule sees where vanilla put them");
        assertEquals(death, asked.getFirst().deathLocation());
        assertEquals(RespawnReason.DEATH, asked.getFirst().reason());
        assertEquals(List.of(spawn), player.teleports());
        assertEquals(List.of(spawn), standingAt, "callbacks after the move, once");
        assertFalse(RespawnRuntime.polling(), "the watch stops once nobody is dead");
    }

    @Test
    @DisplayName("Folia: rules that keep the landing move nobody, and callbacks still run")
    void foliaNullKeeps() {
        folia();
        Respawns.of(plugin).location(EventPriority.NORMAL, ctx -> null);
        Respawns.of(plugin).location(EventPriority.HIGH, ctx -> ctx.target());
        AtomicInteger ran = new AtomicInteger();
        Respawns.of(plugin).after(p -> ran.incrementAndGet());

        die();
        standUp(vanilla);
        FakeServer.tick(10);

        assertTrue(player.teleports().isEmpty());
        assertEquals(1, ran.get());
    }

    @Test
    @DisplayName("Folia: a landing next to the stored respawn point is a bed respawn")
    void foliaBedFlag() {
        folia();
        List<Boolean> beds = new ArrayList<>();
        Respawns.of(plugin).location(EventPriority.NORMAL, ctx -> {
            beds.add(ctx.bedSpawn());
            return null;
        });

        player.respawnPoint(new Location(world, 1, 64, 1));
        die();
        standUp(vanilla);
        FakeServer.tick(10);

        // The bed was gone: vanilla used the world spawn, far from it.
        player.respawnPoint(new Location(world, 300, 64, 300));
        die();
        standUp(vanilla);
        FakeServer.tick(10);

        player.respawnPoint(null);
        die();
        standUp(vanilla);
        FakeServer.tick(10);

        assertEquals(List.of(true, false, false), beds);
    }

    @Test
    @DisplayName("Folia: a respawn event for the death claims it, so nothing runs twice")
    void eventClaimsTheDeath() {
        folia();
        AtomicInteger rules = new AtomicInteger();
        AtomicInteger afters = new AtomicInteger();
        Respawns.of(plugin).location(EventPriority.NORMAL, ctx -> {
            rules.incrementAndGet();
            return null;
        });
        Respawns.of(plugin).after(p -> afters.incrementAndGet());

        die();
        PlayerRespawnEvent event = respawn(RespawnReason.DEATH, false, false);
        FakeServer.dispatch(event);
        RespawnRuntime.fireRules(event);
        standUp(vanilla);
        FakeServer.tick(10);

        assertEquals(1, rules.get());
        assertEquals(1, afters.get());
        assertTrue(player.teleports().isEmpty());
    }

    @Test
    @DisplayName("Folia: quitting on the respawn screen forgets the death")
    void quitWhileDead() {
        folia();
        AtomicInteger rules = new AtomicInteger();
        Respawns.of(plugin).location(EventPriority.NORMAL, ctx -> {
            rules.incrementAndGet();
            return null;
        });

        die();
        FakeServer.dispatch(new PlayerQuitEvent(player.player(), Component.empty(),
                PlayerQuitEvent.QuitReason.DISCONNECTED));
        player.revive();
        FakeServer.tick(10);

        assertEquals(0, rules.get());
        assertFalse(RespawnRuntime.polling());
    }

    @Test
    @DisplayName("Folia: a long respawn screen keeps one hop in flight, not one per tick")
    void longRespawnScreen() {
        folia();
        die();
        FakeServer.tick(400);
        assertTrue(RespawnRuntime.polling());
        assertTrue(FakeServer.liveTasks() <= 3, "only the watch and its hop: " + FakeServer.liveTasks());
    }

    @Test
    @DisplayName("a cancelled rule and a released plugin no longer run")
    void cancelAndRelease() {
        folia();
        AtomicInteger cancelled = new AtomicInteger();
        AtomicInteger released = new AtomicInteger();
        RespawnHook hook = Respawns.of(plugin).location(EventPriority.NORMAL, ctx -> {
            cancelled.incrementAndGet();
            return null;
        });
        Plugin other = FakeServer.newPlugin("Other");
        Respawns.of(other).after(p -> released.incrementAndGet());
        hook.cancel();
        Respawns.release(other.getName());

        die();
        standUp(vanilla);
        FakeServer.tick(10);

        assertEquals(0, cancelled.get());
        assertEquals(0, released.get());
    }

    // ---------------------------------------------------------------- helpers

    private void paper() {
        RespawnRuntime.init(library, false);
    }

    private void folia() {
        RespawnRuntime.init(library, true);
    }

    private void die() {
        player.at(death);
        player.die();
        DamageSource source = (DamageSource) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DamageSource.class}, (self, method, args) -> FakeServer.defaultValue(method.getReturnType()));
        FakeServer.dispatch(new PlayerDeathEvent(player.player(), source, new ArrayList<>(), 0, Component.empty()));
    }

    private void standUp(Location where) {
        player.at(where);
        player.revive();
    }

    private PlayerRespawnEvent respawn(RespawnReason reason, boolean bed, boolean anchor) {
        Player who = player.player();
        return new PlayerRespawnEvent(who, vanilla.clone(), bed, anchor, false, reason, ImmutableSet.builder());
    }
}
