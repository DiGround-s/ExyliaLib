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

    DisplayPaint(@NotNull String owner, @NotNull DisplayModel model, @NotNull DisplayMotion motion,
                 @NotNull Face face, boolean faceOut, double turnRadians) {
        this.owner = owner;
        this.model = model;
        this.motion = motion;
        this.face = face;
        this.faceOut = faceOut;
        this.turnRadians = turnRadians;
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
        if (face == Face.FIXED) {
            return this;
        }
        Player wearer = face == Face.KILLER ? target.source() : asPlayer(target.target());
        if (wearer == null) {
            return this;
        }
        DisplayModel worn = Heads.wearing(model, wearer);
        return worn == model ? this
                : new DisplayPaint(owner, worn, motion, Face.FIXED, faceOut, turnRadians);
    }

    @Override
    public void drawAt(@NotNull List<Player> observers, @NotNull Location anchor,
                       double x, double y, double z) {
        if (observers.isEmpty()) {
            return;
        }
        DisplayRuntime.show(owner, model, motionAt(x, z),
                anchor.clone().add(x, y, z), observers);
    }

    @Override
    public long trailMillis() {
        return motion.lifeMillis();
    }

    /**
     * The motion for one point of the shape.
     *
     * <p>{@code face_out:} is what makes a ring of blades read as a ring of
     * blades rather than twelve swords lying in the same direction: each one is
     * turned to point away from the middle. The extra {@code turn:} exists
     * because which way a model's own geometry points is a fact about that
     * model, not about the maths, and a server owner with a resource pack needs
     * a knob rather than a rebuild.
     */
    private DisplayMotion motionAt(double x, double z) {
        if (!faceOut && turnRadians == 0.0) {
            return motion;
        }
        double outward = faceOut ? -Math.atan2(x, z) : 0.0;
        return motion.turnedBy(Rotation.around(Rotation.Axis.Y, outward + turnRadians));
    }

    private static @Nullable Player asPlayer(@Nullable org.bukkit.entity.Entity entity) {
        return entity instanceof Player player ? player : null;
    }
}
