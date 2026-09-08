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
    VORTEX,

    /**
     * The head swells until it is far too big, wobbles there, and bursts.
     *
     * <p>The body waits underneath it the whole time, which is what makes it
     * funny rather than merely loud: everything is still standing, and one part
     * of it is very obviously about to stop.
     */
    BALLOON,

    /**
     * The arms go flat above the head and turn into a rotor.
     *
     * <p>The body lifts off, the rest of it counter-turns underneath, and it
     * leaves forwards. Nothing lands.
     */
    HELICOPTER,

    /**
     * Arms out as wings, nose up, and away.
     *
     * <p>It banks as it climbs, which is the detail that turns a body sliding
     * through the air into a body flying. Give it a negative climb and it is a
     * dive instead.
     */
    PLANE,

    /**
     * Driven straight down and left flat on the floor.
     *
     * <p>Every piece keeps its own colour and loses its height, so what is left
     * is a player-shaped stain rather than a pile of blocks.
     */
    FLATTEN,

    /**
     * Sinks. The pieces lose their height where they stand and are gone.
     *
     * <p>The quiet one. No throw, no bounce, nothing to look at afterwards.
     */
    MELT,

    /**
     * The pieces lay themselves out into letters and hold there.
     *
     * <p>Not a caption above a body: the letters <em>are</em> the body. Every
     * stroke is one or more of their own pieces, stretched along it and still
     * the colour of the part of them it came from, and when the sign has been
     * read it lets go and falls.
     */
    SIGN,

    /**
     * Sent somewhere, in one piece.
     *
     * <p>The body keeps its own shape and turns end over end as it goes, so
     * what leaves is a person and not a cloud of parts. Give it no gravity and
     * it does not come back: a body put out of an airlock is still a body all
     * the way to the horizon, and that is the whole difference between being
     * ejected and being blown up.
     */
    THROWN;

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
            case "BALLOON", "BIGHEAD", "BIG_HEAD", "SWELL", "POP" -> BALLOON;
            case "HELICOPTER", "CHOPPER", "ROTOR" -> HELICOPTER;
            case "PLANE", "FLY", "GLIDE", "JET" -> PLANE;
            case "FLATTEN", "PANCAKE", "SQUASH", "FLAT" -> FLATTEN;
            case "MELT", "SINK", "DISSOLVE" -> MELT;
            case "SIGN", "LETTERS", "SPELL", "WORD" -> SIGN;
            case "THROWN", "LAUNCHED", "EJECTED", "CARRIED", "SPACED" -> THROWN;
            default -> BURST;
        };
    }

    /** Whether this pose holds the body in the air before it lets go. */
    public boolean isHeld() {
        return this == SPREAD || this == KNOCKED;
    }

    /**
     * Whether this pose leaves rather than lands.
     *
     * <p>Nothing that flies away reads {@code bounce} or {@code settle}: there
     * is no floor in its future.
     */
    public boolean flies() {
        return this == HELICOPTER || this == PLANE || this == VORTEX || this == THROWN;
    }
}
