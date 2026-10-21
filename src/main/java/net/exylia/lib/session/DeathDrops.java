package net.exylia.lib.session;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Whose a dying player's drops are, when they are not just the floor's.
 *
 * <pre>{@code
 * // A duel: what the loser drops is the winner's loot.
 * DeathDrops.claim(this, player -> duels.isFighting(player.getUniqueId()));
 *
 * // A graves plugin: leaves those deaths alone.
 * if (DeathDrops.claimedBy(player).isPresent() || Sessions.holder(player).isPresent()) return;
 * }</pre>
 *
 * <p>A plugin that holds a player through {@link Sessions} already says so; this
 * is for a fight that never takes the player anywhere, such as a duel inside a
 * region of the open world. Plugins that take the drops — graves, death chests —
 * ask here and leave a claimed death alone.
 *
 * <p>One rule per plugin, replaced by a second call, released with the plugin.
 * Asked on the dying player's thread, during the death event: a rule must answer
 * from memory.
 *
 * @since 1.322.0
 */
public final class DeathDrops {

    private static final Map<String, Predicate<Player>> RULES = new ConcurrentHashMap<>();

    private DeathDrops() {
        throw new AssertionError("No instances.");
    }

    /** Declares which deaths' drops this plugin owns, replacing its previous rule. */
    public static void claim(@NotNull Plugin plugin, @NotNull Predicate<Player> owns) {
        RULES.put(plugin.getName(), owns);
    }

    /** Takes this plugin's rule away. */
    public static void release(@NotNull Plugin plugin) {
        RULES.remove(plugin.getName());
    }

    /** The plugin whose rule claims this player's drops, if any does. A rule that throws claims nothing. */
    public static @NotNull Optional<String> claimedBy(@NotNull Player player) {
        for (Map.Entry<String, Predicate<Player>> rule : RULES.entrySet()) {
            try {
                if (rule.getValue().test(player)) return Optional.of(rule.getKey());
            } catch (RuntimeException ignored) {
                // A broken rule must not take the death event down with it.
            }
        }
        return Optional.empty();
    }

    /** Called by the library when a plugin is disabled. */
    public static void forgetPlugin(@NotNull String plugin) {
        RULES.remove(plugin);
    }
}
