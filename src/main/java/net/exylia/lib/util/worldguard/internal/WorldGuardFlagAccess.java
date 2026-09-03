package net.exylia.lib.util.worldguard.internal;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The only class that names WorldGuard's flag types.
 *
 * <p>The JVM resolves a class's references lazily, so the public facade loads
 * fine on a server without WorldGuard as long as nothing outside this class
 * mentions it. The facade checks WorldGuard is there before calling in.
 */
public final class WorldGuardFlagAccess {

    private static final Map<String, StateFlag> FLAGS = new ConcurrentHashMap<>();

    private WorldGuardFlagAccess() {
        throw new AssertionError("No instances.");
    }

    /**
     * Registers a state flag, or adopts one already registered under the name.
     *
     * <p>Everything is caught. A flag that cannot be registered is a gate the
     * server does not have, which is a line in the log; it is never a reason
     * for the library — and therefore every plugin depending on it — to fail to
     * load.
     *
     * @return whether the flag can be queried afterwards
     */
    public static boolean register(@NotNull String name, boolean allowedByDefault) {
        try {
            FlagRegistry registry = WorldGuard.getInstance().getFlagRegistry();
            Flag<?> existing = registry.get(name);
            if (existing != null) {
                // Another plugin got there first. Sharing its flag is right
                // when it is the same kind of gate, and refusing to answer is
                // right when it is not: a numeric flag with our name is not a
                // permission to draw anything.
                if (existing instanceof StateFlag state) {
                    FLAGS.put(name, state);
                    return true;
                }
                return false;
            }
            StateFlag flag = new StateFlag(name, allowedByDefault);
            registry.register(flag);
            FLAGS.put(name, flag);
            return true;
        } catch (Throwable refused) {
            // Registered too late, or a WorldGuard whose registry moved.
            return false;
        }
    }

    /**
     * Whether the regions covering a location permit this flag.
     *
     * <p>Asked with no subject: an effect belongs to the place it is drawn in,
     * not to a player's membership of the region it is drawn in. A region that
     * denies the flag denies it for its own members too, which is what a server
     * owner writing {@code deny} on a lobby means.
     */
    public static boolean allows(@NotNull String name, @NotNull Location where) {
        StateFlag flag = FLAGS.get(name);
        if (flag == null) {
            return true;
        }
        try {
            RegionQuery query = WorldGuard.getInstance().getPlatform()
                    .getRegionContainer().createQuery();
            return query.testState(BukkitAdapter.adapt(where), null, flag);
        } catch (Throwable unavailable) {
            // A world WorldGuard has no manager for, or a region index still
            // loading. Nothing is being protected yet, so nothing is withheld.
            return true;
        }
    }
}
