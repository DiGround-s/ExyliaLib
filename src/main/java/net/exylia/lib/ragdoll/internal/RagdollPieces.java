package net.exylia.lib.ragdoll.internal;

import net.exylia.lib.display.DisplayKeyframe;
import net.exylia.lib.display.Rotation;
import net.exylia.lib.ragdoll.RagdollMotion;
import net.exylia.lib.ragdoll.RagdollPart;
import net.exylia.lib.ragdoll.RagdollPose;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Every piece of a body and every pose it will be sent, before anything is sent.
 *
 * <p>The half of drawing a ragdoll that needs no server: which pieces there are,
 * where each one goes, how it finishes, how it fades and which of its poses the
 * client would have drawn anyway. The builder only has to give each piece a
 * block and put it on screen, and everything here can be asserted.
 */
@ApiStatus.Internal
public final class RagdollPieces {

    /** How long the pieces take to shrink away at the end. */
    private static final long FADE_MS = 260L;

    /**
     * How far a head item sits below the entity it is drawn on, per block of
     * its size.
     *
     * <p>The vanilla head model fills the lower half of an item's space, so an
     * item display of one draws a quarter of a block low. A calibration knob:
     * a resource pack with its own head model wants a different number.
     */
    private static final float HEAD_LIFT = 0.25f;

    private static final float[] NOWHERE = {0f, 0f, 0f};

    private RagdollPieces() {
    }

    /**
     * One piece of a body.
     *
     * @param part  the part it belongs to
     * @param cellX its column within that part; zero for the head
     * @param cellY its row within that part; zero for the head
     * @param poses every pose it is sent, ready for the display module
     */
    public record Piece(RagdollPart part, int cellX, int cellY, List<DisplayKeyframe> poses) {
    }

    /**
     * Solves a whole body.
     *
     * @param motion what happens to it
     * @param detail how many cells each part is cut into on each axis
     * @param scale  how big it is; 1 is player-sized
     * @param facing which way it faces
     * @param random where the variation between pieces comes from
     * @return every piece, the head first
     */
    public static List<Piece> solve(RagdollMotion motion, int detail, double scale, Rotation facing,
                                    RandomGenerator random) {
        RagdollPart[] parts = RagdollPart.values();
        RagdollFlight.Flight[] flights = new RagdollFlight.Flight[parts.length];
        for (RagdollPart part : parts) {
            flights[part.ordinal()] = RagdollFlight.solve(part, motion, scale, facing, random);
        }
        boolean finishing = motion.pose() == RagdollPose.ANIMATE;
        boolean spelling = motion.pose() == RagdollPose.SIGN;
        RagdollFlight.Flight chest = flights[RagdollPart.TORSO.ordinal()];
        int end = chest.times().length - 1;
        double[] middle = {chest.x()[end], chest.y()[end], chest.z()[end]};
        // Every piece but the head, counted once across the whole body, so a
        // word can be shared out among them.
        int pieces = (parts.length - 1) * detail * detail;

        List<Piece> solved = new ArrayList<>(1 + pieces);
        for (RagdollPart part : parts) {
            RagdollFlight.Flight flight = flights[part.ordinal()];
            if (part == RagdollPart.HEAD) {
                float size = (float) scale;
                List<DisplayKeyframe> centres = carried(flight, NOWHERE, new float[]{size, size, size});
                if (finishing) {
                    RagdollFinishes.extend(centres, motion, scale, middle, true, random);
                }
                solved.add(new Piece(part, 0, 0, displayed(centres, motion, HEAD_LIFT)));
                continue;
            }
            float width = part.blockWidth() * (float) scale;
            float height = part.blockHeight() * (float) scale;
            float depth = part.blockDepth() * (float) scale;
            float[] size = {width / detail, height / detail, depth};
            for (int cellY = 0; cellY < detail; cellY++) {
                for (int cellX = 0; cellX < detail; cellX++) {
                    // Where this cell sits inside its own part, before the part
                    // is turned. The grid runs left to right and top to bottom,
                    // as the skin does.
                    float[] local = {
                            ((cellX + 0.5f) / detail - 0.5f) * width,
                            (0.5f - (cellY + 0.5f) / detail) * height,
                            0f};
                    RagdollFlight.Flight path = flight;
                    if (spelling) {
                        int index = (part.ordinal() - 1) * detail * detail + cellY * detail + cellX;
                        RagdollSign.Placement to = RagdollSign.place(
                                motion.sign(), motion.letters(), index, pieces);
                        if (to == null) {
                            continue;
                        }
                        // Placed by the word rather than by the body, so its
                        // offset inside the part it came from is spent here.
                        double[] start = {
                                flight.x()[0] + local[0],
                                flight.y()[0] + local[1],
                                flight.z()[0] + local[2]};
                        path = RagdollFlight.signCell(motion, scale, facing, start, to, size);
                        local = NOWHERE;
                    }
                    List<DisplayKeyframe> centres = carried(path, local, size);
                    if (finishing) {
                        RagdollFinishes.extend(centres, motion, scale, middle, false, random);
                    }
                    solved.add(new Piece(part, cellX, cellY, displayed(centres, motion, 0f)));
                }
            }
        }
        return solved;
    }

    /**
     * A part's flight, as the flight of one piece of it.
     *
     * <p>The piece is carried by the part: its own offset grows with the part,
     * is turned by whatever the part has turned to, and is then added to where
     * the part is. Growing it afterwards would leave a swollen head as four
     * cells drifting apart from each other.
     */
    private static List<DisplayKeyframe> carried(RagdollFlight.Flight flight, float[] local, float[] size) {
        int count = flight.times().length;
        List<DisplayKeyframe> poses = new ArrayList<>(count + 48);
        for (int index = 0; index < count; index++) {
            Rotation rotation = flight.rotations()[index];
            double[] grown = flight.scales()[index];
            float[] offset = rotation.apply(new float[]{
                    (float) (local[0] * grown[0]),
                    (float) (local[1] * grown[1]),
                    (float) (local[2] * grown[2])});
            poses.add(new DisplayKeyframe(flight.times()[index],
                    (float) flight.x()[index] + offset[0],
                    (float) flight.y()[index] + offset[1],
                    (float) flight.z()[index] + offset[2],
                    rotation,
                    (float) (size[0] * grown[0]),
                    (float) (size[1] * grown[1]),
                    (float) (size[2] * grown[2])));
        }
        return poses;
    }

    /**
     * Centred poses, as the poses a display is sent: faded at the end, lifted
     * where the model draws low, and thinned.
     */
    private static List<DisplayKeyframe> displayed(List<DisplayKeyframe> centres, RagdollMotion motion,
                                                   float lift) {
        List<DisplayKeyframe> poses = new ArrayList<>(centres.size());
        for (DisplayKeyframe centre : centres) {
            float shrink = shrink(centre.atMillis(), motion);
            float sizeY = centre.scaleY() * shrink;
            float[] offset = lift == 0f
                    ? NOWHERE
                    : centre.rotation().apply(new float[]{0f, lift * sizeY, 0f});
            poses.add(new DisplayKeyframe(centre.atMillis(),
                    centre.x() + offset[0], centre.y() + offset[1], centre.z() + offset[2],
                    centre.rotation(),
                    centre.scaleX() * shrink, sizeY, centre.scaleZ() * shrink));
        }
        return KeyframeThinning.thin(poses);
    }

    /** How much of its size a piece still has, so it shrinks away at the end. */
    private static float shrink(long atMillis, RagdollMotion motion) {
        if (!motion.fade()) {
            return 1f;
        }
        long remaining = motion.lifeMillis() - atMillis;
        return remaining >= FADE_MS ? 1f : Math.max(0.02f, (float) remaining / FADE_MS);
    }
}
