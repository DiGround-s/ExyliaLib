package net.exylia.lib.ragdoll.internal;

import net.exylia.lib.display.DisplayKeyframe;
import net.exylia.lib.display.Rotation;
import net.exylia.lib.ragdoll.RagdollFinish;
import net.exylia.lib.ragdoll.RagdollMotion;
import net.exylia.lib.ragdoll.RagdollPart;
import net.exylia.lib.ragdoll.RagdollPose;
import org.bukkit.Material;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * Every piece of a body and every pose it will be sent, before anything is sent.
 *
 * <p>The half of drawing a ragdoll that needs no server: which pieces there are,
 * where each one goes, how it finishes, how it fades and which of its poses the
 * client would have drawn anyway. The builder only has to give each piece a
 * block or an item and put it on screen, and everything here can be asserted.
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

    /**
     * The turn every item model needs before it is posed.
     *
     * <p>An item display draws its model turned half round, so a head posed
     * facing whoever did the killing shows them the back of its hair. Every
     * preview showed every body from behind until this was put back, which is
     * the kind of bug that looks like a camera problem.
     */
    private static final Rotation ITEM_FACING = Rotation.around(Rotation.Axis.Y, Math.PI);

    /**
     * How a held item is turned in the hand: point forwards, blade on edge.
     *
     * <p>An item's own model is a flat plate with its tip up and to the right.
     * Rolled so the tip is straight up, turned so the plate is edge-on to
     * whoever is in front, and pitched so the tip points where the arm does
     * not: forward, the way a player holds a sword.
     */
    private static final Rotation HELD = ITEM_FACING
            .then(Rotation.around(Rotation.Axis.Z, Math.PI / 4))
            .then(Rotation.around(Rotation.Axis.Y, Math.PI / 2))
            .then(Rotation.around(Rotation.Axis.X, Math.PI / 2));

    /** How far along its own tip a held item's middle is from the hand, per block of its size. */
    private static final float GRIP = 0.28f;

    private static final float[] NOWHERE = {0f, 0f, 0f};

    private RagdollPieces() {
    }

    /** Something a body carries rather than is made of. */
    public enum Prop {

        /** Held in the right hand. */
        MAIN_HAND,

        /** Held in the left hand. */
        OFF_HAND,

        /** Worn on the head. */
        HAT,

        /** A puppet string from the right hand. */
        STRING_RIGHT,

        /** A puppet string from the left hand. */
        STRING_LEFT,

        /** A puppet string from the top of the head. */
        STRING_HEAD,

        /** A chain from the floor at the body's right to the right wrist. */
        CHAIN_RIGHT,

        /** A chain from the floor at the body's left to the left wrist. */
        CHAIN_LEFT
    }

    /** How wide a chain block is drawn, per block of body; its model is a sliver of that. */
    private static final float CHAIN = 0.9f;

    /** How thick a puppet string is, per block of body. */
    private static final float STRING = 0.035f;

    /** How long a cut string takes to whip up out of sight. */
    private static final long STRING_RETRACT_MS = 150L;

    /**
     * One piece of a body.
     *
     * @param part  the part it belongs to, or is carried by
     * @param cellX its column within that part; zero for the head and props
     * @param cellY its row within that part; zero for the head and props
     * @param prop  what it is carrying, or {@code null} when it is the body
     * @param poses every pose it is sent, ready for the display module
     * @param block the block it is drawn in when that was decided with its
     *              shape, as a shell's plates are; {@code null} leaves it to
     *              the builder
     */
    public record Piece(RagdollPart part, int cellX, int cellY, @Nullable Prop prop,
                       List<DisplayKeyframe> poses, @Nullable Material block) {

        /** A piece whose look the builder works out from the skin. */
        public Piece(RagdollPart part, int cellX, int cellY, @Nullable Prop prop, List<DisplayKeyframe> poses) {
            this(part, cellX, cellY, prop, poses, null);
        }
    }

    /** Solves a whole body carrying nothing. */
    public static List<Piece> solve(RagdollMotion motion, int detail, double scale, Rotation facing,
                                    RandomGenerator random) {
        return solve(motion, detail, scale, facing, random, EnumSet.noneOf(Prop.class));
    }

    /** Solves a whole body drawn in blocks. */
    public static List<Piece> solve(RagdollMotion motion, int detail, double scale, Rotation facing,
                                    RandomGenerator random, Set<Prop> props) {
        return solve(motion, detail, scale, facing, random, props, false);
    }

    /** Solves a whole body cut into cells. */
    public static List<Piece> solve(RagdollMotion motion, int detail, double scale, Rotation facing,
                                    RandomGenerator random, Set<Prop> props, boolean skinned) {
        return solve(motion, detail, scale, facing, random, props, skinned, null);
    }

    /**
     * Solves a whole body.
     *
     * <p>A skinned body is solved exactly as a body in blocks is &mdash; same
     * centres, same finishes, same word spelled &mdash; and differs only in
     * how each cell is sent: as a head, which the client draws at half its
     * size, turned and lifted the way the real head is.
     *
     * @param motion  what happens to it
     * @param detail  how many cells each part is cut into on each axis
     * @param scale   how big it is; 1 is player-sized
     * @param facing  which way it faces
     * @param random  where the variation between pieces comes from
     * @param props   what it carries
     * <p>A shell is solved the way cells are: every core and plate is carried by
     * its part at its own offset, finishes with the rest and fades with the
     * rest. A body that spells a word ignores its shell and is cut into cells
     * at {@code detail}, because thin plates cannot be laid out as letters.
     *
     * @param skinned whether every cell is drawn as a head wearing its real skin
     * @param shell   the blocks of a body at detail five, or {@code null} for cells
     * @return every piece, the head first and props last
     */
    public static List<Piece> solve(RagdollMotion motion, int detail, double scale, Rotation facing,
                                    RandomGenerator random, Set<Prop> props, boolean skinned,
                                    @Nullable List<RagdollShell.Piece> shell) {
        RagdollPart[] parts = RagdollPart.values();
        RagdollFlight.Flight[] flights = new RagdollFlight.Flight[parts.length];
        for (RagdollPart part : parts) {
            flights[part.ordinal()] = RagdollFlight.solve(part, motion, scale, facing, random);
        }
        boolean finishing = motion.pose() == RagdollPose.ANIMATE;
        boolean spelling = motion.pose() == RagdollPose.SIGN;
        boolean shelled = shell != null && !skinned && !spelling
                && !(finishing && motion.finish() == RagdollFinish.SPELL);
        RagdollFlight.Flight chest = flights[RagdollPart.TORSO.ordinal()];
        int end = chest.times().length - 1;
        double[] middle = {chest.x()[end], chest.y()[end], chest.z()[end]};
        // Every piece but the head, counted once across the whole body, so a
        // word can be shared out among them.
        int pieces = 0;
        for (RagdollPart part : parts) {
            if (part != RagdollPart.HEAD) {
                pieces += part.columns(detail) * part.rows(detail);
            }
        }
        if (shelled) {
            pieces = shell.size();
        }
        int running = 0;

        List<Piece> solved = new ArrayList<>(1 + pieces + props.size());
        for (RagdollPart part : parts) {
            RagdollFlight.Flight flight = flights[part.ordinal()];
            if (part == RagdollPart.HEAD) {
                float size = (float) scale;
                List<DisplayKeyframe> centres = carried(flight, NOWHERE, new float[]{size, size, size});
                if (finishing) {
                    RagdollFinishes.extend(centres, motion, scale, middle, true, random,
                            new RagdollFinishes.Spelling(-1, pieces, facing));
                }
                solved.add(new Piece(part, 0, 0, null,
                        displayed(centres, motion, HEAD_LIFT, ITEM_FACING)));
                continue;
            }
            if (shelled) {
                float pixel = RagdollPart.PIXEL * (float) scale;
                for (RagdollShell.Piece block : shell) {
                    if (block.part() != part) {
                        continue;
                    }
                    float[] local = {block.centre()[0] * pixel, block.centre()[1] * pixel, block.centre()[2] * pixel};
                    float[] size = {block.size()[0] * pixel, block.size()[1] * pixel, block.size()[2] * pixel};
                    int index = running++;
                    List<DisplayKeyframe> centres = carried(flight, local, size);
                    if (finishing) {
                        RagdollFinishes.extend(centres, motion, scale, middle, false, random,
                                new RagdollFinishes.Spelling(index, pieces, facing));
                    }
                    solved.add(new Piece(part, 0, 0, null,
                            displayed(centres, motion, 0f, Rotation.NONE), block.block()));
                }
                continue;
            }
            float width = part.blockWidth() * (float) scale;
            float height = part.blockHeight() * (float) scale;
            float depth = part.blockDepth() * (float) scale;
            int columns = part.columns(detail);
            int rows = part.rows(detail);
            float[] size = {width / columns, height / rows, depth};
            for (int cellY = 0; cellY < rows; cellY++) {
                for (int cellX = 0; cellX < columns; cellX++) {
                    // Where this cell sits inside its own part, before the part
                    // is turned. The grid runs left to right and top to bottom,
                    // as the skin does.
                    float[] local = {
                            ((cellX + 0.5f) / columns - 0.5f) * width,
                            (0.5f - (cellY + 0.5f) / rows) * height,
                            0f};
                    int index = running++;
                    RagdollFlight.Flight path = flight;
                    if (spelling) {
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
                        RagdollFinishes.extend(centres, motion, scale, middle, false, random,
                                new RagdollFinishes.Spelling(index, pieces, facing));
                    }
                    solved.add(new Piece(part, cellX, cellY, null, skinned
                            ? displayed(centres, motion, HEAD_LIFT, ITEM_FACING, CUBE_GROWTH)
                            : displayed(centres, motion, 0f, Rotation.NONE)));
                }
            }
        }
        for (Prop prop : props) {
            if (prop == Prop.HAT || prop == Prop.MAIN_HAND || prop == Prop.OFF_HAND) {
                solved.add(prop(prop, flights, motion, scale, middle, facing, random, pieces, finishing));
            }
        }
        if (motion.strings() > 0) {
            for (Prop string : List.of(Prop.STRING_RIGHT, Prop.STRING_LEFT, Prop.STRING_HEAD)) {
                Piece piece = string(string, flights, motion, scale);
                if (piece != null) {
                    solved.add(piece);
                }
            }
        }
        if (motion.chains() > 0) {
            for (Prop chain : List.of(Prop.CHAIN_RIGHT, Prop.CHAIN_LEFT)) {
                Piece piece = chain(chain, flights, motion, scale, facing);
                if (piece != null) {
                    solved.add(piece);
                }
            }
        }
        return solved;
    }

    /**
     * A chain: from a point on the floor out to the body's side up to the
     * wrist, re-measured at every pose and turned to lie along the line
     * between them.
     *
     * <p>Unlike a string it may lean, because both of its ends belong to the
     * body: the floor point is placed by the body's own facing, so it is at
     * the body's side from wherever anybody watches.
     */
    private static @Nullable Piece chain(Prop prop, RagdollFlight.Flight[] flights, RagdollMotion motion,
                                         double scale, Rotation facing) {
        RagdollPart part = prop == Prop.CHAIN_RIGHT ? RagdollPart.ARM_RIGHT : RagdollPart.ARM_LEFT;
        RagdollFlight.Flight flight = flights[part.ordinal()];
        // A body's right is towards -X before it is turned.
        float side = (float) (motion.chains() * scale) * (prop == Prop.CHAIN_RIGHT ? -1f : 1f);
        float[] floor = facing.apply(new float[]{side, 0f, 0f});
        long snip = Math.min(motion.lifeMillis(), motion.snipMillis());
        float thick = CHAIN * (float) scale;
        List<DisplayKeyframe> poses = new ArrayList<>();
        for (int index = 0; index < flight.times().length; index++) {
            long at = flight.times()[index];
            if (at > snip) {
                break;
            }
            float grown = (float) (scale * flight.scales()[index][1]);
            float[] end = flight.rotations()[index].apply(new float[]{0f, -6 * RagdollPart.PIXEL * grown, 0f});
            float dx = (float) flight.x()[index] + end[0] - floor[0];
            float dy = (float) flight.y()[index] + end[1];
            float dz = (float) flight.z()[index] + end[2] - floor[2];
            float length = (float) Math.max(0.02, Math.sqrt(dx * dx + dy * dy + dz * dz));
            poses.add(new DisplayKeyframe(at, floor[0] + dx / 2, dy / 2, floor[2] + dz / 2,
                    along(dx, dy, dz), thick, length, thick));
        }
        if (poses.isEmpty()) {
            return null;
        }
        DisplayKeyframe last = poses.get(poses.size() - 1);
        // Broken, it drops back into the floor it was fixed to.
        long gone = Math.min(motion.lifeMillis(), last.atMillis() + STRING_RETRACT_MS);
        DisplayKeyframe broken = new DisplayKeyframe(gone, floor[0], 0.01f, floor[2], last.rotation(),
                thick * 0.5f, 0.02f, thick * 0.5f);
        if (gone > last.atMillis()) {
            poses.add(broken);
        }
        if (motion.lifeMillis() > gone) {
            poses.add(new DisplayKeyframe(motion.lifeMillis(), broken.x(), broken.y(), broken.z(),
                    broken.rotation(), broken.scaleX(), broken.scaleY(), broken.scaleZ()));
        }
        return new Piece(part, 0, 0, prop, KeyframeThinning.thin(poses));
    }

    /** The turn that lays a model's upright axis along a direction. */
    static Rotation along(double dx, double dy, double dz) {
        double level = Math.sqrt(dx * dx + dz * dz);
        return Rotation.around(Rotation.Axis.X, Math.atan2(level, dy))
                .then(Rotation.around(Rotation.Axis.Y, Math.atan2(dx, dz)));
    }

    /**
     * A puppet string: from a hand or the top of the head straight up to the
     * height the file gave, re-measured at every pose.
     *
     * <p>Vertical on purpose. A string leaning from the hand to a fixed bar
     * would need the bar to be where the body's facing puts it, and the body
     * faces the killer while everything else in a sequence is written on the
     * world's axes. Straight up reads as a string wherever anybody stands.
     */
    private static @Nullable Piece string(Prop prop, RagdollFlight.Flight[] flights, RagdollMotion motion,
                                          double scale) {
        RagdollPart part = prop == Prop.STRING_HEAD ? RagdollPart.HEAD
                : prop == Prop.STRING_RIGHT ? RagdollPart.ARM_RIGHT : RagdollPart.ARM_LEFT;
        RagdollFlight.Flight flight = flights[part.ordinal()];
        double top = motion.strings();
        long snip = Math.min(motion.lifeMillis(), motion.snipMillis());
        float thick = STRING * (float) scale;
        List<DisplayKeyframe> poses = new ArrayList<>();
        for (int index = 0; index < flight.times().length; index++) {
            long at = flight.times()[index];
            if (at > snip) {
                break;
            }
            float grown = (float) (scale * flight.scales()[index][1]);
            float reach = part == RagdollPart.HEAD ? 4 * RagdollPart.PIXEL * grown : -6 * RagdollPart.PIXEL * grown;
            float[] end = flight.rotations()[index].apply(new float[]{0f, reach, 0f});
            float x = (float) flight.x()[index] + end[0];
            float y = (float) flight.y()[index] + end[1];
            float z = (float) flight.z()[index] + end[2];
            float length = (float) Math.max(0.02, top - y);
            poses.add(new DisplayKeyframe(at, x, y + length / 2, z, Rotation.NONE, thick, length, thick));
        }
        if (poses.isEmpty()) {
            return null;
        }
        DisplayKeyframe last = poses.get(poses.size() - 1);
        long gone = Math.min(motion.lifeMillis(), last.atMillis() + STRING_RETRACT_MS);
        DisplayKeyframe cut = new DisplayKeyframe(gone, last.x(), (float) top, last.z(), Rotation.NONE,
                thick * 0.5f, 0.02f, thick * 0.5f);
        if (gone > last.atMillis()) {
            poses.add(cut);
        }
        if (motion.lifeMillis() > gone) {
            poses.add(new DisplayKeyframe(motion.lifeMillis(), cut.x(), cut.y(), cut.z(), Rotation.NONE,
                    cut.scaleX(), cut.scaleY(), cut.scaleZ()));
        }
        return new Piece(part, 0, 0, prop, KeyframeThinning.thin(poses));
    }

    /**
     * Something carried, riding the part that carries it.
     *
     * <p>Solved exactly as a piece of that part is, offset to the hand or the
     * crown of the head, so it goes wherever the choreography takes the arm and
     * comes apart with the body when the body does.
     */
    private static Piece prop(Prop prop, RagdollFlight.Flight[] flights, RagdollMotion motion,
                              double scale, double[] middle, Rotation facing, RandomGenerator random,
                              int pieces, boolean finishing) {
        boolean worn = prop == Prop.HAT;
        RagdollPart part = worn ? RagdollPart.HEAD
                : prop == Prop.MAIN_HAND ? RagdollPart.ARM_RIGHT : RagdollPart.ARM_LEFT;
        float size = (float) ((worn ? motion.hatSize() : motion.holdSize()) * scale);
        float[] local = worn
                ? new float[]{0f, (float) (motion.hatRaise() * scale), 0f}
                // The end of the arm, and then half the item forwards, so the
                // hand is round its handle and not its middle.
                : new float[]{0f, -7 * RagdollPart.PIXEL * (float) scale, GRIP * size};
        List<DisplayKeyframe> centres = carried(flights[part.ordinal()], local,
                new float[]{size, size, size});
        if (finishing) {
            RagdollFinishes.extend(centres, motion, scale, middle, false, random,
                    new RagdollFinishes.Spelling(-1, pieces, facing));
        }
        return new Piece(part, 0, 0, prop, displayed(centres, motion, 0f, worn ? ITEM_FACING : HELD));
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
     * where the model draws low, turned the way the model needs, and thinned.
     *
     * @param lift  how far the model draws below its entity, per block of size
     * @param model the turn the model needs before the pose's own
     */
    private static List<DisplayKeyframe> displayed(List<DisplayKeyframe> centres, RagdollMotion motion,
                                                   float lift, Rotation model) {
        return displayed(centres, motion, lift, model, 1f);
    }

    /**
     * How much bigger a cube of skin is sent than the cell it fills.
     *
     * <p>A player head drawn at a size of one is eight pixels, half a block, so
     * a cell a quarter of a block across is a head at half a block of size.
     * Applied here, after everything is solved, so a skinned body flies, finishes
     * and spells exactly as the same body in blocks does.
     */
    private static final float CUBE_GROWTH = 2f;

    /**
     * Centred poses as a display is sent them, for a model drawn at a multiple
     * of the size it was solved at.
     *
     * @param growth how much bigger the model is sent than the piece it draws
     */
    private static List<DisplayKeyframe> displayed(List<DisplayKeyframe> centres, RagdollMotion motion,
                                                   float lift, Rotation model, float growth) {
        List<DisplayKeyframe> poses = new ArrayList<>(centres.size());
        for (DisplayKeyframe centre : centres) {
            float shrink = shrink(centre.atMillis(), motion) * growth;
            float sizeY = centre.scaleY() * shrink;
            float[] offset = lift == 0f
                    ? NOWHERE
                    : centre.rotation().apply(new float[]{0f, lift * sizeY, 0f});
            poses.add(new DisplayKeyframe(centre.atMillis(),
                    centre.x() + offset[0], centre.y() + offset[1], centre.z() + offset[2],
                    model.isNone() ? centre.rotation() : model.then(centre.rotation()),
                    centre.scaleX() * shrink, sizeY, centre.scaleZ() * shrink));
        }
        return KeyframeThinning.thin(poses);
    }

    /** How much of its size a piece still has, so it shrinks away at the end. */
    private static float shrink(long atMillis, RagdollMotion motion) {
        // A spelled word drops at the end and is read until it does; shrinking
        // it on the way down hides the fall that makes it funny.
        if (!motion.fade()) {
            return 1f;
        }
        long remaining = motion.lifeMillis() - atMillis;
        if (motion.pose() == RagdollPose.ANIMATE && motion.finish() == RagdollFinish.SPELL) {
            remaining += FADE_MS / 2;
        }
        return remaining >= FADE_MS ? 1f : Math.max(0.02f, (float) remaining / FADE_MS);
    }
}
