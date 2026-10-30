package net.exylia.lib.util.crate;

/**
 * How a crate opening is shown. The prize is decided, and the key spent, before either draws anything:
 * this only chooses what the player watches.
 *
 * @since 1.416.0
 */
public enum CrateOpening {

    /** The reels fall down the plugin's {@code crate_open} menu. */
    INVENTORY,
    /**
     * A crate stands in the world in front of the player ({@code net.exylia.lib.spatial.crate.CrateScene}).
     * Falls back to {@link #INVENTORY} without PacketEvents.
     */
    SPATIAL
}
