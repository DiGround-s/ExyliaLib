package net.exylia.lib.util;

import net.exylia.lib.platform.RequiresServer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The ender pearls a player still has in the air.
 *
 * <p>A pearl that lands after its thrower was moved teleports them straight
 * back to where they were, so arenas and lobbies remove them before moving a
 * player. Paper has had {@code Player#getEnderPearls} since 1.21.2; on 1.21.1
 * the pearls are found by their shooter instead.
 *
 * <pre>{@code
 * for (EnderPearl pearl : EnderPearls.inFlight(player)) {
 *     pearl.remove();
 * }
 * }</pre>
 *
 * <p>Call it on the thread that owns the player. The older path walks every
 * world's pearls, which is only done on Paper: Folia starts at 1.21.4 and
 * always takes the direct API.
 *
 * @since 1.448.0
 */
public final class EnderPearls {

    private static final boolean DIRECT = hasDirect();

    private EnderPearls() {
    }

    /**
     * The pearls this player threw that have not landed yet.
     *
     * @param player the thrower
     * @return the pearls in flight, possibly empty; a copy the caller may keep
     */
    public static @NotNull Collection<EnderPearl> inFlight(@NotNull Player player) {
        if (DIRECT) {
            return Direct.of(player);
        }
        List<EnderPearl> found = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            for (EnderPearl pearl : world.getEntitiesByClass(EnderPearl.class)) {
                if (player.equals(pearl.getShooter())) {
                    found.add(pearl);
                }
            }
        }
        return found;
    }

    private static boolean hasDirect() {
        try {
            Player.class.getMethod("getEnderPearls");
            return true;
        } catch (NoSuchMethodException | LinkageError older) {
            return false;
        }
    }

    @RequiresServer("1.21.4")
    private static final class Direct {
        static Collection<EnderPearl> of(Player player) {
            return new ArrayList<>(player.getEnderPearls());
        }
    }
}
