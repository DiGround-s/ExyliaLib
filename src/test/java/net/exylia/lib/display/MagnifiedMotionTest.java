package net.exylia.lib.display;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class MagnifiedMotionTest {

    @Test
    void offsetsAndSizesGrowTimingAndTurnStay() {
        Rotation turn = Rotation.around(Rotation.Axis.Y, 1.0);
        DisplayMotion motion = DisplayMotion.of(List.of(
                new DisplayKeyframe(0, 1f, 2f, 3f, turn, 0.5f, 0.5f, 0.1f),
                new DisplayKeyframe(400, -1f, 0f, 0.5f, turn, 1f, 1f, 1f)), 2_000).looping(0, 400, 1, 1);
        DisplayMotion larger = motion.magnified(2.5);
        DisplayKeyframe first = larger.poses().getFirst();
        assertEquals(2.5f, first.x(), 1e-6);
        assertEquals(7.5f, first.z(), 1e-6);
        assertEquals(1.25f, first.scaleX(), 1e-6);
        assertEquals(0.25f, first.scaleZ(), 1e-6);
        assertEquals(turn, first.rotation());
        assertEquals(400, larger.poses().getLast().atMillis());
        assertEquals(2_000, larger.lifeMillis());
        assertEquals(400, larger.cycleMillis());
        assertSame(motion, motion.magnified(1));
        // Read back through the inverse, a pose is what it was.
        assertEquals(-1f, larger.poses().getLast().magnified(1 / 2.5).x(), 1e-6);
    }
}
