package net.exylia.lib.util.sequence.internal;

import net.exylia.lib.display.DisplayModel;
import net.exylia.lib.display.DisplayMotion;
import net.exylia.lib.display.Rotation;
import net.exylia.lib.display.internal.DisplayRuntime;
import net.exylia.lib.util.sequence.SequenceTarget;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A shape's points drawn as display entities.
 *
 * <p>The counterpart to {@link ParticlePaint}: same shapes, same animation, same
 * visibility, solid objects instead of light. One line of configuration turns a
 * circle of flame into a circle of swords.
 *
 * <h2>One motion, turned per point</h2>
 * Every display in a shape moves the same way. The only thing that differs is
 * which way it faces, and that is one quaternion multiply per point rather than
 * a rebuilt animation per point &mdash; so a twelve-blade ring costs the same
 * arithmetic as one blade, twelve times, and no parsing at all.
 *
 * <h2>Who owns what</h2>
 * The displays belong to the plugin whose sequence drew them, so disabling that
 * plugin takes them off the clients showing them. Nothing here schedules
 * anything: the display module's own driver moves every display on the server
 * from one timer.
 */
final class DisplayPaint implements Paint {

    /** What an effect is carried by, when it is carried by anything. */
    enum Follow {

        /** Nothing: it stays where it was drawn. */
        NONE,

        /** Whoever set the sequence off. */
        SOURCE,

        /** Whoever it happened to. */
        VICTIM
    }

    /** Where a head's face comes from, when it is not a fixed texture. */
    enum Face {

        /** A texture written in the file. Resolved once, at compile. */
        FIXED,

        /** Whoever set the sequence off. */
        KILLER,

        /** Whoever it happened to. */
        VICTIM
    }

    private final String owner;
    private final DisplayModel model;
    private final DisplayMotion motion;
    private final Face face;
    private final boolean faceOut;
    private final double turnRadians;
    private final double pull;
    private final double orbit;
    private final double vary;
    private final Follow follow;
    private final org.bukkit.entity.Entity carrier;

    DisplayPaint(@NotNull String owner, @NotNull DisplayModel model, @NotNull DisplayMotion motion,
                 @NotNull Face face, boolean faceOut, double turnRadians, double pull,
                 double orbit, double vary, @NotNull Follow follow) {
        this(owner, model, motion, face, faceOut, turnRadians, pull, orbit, vary, follow, null);
    }

    private DisplayPaint(@NotNull String owner, @NotNull DisplayModel model, @NotNull DisplayMotion motion,
                 @NotNull Face face, boolean faceOut, double turnRadians, double pull,
                 double orbit, double vary, @NotNull Follow follow,
                 @Nullable org.bukkit.entity.Entity carrier) {
        this.follow = follow;
        this.carrier = carrier;
        this.owner = owner;
        this.model = model;
        this.motion = motion;
        this.face = face;
        this.faceOut = faceOut;
        this.turnRadians = turnRadians;
        this.pull = pull;
        this.orbit = orbit;
        this.vary = vary;
    }

    /**
     * Resolves a head that wears somebody's face.
     *
     * <p>Once per play. The profile of a player who is on the server is already
     * in memory, so this costs no lookup and blocks on nothing; a player who has
     * gone leaves the head as whatever the file said, which is a head rather
     * than a missing effect.
     */
    @Override
    public @NotNull Paint forPlay(@NotNull SequenceTarget target) {
        DisplayModel drawn = model;
        if (face != Face.FIXED) {
            Player wearer = face == Face.KILLER ? target.source() : asPlayer(target.target());
            if (wearer != null) {
                drawn = Heads.wearing(model, wearer);
            }
        }
        // Who carries it is decided per play, like whose face a head wears:
        // the line says "the one who set this off", and which player that is
        // depends on the play, not on the file.
        org.bukkit.entity.Entity mount = switch (follow) {
            case SOURCE -> target.source();
            case VICTIM -> target.target();
            case NONE -> null;
        };
        if (drawn == model && mount == null) {
            return this;
        }
        return new DisplayPaint(owner, drawn, motion, Face.FIXED, faceOut, turnRadians,
                pull, orbit, vary, follow, mount);
    }

    @Override
    public void drawAt(@NotNull List<Player> observers, @NotNull Location anchor,
                       double x, double y, double z) {
        if (observers.isEmpty()) {
            return;
        }
        if (carrier == null) {
            DisplayRuntime.show(owner, model, motionAt(x, y, z),
                    anchor.clone().add(x, y, z), observers);
            return;
        }
        // Seated, the mount decides where it is, so the shape's own point and
        // whatever the anchor had over the mount both move into the motion.
        // Written the other way round — the point left in the spawn location —
        // the client pins every piece of the shape to the same spot and a ring
        // comes out as one blade.
        Location seat = carrier.getLocation();
        DisplayMotion carried = motionAt(x, y, z).movedBy(
                anchor.getX() + x - seat.getX(),
                anchor.getY() + y - seat.getY(),
                anchor.getZ() + z - seat.getZ());
        DisplayRuntime.show(owner, model, carried, seat, observers, carrier.getEntityId());
    }

    @Override
    public long trailMillis() {
        return motion.lifeMillis();
    }

    /**
     * The motion for one point of the shape.
     *
     * <p>{@code pull:} is the other half of a ring: {@code face_out:} points
     * each blade at the middle and this is what sends it there. Both need the
     * point, which is why they happen here and not when the file was read.
     *
     * <p>{@code face_out:} is what makes a ring of blades read as a ring of
     * blades rather than twelve swords lying in the same direction: each one is
     * turned to point away from the middle. The extra {@code turn:} exists
     * because which way a model's own geometry points is a fact about that
     * model, not about the maths, and a server owner with a resource pack needs
     * a knob rather than a rebuild.
     */
    private DisplayMotion motionAt(double x, double y, double z) {
        DisplayMotion built = motion;
        if (vary != 0.0) {
            built = built.scaledBy(1.0 + vary * (spread(x, y, z) * 2.0 - 1.0));
        }
        if (faceOut || turnRadians != 0.0) {
            // atan2(x, z), not the other way about and not negated: a turn of
            // theta about the vertical takes the model's face from due south to
            // (sin theta, cos theta), so facing away from the middle is the
            // angle of the point itself. Negated, a ring turns its blades
            // inwards and shows the player their backs.
            double outward = faceOut ? Math.atan2(x, z) : 0.0;
            built = built.turnedBy(Rotation.around(Rotation.Axis.Y, outward + turnRadians));
        }
        if (orbit != 0.0) {
            built = built.orbiting(x, z, orbit, faceOut);
        }
        if (pull != 0.0) {
            // Sideways only. Which way a blade travels to reach the middle is
            // a fact about where it started; how high it ends is a decision the
            // file already made with to: or gravity:, and folding the two
            // together would make one of them silently override the other.
            built = built.drifting(-x * pull, 0.0, -z * pull);
        }
        return built;
    }

    /**
     * A number between zero and one that belongs to one point of a shape.
     *
     * <p>Not random. A sequence is compiled once and played by every kill on
     * the server, so a random size would make the same effect a different
     * effect each time and untestable besides. This is a hash of where the
     * point is, which gives a shape whose pieces differ from each other and
     * whose every play is the same.
     */
    private static double spread(double x, double y, double z) {
        long bits = Double.doubleToLongBits(x * 73.1 + y * 151.7 + z * 311.3);
        bits ^= bits >>> 29;
        bits *= 0xBF58476D1CE4E5B9L;
        bits ^= bits >>> 32;
        return (bits >>> 11) / (double) (1L << 53);
    }

    private static @Nullable Player asPlayer(@Nullable org.bukkit.entity.Entity entity) {
        return entity instanceof Player player ? player : null;
    }
}
