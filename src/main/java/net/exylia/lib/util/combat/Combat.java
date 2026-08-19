package net.exylia.lib.util.combat;

import net.exylia.lib.util.combat.internal.CombatRuntime;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Optional;

/**
 * Whether a player is in combat, without asking which plugin decides that.
 *
 * <pre>{@code
 * if (Combat.isTagged(player)) {
 *     Text.of("{error}You cannot warp while in combat.").send(player);
 *     return;
 * }
 *
 * // and a plugin that starts a fight of its own says so
 * Combat.tag(target, attacker, Duration.ofSeconds(15));
 * }</pre>
 *
 * <h2>What this replaces</h2>
 * Four plugins each grew their own hook for the same question, and each one
 * knew a different set of combat plugins — so the same server answered "is this
 * player in combat" differently depending on which plugin asked. Asking here
 * means one answer.
 *
 * <h2>When nothing is installed</h2>
 * Every method still works. Nobody is ever tagged, PvP is always allowed, and
 * stats are empty. A server with no combat plugin behaves like a server where
 * nobody is fighting, which is the truth.
 *
 * <h2>Threading</h2>
 * Every method is safe from any thread. Reads go through a short cache; a write
 * ({@link #tag}, {@link #untag}) reaches the underlying plugin directly, so it
 * is called on whichever thread the caller is on and that plugin's own rules
 * apply.
 *
 * @since 1.36.0
 */
public final class Combat {

    private Combat() {
        throw new AssertionError("No instances.");
    }

    /**
     * Returns whether a player is currently in combat.
     *
     * @param player the player
     * @return {@code true} when they are tagged
     */
    public static boolean isTagged(@NotNull Player player) {
        return CombatRuntime.isTagged(player);
    }

    /**
     * Returns how long a player stays in combat.
     *
     * @param player the player
     * @return the time left, {@link Duration#ZERO} when they are not tagged
     */
    public static @NotNull Duration remaining(@NotNull Player player) {
        return CombatRuntime.remaining(player);
    }

    /**
     * Returns who a player is fighting.
     *
     * @param player the player
     * @return their opponent, empty when they are not tagged or nobody knows
     */
    public static @NotNull Optional<Player> opponentOf(@NotNull Player player) {
        return CombatRuntime.opponentOf(player);
    }

    /**
     * Puts a player in combat with another, for the combat plugin's own
     * default time.
     *
     * @param target   who is tagged
     * @param attacker who tagged them
     */
    public static void tag(@NotNull Player target, @NotNull Player attacker) {
        CombatRuntime.tag(target, attacker, null);
    }

    /**
     * Puts a player in combat for a set time.
     *
     * <p>A plugin that does not honour a custom time uses its own; that is
     * their rule and not something to work around by tagging twice.
     *
     * @param target   who is tagged
     * @param attacker who tagged them
     * @param duration how long it lasts
     */
    public static void tag(@NotNull Player target, @NotNull Player attacker,
                           @NotNull Duration duration) {
        CombatRuntime.tag(target, attacker, duration);
    }

    /**
     * Takes a player out of combat.
     *
     * @param player the player
     */
    public static void untag(@NotNull Player player) {
        CombatRuntime.untag(player);
    }

    /**
     * Returns whether a player is under new-player or respawn protection.
     *
     * @param player the player
     * @return {@code true} when the combat plugin is shielding them
     */
    public static boolean isProtected(@NotNull Player player) {
        return CombatRuntime.isProtected(player);
    }

    /**
     * Returns whether a player has PvP switched on.
     *
     * <p>{@code true} when nothing is installed: a server with no combat plugin
     * is a server where PvP is on.
     *
     * @param player the player
     * @return {@code true} when they can fight
     */
    public static boolean isPvpEnabled(@NotNull Player player) {
        return CombatRuntime.isPvpEnabled(player);
    }

    /**
     * Switches a player's PvP on or off.
     *
     * @param player  the player
     * @param enabled whether they can fight
     */
    public static void setPvpEnabled(@NotNull Player player, boolean enabled) {
        CombatRuntime.setPvpEnabled(player, enabled);
    }

    /**
     * Returns whether one player is allowed to hit another.
     *
     * <p>Fails open: when nothing is installed, or the combat plugin throws,
     * the answer is {@code true}. Failing the other way would silently stop
     * every fight on the server because one integration broke.
     *
     * @param attacker who is hitting
     * @param defender who is being hit
     * @return {@code true} when the hit is allowed
     */
    public static boolean canAttack(@NotNull Player attacker, @NotNull Player defender) {
        return CombatRuntime.canAttack(attacker, defender);
    }

    /**
     * Returns what the combat plugin counted about a player.
     *
     * @param player the player
     * @return their stats, empty when the active plugin keeps none
     */
    public static @NotNull Optional<CombatStats> statsOf(@NotNull Player player) {
        return CombatRuntime.statsOf(player);
    }

    /**
     * Returns the name of the combat plugin in use.
     *
     * @return its name, or an empty string when none was found
     */
    public static @NotNull String providerName() {
        return CombatRuntime.providerName();
    }

    /**
     * Returns whether a combat plugin was found at all.
     *
     * @return {@code true} when one is active
     */
    public static boolean isSupported() {
        return CombatRuntime.isSupported();
    }

    /**
     * Registers an integration for a combat plugin this library does not know.
     *
     * <p>The highest priority wins, and any bridge beats automatic detection.
     * For a server running something built in-house, or newer than this
     * library.
     *
     * @param bridge   the integration
     * @param priority higher wins
     */
    public static void registerBridge(@NotNull CombatBridge bridge, int priority) {
        CombatRuntime.registerBridge(bridge, priority);
    }

    /**
     * Drops everything cached.
     *
     * <p>Rarely needed: entries expire on their own in a few seconds. For a
     * plugin that changed a player's state behind the combat plugin's back and
     * wants the next read to see it.
     */
    public static void invalidate() {
        CombatRuntime.invalidate();
    }
}
