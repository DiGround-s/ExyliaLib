package net.exylia.lib.ragdoll;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * What happens to a body, as choreography rather than as physics.
 *
 * <p>Every one of these is solved in advance and handed to the client as poses,
 * so the difference between them is what the file wants to say and never what
 * the server can afford. A body that is thrown apart, a body that is held up
 * and opened out, a body that is knocked about while it hangs there, and a body
 * that is taken: four different deaths out of the same six pieces.
 *
 * @since 1.121.0
 */
public enum RagdollPose {

    /**
     * Thrown apart. The pieces leave outwards, fall, bounce and rest.
     *
     * <p>The death everybody writes first, and the one that reads as violence.
     */
    BURST,

    /**
     * Lifted off the ground and opened out, arms and legs wide, turning slowly.
     *
     * <p>Held there for as long as the file says, and then let go, so it falls
     * the rest of the way. What this is for is the beat in the middle: a body
     * hanging open in the air is a whole second in which something else can
     * happen to it.
     */
    SPREAD,

    /**
     * Held open like {@link #SPREAD}, and then hit, several times, from
     * different sides.
     *
     * <p>Each blow shoves the whole body and spins it, and it drifts back
     * before the next one lands. The blows are on a fixed beat, so whatever is
     * doing the hitting &mdash; snowballs, anvils, a fist &mdash; is written as
     * its own lines timed with {@code [DELAY]} and lands exactly with them.
     */
    KNOCKED,

    /**
     * Taken. The pieces spiral inwards and upwards and are gone at the top.
     *
     * <p>The one death with nothing left on the floor.
     */
    VORTEX;

    /**
     * Reads a pose from configuration, defaulting to {@link #BURST}.
     *
     * @param name what the file said
     * @return the pose
     */
    public static @NotNull RagdollPose of(@NotNull String name) {
        return switch (name.trim().toUpperCase(Locale.ROOT)) {
            case "SPREAD", "STARFISH", "OPEN" -> SPREAD;
            case "KNOCKED", "KNOCK", "BATTED", "HIT" -> KNOCKED;
            case "VORTEX", "TAKEN", "ASCEND", "SPIRAL" -> VORTEX;
            default -> BURST;
        };
    }

    /** Whether this pose holds the body in the air before it lets go. */
    public boolean isHeld() {
        return this == SPREAD || this == KNOCKED;
    }
}
