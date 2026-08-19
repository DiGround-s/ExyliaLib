package net.exylia.lib.util.combat.internal;

import net.exylia.lib.util.combat.CombatStats;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Optional;

/**
 * One combat plugin, seen the same way as every other.
 *
 * <p>Adding a plugin means adding one implementation and one line in
 * {@link CombatRuntime}. Nothing outside this package knows how many there are.
 *
 * <p>Every default here is what a server with no combat plugin does, so an
 * implementation only writes what its plugin can really answer.
 */
public interface CombatProvider {

    /** Returns whether this plugin is installed and usable. */
    boolean enabled();

    /** Returns the plugin's name, as it should read in a log line. */
    String name();

    boolean isTagged(Player player);

    default Duration remaining(Player player) {
        return Duration.ZERO;
    }

    default Optional<Player> opponentOf(Player player) {
        return Optional.empty();
    }

    /** Tags a player. A {@code null} duration means the plugin's own default. */
    default void tag(Player target, Player attacker, Duration duration) {
    }

    default void untag(Player player) {
    }

    default boolean isProtected(Player player) {
        return false;
    }

    default boolean isPvpEnabled(Player player) {
        return true;
    }

    default void setPvpEnabled(Player player, boolean enabled) {
    }

    default boolean canAttack(Player attacker, Player defender) {
        return true;
    }

    default Optional<CombatStats> statsOf(Player player) {
        return Optional.empty();
    }

    /** Builds a provider, or returns one that reports itself disabled. */
    interface Factory {
        CombatProvider tryCreate();
    }
}
