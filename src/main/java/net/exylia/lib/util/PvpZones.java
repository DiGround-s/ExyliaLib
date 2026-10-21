package net.exylia.lib.util;

import net.exylia.lib.region.CommonRegionPolicies;
import net.exylia.lib.region.Regions;
import net.exylia.lib.util.internal.ClaimPvp;
import net.exylia.lib.util.worldguard.WorldGuardRegions;
import net.exylia.lib.util.worldguard.internal.WorldGuardAccess;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * Whether PvP is refused at a place, as the region and claim plugins on the
 * server see it.
 *
 * <p>One question with one answer for every plugin that needs it — a combat
 * tag keeping a fighter out of a safe zone, a barrier drawing the border, a
 * reward skipped at spawn. Each {@link Source} is asked only when the caller
 * names it, so a server owner can switch any of them off in the consuming
 * plugin's own config.
 *
 * <p>None of the claim plugins is on the compile classpath: they are reached by
 * reflection through their own class loader, resolved once per plugin instance.
 * A source whose plugin is missing, disabled or answers with an error says
 * "allowed": a gate that cannot be asked never withholds anything.
 *
 * <p>Ask on the thread that owns the location.
 *
 * @since 1.335.0
 */
public final class PvpZones {

    /** Where a refusal can come from. */
    public enum Source {
        /** A WorldGuard region with {@code pvp deny}, the global region included. */
        WORLDGUARD("WorldGuard"),
        /** An ExyliaLib region declaring {@code pvp: false} ({@link CommonRegionPolicies#PVP}). */
        EXYLIA_REGIONS(null),
        /** A Lands area where the player's role lacks the attack-player flag. Needs the player. */
        LANDS("Lands"),
        /** A GriefPrevention claim its config treats as a PvP safe zone. */
        GRIEF_PREVENTION("GriefPrevention"),
        /** A Towny town block, or wilderness, with PvP off. */
        TOWNY("Towny"),
        /** A SuperiorSkyblock2 island without the {@code PVP} island flag. */
        SUPERIOR_SKYBLOCK("SuperiorSkyblock2");

        private final String pluginName;

        Source(String pluginName) {
            this.pluginName = pluginName;
        }

        /** The plugin behind it, {@code null} for ExyliaLib's own regions. */
        public @Nullable String pluginName() {
            return pluginName;
        }

        /**
         * Whether this source can answer on this server right now: its plugin
         * is enabled, or for ExyliaLib's regions, any region is registered.
         */
        public boolean available() {
            if (pluginName == null) {
                return Regions.registered() > 0;
            }
            Plugin plugin = Bukkit.getPluginManager().getPlugin(pluginName);
            return plugin != null && plugin.isEnabled();
        }
    }

    private static final ThreadLocal<Location> PROBE = ThreadLocal.withInitial(() -> new Location(null, 0, 0, 0));

    private PvpZones() {
    }

    /**
     * Whether any of the sources refuses PvP at a location.
     *
     * @param location the place
     * @param player   who would fight there; {@code null} skips the sources that need one
     * @param sources  the sources to ask
     * @return the first source that refuses, or {@code null} when every one allows
     */
    public static @Nullable Source deniedBy(@NotNull Location location, @Nullable Player player,
                                            @NotNull Set<Source> sources) {
        World world = location.getWorld();
        if (world == null || sources.isEmpty()) {
            return null;
        }
        for (Source source : sources) {
            if (denies(source, world, location.getX(), location.getY(), location.getZ(), player)) {
                return source;
            }
        }
        return null;
    }

    /** Whether any of the sources refuses PvP at a location. */
    public static boolean isDenied(@NotNull Location location, @Nullable Player player, @NotNull Set<Source> sources) {
        return deniedBy(location, player, sources) != null;
    }

    /**
     * Whether one source refuses PvP at a block. Meant for scans: no
     * {@link Location} is allocated per call.
     */
    public static boolean denies(@NotNull Source source, @NotNull World world, int x, int y, int z,
                                 @Nullable Player player) {
        return denies(source, world, x + 0.5, y, z + 0.5, player);
    }

    private static boolean denies(Source source, World world, double x, double y, double z, Player player) {
        return switch (source) {
            case WORLDGUARD -> WorldGuardRegions.available() && WorldGuardAccess.pvpDenied(world, x, y, z);
            case EXYLIA_REGIONS -> !Regions.resolve(world.getUID(), x, y, z, CommonRegionPolicies.PVP).value();
            default -> {
                Location probe = PROBE.get();
                probe.setWorld(world);
                probe.setX(x);
                probe.setY(y);
                probe.setZ(z);
                try {
                    yield ClaimPvp.denies(source, probe, player);
                } finally {
                    probe.setWorld(null);
                }
            }
        };
    }
}
