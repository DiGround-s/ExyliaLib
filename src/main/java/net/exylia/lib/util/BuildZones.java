package net.exylia.lib.util;

import net.exylia.lib.api.ExyliaAPI;
import net.exylia.lib.api.protections.ProtectionsService;
import net.exylia.lib.region.CommonRegionPolicies;
import net.exylia.lib.region.PolicyKey;
import net.exylia.lib.region.Regions;
import net.exylia.lib.util.internal.ClaimBuild;
import net.exylia.lib.util.worldguard.WorldGuardRegions;
import net.exylia.lib.util.worldguard.internal.WorldGuardAccess;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Whether a player may build, break or use a block, as every region and claim
 * plugin on the server sees it.
 *
 * <pre>{@code
 * if (!BuildZones.canBuild(player, block)) {
 *     messages.send(player, text.notYourLand());
 *     return;
 * }
 * // or ask only the sources the server owner left on, and say which one refused
 * BuildZones.Source refused = BuildZones.deniedBy(player, block, BuildZones.Action.CONTAINER, settings.zoneSources());
 * }</pre>
 *
 * <p>For actions that happen outside a block event (a menu opening a block's
 * storage, a remote sell, a placement a plugin makes itself), where no claim
 * plugin's listener gets the chance to cancel. Inside a {@code BlockPlaceEvent}
 * listener at {@code ignoreCancelled = true} the claim plugins have already
 * spoken.
 *
 * <p>Soft everywhere: a source whose plugin is missing, disabled, or fails when
 * asked says "allowed", and is logged once. Each plugin's own bypass (WorldGuard
 * bypass, GriefPrevention's ignore-claims, Lands admins) applies, because each
 * is asked through the check its own listeners use. The claim plugins are
 * reached by reflection, as in {@link PvpZones}; nothing loads without them.
 *
 * <p>Ask on the thread that owns the block (the player's region on Folia),
 * because the claim plugins read their caches there.
 *
 * @since 1.432.0
 */
public final class BuildZones {

    /** What the player is about to do to the block. */
    public enum Action {
        /** Place a block. */
        BUILD,
        /** Break a block. */
        BREAK,
        /** Use a block: a button, a door, a block opening a menu. */
        INTERACT,
        /** Open or take from a block's storage: a chest, a spawner's loot. */
        CONTAINER
    }

    /** Where a refusal can come from. */
    public enum Source {
        /** WorldGuard's own build test with the action's flag ({@code block-place}, {@code block-break}, {@code interact}, {@code chest-access}). */
        WORLDGUARD("WorldGuard"),
        /** An ExyliaLib region declaring {@code build}, {@code break} or {@code interact: false}. */
        EXYLIA_REGIONS(null),
        /** ExyliaProtections, through its {@link ProtectionsService}. */
        EXYLIA_PROTECTIONS("ExyliaProtections"),
        /** A Lands area where the player's role lacks the action's flag. */
        LANDS("Lands"),
        /** A GriefPrevention claim without the player's build, container or access trust. */
        GRIEF_PREVENTION("GriefPrevention"),
        /** Towny's build, destroy or switch permission at the block. */
        TOWNY("Towny"),
        /** A SuperiorSkyblock2 island without the player's build, break, interact or chest-access privilege. */
        SUPERIOR_SKYBLOCK("SuperiorSkyblock2");

        private final String pluginName;

        Source(String pluginName) {
            this.pluginName = pluginName;
        }

        /** The plugin behind it, {@code null} for ExyliaLib's own regions. */
        public @Nullable String pluginName() {
            return pluginName;
        }

        /** Whether this source can answer on this server right now. */
        public boolean available() {
            if (pluginName == null) {
                return Regions.registered() > 0;
            }
            Plugin plugin = Bukkit.getPluginManager().getPlugin(pluginName);
            return plugin != null && plugin.isEnabled();
        }
    }

    /** Every source, for callers that don't let the owner choose. */
    public static final Set<Source> ALL = Collections.unmodifiableSet(EnumSet.allOf(Source.class));

    private BuildZones() {
    }

    /** Whether every source lets the player place a block here. */
    public static boolean canBuild(@NotNull Player player, @NotNull Block block) {
        return deniedBy(player, block, Action.BUILD, ALL) == null;
    }

    /** Whether every source lets the player break this block. */
    public static boolean canBreak(@NotNull Player player, @NotNull Block block) {
        return deniedBy(player, block, Action.BREAK, ALL) == null;
    }

    /** Whether every source lets the player use this block. */
    public static boolean canInteract(@NotNull Player player, @NotNull Block block) {
        return deniedBy(player, block, Action.INTERACT, ALL) == null;
    }

    /** Whether every source lets the player open or take from this block's storage. */
    public static boolean canOpen(@NotNull Player player, @NotNull Block block) {
        return deniedBy(player, block, Action.CONTAINER, ALL) == null;
    }

    /** Whether every source allows the action. */
    public static boolean allows(@NotNull Player player, @NotNull Block block, @NotNull Action action) {
        return deniedBy(player, block, action, ALL) == null;
    }

    /**
     * The first source that refuses the action, asking only the ones named.
     *
     * @param player  who acts
     * @param block   on what
     * @param action  what they do
     * @param sources the sources to ask, in enum order
     * @return the source that refuses, or {@code null} when every one allows
     */
    public static @Nullable Source deniedBy(@NotNull Player player, @NotNull Block block, @NotNull Action action,
                                            @NotNull Set<Source> sources) {
        for (Source source : sources) {
            if (!allows(source, player, block, action)) {
                return source;
            }
        }
        return null;
    }

    /** Whether one source allows the action. A source that cannot answer allows it. */
    public static boolean allows(@NotNull Source source, @NotNull Player player, @NotNull Block block,
                                 @NotNull Action action) {
        World world = block.getWorld();
        return switch (source) {
            case WORLDGUARD -> !WorldGuardRegions.available()
                    || WorldGuardAccess.allows(player, block.getLocation(), action);
            case EXYLIA_REGIONS -> Regions.registered() == 0
                    || Regions.resolve(world.getUID(), block.getX() + 0.5, block.getY(), block.getZ() + 0.5, policy(action)).value();
            case EXYLIA_PROTECTIONS -> protections(player, block.getLocation(), action);
            default -> ClaimBuild.allows(source, player, block, action);
        };
    }

    static PolicyKey<Boolean> policy(Action action) {
        return switch (action) {
            case BUILD -> CommonRegionPolicies.BUILD;
            case BREAK -> CommonRegionPolicies.BREAK;
            case INTERACT, CONTAINER -> CommonRegionPolicies.INTERACT;
        };
    }

    private static boolean protections(Player player, Location at, Action action) {
        if (!Source.EXYLIA_PROTECTIONS.available()) {
            return true;
        }
        ProtectionsService service = ExyliaAPI.get(ProtectionsService.class).orElse(null);
        if (service == null) {
            return true;
        }
        try {
            return switch (action) {
                case BUILD -> service.canBuild(player, at);
                case BREAK -> service.canBreak(player, at);
                case INTERACT, CONTAINER -> service.canInteract(player, at);
            };
        } catch (RuntimeException | LinkageError failure) {
            // An ExyliaProtections build that cannot answer allows, like every other source.
            return true;
        }
    }
}
