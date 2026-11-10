package net.exylia.lib.movement;

import net.exylia.lib.task.Tasks;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who was launched and has not landed yet, so the fall that ends the flight
 * costs no health.
 *
 * <p>A landing is noticed on a move with the ground flag set, and forgotten a
 * few ticks later rather than at once: the fall damage of that same landing
 * can be dealt after the move that reports it.
 */
final class FallGuard {

    private static final long FORGET_AFTER_TICKS = 3L;

    private final Plugin plugin;
    private final Set<UUID> airborne = ConcurrentHashMap.newKeySet();

    FallGuard(Plugin plugin) {
        this.plugin = plugin;
    }

    void launched(Player player) {
        airborne.add(player.getUniqueId());
    }

    boolean isAirborne(Player player) {
        return airborne.contains(player.getUniqueId());
    }

    /** On a move: a launched player back on the ground is forgotten shortly. */
    void moved(Player player) {
        if (!player.isOnGround() || !airborne.contains(player.getUniqueId())) return;
        Tasks.of(plugin).runAtEntityLater(player, FORGET_AFTER_TICKS, () -> {
            if (player.isOnGround()) airborne.remove(player.getUniqueId());
        });
    }

    /** Whether a fall should be cancelled; forgets the player when it is. */
    boolean absorbs(Player player) {
        return airborne.remove(player.getUniqueId());
    }

    void forget(UUID player) {
        airborne.remove(player);
    }

    void clear() {
        airborne.clear();
    }
}
