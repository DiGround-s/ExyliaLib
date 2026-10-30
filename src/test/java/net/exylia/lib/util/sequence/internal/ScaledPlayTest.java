package net.exylia.lib.util.sequence.internal;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.display.DisplayKeyframe;
import net.exylia.lib.display.DisplayMotion;
import net.exylia.lib.display.Rotation;
import net.exylia.lib.npc.NpcModel;
import net.exylia.lib.npc.NpcMotion;
import net.exylia.lib.util.sequence.SequenceFactory;
import net.exylia.lib.util.sequence.SequenceTarget;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A sequence played as a miniature: {@link SequenceTarget#scale} and what each piece does with it. */
class ScaledPlayTest {

    private static final double EPSILON = 1e-5;
    private World world;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        world = FakeServer.newWorld("scaled");
    }

    @AfterEach
    void tearDown() {
        FakeServer.reset();
    }

    private static net.exylia.lib.util.sequence.SequenceRun run() {
        net.exylia.lib.task.TaskScheduler idle = (net.exylia.lib.task.TaskScheduler) java.lang.reflect.Proxy.newProxyInstance(
                ScaledPlayTest.class.getClassLoader(), new Class<?>[]{net.exylia.lib.task.TaskScheduler.class},
                (proxy, method, args) -> null);
        return SequenceFactory.run(idle, () -> { });
    }

    @Test
    @DisplayName("the scale is 1 by default, survives every copy of the target and refuses nonsense")
    void scaleSurvivesCopies() {
        SequenceTarget plain = SequenceTarget.at(new Location(world, 0, 64, 0));
        assertEquals(1.0, plain.scale());
        assertFalse(plain.isScaled());

        Player viewer = new FakePlayer("Viewer").at(new Location(world, 0, 64, 3)).player();
        SequenceTarget small = plain.scale(0.4).by(viewer).onlyTo(viewer).within(8)
                .movedTo(new Location(world, 1, 64, 0)).follow(viewer).wearing(null);
        assertEquals(0.4, small.scale(), EPSILON);
        assertTrue(small.isScaled());

        assertThrows(IllegalArgumentException.class, () -> plain.scale(0));
        assertThrows(IllegalArgumentException.class, () -> plain.scale(-1));
        assertThrows(IllegalArgumentException.class, () -> plain.scale(Double.NaN));
    }

    @Test
    @DisplayName("a shape's points shrink about the anchor, rotation and all")
    void shapeShrinksAboutTheAnchor() {
        Location anchor = new Location(world, 10, 64, 10);
        Player viewer = new FakePlayer("Viewer").at(anchor.clone().add(0, 0, 3)).player();
        FakeServer.online(viewer);
        List<double[]> drawn = new ArrayList<>();
        Paint recording = (observers, at, x, y, z) -> drawn.add(new double[]{x, y, z});
        ShapeStep ring = ShapeStep.of(recording, List.of(new Vector(2, 0.5, 0), new Vector(0, 3, -1)),
                1, 50, 90, false);

        ring.play(SequenceTarget.at(anchor).onlyTo(viewer), run());
        List<double[]> full = List.copyOf(drawn);
        drawn.clear();
        ring.play(SequenceTarget.at(anchor).onlyTo(viewer).scale(0.25), run());

        assertEquals(2, full.size());
        assertEquals(2, drawn.size());
        for (int i = 0; i < full.size(); i++) {
            for (int axis = 0; axis < 3; axis++) {
                assertEquals(full.get(i)[axis] * 0.25, drawn.get(i)[axis], EPSILON, "point " + i + " axis " + axis);
            }
        }
    }

    @Test
    @DisplayName("a magnified motion scales offsets and sizes, and above() keeps every pose over the floor")
    void motionMagnifiesAndStaysAboveTheFloor() {
        DisplayMotion thrown = DisplayMotion.of(List.of(
                new DisplayKeyframe(0, 0f, 1f, 0f, Rotation.NONE, 1f, 1f, 1f),
                new DisplayKeyframe(500, 2f, 3f, 0f, Rotation.NONE, 0.5f, 0.5f, 0.5f),
                new DisplayKeyframe(1000, 4f, -6f, 0f, Rotation.NONE, 0.5f, 0.5f, 0.5f)), 1000);

        DisplayMotion small = thrown.magnified(0.5);
        DisplayKeyframe peak = small.poses().get(1);
        assertEquals(1f, peak.x(), EPSILON);
        assertEquals(1.5f, peak.y(), EPSILON);
        assertEquals(0.25f, peak.scaleX(), EPSILON);

        DisplayMotion landed = small.above(-0.2);
        assertEquals(-0.2f, landed.poses().get(2).y(), EPSILON, "the fall stops on the floor");
        assertEquals(2f, landed.poses().get(2).x(), EPSILON, "and keeps going sideways");
        assertEquals(small.poses().get(1), landed.poses().get(1), "poses above the floor are untouched");
        assertSame(small, small.above(-10), "nothing that low: the same motion");
    }

    @Test
    @DisplayName("a magnified NPC motion moves and falls that much less, on the same clock")
    void npcMotionMagnifies() {
        NpcMotion full = NpcMotion.builder().from(0, 0, 1).to(2, 4, 0).gravity(9).over(1000).turn(90).build();
        NpcMotion small = full.magnified(0.4);
        for (long t : new long[]{0, 250, 500, 1000, 1500}) {
            double[] a = full.at(t);
            double[] b = small.at(t);
            for (int axis = 0; axis < 3; axis++) {
                assertEquals(a[axis] * 0.4, b[axis], EPSILON, "t=" + t + " axis " + axis);
            }
            assertEquals(full.turnedBy(t), small.turnedBy(t), EPSILON, "turns are not scaled");
        }
        assertSame(full, full.magnified(1.0));
    }

    @Test
    @DisplayName("a nameless NPC has a generated, protocol-sized name that is never the player's")
    void namelessNpc() {
        Player owner = new FakePlayer("Ground_").at(new Location(world, 0, 64, 0)).player();
        NpcModel named = NpcModel.of(owner);
        NpcModel hidden = named.nameless().scale(0.4);

        assertFalse(named.isNameless());
        assertEquals("Ground_", named.name());
        assertTrue(hidden.isNameless(), "kept through a copy");
        assertNotEquals("Ground_", hidden.name());
        assertTrue(hidden.name().length() <= 16, hidden.name());
        assertTrue(hidden.name().matches("[a-z0-9]+"), hidden.name());
        assertEquals(named.id(), hidden.id());
        assertSame(owner, hidden.skinFrom(), "the skin is kept");
    }
}
