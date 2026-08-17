package net.exylia.lib.region;

/** Describes why a player's effective region membership changed. */
public enum RegionChangeCause {
    MOVE,
    TELEPORT,
    WORLD_CHANGE,
    REGISTER,
    REPLACE,
    UNREGISTER,
    RELEASE
}
