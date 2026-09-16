package net.exylia.lib.ragdoll;

import net.exylia.lib.display.DisplayKeyframe;
import net.exylia.lib.display.Rotation;
import net.exylia.lib.ragdoll.internal.RagdollPieces;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That a prop tied to a joint actually travels with it.
 *
 * <h2>Why this matters</h2>
 * Before this, a body could carry three things and every one of them had to be
 * an item: a flat sprite in a hand, or a whole block the size of a head. A shape
 * made of displays could be drawn near a body but never attached to it, because
 * a display is placed against the world and a hand is not in the world — it is
 * somewhere in the middle of an animation nobody has played yet.
 *
 * <p>So the thing worth asserting is the one that was impossible: take a block
 * hung off the right hand, take the right arm's own cells, and check they move
 * together. If the prop is solved against the world instead of against the
 * joint, the arm swings and the prop stays where it was.
 */
class RagdollPropTest {

    private static final Rotation NORTH = Rotation.around(Rotation.Axis.Y, 0);

    /** How long the test choreography runs before its finish takes over. */
    private static final long CHOREOGRAPHY = 1500;

    /** How far a player head is drawn above the joint it hangs from. */
    private static final double HEAD_LIFT = 0.25;

    /** An animation that throws the right arm from its side to over the head. */
    private static RagdollMotion swinging() {
        RagdollAnimation animation = RagdollAnimation.parse(
                "0.4 arm_r=0,0,0 | 0.6 arm_r=170,0,0 | 0.5 arm_r=0,0,0",
                problem -> {
                    throw new AssertionError(problem);
                });
        return RagdollMotion.builder()
                .pose(RagdollPose.ANIMATE)
                .animation(animation)
                .finish(RagdollFinish.HOLD)
                .intactFor(0)
                .build();
    }

    private static RagdollProp oneBlock(RagdollJoint joint) {
        List<String> problems = new ArrayList<>();
        RagdollProp prop = RagdollProp.parse("probe", joint, new float[3],
                List.of("at STONE y:0 s:0.1"), problems::add);
        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals(1, prop.blocks().size());
        return prop;
    }

    private static List<RagdollPieces.Piece> solve(List<RagdollProp> rigs) {
        return RagdollPieces.solve(swinging(), 1, 1.0, NORTH, new Random(7),
                EnumSet.noneOf(RagdollPieces.Prop.class), null, rigs);
    }

    @Test
    @DisplayName("a block tied to the hand moves with the arm that swings it")
    void thePropRidesTheJoint() {
        List<RagdollPieces.Piece> solved = solve(List.of(oneBlock(RagdollJoint.HAND_RIGHT)));

        RagdollPieces.Piece prop = solved.stream()
                .filter(piece -> piece.prop() == RagdollPieces.Prop.RIG)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the prop was not solved at all"));
        RagdollPieces.Piece arm = solved.stream()
                .filter(piece -> piece.prop() == null && piece.part() == RagdollPart.ARM_RIGHT)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no right arm to compare against"));

        assertEquals(Material.STONE, prop.block(), "the prop kept its own material");
        // The same clock, give or take the frame a fading body part adds for
        // itself at the end: a prop does not fade, it is not made of skin.
        assertTrue(Math.abs(arm.poses().size() - prop.poses().size()) <= 2,
                "the prop is on its own clock: " + prop.poses().size()
                        + " poses against the arm's " + arm.poses().size());

        // The arm swings through most of a half turn, so it travels. If the
        // prop were placed against the world it would not.
        double armMoved = travelled(arm.poses());
        double propMoved = travelled(prop.poses());
        assertTrue(armMoved > 0.3, "the test animation does not move the arm: " + armMoved);
        assertTrue(propMoved > 0.3,
                "the prop travelled " + String.format("%.3f", propMoved)
                        + " blocks while the arm travelled " + String.format("%.3f", armMoved)
                        + ": it is not riding the joint");
    }

    @Test
    @DisplayName("a block with no offset sits exactly where its joint is")
    void theOffsetIsInTheJointsOwnFrame() {
        // The exact form of "rigidly attached". FACE sits at the middle of the
        // head, so a block at no offset from it must land on the head's own
        // centre — every frame, to the float. If a prop were solved against the
        // world rather than against the joint, this diverges on frame two.
        List<String> problems = new ArrayList<>();
        RagdollProp pinned = RagdollProp.parse("pinned", RagdollJoint.FACE, new float[3],
                List.of("at STONE y:0 s:0.1"), problems::add);
        assertTrue(problems.isEmpty(), problems.toString());

        List<RagdollPieces.Piece> solved = solve(List.of(pinned));
        RagdollPieces.Piece prop = solved.stream()
                .filter(piece -> piece.prop() == RagdollPieces.Prop.RIG)
                .findFirst()
                .orElseThrow();
        RagdollPieces.Piece head = solved.stream()
                .filter(piece -> piece.prop() == null && piece.part() == RagdollPart.HEAD)
                .findFirst()
                .orElseThrow();

        // Sampled by time, never by index. Every piece's keyframes are thinned
        // on their own — a head that barely moves keeps three of them and an arm
        // that swings keeps twenty — so the fifth pose of one piece and the
        // fifth of another are different moments. Comparing them by index is how
        // two things that track each other perfectly look like they drift.
        // A head is drawn as a player head item, which hangs from its base, so
        // its display sits a quarter block above the joint it belongs to. That
        // quarter block is the only difference there may ever be between the two,
        // and it must be exactly that at every moment: the prop is on the joint.
        // Through the choreography. After its last frame the finish takes over,
        // the body settles and fades, and a prop does not go with it — it is not
        // made of skin.
        for (long at = 0; at <= CHOREOGRAPHY; at += 50) {
            double[] mine = where(prop.poses(), at);
            double[] theirs = where(head.poses(), at);
            assertEquals(theirs[0], mine[0], 1e-3, "x at " + at + "ms");
            assertEquals(theirs[1] - HEAD_LIFT, mine[1], 1e-3, "y at " + at + "ms");
            assertEquals(theirs[2], mine[2], 1e-3, "z at " + at + "ms");
        }
    }

    @Test
    @DisplayName("two joints carry two different props")
    void twoPropsAtOnce() {
        List<RagdollPieces.Piece> solved = solve(
                List.of(oneBlock(RagdollJoint.HAND_RIGHT), oneBlock(RagdollJoint.HEAD)));

        List<RagdollPieces.Piece> props = solved.stream()
                .filter(piece -> piece.prop() == RagdollPieces.Prop.RIG)
                .toList();
        assertEquals(2, props.size());
        assertTrue(props.stream().anyMatch(piece -> piece.part() == RagdollPart.ARM_RIGHT));
        assertTrue(props.stream().anyMatch(piece -> piece.part() == RagdollPart.HEAD));
    }

    @Test
    @DisplayName("a ring is as many blocks as it was asked for, in a circle")
    void ringsAreRound() {
        List<String> problems = new ArrayList<>();
        RagdollProp prop = RagdollProp.parse("ring", RagdollJoint.HEAD, new float[3],
                List.of("ring GOLD_BLOCK r:0.3 n:8 y:0.1 s:0.05"), problems::add);

        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals(8, prop.blocks().size());
        for (RagdollProp.Block block : prop.blocks()) {
            double radius = Math.hypot(block.x(), block.z());
            assertEquals(0.3, radius, 1e-4, "a ring's blocks sit on its radius");
            assertEquals(0.1f, block.y(), 1e-4);
            assertEquals(0.05f, block.size(), 1e-4);
        }
    }

    @Test
    @DisplayName("a line that cannot be read costs its own line and nothing else")
    void badLinesAreSkipped() {
        List<String> problems = new ArrayList<>();
        RagdollProp prop = RagdollProp.parse("mixed", RagdollJoint.HEAD, new float[3],
                List.of("at STONE y:0 s:0.1",
                        "ring NOT_A_MATERIAL r:0.3 n:4",
                        "wobble STONE",
                        "at STONE y:0.2 s:0.1 nonsense:3",
                        "at STONE y:0.3 s:0.1"), problems::add);

        assertEquals(3, problems.size(), problems.toString());
        assertEquals(2, prop.blocks().size(), "the two good lines still made blocks");
        assertFalse(prop.isEmpty());
    }

    @Test
    @DisplayName("a prop cannot be big enough to flood a client")
    void thereIsACeiling() {
        List<String> problems = new ArrayList<>();
        RagdollProp prop = RagdollProp.parse("huge", RagdollJoint.HEAD, new float[3],
                List.of("ring STONE r:2 n:200 s:0.1", "ring STONE r:3 n:200 s:0.1"),
                problems::add);

        assertTrue(prop.blocks().size() <= RagdollProp.MAX_BLOCKS,
                "a prop of " + prop.blocks().size() + " blocks got through");
        assertTrue(problems.stream().anyMatch(problem -> problem.contains("more than")),
                "and nobody was told: " + problems);
    }

    /**
     * Where a piece is at one moment, between the keyframes it was given.
     *
     * <p>The same reading the client does: a display is told a pose and how long
     * it has to reach it, and draws its own frames in between.
     */
    private static double[] where(List<DisplayKeyframe> poses, long millis) {
        DisplayKeyframe before = poses.get(0);
        for (DisplayKeyframe pose : poses) {
            if (pose.atMillis() > millis) {
                long span = pose.atMillis() - before.atMillis();
                double through = span <= 0 ? 0 : (double) (millis - before.atMillis()) / span;
                return new double[]{
                        before.x() + (pose.x() - before.x()) * through,
                        before.y() + (pose.y() - before.y()) * through,
                        before.z() + (pose.z() - before.z()) * through};
            }
            before = pose;
        }
        return new double[]{before.x(), before.y(), before.z()};
    }

    /** How far a piece's centre travels over the whole animation. */
    private static double travelled(List<DisplayKeyframe> poses) {
        double total = 0;
        for (int index = 1; index < poses.size(); index++) {
            total += apart(poses.get(index), poses.get(index - 1));
        }
        return total;
    }

    private static double apart(DisplayKeyframe one, DisplayKeyframe other) {
        double dx = one.x() - other.x();
        double dy = one.y() - other.y();
        double dz = one.z() - other.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
