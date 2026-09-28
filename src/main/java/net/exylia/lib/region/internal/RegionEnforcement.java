package net.exylia.lib.region.internal;

import net.exylia.lib.region.CommonRegionPolicies;
import net.exylia.lib.region.MaterialSet;
import net.exylia.lib.region.PolicyKey;
import net.exylia.lib.region.RegionSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;
import java.util.function.BiPredicate;

/**
 * Which owners asked the library to enforce their regions, and what that enforcement
 * decides.
 *
 * <h2>Owner-scoped</h2>
 * Each enforcing owner is judged over its own regions alone: its regions at the point,
 * narrowed to those its audience accepts for the player, highest priority first; the
 * first that declares the policy decides, and none declaring it means the key's
 * permissive default. An action is refused when any enforcing owner refuses it. One
 * plugin's region, however high its priority, can therefore neither enforce nor
 * relax another plugin's.
 *
 * <h2>Cost when nothing uses it</h2>
 * {@link #active()} is a single volatile read. A server where no plugin called
 * {@code enforce} pays that read per event the listener handles and nothing else.
 */
public final class RegionEnforcement {

    /** What a player is attempting. */
    public enum Check {
        BREAK(CommonRegionPolicies.BREAK),
        BUILD(CommonRegionPolicies.BUILD),
        INTERACT(CommonRegionPolicies.INTERACT),
        PVP(CommonRegionPolicies.PVP),
        FALL_DAMAGE(CommonRegionPolicies.FALL_DAMAGE),
        ITEM_DROP(CommonRegionPolicies.ITEM_DROP),
        ITEM_PICKUP(CommonRegionPolicies.ITEM_PICKUP);

        private final PolicyKey<Boolean> key;

        Check(PolicyKey<Boolean> key) {
            this.key = key;
        }
    }

    private static final ConcurrentMap<String, BiPredicate<Player, RegionSnapshot>> AUDIENCES =
            new ConcurrentHashMap<>();
    private static volatile boolean active;
    private static final Logger LOGGER = Logger.getLogger("ExyliaLib");
    /** Owners whose audience has thrown, so the failure is reported once rather than per event. */
    private static final java.util.Set<String> FAILED = ConcurrentHashMap.newKeySet();

    private RegionEnforcement() {
    }

    /** Starts enforcing one owner's regions, replacing any previous audience. */
    public static synchronized void enforce(@NotNull String owner,
                                            @NotNull BiPredicate<Player, RegionSnapshot> audience) {
        AUDIENCES.put(Objects.requireNonNull(owner, "owner"), Objects.requireNonNull(audience, "audience"));
        active = true;
    }

    /** Stops enforcing one owner's regions; returns whether it was enforcing. */
    public static synchronized boolean stop(@NotNull String owner) {
        boolean removed = AUDIENCES.remove(Objects.requireNonNull(owner, "owner")) != null;
        FAILED.remove(owner);
        active = !AUDIENCES.isEmpty();
        return removed;
    }

    /** Stops enforcing everything, on shutdown. */
    public static synchronized void releaseAll() {
        AUDIENCES.clear();
        FAILED.clear();
        active = false;
    }

    /** Whether one owner is enforcing. */
    public static boolean enforcing(@NotNull String owner) {
        return AUDIENCES.containsKey(Objects.requireNonNull(owner, "owner"));
    }

    /** The gate every handler reads before doing anything at all. */
    public static boolean active() {
        return active;
    }

    /**
     * Whether any enforcing owner refuses the action at a point.
     *
     * @param material the block broken or placed; ignored by every other check
     */
    public static boolean denies(@NotNull Player player, @NotNull UUID worldId,
                                 double x, double y, double z,
                                 @NotNull Check check, @Nullable Material material) {
        if (!active) return false;
        for (Map.Entry<String, BiPredicate<Player, RegionSnapshot>> entry : AUDIENCES.entrySet()) {
            List<RegionSnapshot> regions = RegionRuntime.queryOwner(entry.getKey(), worldId, x, y, z);
            if (regions.isEmpty()) continue;
            if (denies(regions, entry.getValue(), player, check, material, x, y, z)) return true;
        }
        return false;
    }

    /**
     * Whether a hit between two players is refused, from either end: at the victim's
     * location by the regions applying to the victim, or at the attacker's by those
     * applying to the attacker. Hurting yourself is never PvP.
     */
    public static boolean pvpDenied(@NotNull Player attacker, @NotNull Player victim) {
        return !attacker.equals(victim)
                && (denies(victim, victim.getLocation(), Check.PVP)
                || denies(attacker, attacker.getLocation(), Check.PVP));
    }

    private static boolean denies(Player player, Location at, Check check) {
        return at.getWorld() != null
                && denies(player, at.getWorld().getUID(), at.getX(), at.getY(), at.getZ(), check, null);
    }

    /**
     * One owner's verdict over its regions at the point, highest priority first.
     *
     * <p>A {@code *_only} flag that holds makes its list the whole answer for the
     * material, even where the plain flag says no: "only these" is a whitelist.
     * {@code player_build_only} is independent of both: a break it refuses is refused
     * whatever {@code break} says, and {@code break false} refuses whatever it says.
     */
    static boolean denies(List<RegionSnapshot> regions, BiPredicate<Player, RegionSnapshot> audience,
                          Player player, Check check, @Nullable Material material,
                          double x, double y, double z) {
        List<RegionSnapshot> applying = null;
        for (int index = 0; index < regions.size(); index++) {
            RegionSnapshot region = regions.get(index);
            if (!accepts(audience, player, region)) continue;
            if (applying == null) applying = new ArrayList<>(regions.size());
            applying.add(region);
        }
        if (applying == null) return false;
        return switch (check) {
            case BREAK -> !mayChange(applying, CommonRegionPolicies.BREAKABLE_BLOCKS_ONLY,
                    CommonRegionPolicies.BREAKABLE_BLOCKS, CommonRegionPolicies.BREAK, material)
                    || value(applying, CommonRegionPolicies.PLAYER_BUILD_ONLY)
                    && !placedByPlayer(regions, x, y, z);
            case BUILD -> !mayChange(applying, CommonRegionPolicies.ALLOWED_BLOCKS_ONLY,
                    CommonRegionPolicies.ALLOWED_BLOCKS, CommonRegionPolicies.BUILD, material);
            default -> !value(applying, check.key);
        };
    }

    /**
     * A consumer's audience that throws exempts the player from that region instead of
     * failing the event: one plugin's bug must not break every block break on the server.
     */
    private static boolean accepts(BiPredicate<Player, RegionSnapshot> audience,
                                   Player player, RegionSnapshot region) {
        try {
            return audience.test(player, region);
        } catch (RuntimeException failure) {
            if (FAILED.add(region.owner())) {
                LOGGER.warning("The region audience of " + region.owner()
                        + " failed; its rules are skipped for that player: " + failure);
            }
            return false;
        }
    }

    private static boolean mayChange(List<RegionSnapshot> applying, PolicyKey<Boolean> only,
                                     PolicyKey<MaterialSet> list, PolicyKey<Boolean> plain,
                                     @Nullable Material material) {
        if (value(applying, only)) return material != null && value(applying, list).contains(material);
        return value(applying, plain);
    }

    private static <T> T value(List<RegionSnapshot> applying, PolicyKey<T> key) {
        for (int index = 0; index < applying.size(); index++) {
            java.util.Optional<T> value = applying.get(index).policySet().explicit(key);
            if (value.isPresent()) return value.get();
        }
        return key.defaultValue();
    }

    /** Over every region of the owner at the block, as {@code PluginRegions.placedByPlayer} answers. */
    private static boolean placedByPlayer(List<RegionSnapshot> regions, double x, double y, double z) {
        int blockX = (int) Math.floor(x);
        int blockY = (int) Math.floor(y);
        int blockZ = (int) Math.floor(z);
        for (int index = 0; index < regions.size(); index++) {
            if (PlacedBlockRuntime.tracked(regions.get(index).id(), blockX, blockY, blockZ)) return true;
        }
        return false;
    }
}
