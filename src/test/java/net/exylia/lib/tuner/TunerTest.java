package net.exylia.lib.tuner;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.effect.internal.EffectRuntime;
import net.exylia.lib.effect.internal.Packets;
import net.exylia.lib.text.Colors;
import net.exylia.lib.text.Palette;
import net.exylia.lib.tuner.internal.TunerListener;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TunerTest {

    private Plugin plugin;
    private FakePlayer steve;
    private FakePlayer alex;
    private final double[] x = {0.5};
    private final double[] yaw = {0};
    private final List<TunerEnd> ends = new ArrayList<>();
    private int changes;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Packets.override(false);
        Colors.apply(new Palette());
        plugin = FakeServer.newPlugin("Tuning", null);
        Location at = new Location(FakeServer.newWorld("world"), 0, 64, 0);
        steve = new FakePlayer("Steve").at(at);
        alex = new FakePlayer("Alex").at(at);
    }

    @AfterEach
    void tearDown() {
        Tuner.ACTIVE.clear();
        EffectRuntime.stopEverything();
        EffectRuntime.releaseAll();
        FakeServer.reset();
    }

    private Tuner tuner() {
        return Tuner.of(plugin, "spawn").key("holo:spawn")
                .axis(TunerAxis.blocks("X", () -> x[0], value -> x[0] = value))
                .axis(TunerAxis.degrees("Yaw", () -> yaw[0], value -> yaw[0] = value))
                .onChange(session -> changes++)
                .onEnd((session, end) -> ends.add(end));
    }

    @Test
    @DisplayName("the wheel reads up and down across the hotbar's wrap, and ignores number-key jumps")
    void notches() {
        assertEquals(1, TunerListener.notch(4, 3));
        assertEquals(-1, TunerListener.notch(4, 5));
        assertEquals(1, TunerListener.notch(0, 8));
        assertEquals(-1, TunerListener.notch(8, 0));
        assertEquals(0, TunerListener.notch(2, 6));
        assertEquals(0, TunerListener.notch(3, 3));
    }

    @Test
    @DisplayName("a notch moves the value by the step, and sneak changes the step")
    void nudgeAndStep() {
        TunerSession session = tuner().start(steve.player()).orElseThrow();

        session.nudge(1);
        assertEquals(0.6, x[0], 1e-9);
        session.shiftStep(1);
        session.nudge(-2);
        assertEquals(-0.4, x[0], 1e-9);
        session.shiftStep(99);
        assertEquals(1, session.axis().currentStep());
        assertEquals(2, changes);
    }

    @Test
    @DisplayName("F walks the axes and wraps; angles fold and snap to 15 degrees")
    void axesAndAngles() {
        TunerSession session = tuner().start(steve.player()).orElseThrow();

        session.cycle(1);
        assertEquals("Yaw", session.axis().label());
        session.shiftStep(2); // 45
        for (int i = 0; i < 5; i++) session.nudge(1);
        assertEquals(-135, yaw[0], 1e-9);
        session.shiftStep(-2); // 5
        session.nudge(1);
        session.snap();
        assertEquals(-135, yaw[0], 1e-9);
        session.cycle(1);
        assertEquals("X", session.axis().label());
    }

    @Test
    @DisplayName("a range stops the value at its bound without telling the consumer it changed")
    void range() {
        double[] scale = {1};
        TunerSession session = Tuner.of(plugin, "s")
                .axis(TunerAxis.of("Scale", () -> scale[0], v -> scale[0] = v).steps(0.5).range(0.5, 1.5))
                .onChange(s -> changes++)
                .start(steve.player()).orElseThrow();

        session.nudge(5);
        assertEquals(1.5, scale[0], 1e-9);
        session.nudge(1);
        assertEquals(1, changes);
    }

    @Test
    @DisplayName("a relative axis hands over distances and shows how far it went")
    void relative() {
        List<Double> moves = new ArrayList<>();
        TunerSession session = Tuner.of(plugin, "s")
                .axis(TunerAxis.relative("Forward", moves::add).step(4))
                .start(steve.player()).orElseThrow();

        session.nudge(2);
        session.nudge(-1);
        assertEquals(List.of(2.0, -1.0), moves);
        assertEquals(1, session.axis().value(), 1e-9);
    }

    @Test
    @DisplayName("one player per key, one session per player, and each ends exactly once")
    void exclusivity() {
        TunerSession first = tuner().start(steve.player()).orElseThrow();
        assertTrue(tuner().start(alex.player()).isEmpty());

        TunerSession second = tuner().start(steve.player()).orElseThrow();
        assertFalse(first.isActive());
        assertEquals(List.of(TunerEnd.REPLACED), ends);

        second.cancel();
        second.save();
        assertEquals(List.of(TunerEnd.REPLACED, TunerEnd.CANCELLED), ends);
        assertFalse(TunerEnd.CANCELLED.kept());
        assertTrue(Tuner.active(steve.player()).isEmpty());
        assertTrue(tuner().start(alex.player()).isPresent());
    }

    @Test
    @DisplayName("disabling the plugin ends its sessions keeping the values")
    void release() {
        tuner().start(steve.player()).orElseThrow();

        assertEquals(1, Tuner.release("Tuning"));
        assertEquals(List.of(TunerEnd.DISABLED), ends);
        assertTrue(TunerEnd.DISABLED.kept());
        assertEquals(0, Tuner.release("Tuning"));
    }

    @Test
    @DisplayName("values read cleanly on the HUD")
    void format() {
        assertEquals("0.3", TunerAxis.format(0.1 + 0.2));
        assertEquals("15", TunerAxis.format(15.0));
        assertEquals("-0.05", TunerAxis.format(-0.05));
        assertEquals(-180, TunerAxis.fold(540), 1e-9);
    }
}
