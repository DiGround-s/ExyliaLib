package net.exylia.lib.ragdoll;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * What a choreographed body does once its last frame has been reached.
 *
 * <p>Worked out piece by piece rather than part by part, from wherever each
 * piece was and however fast it was moving when the choreography ended. A body
 * that is spun into the air and let go at the top keeps flying the way it was
 * spinning; a body that is still when it ends falls straight down. Nothing
 * snaps, because nothing starts from a standing body.
 *
 * @since 1.132.0
 */
public enum RagdollFinish {

    /** Stays in its last pose until its life is up, and shrinks away. */
    HOLD,

    /** Every piece is thrown outwards on the usual {@code speed up spread} numbers, and bounces. */
    BURST,

    /** Every piece simply drops, keeping the momentum it had. A body that goes limp. */
    COLLAPSE,

    /**
     * Every piece spirals into the middle of the body and is gone.
     *
     * <p>{@code turns} is how far round they are carried on the way in.
     */
    IMPLODE,

    /**
     * The body blows away as dust, from the top down: each piece drifts up and
     * away and shrinks to nothing, a little after the one above it.
     */
    DISSOLVE;

    /**
     * Reads a finish from configuration, defaulting to {@link #HOLD}.
     *
     * @param name what the file said
     * @return the finish
     */
    public static @NotNull RagdollFinish of(@NotNull String name) {
        return switch (name.trim().toUpperCase(Locale.ROOT)) {
            case "BURST", "EXPLODE", "SHATTER" -> BURST;
            case "COLLAPSE", "FALL", "DROP", "LIMP" -> COLLAPSE;
            case "IMPLODE", "VANISH", "SINGULARITY" -> IMPLODE;
            case "DISSOLVE", "DUST", "DISINTEGRATE" -> DISSOLVE;
            default -> HOLD;
        };
    }
}
