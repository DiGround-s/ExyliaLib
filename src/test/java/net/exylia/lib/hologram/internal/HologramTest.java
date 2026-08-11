package net.exylia.lib.hologram.internal;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.hologram.Hologram;
import net.exylia.lib.hologram.HologramConfig;
import net.exylia.lib.hologram.Holograms;
import net.exylia.lib.placeholder.Placeholders;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Colors;
import net.exylia.lib.text.Palette;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a hologram actually writes to a client.
 *
 * <p>A hologram is packets, so every promise the module makes is a statement
 * about which packets are sent and, more importantly, which are not: a player
 * out of range gets nothing, a line whose value did not change is not re-sent,
 * and a hologram with no placeholders never sends anything twice.
 */
class HologramTest {

    private static final String PLUGIN = "HologramTest";

    private final AtomicLong clock = new AtomicLong(10_000L);
    private final AtomicInteger nextId = new AtomicInteger(1000);
    private final AtomicReference<String> capturer = new AtomicReference<>("nobody");

    private FakeSink sink;
    private Plugin plugin;
    private World world;
    private FakePlayer near;
    private FakePlayer far;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Colors.apply(new Palette());

        plugin = FakeServer.newPlugin(PLUGIN, null);
        sink = new FakeSink();
        HologramRuntime.testHooks(Tasks.of(plugin), plugin.getLogger(),
                clock::get, nextId::getAndIncrement, sink);

        world = FakeServer.newWorld("world");
        near = new FakePlayer("Near").at(new Location(world, 0, 64, 0));
        far = new FakePlayer("Far").at(new Location(world, 500, 64, 500));
        FakeServer.online(near.player(), far.player());

        Placeholders.group(plugin, "koth")
                .add("capturer", request -> capturer.get())
                .register();
    }

    @AfterEach
    void tearDown() {
        HologramRuntime.removeEverything();
        Placeholders.unregisterAll(PLUGIN);
        FakeServer.reset();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static HologramConfig text(List<String> lines) {
        return new HologramConfig(true, HologramConfig.Kind.TEXT, lines, "", "",
                false, 32.0, 0, 0, 0,
                new HologramConfig.Properties(), new HologramConfig.Refresh());
    }

    private Location spawn() {
        return new Location(world, 0, 64, 0);
    }

    /** Runs the runtime driver once. The delay is 5 ticks, so the first
     * run happens at tick 6. */
    private static void drive() {
        FakeServer.tick(6);
    }

    private void advanceSeconds(double seconds) {
        clock.addAndGet((long) (seconds * 1000));
    }

    // ------------------------------------------------------------------
    // Visibility
    // ------------------------------------------------------------------

    @Test
    @DisplayName("only players inside the view distance are sent the hologram")
    void onlyNearbyPlayersSeeIt() {
        Holograms.show(plugin, "koth", spawn(), text(List.of("Line one", "Line two")));
        drive();

        assertEquals(2, sink.count("spawn"), "one spawn per line, for the near player only");
        assertTrue(sink.calls("spawn").stream().allMatch(call -> call.contains(":Near:")));
    }

    @Test
    @DisplayName("walking into range sends it, walking out takes it back")
    void viewersFollowTheViewDistance() {
        Hologram hologram = Holograms.show(plugin, "koth", spawn(), text(List.of("Line")));
        drive();
        assertEquals(1, hologram.viewerCount());
        sink.clear();

        far.at(new Location(world, 5, 64, 5));
        drive();
        assertEquals(2, hologram.viewerCount());
        assertEquals(1, sink.count("spawn"));
        sink.clear();

        far.at(new Location(world, 500, 64, 500));
        drive();
        assertEquals(1, hologram.viewerCount());
        assertEquals(List.of("destroy:Far:1"), sink.calls("destroy"));
    }

    @Test
    @DisplayName("a player who stays in range is not sent it again every tick")
    void stayingInRangeSendsNothing() {
        Holograms.show(plugin, "koth", spawn(), text(List.of("Static line")));
        drive();
        sink.clear();

        advanceSeconds(10);
        drive();
        drive();

        assertEquals(List.of(), sink.calls());
    }

    @Test
    @DisplayName("a visibility filter hides it from players who fail it")
    void visibilityFilterIsApplied() {
        Hologram hologram = Holograms.show(plugin, "koth", spawn(), text(List.of("Secret")));
        drive();
        assertEquals(1, hologram.viewerCount());
        sink.clear();

        hologram.visibleIf(player -> false);
        drive();

        assertEquals(0, hologram.viewerCount());
        assertEquals(1, sink.count("destroy"));
    }

    @Test
    @DisplayName("a player in another world never sees it")
    void otherWorldsDoNotSeeIt() {
        World nether = FakeServer.newWorld("nether");
        near.at(new Location(nether, 0, 64, 0));

        Hologram hologram = Holograms.show(plugin, "koth", spawn(), text(List.of("Line")));
        drive();

        assertEquals(0, hologram.viewerCount());
    }

    // ------------------------------------------------------------------
    // Refreshing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("only the line whose value changed is re-sent")
    void onlyChangedLinesAreSent() {
        Holograms.show(plugin, "koth", spawn(),
                text(List.of("Capturing: %koth_capturer%", "Static line")));
        drive();
        sink.clear();

        capturer.set("Steve");
        advanceSeconds(1.1);
        drive();

        assertEquals(List.of("text:Near:1000:Capturing: Steve"), sink.calls("text"));
    }

    @Test
    @DisplayName("a hologram with no placeholders never refreshes")
    void staticHologramsNeverRefresh() {
        Holograms.show(plugin, "koth", spawn(), text(List.of("Spawn", "Welcome")));
        drive();
        sink.clear();

        advanceSeconds(60);
        drive();

        assertEquals(List.of(), sink.calls(), "a static hologram should cost nothing after spawn");
    }

    @Test
    @DisplayName("a refresh with nothing changed sends nothing")
    void unchangedRefreshSendsNothing() {
        Holograms.show(plugin, "koth", spawn(), text(List.of("Capturing: %koth_capturer%")));
        drive();
        sink.clear();

        advanceSeconds(5);
        drive();

        assertEquals(List.of(), sink.calls());
    }

    @Test
    @DisplayName("new data reaches the placeholders")
    void updateDataIsPickedUp() {
        Placeholders.register(plugin, "koth_arena",
                request -> request.get("arena", String.class));

        Hologram hologram = Holograms.show(plugin, "koth", spawn(),
                text(List.of("Arena: %koth_arena%")), Map.of("arena", "alpha"));
        drive();
        sink.clear();

        hologram.updateData(Map.of("arena", "beta"));
        advanceSeconds(1.1);
        drive();

        assertEquals(List.of("text:Near:1000:Arena: beta"), sink.calls("text"));
    }

    // ------------------------------------------------------------------
    // Moving and layout
    // ------------------------------------------------------------------

    @Test
    @DisplayName("lines stack downwards, so the first line is on top")
    void linesStackDownwards() {
        Holograms.show(plugin, "koth", spawn(), text(List.of("top", "middle", "bottom")));
        drive();

        // Spacing is 0.25 by default: the last line sits at the anchor.
        assertEquals(List.of("spawn:Near:1000:64.5", "spawn:Near:1001:64.25",
                "spawn:Near:1002:64.0"), sink.calls("spawn"));
    }

    @Test
    @DisplayName("moving teleports the displays instead of respawning them")
    void movingTeleports() {
        Hologram hologram = Holograms.show(plugin, "koth", spawn(), text(List.of("Line")));
        drive();
        sink.clear();

        hologram.moveTo(new Location(world, 0, 70, 0));

        assertEquals(List.of("teleport:Near:1000:70.0"), sink.calls("teleport"));
        assertEquals(0, sink.count("spawn"), "moving must not respawn the hologram");
        assertEquals(0, sink.count("destroy"));
    }

    @Test
    @DisplayName("the configured offset is applied to where it stands")
    void offsetIsApplied() {
        HologramConfig config = new HologramConfig(true, HologramConfig.Kind.TEXT,
                List.of("Line"), "", "", false, 32.0, 0, 2.0, 0,
                new HologramConfig.Properties(), new HologramConfig.Refresh());

        Hologram hologram = Holograms.show(plugin, "koth", spawn(), config);

        assertEquals(66.0, hologram.location().getY(), 0.001);
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Test
    @DisplayName("removing takes it off every screen")
    void removingDespawnsForEveryone() {
        Hologram hologram = Holograms.show(plugin, "koth", spawn(), text(List.of("a", "b")));
        drive();
        sink.clear();

        hologram.remove();

        assertTrue(hologram.removed());
        assertEquals(List.of("destroy:Near:2"), sink.calls("destroy"));
        assertEquals(0, HologramRuntime.count());
    }

    @Test
    @DisplayName("reusing an id replaces the hologram that had it")
    void reusingAnIdReplaces() {
        Hologram first = Holograms.show(plugin, "koth", spawn(), text(List.of("old")));
        drive();

        Holograms.show(plugin, "koth", spawn(), text(List.of("new")));
        drive();

        assertTrue(first.removed(), "the replaced hologram should be gone");
        assertEquals(1, HologramRuntime.count());
    }

    @Test
    @DisplayName("disabling a plugin removes its holograms")
    void disablingAPluginRemovesThem() {
        Hologram hologram = Holograms.show(plugin, "koth", spawn(), text(List.of("Line")));
        drive();

        assertEquals(1, HologramRuntime.removeAll(PLUGIN));

        assertTrue(hologram.removed());
        assertEquals(0, HologramRuntime.count());
    }

    @Test
    @DisplayName("the driver only runs while there are holograms")
    void driverStopsWhenIdle() {
        assertEquals(0, FakeServer.liveRepeatingTasks());

        Hologram hologram = Holograms.show(plugin, "koth", spawn(), text(List.of("Line")));
        drive();
        assertEquals(1, FakeServer.liveRepeatingTasks());

        hologram.remove();
        assertEquals(0, FakeServer.liveRepeatingTasks(),
                "the driver should stop once the last hologram is gone");
    }

    @Test
    @DisplayName("a disabled config shows nothing and sends no packets")
    void disabledConfigShowsNothing() {
        HologramConfig disabled = new HologramConfig(false, HologramConfig.Kind.TEXT,
                List.of("Line"), "", "", false, 32.0, 0, 0, 0,
                new HologramConfig.Properties(), new HologramConfig.Refresh());

        Hologram hologram = Holograms.show(plugin, "koth", spawn(), disabled);
        drive();

        assertTrue(hologram.removed());
        assertEquals(List.of(), sink.calls());
        assertEquals(0, HologramRuntime.count());
    }

    @Test
    @DisplayName("a player who leaves is forgotten without sending them packets")
    void leavingPlayersAreForgotten() {
        Hologram hologram = Holograms.show(plugin, "koth", spawn(), text(List.of("Line")));
        drive();
        sink.clear();

        near.disconnect();
        HologramRuntime.forget(near.player());

        assertFalse(hologram.isViewing(near.player()));
        assertEquals(List.of(), sink.calls(), "nothing should be sent to a client that is gone");
    }

    // ------------------------------------------------------------------
    // Per-player
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a per-player hologram resolves its lines for each viewer")
    void perPlayerResolvesPerViewer() {
        Placeholders.register(plugin, "koth_viewer",
                request -> request.hasViewer() ? request.requireViewer().getName() : "nobody");

        HologramConfig config = new HologramConfig(true, HologramConfig.Kind.TEXT,
                List.of("Hello %koth_viewer%"), "", "", true, 32.0, 0, 0, 0,
                new HologramConfig.Properties(), new HologramConfig.Refresh());

        far.at(new Location(world, 1, 64, 1));
        Holograms.show(plugin, "koth", spawn(), config);
        drive();
        sink.clear();

        advanceSeconds(1.1);
        drive();

        assertEquals(List.of("text:Near:1000:Hello Near", "text:Far:1000:Hello Far"),
                sink.calls("text"));
    }
}
