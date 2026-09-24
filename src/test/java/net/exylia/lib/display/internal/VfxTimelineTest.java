package net.exylia.lib.display.internal;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.display.DisplayKeyframe;
import net.exylia.lib.display.DisplayModel;
import net.exylia.lib.display.DisplayMotion;
import net.exylia.lib.display.DisplaySettings;
import net.exylia.lib.display.Vfx;
import net.exylia.lib.display.VfxRun;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a {@link Vfx} is scheduled and taken down.
 *
 * <p>Driven through the fake server's scheduler and a sink that counts what
 * would have reached a client: a display that is spawned and never destroyed
 * stays on that player's screen until they relog, so "cancel takes everything
 * down, once" is asserted on the packets, not on internal state.
 */
class VfxTimelineTest {

    private static final String OWNER = "VfxTest";

    private final AtomicInteger ids = new AtomicInteger();
    private final List<Integer> spawned = new ArrayList<>();
    private final Map<Integer, Integer> destroyed = new HashMap<>();

    private final DisplaySink sink = new DisplaySink() {
        @Override
        public synchronized void spawn(List<Player> viewers, int entityId, DisplayModel model,
                                       Location at, DisplayKeyframe pose) {
            spawned.add(entityId);
        }

        @Override
        public void pose(List<Player> viewers, int entityId, DisplayModel model,
                         DisplayKeyframe pose, int overTicks) {
        }

        @Override
        public void mount(List<Player> viewers, int vehicleId, int[] passengers) {
        }

        @Override
        public synchronized void destroy(List<Player> viewers, int entityId) {
            destroyed.merge(entityId, 1, Integer::sum);
        }
    };

    private Plugin plugin;
    private World world;
    private Location origin;
    private FakePlayer viewer;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        world = FakeServer.newWorld("vfx");
        FakeServer.worlds(world);
        origin = new Location(world, 0, 64, 0);
        viewer = new FakePlayer("Viewer").at(origin);
        FakeServer.online(viewer.player());
        plugin = FakeServer.newPlugin(OWNER);
        DisplayRuntime.testHooks(() -> 0L, ids::incrementAndGet, sink);
        DisplayRuntime.apply(new DisplaySettings());
    }

    @AfterEach
    void tearDown() {
        DisplayRuntime.release(OWNER);
        DisplayRuntime.testHooks(null, null, null);
        DisplayRuntime.apply(null);
        FakeServer.reset();
    }

    private static DisplayModel label() {
        return DisplayModel.text(Component.text("x"));
    }

    private Vfx watched() {
        return Vfx.at(origin).viewers(List.of(viewer.player()));
    }

    @Test
    @DisplayName("pieces are grouped by tick: one task per tick, the first tick inline")
    void groupsByTick() {
        Vfx vfx = watched()
                .display(0, label(), DisplayMotion.still(1000))
                .display(20, label(), DisplayMotion.still(1000))
                .display(60, label(), DisplayMotion.still(1000))
                .display(70, label(), DisplayMotion.still(1000))
                .display(500, label(), DisplayMotion.still(1000));

        vfx.play(plugin);

        assertEquals(2, spawned.size(), "what falls on the first tick is drawn at once");
        assertEquals(2, FakeServer.liveTasks(), "60 and 70 ms share a tick; 500 ms is another");
        FakeServer.tick(30);
        assertEquals(5, spawned.size());
        assertEquals(0, FakeServer.liveTasks(), "nothing is left scheduled once it has all run");
    }

    @Test
    @DisplayName("cancelling takes every display down exactly once and stops what was still to come")
    void cancelRemovesEachDisplayOnce() {
        VfxRun run = watched()
                .display(0, label(), DisplayMotion.still(5000))
                .display(0, label(), DisplayMotion.still(5000))
                .display(1000, label(), DisplayMotion.still(5000))
                .play(plugin);
        assertEquals(2, spawned.size());

        run.cancel();
        run.cancel();
        FakeServer.tick(40);

        assertEquals(2, spawned.size(), "the piece at one second never came");
        assertEquals(2, destroyed.size());
        for (int id : spawned) {
            assertEquals(1, destroyed.get(id), "display " + id + " was destroyed once");
        }
        assertEquals(0, DisplayRuntime.active(OWNER));
        assertTrue(run.isCancelled());
        assertTrue(run.isDone());
    }

    @Test
    @DisplayName("an effect holds at most max-per-effect displays; the first added are kept")
    void budgetCutsTheTail() {
        DisplayRuntime.apply(new DisplaySettings(20_000, 3));
        Vfx vfx = watched();
        for (int piece = 0; piece < 5; piece++) {
            vfx.display(piece * 100L, label(), DisplayMotion.still(200));
        }

        assertEquals(3, vfx.displays());
        assertEquals(2, vfx.dropped());
        vfx.play(plugin);
        FakeServer.tick(30);
        assertEquals(3, spawned.size());
    }

    @Test
    @DisplayName("the server budget still rules each display: a full server loses the tail")
    void serverBudgetStillRules() {
        DisplayRuntime.apply(new DisplaySettings(2, 128));
        watched()
                .display(0, label(), DisplayMotion.still(1000))
                .display(0, label(), DisplayMotion.still(1000))
                .display(0, label(), DisplayMotion.still(1000))
                .play(plugin);

        assertEquals(2, spawned.size());
    }

    @Test
    @DisplayName("with nobody watching, nothing is scheduled at all")
    void nobodyWatching() {
        VfxRun run = Vfx.at(origin)
                .display(0, label(), DisplayMotion.still(1000))
                .display(900, label(), DisplayMotion.still(1000))
                .play(plugin);

        assertEquals(0, FakeServer.liveTasks());
        assertEquals(0, spawned.size());
        assertTrue(run.isDone());
    }

    @Test
    @DisplayName("a shake tilts the viewers in range, as many times as asked, from the blow's side")
    void shakesViewersInRange() {
        FakePlayer ahead = new FakePlayer("Ahead").at(new Location(world, 0, 64, -5, 0f, 0f));
        FakePlayer far = new FakePlayer("Far").at(new Location(world, 30, 64, 0));
        FakeServer.online(viewer.player(), ahead.player(), far.player());

        Vfx.at(origin).viewers(List.of(ahead.player(), far.player()))
                .shake(0, 10, 2, 100)
                .play(plugin);
        FakeServer.tick(10);

        assertEquals(List.of(0f, 0f), ahead.hurtAnimations(),
                "twice, and straight ahead: the origin is south of a player facing south");
        assertTrue(far.hurtAnimations().isEmpty(), "thirty blocks is out of range");
    }

    @Test
    @DisplayName("more than fifteen viewers lowers the detail, unless it is forced")
    void levelOfDetail() {
        List<Player> crowd = new ArrayList<>();
        for (int index = 0; index < Vfx.LOD_VIEWERS; index++) {
            crowd.add(new FakePlayer("P" + index).player());
        }
        assertFalse(Vfx.at(origin).viewers(crowd).lod());
        crowd.add(new FakePlayer("One more").player());
        assertTrue(Vfx.at(origin).viewers(crowd).lod());
        assertFalse(Vfx.at(origin).viewers(crowd).lod(false).lod());
    }

    @Test
    @DisplayName("an effect lasts until its last piece is gone")
    void lengthCoversTheLastPiece() {
        Vfx vfx = watched()
                .display(200, label(), DisplayMotion.still(1500))
                .shake(1000, 8);

        assertEquals(1700, vfx.lengthMillis());
    }
}
