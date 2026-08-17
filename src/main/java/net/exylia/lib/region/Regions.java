package net.exylia.lib.region;

import net.exylia.lib.region.internal.RegionRuntime;
import net.exylia.lib.region.internal.SelectionRuntime;
import net.exylia.lib.region.internal.VisualizationRuntime;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Global entry point for the shared immutable region registry. */
public final class Regions {

    private Regions() {
    }

    /** Returns one plugin's owner-scoped region facade using its derived namespace. */
    public static @NotNull PluginRegions of(@NotNull Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        String namespace = plugin.getName().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.-]", "");
        return of(plugin, namespace);
    }

    /** Returns one plugin's owner-scoped region facade with an explicit stable namespace. */
    public static @NotNull PluginRegions of(@NotNull Plugin plugin,
                                             @NotNull String namespace) {
        return new PluginRegions(plugin, namespace);
    }

    /** Finds all globally registered regions containing a Bukkit location. */
    public static @NotNull List<RegionSnapshot> at(@NotNull Location location) {
        Objects.requireNonNull(location, "location");
        World world = Objects.requireNonNull(location.getWorld(), "location world");
        return at(world.getUID(), location.getX(), location.getY(), location.getZ());
    }

    /** Finds all globally registered regions containing a primitive point. */
    public static @NotNull List<RegionSnapshot> at(@NotNull UUID worldId,
                                                    double x, double y, double z) {
        return RegionRuntime.query(worldId, x, y, z);
    }

    /** Performs an exact global identifier lookup. */
    public static @NotNull Optional<RegionSnapshot> get(@NotNull RegionId id) {
        return Optional.ofNullable(RegionRuntime.get(id));
    }

    /** Resolves a policy globally by normal region precedence. */
    public static <T> @NotNull PolicyResolution<T> resolve(@NotNull Location location,
                                                            @NotNull PolicyKey<T> key) {
        Objects.requireNonNull(location, "location");
        World world = Objects.requireNonNull(location.getWorld(), "location world");
        return resolve(world.getUID(), location.getX(), location.getY(), location.getZ(), key);
    }

    /** Resolves a policy globally at a primitive point. */
    public static <T> @NotNull PolicyResolution<T> resolve(@NotNull UUID worldId,
                                                            double x, double y, double z,
                                                            @NotNull PolicyKey<T> key) {
        return RegionRuntime.resolve(worldId, x, y, z, key);
    }

    /** Returns all globally registered snapshots in deterministic query order. */
    public static @NotNull List<RegionSnapshot> all() {
        return RegionRuntime.all();
    }

    /** Returns the number of globally registered regions. */
    public static int registered() {
        return RegionRuntime.size();
    }

    /** Releases regions and selections whose owner exactly matches the plugin name. */
    public static int release(@NotNull String pluginName) {
        SelectionRuntime.release(pluginName);
        VisualizationRuntime.release(pluginName);
        return RegionRuntime.release(pluginName);
    }

    /** Releases every registered region, active selection, and active outline. */
    public static void releaseAll() {
        SelectionRuntime.releaseAll();
        VisualizationRuntime.releaseAll();
        RegionRuntime.releaseAll();
    }
}
