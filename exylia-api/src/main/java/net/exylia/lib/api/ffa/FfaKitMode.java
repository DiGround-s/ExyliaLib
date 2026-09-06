package net.exylia.lib.api.ffa;

/**
 * How an arena decides which kit a player fights with.
 *
 * @since 1.0.0
 */
public enum FfaKitMode {

    /** The player picks, from the kits the arena carries. */
    SELECTABLE,

    /** The arena picks one for them on every spawn. */
    RANDOM
}
