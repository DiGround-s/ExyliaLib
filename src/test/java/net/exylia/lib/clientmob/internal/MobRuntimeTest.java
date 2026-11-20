package net.exylia.lib.clientmob.internal;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.clientmob.ClientMob;
import net.exylia.lib.clientmob.ClientMobs;
import net.exylia.lib.interaction.Click;
import net.exylia.lib.task.Tasks;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MobRuntimeTest {

    private final List<String> sent = new CopyOnWriteArrayList<>();
    private final List<String> clicks = new CopyOnWriteArrayList<>();
    private World world;
    private FakePlayer owner;
    private ClientMobs mobs;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Plugin plugin = FakeServer.newPlugin("MobTest", null);
        AtomicInteger ids = new AtomicInteger(7000);
        MobRuntime.testHooks(Tasks.of(plugin), ids::getAndIncrement, new MobSink() {
            @Override public void spawn(MobImpl m) { sent.add("spawn " + m.id); }
            @Override public void despawn(MobImpl m) { sent.add("despawn " + m.id); }
            @Override public void move(MobImpl m, double dx, double dy, double dz) { sent.add("move"); }
            @Override public void teleport(MobImpl m) { sent.add("teleport"); }
            @Override public void rotate(MobImpl m) { sent.add("rotate"); }
            @Override public void swing(MobImpl m) { sent.add("swing"); }
            @Override public void status(MobImpl m, byte s) { sent.add("status " + s); }
            @Override public void state(MobImpl m) { sent.add("state"); }
            @Override public void glow(MobImpl m) { sent.add("glow"); }
        });
        world = FakeServer.newWorld("world");
        owner = new FakePlayer("Owner").at(new Location(world, 0, 64, 0));
        FakeServer.online(owner.player());
        mobs = ClientMobs.of(plugin).range(32).onClick((v, m, c) -> clicks.add(v.getName() + " " + c));
    }

    @AfterEach
    void tearDown() {
        MobRuntime.removeEverything();
        FakeServer.reset();
    }

    private static void pass() {
        FakeServer.tick((int) MobRuntime.PERIOD_TICKS + 1);
    }

    @Test
    void sentOnlyWhileTheViewerIsNear() {
        MobImpl mob = (MobImpl) mobs.spawn(owner.player(), EntityType.ZOMBIE, new Location(world, 10, 64, 0));
        pass();
        assertEquals(List.of("spawn " + mob.id), sent);
        owner.at(new Location(world, 60, 64, 0));
        pass();
        assertEquals("despawn " + mob.id, sent.get(1));
    }

    @Test
    void walkArrivesAndCompletes() {
        ClientMob mob = mobs.spawn(owner.player(), EntityType.IRON_GOLEM, new Location(world, 5, 64, 0));
        pass();
        sent.clear();
        CompletableFuture<Void> done = mob.moveTo(new Location(world, 5, 64, 2), 4.0); // 0.5 s: 5 steps
        assertFalse(done.isDone());
        FakeServer.tick(20);
        assertTrue(done.isDone());
        assertEquals(2.0, mob.location().getZ(), 1e-9);
        assertEquals(0f, mob.location().getYaw(), 1e-4);
        assertEquals("teleport", sent.get(sent.size() - 1));
        assertEquals(4, sent.stream().filter("move"::equals).count());
        sent.clear();
        FakeServer.tick(20);
        assertTrue(sent.isEmpty()); // idle: nothing sent
    }

    @Test
    void newerMoveReplacesTheOldOne() {
        ClientMob mob = mobs.spawn(owner.player(), EntityType.ZOMBIE, new Location(world, 5, 64, 0));
        CompletableFuture<Void> first = mob.moveTo(new Location(world, 5, 64, 50), 1.0);
        CompletableFuture<Void> second = mob.moveTo(new Location(world, 5, 64, 1), 10.0);
        assertTrue(first.isDone());
        assertFalse(second.isDone());
        FakeServer.tick(10);
        assertTrue(second.isDone());
    }

    @Test
    void walkingWhileOffScreenStillArrives() {
        ClientMob mob = mobs.spawn(owner.player(), EntityType.ZOMBIE, new Location(world, 200, 64, 0));
        CompletableFuture<Void> done = mob.moveTo(new Location(world, 200, 64, 1), 10.0);
        FakeServer.tick(10);
        assertTrue(done.isDone());
        assertTrue(sent.isEmpty());
    }

    @Test
    void removeAndQuitCompleteWalks() {
        ClientMob a = mobs.spawn(owner.player(), EntityType.ZOMBIE, new Location(world, 5, 64, 0));
        CompletableFuture<Void> f = a.moveTo(new Location(world, 5, 64, 50), 1.0);
        a.remove();
        assertTrue(f.isDone());
        assertTrue(a.isRemoved());
        ClientMob b = mobs.spawn(owner.player(), EntityType.ZOMBIE, new Location(world, 5, 64, 0));
        MobRuntime.forget(owner.player());
        assertTrue(b.isRemoved());
        assertEquals(0, mobs.clear(owner.player()));
    }

    @Test
    void clicksCountForTheViewerWithinReach() {
        MobImpl mob = (MobImpl) mobs.spawn(owner.player(), EntityType.COW, new Location(world, 3, 64, 0));
        FakePlayer stranger = new FakePlayer("Stranger").at(new Location(world, 3, 64, 0));
        FakeServer.online(stranger.player());
        MobRuntime.clicked(owner.player(), mob.id, Click.LEFT);
        MobRuntime.clicked(stranger.player(), mob.id, Click.LEFT);
        owner.at(new Location(world, 30, 64, 0));
        MobRuntime.clicked(owner.player(), mob.id, Click.RIGHT);
        FakeServer.tick(2);
        assertEquals(List.of("Owner LEFT"), clicks);
    }

    @Test
    void changesReachTheClientOnlyWhileSent() {
        ClientMob mob = mobs.spawn(owner.player(), EntityType.WOLF, new Location(world, 100, 64, 0));
        mob.sitting(true);
        mob.swing();
        assertTrue(sent.isEmpty());
        owner.at(new Location(world, 98, 64, 0));
        pass();
        mob.swing();
        mob.status((byte) 4);
        mob.sitting(true); // unchanged
        mob.sitting(false);
        assertEquals(List.of("spawn " + ((MobImpl) mob).id, "swing", "status 4", "state"), sent);
    }

    @Test
    void aRespawnSendsEveryMobAgain() {
        MobImpl mob = (MobImpl) mobs.spawn(owner.player(), EntityType.COW, new Location(world, 2, 64, 0));
        pass();
        MobRuntime.respawned(owner.player());
        pass();
        assertEquals(List.of("spawn " + mob.id, "spawn " + mob.id), sent);
    }
}
