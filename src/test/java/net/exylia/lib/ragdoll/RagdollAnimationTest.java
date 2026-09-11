package net.exylia.lib.ragdoll;

import net.exylia.lib.display.DisplayKeyframe;
import net.exylia.lib.display.Rotation;
import net.exylia.lib.ragdoll.internal.KeyframeThinning;
import net.exylia.lib.ragdoll.internal.RagdollFlight;
import net.exylia.lib.ragdoll.internal.RagdollPieces;
import net.exylia.lib.ragdoll.internal.RagdollRig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A choreographed body: that it is written the way the file says, that it
 * never comes apart until it is told to, and that what it does afterwards
 * carries on from where it was.
 */
class RagdollAnimationTest {

    private static final double PX = RagdollPart.PIXEL;

    private static RagdollAnimation parsed(String keys) {
        List<String> problems = new ArrayList<>();
        RagdollAnimation animation = RagdollAnimation.parse(keys, problems::add);
        assertEquals(List.of(), problems, "\"" + keys + "\" should read cleanly");
        return animation;
    }

    private static RagdollRig.Placed place(RagdollPart part, double[] pose) {
        return RagdollRig.place(part, pose, 1.0, Rotation.NONE, 0);
    }

    private static double[] turn(Rotation rotation, double x, double y, double z) {
        float[] turned = rotation.apply(new float[]{(float) x, (float) y, (float) z});
        return new double[]{turned[0], turned[1], turned[2]};
    }

    private static double gap(double[] one, double[] other) {
        return Math.sqrt(Math.pow(one[0] - other[0], 2) + Math.pow(one[1] - other[1], 2)
                + Math.pow(one[2] - other[2], 2));
    }

    @Test
    @DisplayName("a skeleton left alone is the body every other pose starts from")
    void standingIsTheStandingBody() {
        double[] standing = RagdollRig.standing();
        for (RagdollPart part : RagdollPart.values()) {
            RagdollRig.Placed placed = place(part, standing);
            assertEquals(part.blockOffsetX(), placed.x(), 1e-5, part + " across");
            assertEquals(part.blockCentreY(), placed.y(), 1e-5, part + " up");
            assertEquals(0, placed.z(), 1e-5, part + " forward");
            assertEquals(1, placed.size(), 1e-9, part + " size");
        }
    }

    @Test
    @DisplayName("nothing comes off the body however it is turned")
    void jointsHold() {
        // The whole reason for a skeleton. Every part is placed from its parent,
        // so the end of an arm nearest the shoulder is the shoulder, whatever
        // the hips, the chest and the arm have each been turned to.
        Random random = new Random(12);
        for (int trial = 0; trial < 300; trial++) {
            double[] pose = RagdollRig.standing();
            pose[RagdollRig.RIGHT] = random.nextDouble(-2, 2);
            pose[RagdollRig.UP] = random.nextDouble(-1, 3);
            pose[RagdollRig.FORWARD] = random.nextDouble(-2, 2);
            for (int channel : new int[]{RagdollRig.FLIP, RagdollRig.TURN, RagdollRig.LEAN}) {
                pose[channel] = random.nextDouble(-400, 400);
            }
            for (int joint = RagdollRig.HEAD; joint <= RagdollRig.LEG_LEFT; joint++) {
                pose[RagdollRig.of(joint, RagdollRig.PITCH)] = random.nextDouble(-180, 180);
                pose[RagdollRig.of(joint, RagdollRig.YAW)] = random.nextDouble(-180, 180);
                pose[RagdollRig.of(joint, RagdollRig.ROLL)] = random.nextDouble(-180, 180);
            }
            RagdollRig.Placed chest = place(RagdollPart.TORSO, pose);
            RagdollRig.Placed head = place(RagdollPart.HEAD, pose);
            double[] neck = add(chest, turn(chest.rotation(), 0, 6 * PX, 0));
            assertTrue(gap(neck, add(head, turn(head.rotation(), 0, -4 * PX, 0))) < 1e-3,
                    "the head came off the neck");

            for (RagdollPart arm : List.of(RagdollPart.ARM_LEFT, RagdollPart.ARM_RIGHT)) {
                double side = arm == RagdollPart.ARM_LEFT ? 1 : -1;
                RagdollRig.Placed placed = place(arm, pose);
                double[] shoulder = add(chest, turn(chest.rotation(), side * 6 * PX, 4 * PX, 0));
                assertTrue(gap(shoulder, add(placed, turn(placed.rotation(), 0, 4 * PX, 0))) < 1e-3,
                        arm + " came off the shoulder");
            }
            // The hips are the chest's own pivot, turned by the hips alone.
            double[] hips = add(chest, turn(chest.rotation(), 0, -6 * PX, 0));
            RagdollRig.Placed left = place(RagdollPart.LEG_LEFT, pose);
            RagdollRig.Placed right = place(RagdollPart.LEG_RIGHT, pose);
            double[] leftHip = add(left, turn(left.rotation(), 0, 6 * PX, 0));
            double[] rightHip = add(right, turn(right.rotation(), 0, 6 * PX, 0));
            double[] between = {(leftHip[0] + rightHip[0]) / 2, (leftHip[1] + rightHip[1]) / 2,
                    (leftHip[2] + rightHip[2]) / 2};
            assertTrue(gap(between, hips) < 1e-3, "the legs came off the hips");
            assertEquals(4 * PX, gap(leftHip, rightHip), 1e-3, "the hips came apart");
        }
    }

    private static double[] add(RagdollRig.Placed placed, double[] offset) {
        return new double[]{placed.x() + offset[0], placed.y() + offset[1], placed.z() + offset[2]};
    }

    @Test
    @DisplayName("the words mean the body's own left, right, forward and up")
    void theWordsMeanWhatTheySay() {
        double[] pose = parsed("0.1 at=1,0.5,2").at(100);
        RagdollRig.Placed chest = place(RagdollPart.TORSO, pose);
        // A body faces south when nothing turns it, so its right is west.
        assertEquals(-1, chest.x(), 1e-4, "right is the body's own right");
        assertEquals(RagdollPart.TORSO.blockCentreY() + 0.5, chest.y(), 1e-4);
        assertEquals(2, chest.z(), 1e-4, "forward is the way it faces");

        double[] out = parsed("0.1 arms=0,0,90").at(100);
        RagdollRig.Placed left = place(RagdollPart.ARM_LEFT, out);
        RagdollRig.Placed right = place(RagdollPart.ARM_RIGHT, out);
        assertTrue(left.x() > 0.4 && right.x() < -0.4, "arms=0,0,90 opens both arms outwards");
        assertEquals(left.x(), -right.x(), 1e-4, "and opens them the same");
        assertEquals(left.y(), right.y(), 1e-4);

        RagdollRig.Placed forward = place(RagdollPart.ARM_LEFT, parsed("0.1 arm_l=90").at(100));
        assertTrue(forward.z() > 0.2, "a pitched arm swings forward, not back");

        RagdollRig.Placed bowed = place(RagdollPart.HEAD, parsed("0.1 flip=90").at(100));
        assertTrue(bowed.z() > 0.8 && bowed.y() < 1.0, "flip tips the body forwards");
        RagdollRig.Placed turned = place(RagdollPart.ARM_LEFT, parsed("0.1 turn=90").at(100));
        assertTrue(turned.z() < -0.2, "turn swings the body to its left, taking the left arm back");
    }

    @Test
    @DisplayName("a frame is reached exactly when the file says, and carries the rest over")
    void framesLandOnTime() {
        RagdollAnimation animation = parsed("0.5 up=1 | 0.25 turn=~90 | 0.25 turn=~90 | 0 arms=0,0,90");
        assertEquals(1000, animation.durationMillis());
        assertEquals(4, animation.frames());
        assertEquals(1, animation.at(500)[RagdollRig.UP], 1e-9);
        assertEquals(1, animation.at(750)[RagdollRig.UP], 1e-9, "a frame keeps what it does not mention");
        assertEquals(90, animation.at(750)[RagdollRig.TURN], 1e-9);
        assertEquals(180, animation.at(1000)[RagdollRig.TURN], 1e-9, "~ adds to where it already was");
        assertEquals(0.5, animation.at(250)[RagdollRig.UP], 1e-9, "in_out is halfway at halfway");
        assertEquals(90, animation.at(5000)[RagdollRig.of(RagdollRig.ARM_LEFT, RagdollRig.ROLL)], 1e-9,
                "a frame that takes no time still happens");
    }

    @Test
    @DisplayName("a smooth path goes through its frames without stopping at them")
    void smoothFlows() {
        RagdollAnimation animation = parsed(
                "0.5 right=1 ease=smooth | 0.5 right=2 ease=smooth | 0.5 right=3 ease=smooth");
        assertEquals(1, animation.at(500)[RagdollRig.RIGHT], 1e-9);
        assertEquals(2, animation.at(1000)[RagdollRig.RIGHT], 1e-9);
        double through = animation.at(525)[RagdollRig.RIGHT] - animation.at(475)[RagdollRig.RIGHT];
        assertTrue(through > 0.06, "it stopped at a frame it should have swept through: " + through);

        RagdollAnimation stopping = parsed("0.5 right=1 | 0.5 right=2");
        double stopped = stopping.at(525)[RagdollRig.RIGHT] - stopping.at(475)[RagdollRig.RIGHT];
        assertTrue(stopped < 0.01, "in_out arrives, which is a stop: " + stopped);
    }

    @Test
    @DisplayName("an overshoot overshoots and a snap cuts")
    void easesHaveCharacter() {
        RagdollAnimation back = parsed("1 up=1 ease=back");
        double highest = 0;
        for (long at = 0; at <= 1000; at += 10) {
            highest = Math.max(highest, back.at(at)[RagdollRig.UP]);
        }
        assertTrue(highest > 1.05, "back never went past its frame");
        assertEquals(1, parsed("1 up=1 ease=snap").at(1)[RagdollRig.UP], 1e-9);
        assertTrue(parsed("1 up=1 ease=anticipate").at(200)[RagdollRig.UP] < 0,
                "anticipate never pulled back first");
    }

    @Test
    @DisplayName("what cannot be read is named and skipped, and the rest still dances")
    void problemsAreNamed() {
        List<String> problems = new ArrayList<>();
        RagdollAnimation animation = RagdollAnimation.parse(
                "0.2 wiggle | nonsense | 0.3 up=a | 0.3 head=1,2,3,4 | 0.3 ease=wobbly | 0.2 up=1",
                problems::add);
        assertEquals(5, problems.size(), "each mistake once: " + problems);
        assertEquals(5, animation.frames(), "only the frame with no seconds is lost");
        assertEquals(1, animation.at(10_000)[RagdollRig.UP], 1e-9);
    }

    @Test
    @DisplayName("a spin the client would take the wrong way round is reported")
    void aSpinTooFastIsReported() {
        List<String> problems = new ArrayList<>();
        RagdollAnimation.parse("0.1 turn=720", problems::add);
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("too fast"));
    }

    @Test
    @DisplayName("every named pose keeps the body out of the floor")
    void posesStayOnTheFloor() {
        for (String name : List.of("stand", "tpose", "star", "cheer", "reach", "zombie", "hug",
                "crouch", "sit", "kneel", "lie", "prone", "bow", "pray", "dab", "splits", "float",
                "limp", "fetal", "swoon", "heart", "arabesque")) {
            double[] pose = parsed("1 " + name).at(1000);
            for (RagdollPart part : RagdollPart.values()) {
                RagdollRig.Placed placed = place(part, pose);
                double half = part == RagdollPart.HEAD ? 4 * PX : 0;
                double lowest = Double.MAX_VALUE;
                double[] size = {part.blockWidth(), part.blockHeight(), part.blockDepth()};
                for (int dx = -1; dx <= 1; dx += 2) {
                    for (int dy = -1; dy <= 1; dy += 2) {
                        for (int dz = -1; dz <= 1; dz += 2) {
                            double[] corner = turn(placed.rotation(),
                                    dx * (half > 0 ? half : size[0] / 2),
                                    dy * (half > 0 ? half : size[1] / 2),
                                    dz * (half > 0 ? half : size[2] / 2));
                            lowest = Math.min(lowest, placed.y() + corner[1]);
                        }
                    }
                }
                assertTrue(lowest > -0.1, name + " puts " + part + " " + (-lowest) + " into the floor");
            }
        }
    }

    // ---------------------------------------------------------------- finishes

    private static RagdollMotion choreographed(String keys, RagdollFinish finish) {
        return RagdollMotion.builder()
                .pose(RagdollPose.ANIMATE)
                .animation(parsed(keys))
                .finish(finish)
                .intactFor(0.2)
                .life(0.2 + parsed(keys).durationMillis() / 1000.0 + 1.6)
                .build();
    }

    /** Where a piece is at a moment, drawn the way the client draws it. */
    private static DisplayKeyframe at(List<DisplayKeyframe> poses, long millis) {
        DisplayKeyframe previous = poses.get(0);
        for (DisplayKeyframe pose : poses) {
            if (pose.atMillis() >= millis) {
                long span = pose.atMillis() - previous.atMillis();
                double share = span <= 0 ? 1 : (double) (millis - previous.atMillis()) / span;
                return new DisplayKeyframe(millis,
                        (float) (previous.x() + (pose.x() - previous.x()) * share),
                        (float) (previous.y() + (pose.y() - previous.y()) * share),
                        (float) (previous.z() + (pose.z() - previous.z()) * share),
                        pose.rotation(),
                        (float) (previous.scaleX() + (pose.scaleX() - previous.scaleX()) * share),
                        (float) (previous.scaleY() + (pose.scaleY() - previous.scaleY()) * share),
                        (float) (previous.scaleZ() + (pose.scaleZ() - previous.scaleZ()) * share));
            }
            previous = pose;
        }
        return poses.get(poses.size() - 1);
    }

    @Test
    @DisplayName("a finish carries on from the last frame instead of snapping")
    void finishesCarryOn() {
        String keys = "0.4 up=1.2 flip=-180 ease=in";
        for (RagdollFinish finish : RagdollFinish.values()) {
            RagdollMotion motion = choreographed(keys, finish);
            for (RagdollPieces.Piece piece : RagdollPieces.solve(motion, 2, 1.0, Rotation.NONE, new Random(4))) {
                List<DisplayKeyframe> poses = piece.poses();
                DisplayKeyframe before = at(poses, motion.finishAt() - RagdollFlight.FRAME_MS);
                DisplayKeyframe ending = at(poses, motion.finishAt());
                DisplayKeyframe after = at(poses, motion.finishAt() + RagdollFlight.FRAME_MS);
                // Measured against how fast it was already going. The head of
                // a body flipped over with ease=in is moving at thirty blocks a
                // second when the last frame lands, and carrying on at that
                // speed is the point; what is not allowed is a jump on top.
                double going = distance(before, ending);
                double moved = distance(ending, after);
                assertTrue(moved < going + 0.75, finish + ": " + piece.part() + " jumped " + moved
                        + " blocks the moment the choreography ended, having moved " + going);
                for (DisplayKeyframe pose : poses) {
                    assertTrue(pose.y() > -0.05, finish + ": " + piece.part() + " went under the floor");
                }
            }
        }
    }

    private static double distance(DisplayKeyframe one, DisplayKeyframe other) {
        return Math.sqrt(Math.pow(other.x() - one.x(), 2) + Math.pow(other.y() - one.y(), 2)
                + Math.pow(other.z() - one.z(), 2));
    }

    @Test
    @DisplayName("a burst body leaves, an imploded one is taken, a held one stays")
    void finishesDoWhatTheySay() {
        String keys = "0.5 up=1";
        RagdollMotion held = choreographed(keys, RagdollFinish.HOLD);
        RagdollMotion burst = choreographed(keys, RagdollFinish.BURST);
        RagdollMotion imploded = choreographed(keys, RagdollFinish.IMPLODE);
        RagdollMotion dissolved = choreographed(keys, RagdollFinish.DISSOLVE);

        List<RagdollPieces.Piece> still = RagdollPieces.solve(held, 1, 1.0, Rotation.NONE, new Random(1));
        List<RagdollPieces.Piece> thrown = RagdollPieces.solve(burst, 1, 1.0, Rotation.NONE, new Random(1));
        List<RagdollPieces.Piece> taken = RagdollPieces.solve(imploded, 1, 1.0, Rotation.NONE, new Random(1));
        List<RagdollPieces.Piece> dust = RagdollPieces.solve(dissolved, 1, 1.0, Rotation.NONE, new Random(1));
        long nearlyGone = held.lifeMillis() - 300;

        double spread = 0;
        for (int index = 0; index < still.size(); index++) {
            DisplayKeyframe end = at(still.get(index).poses(), held.finishAt());
            DisplayKeyframe later = at(still.get(index).poses(), nearlyGone);
            // Within what thinning allows a pose to stray, and no further.
            assertEquals(end.x(), later.x(), 0.03, "a held body moved");
            assertEquals(end.y(), later.y(), 0.03, "a held body moved");

            DisplayKeyframe flown = at(thrown.get(index).poses(), nearlyGone);
            spread += Math.hypot(flown.x() - end.x(), flown.z() - end.z());

            DisplayKeyframe small = at(taken.get(index).poses(), imploded.lifeMillis() - 60);
            assertTrue(small.scaleY() < end.scaleY() * 0.3, "an imploded piece kept its size");

            DisplayKeyframe blown = at(dust.get(index).poses(), dissolved.lifeMillis() - 60);
            assertTrue(blown.scaleY() < end.scaleY() * 0.5, "a dissolved piece kept its size");
        }
        assertTrue(spread / still.size() > 1.0, "a burst body barely came apart: " + spread / still.size());
    }

    @Test
    @DisplayName("a choreography stands whole for intact, then moves every tick")
    void standsThenDances() {
        RagdollMotion motion = choreographed("0.5 up=1", RagdollFinish.HOLD);
        RagdollFlight.Flight flight = RagdollFlight.solve(RagdollPart.TORSO, motion, 1.0,
                Rotation.NONE, new Random(1));
        assertEquals(0L, flight.times()[0]);
        assertEquals(200L, flight.times()[1]);
        assertEquals(RagdollPart.TORSO.blockCentreY(), flight.y()[1], 1e-6, "it moved before intact");
        assertEquals(RagdollFlight.FRAME_MS, flight.times()[2] - flight.times()[1]);
        assertEquals(RagdollPart.TORSO.blockCentreY() + 1, flight.y()[flight.times().length - 1], 1e-4);
    }

    // ---------------------------------------------------------------- thinning

    @Test
    @DisplayName("a straight line is two poses and a curve keeps its curve")
    void thinningKeepsTheShape() {
        List<DisplayKeyframe> line = new ArrayList<>();
        List<DisplayKeyframe> circle = new ArrayList<>();
        for (int index = 0; index <= 40; index++) {
            long at = index * 50L;
            line.add(new DisplayKeyframe(at, index * 0.1f, 1f, 0f, Rotation.NONE, 1f, 1f, 1f));
            double angle = index * Math.PI / 20;
            circle.add(new DisplayKeyframe(at, (float) Math.cos(angle) * 2, 1f, (float) Math.sin(angle) * 2,
                    Rotation.around(Rotation.Axis.Y, angle), 1f, 1f, 1f));
        }
        assertEquals(2, KeyframeThinning.thin(line).size(), "a straight line needs its two ends");

        List<DisplayKeyframe> kept = KeyframeThinning.thin(circle);
        assertFalse(kept.size() < 10, "a circle was cut into a polygon: " + kept.size());
        assertEquals(circle.get(0), kept.get(0));
        assertEquals(circle.get(circle.size() - 1), kept.get(kept.size() - 1));
        for (DisplayKeyframe original : circle) {
            DisplayKeyframe drawn = at(kept, original.atMillis());
            double off = Math.hypot(drawn.x() - original.x(), drawn.z() - original.z());
            assertTrue(off < 0.03, "the thinned circle strays " + off + " blocks from the real one");
        }
    }
}
