package net.exylia.lib.region;

/** Describes why a player's effective region membership changed. */
public enum RegionChangeCause {
    MOVE,
    TELEPORT,
    /**
     * The tracker found the player somewhere its events had not reported.
     *
     * <p>A platform does not always announce every way a player can be moved:
     * Folia's teleports, a plugin moving somebody by packet, a vehicle. The
     * membership poll catches those, and this is what it reports them as.
     */
    SYNC,
    WORLD_CHANGE,
    REGISTER,
    REPLACE,
    UNREGISTER,
    RELEASE
}
