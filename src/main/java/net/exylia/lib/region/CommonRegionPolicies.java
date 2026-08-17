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
