package net.exylia.lib.util.worldguard;

import net.exylia.lib.util.worldguard.internal.WorldGuardAccess;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which WorldGuard regions cover a location, without a compile-time dependency
 * on WorldGuard in the calling plugin.
 *
 * <pre>{@code
 * if (WorldGuardRegions.isIn(player, "spawn")) { ... }
 *
 * for (WorldGuardRegion region : WorldGuardRegions.at(player.getLocation())) {
 *     // highest priority first
 * }
 * }</pre>
 *
 * <h2>Regions are per world</h2>
 * WorldGuard has one region manager per world, so {@code test} in
 * {@code world} and {@code test} in {@code world_nether} are different regions.
 * Every answer here is scoped to the world of the location asked about, and
 * {@link WorldGuardRegion#qualified()} gives the {@code world:id} form for
 * configs that must tell them apart.
 *
 * <h2>When WorldGuard is not installed</h2>
 * Every method still works: nothing is ever inside any region, and every
 * listing is empty. No class of WorldGuard is touched until
 * {@link #available()} has said it is there, so this library loads on servers
 * without it.
 *
 * <h2>Threading</h2>
 * WorldGuard's region index is safe to read from any thread.
 *
 * @since 1.74.0
 */
public final class WorldGuardRegions {

    private WorldGuardRegions() {
        throw new AssertionError("No instances.");
    }

    /** Returns whether WorldGuard is installed and enabled. */
    public static boolean available() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("WorldGuard");
        return plugin != null && plugin.isEnabled();
    }

    /**
     * Returns the regions covering a location, highest priority first.
     *
     * @return the regions, empty without WorldGuard or outside every region
     */
    public static @NotNull List<WorldGuardRegion> at(@NotNull Location location) {
        World world = location.getWorld();
        if (world == null || !available()) {
            return List.of();
        }
        return WorldGuardAccess.at(world, location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    /** Returns the regions covering a player's position, highest priority first. */
    public static @NotNull List<WorldGuardRegion> at(@NotNull Player player) {
        return at(player.getLocation());
    }

    /** Returns the ids of the regions covering a location, in the same order as {@link #at}. */
    public static @NotNull List<String> idsAt(@NotNull Location location) {
        return at(location).stream().map(WorldGuardRegion::id).toList();
    }

    /**
     * Returns whether a location lies inside the region with this id in the
     * location's own world.
     */
    public static boolean isIn(@NotNull Location location, @NotNull String regionId) {
        String wanted = regionId.toLowerCase(Locale.ROOT);
        for (WorldGuardRegion region : at(location)) {
            if (region.id().equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    /** Returns whether a player stands inside the region with this id in their current world. */
    public static boolean isIn(@NotNull Player player, @NotNull String regionId) {
        return isIn(player.getLocation(), regionId);
    }

    /**
     * Returns every region id defined in a world, for tab completion and
     * config validation.
     *
     * @return the ids, empty without WorldGuard
     */
    public static @NotNull Set<String> ids(@NotNull World world) {
        return available() ? WorldGuardAccess.ids(world) : Set.of();
    }

    /** Returns whether a region with this id exists in a world. */
    public static boolean exists(@NotNull World world, @NotNull String regionId) {
        return ids(world).contains(regionId.toLowerCase(Locale.ROOT));
    }
}
