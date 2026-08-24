package net.exylia.lib.region;

/**
 * Standard boolean policies matching the deployed Exylia Commons region keys and defaults.
 *
 * <p>The {@code exylia} namespace is a stable ownership namespace for shared Exylia policies. Key
 * values remain exactly compatible with Commons.
 *
 * @since 1.23.0
 */
public final class CommonRegionPolicies {
    public static final PolicyKey<Boolean> PVP = bool("pvp", true);
    public static final PolicyKey<Boolean> BUILD = bool("build", true);
    public static final PolicyKey<Boolean> BREAK = bool("break", true);
    public static final PolicyKey<Boolean> INTERACT = bool("interact", true);
    public static final PolicyKey<Boolean> PLAYER_BUILD_ONLY = bool("player_build_only", false);
    public static final PolicyKey<Boolean> ALLOWED_BLOCKS_ONLY = bool("allowed_blocks_only", false);
    public static final PolicyKey<Boolean> BREAKABLE_BLOCKS_ONLY = bool("breakable_blocks_only", false);
    public static final PolicyKey<Boolean> TEMPORARY_BLOCKS = bool("temporary_blocks", false);
    /**
     * How long a temporary block lasts, in seconds; zero disables the removal.
     *
     * <p>Commons carried this as a field on the region object rather than as a flag,
     * which meant every consumer had to read it from somewhere else and enforce the
     * lifetime itself. Declaring it as a policy is what lets the library own the
     * whole behaviour: a region states how long its blocks last, and they last that
     * long without any consumer code.
     */
    public static final PolicyKey<Integer> TEMPORARY_BLOCKS_SECONDS =
            PolicyKey.of(new RegionId("exylia", "temporary_blocks_seconds"), Integer.class, 0);
    public static final PolicyKey<Boolean> RE_GIVE_BLOCKS = bool("re_give_blocks", false);
    public static final PolicyKey<Boolean> REGION_MEMBERS_ONLY = bool("region_members_only", false);
    public static final PolicyKey<Boolean> ENTRY = bool("entry", true);
    public static final PolicyKey<Boolean> EXIT = bool("exit", true);
    public static final PolicyKey<Boolean> ITEM_DROP = bool("item_drop", true);
    public static final PolicyKey<Boolean> ITEM_PICKUP = bool("item_pickup", true);
    public static final PolicyKey<Boolean> FALL_DAMAGE = bool("fall_damage", true);

    private CommonRegionPolicies() { }

    private static PolicyKey<Boolean> bool(String value, boolean defaultValue) {
        return PolicyKey.of(new RegionId("exylia", value), Boolean.class, defaultValue);
    }
}
