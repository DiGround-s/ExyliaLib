package net.exylia.lib.util.worldguard.internal;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import net.exylia.lib.util.worldguard.WorldGuardRegion;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * The only class that names WorldGuard types.
 *
 * <p>The JVM resolves a class's references lazily, so as long as nothing
 * outside this class mentions WorldGuard, the public facade loads fine on a
 * server without it. The facade checks the plugin is enabled before calling in.
 */
public final class WorldGuardAccess {

    private static final Comparator<WorldGuardRegion> HIGHEST_FIRST =
            Comparator.comparingInt(WorldGuardRegion::priority).reversed()
                    .thenComparing(WorldGuardRegion::id);

    private WorldGuardAccess() {
        throw new AssertionError("No instances.");
    }

    public static @NotNull List<WorldGuardRegion> at(@NotNull World world, int x, int y, int z) {
        RegionManager manager = manager(world);
        if (manager == null) {
            return List.of();
        }
        List<WorldGuardRegion> out = new ArrayList<>();
        for (ProtectedRegion region : manager.getApplicableRegions(BlockVector3.at(x, y, z))) {
            out.add(new WorldGuardRegion(world.getName(), region.getId(), region.getPriority()));
        }
        out.sort(HIGHEST_FIRST);
        return List.copyOf(out);
    }

    public static @NotNull Set<String> ids(@NotNull World world) {
        RegionManager manager = manager(world);
        return manager == null ? Set.of() : Set.copyOf(manager.getRegions().keySet());
    }

    private static RegionManager manager(World world) {
        return WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
    }
}
