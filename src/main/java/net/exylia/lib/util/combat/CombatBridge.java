package net.exylia.lib.util.combat;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Optional;

/**
 * An integration with a combat plugin this library does not know about.
 *
 * <p>Register one with {@link Combat#registerBridge}. Everything except
 * {@link #name()} and {@link #isTagged} has a default, so a bridge only writes
 * what its plugin can actually answer — the defaults are what a server with no
 * combat plugin does.
 *
 * <pre>{@code
 * Combat.registerBridge(new CombatBridge() {
 *     public String name() { return "CelestCombat"; }
 *     public boolean isTagged(Player player) { return celest.inCombat(player); }
 *     public Duration remaining(Player player) { return celest.timeLeft(player); }
 * }, 10);
 * }</pre>
 *
 * <h2>Threading</h2>
 * Methods are called on whichever thread asked. A bridge that cannot answer
 * from an arbitrary thread has to say so by returning the default rather than
 * by throwing.
 *
 * @since 1.36.0
 */
public interface CombatBridge {

    /**
     * Returns the name of the plugin behind this bridge.
     *
     * @return the name, as it should read in a log line
     */
    @NotNull String name();

    /**
     * Returns whether a player is in combat.
     *
     * @param player the player
     * @return {@code true} when they are tagged
     */
    boolean isTagged(@NotNull Player player);

    /**
     * Returns how long a player stays in combat.
     *
     * @param player the player
     * @return the time left, {@link Duration#ZERO} when untagged or unknown
     */
    default @NotNull Duration remaining(@NotNull Player player) {
        return Duration.ZERO;
    }

    /**
     * Returns who a player is fighting.
     *
     * @param player the player
     * @return their opponent, empty when unknown
     */
    default @NotNull Optional<Player> opponentOf(@NotNull Player player) {
        return Optional.empty();
    }

    /**
     * Puts a player in combat.
     *
     * @param target   who is tagged
     * @param attacker who tagged them
     * @param duration how long, or {@code null} for the plugin's own default
     */
    default void tag(@NotNull Player target, @NotNull Player attacker, Duration duration) {
    }

    /**
     * Takes a player out of combat.
     *
     * @param player the player
     */
    default void untag(@NotNull Player player) {
    }

    /**
     * Returns whether a player is under new-player or respawn protection.
     *
     * @param player the player
     * @return {@code true} when they are protected
     */
    default boolean isProtected(@NotNull Player player) {
        return false;
    }

    /**
     * Returns whether a player has PvP switched on.
     *
     * @param player the player
     * @return {@code true} when they can fight
     */
    default boolean isPvpEnabled(@NotNull Player player) {
        return true;
    }

    /**
     * Switches a player's PvP on or off.
     *
     * @param player  the player
     * @param enabled whether they can fight
     */
    default void setPvpEnabled(@NotNull Player player, boolean enabled) {
    }

    /**
     * Returns whether one player may hit another.
     *
     * @param attacker who is hitting
     * @param defender who is being hit
     * @return {@code true} when the hit is allowed
     */
    default boolean canAttack(@NotNull Player attacker, @NotNull Player defender) {
        return true;
    }

    /**
     * Returns what this plugin counted about a player.
     *
     * @param player the player
     * @return their stats, empty when this plugin counts nothing
     */
    default @NotNull Optional<CombatStats> statsOf(@NotNull Player player) {
        return Optional.empty();
    }
}
