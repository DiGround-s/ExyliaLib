package net.exylia.lib.screen.internal;

import net.exylia.lib.screen.Motion;
import net.exylia.lib.screen.ScreenScene;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The motion kit on a clock the test turns: what runs when, and what is left behind. */
class ScreenSceneMotionTest {

    private static final class Clock implements ScreenScene.Host {
        final TreeMap<Integer, List<Runnable>> due = new TreeMap<>();
        int now;
        boolean reduced;
        boolean spare = true;

        @Override
        public void redraw() {
        }

        @Override
        public void later(int ticks, @NotNull Runnable step) {
            due.computeIfAbsent(now + ticks, key -> new ArrayList<>()).add(step);
        }

        @Override
        public boolean reduced() {
            return reduced;
        }

        @Override
        public boolean spare() {
            return spare;
        }

        void run(int ticks) {
            int end = now + ticks;
            while (!due.isEmpty() && due.firstKey() <= end) {
                now = due.firstKey();
                due.pollFirstEntry().getValue().forEach(Runnable::run);
            }
            now = end;
        }
    }

    private final Clock clock = new Clock();
    private final ScreenScene scene = new ScreenScene("table", clock);

    private List<Layout.Placed> laid() {
        return Layout.lay(scene.node(100, 100), 100, 100);
    }

    private long shards() {
        return laid().stream().filter(p -> p.key().contains("~shard")).count();
    }

    @Test
    void aTimelineRunsItsStepsInOrderAndStopsWhenCancelled() {
        List<String> ran = new ArrayList<>();
        ScreenScene.Timeline timeline = scene.timeline()
                .at(0, () -> ran.add("now")).at(4, () -> ran.add("four")).after(2, () -> ran.add("six"));
        assertEquals(6, timeline.length());
        timeline.play();
        assertEquals(List.of("now"), ran);
        clock.run(4);
        assertEquals(List.of("now", "four"), ran);
        timeline.cancel();
        clock.run(10);
        assertEquals(List.of("now", "four"), ran);
    }

    @Test
    void aFlipChangesTheFaceOnlyAtItsEdge() {
        boolean[] face = {false};
        ScreenScene.Sprite tile = scene.sprite("tile").rect(10, 10, "#000000").at(0, 0);
        tile.flip(6, () -> face[0] = true);
        assertFalse(face[0]);
        assertEquals(0, squeeze(), 1e-9);
        clock.run(3);
        assertTrue(face[0]);
        assertEquals(1, squeeze(), 1e-9);
    }

    private double squeeze() {
        return laid().stream().filter(p -> p.key().contains("tile")).findFirst().orElseThrow().node().transform().squeeze();
    }

    @Test
    void reducedMotionGoesToTheEndAtOnce() {
        clock.reduced = true;
        boolean[] face = {false};
        scene.sprite("tile").rect(10, 10, "#000000").flip(6, () -> face[0] = true);
        assertTrue(face[0]);
        assertEquals(1, squeeze(), 1e-9);
        scene.burst(50, 50, 8, 20, 6, "#ffffff");
        assertEquals(0, shards());
        List<Double> shown = new ArrayList<>();
        scene.countUp(0, 10, 20, shown::add);
        assertEquals(List.of(10d), shown);
    }

    @Test
    void aBurstLeavesNothingBehindAndCountsAsDecoration() {
        scene.burst(50, 50, 8, 20, 6, "#ffffff", "#000000");
        assertEquals(8, shards());
        Layout.Placed first = laid().stream().filter(p -> p.key().contains("~shard")).findFirst().orElseThrow();
        assertTrue(first.node().decoration());
        clock.run(1);
        Layout.Placed moved = laid().stream().filter(p -> p.key().contains("~shard")).findFirst().orElseThrow();
        assertEquals(6, moved.node().transition().ticks());
        clock.run(6);
        assertEquals(0, shards());
    }

    @Test
    void pastTheCeilingDecorationWaits() {
        clock.spare = false;
        scene.burst(50, 50, 8, 20, 6, "#ffffff");
        assertEquals(0, shards());
        scene.shake(2, 8);
        clock.run(8);
        assertTrue(scene.node(100, 100).transform().none());
    }

    @Test
    void aShakeDiesAwayAndEndsWhereItStarted() {
        scene.shake(4, 8);
        assertEquals(4, scene.node(100, 100).transform().x(), 1e-9);
        clock.run(2);
        assertEquals(-3, scene.node(100, 100).transform().x(), 1e-9);
        clock.run(6);
        assertTrue(scene.node(100, 100).transform().none());
    }

    @Test
    void aCountReachesItsTargetExactly() {
        List<Double> shown = new ArrayList<>();
        scene.countUp(100, 241, 12, shown::add);
        clock.run(12);
        assertEquals(241, shown.getLast(), 0);
        assertEquals(6, shown.size());
        for (int i = 1; i < shown.size(); i++) assertTrue(shown.get(i) >= shown.get(i - 1));
    }

    @Test
    void squeezeNarrowsAcrossOnly() {
        Motion.Transform half = Motion.Transform.squeeze(0.5);
        assertEquals(0.5, half.with(Motion.Transform.scale(2)).squeeze(), 1e-9);
        assertEquals(2, half.with(Motion.Transform.scale(2)).scale(), 1e-9);
        assertEquals(new Motion.Transform(1, 2, 3, 1), new Motion.Transform(1, 2, 3));
        assertEquals(0.5, MotionCss.transform("scaleX(0.5)").squeeze(), 1e-9);
    }
}
