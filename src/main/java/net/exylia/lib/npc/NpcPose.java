package net.exylia.lib.npc;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * How an NPC is holding itself.
 *
 * <p>The vanilla poses, named for what a server owner is trying to say rather
 * than for the mechanic they borrow. {@link #LYING} is the one worth knowing
 * about: it is the pose a sleeping player is drawn in, and it is the only way
 * to put a body on the floor without a model of your own.
 *
 * @since 1.88.2
 */
public enum NpcPose {

    /** On its feet. */
    STANDING,

    /** Flat on the ground. What a corpse is. */
    LYING,

    /** Face down and horizontal, as a swimming or crawling player is drawn. */
    CRAWLING,

    /** Crouched. */
    SNEAKING,

    /** Spinning, as a riptide trident throws a player. */
    SPINNING;

    /**
     * Reads a pose from configuration, defaulting to {@link #STANDING}.
     *
     * @param name what the file said
     * @return the pose
     */
    public static @NotNull NpcPose of(@NotNull String name) {
        return switch (name.trim().toUpperCase(Locale.ROOT)) {
            case "LYING", "SLEEPING", "CORPSE", "DEAD" -> LYING;
            case "CRAWLING", "SWIMMING" -> CRAWLING;
            case "SNEAKING", "CROUCHING" -> SNEAKING;
            case "SPINNING", "RIPTIDE" -> SPINNING;
            default -> STANDING;
        };
    }
}
