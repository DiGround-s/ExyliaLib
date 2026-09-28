package net.exylia.lib.region;

/**
 * Standard policies matching the deployed Exylia Commons region keys and defaults.
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
    /**
     * The materials a player may place while {@link #ALLOWED_BLOCKS_ONLY} holds.
     *
     * <p>Commons kept the list on the region object and every consumer copied it into
     * a private field; as a policy it is persisted with the region and read by
     * {@link PluginRegions#enforce()}.
     *
     * @since 1.201.0
     */
    public static final PolicyKey<MaterialSet> ALLOWED_BLOCKS = materials("allowed_blocks");
    /**
     * The materials a player may break while {@link #BREAKABLE_BLOCKS_ONLY} holds.
     *
     * @since 1.201.0
     */
    public static final PolicyKey<MaterialSet> BREAKABLE_BLOCKS = materials("breakable_blocks");

    private CommonRegionPolicies() { }

    private static PolicyKey<Boolean> bool(String value, boolean defaultValue) {
        return PolicyKey.of(new RegionId("exylia", value), Boolean.class, defaultValue);
    }

    private static PolicyKey<MaterialSet> materials(String value) {
        return PolicyKey.of(new RegionId("exylia", value), MaterialSet.class, MaterialSet.empty());
    }
}
