package net.exylia.lib.util.preview.internal;

import net.kyori.adventure.util.TriState;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Everything a preview changes about a player, and how to change it back.
 *
 * <p>Captured before anything is touched and restored as one unit. The whole
 * risk of this module is leaving a player altered &mdash; flying in creative
 * over a survival lobby, invulnerable, or standing in the sky &mdash; so the
 * restore path has to be one call that cannot half-succeed.
 *
 * <p>Nothing here is persisted. A preview is measured in seconds, and a server
 * that dies mid-preview loses the player's position anyway: the server's own
 * last-saved position is the one they log back in at.
 */
final class StagedPlayer {

    private final Location origin;
    private final boolean allowFlight;
    private final boolean flying;
    private final boolean invulnerable;
    private final boolean collidable;
    private final boolean gravity;
    private final boolean silent;
    private final int fireTicks;
    private final GameMode gameMode;
    private final TriState flyingFallDamage;

    private StagedPlayer(Player player) {
        this.origin = player.getLocation().clone();
        this.allowFlight = player.getAllowFlight();
        this.flying = player.isFlying();
        this.invulnerable = player.isInvulnerable();
        this.collidable = player.isCollidable();
        this.gravity = player.hasGravity();
        this.silent = player.isSilent();
        this.fireTicks = player.getFireTicks();
        this.gameMode = player.getGameMode();
        this.flyingFallDamage = player.hasFlyingFallDamage();
    }

    /** Remembers a player exactly as they are now. */
    static @NotNull StagedPlayer capture(@NotNull Player player) {
        return new StagedPlayer(player);
    }

    /** Where they were before the preview. */
    @NotNull Location origin() {
        return origin.clone();
    }

    /**
     * Holds the player still in mid-air.
     *
     * <p>Flight rather than a repeating task that resets velocity: the client
     * predicts its own movement, so a server that fights it every tick produces
     * the rubber-banding that makes a preview feel broken. Told it may fly and
     * that it is flying, the client simply stops falling.
     *
     * <p>The game mode is not changed. Creative would hand the player a
     * creative inventory for the length of the preview, which is a duplication
     * bug waiting to be found.
     */
    void freeze(@NotNull Player player) {
        player.setAllowFlight(true);
        player.setFlying(true);
        // Fall damage is disarmed as well as gravity: a player who lands before
        // the restore finishes must not take the fall.
        player.setFlyingFallDamage(TriState.FALSE);
        player.setGravity(false);
        player.setInvulnerable(true);
        player.setCollidable(false);
        player.setSilent(true);
        player.setFireTicks(0);
    }

    /**
     * Puts everything back.
     *
     * <p>Every field is restored unconditionally rather than only the ones that
     * changed: comparing first would leave a player altered whenever another
     * plugin changed the same field during the preview, which is exactly when
     * getting it wrong matters.
     */
    void restore(@NotNull Player player) {
        player.setGravity(gravity);
        player.setInvulnerable(invulnerable);
        player.setCollidable(collidable);
        player.setSilent(silent);
        player.setFireTicks(fireTicks);
        player.setFlyingFallDamage(flyingFallDamage);
        // Flight last, and only after the player is back on the ground: setting
        // allowFlight false while still in the sky drops them.
        if (player.getGameMode() == gameMode) {
            player.setAllowFlight(allowFlight);
            player.setFlying(flying && allowFlight);
        }
        // A game mode changed by someone else during the preview is left alone.
        // They meant it; the preview did not touch it and must not undo it.
    }
}
