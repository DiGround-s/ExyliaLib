package net.exylia.lib.stage;

import net.exylia.lib.task.Tasks;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

/**
 * Whether a player stands somewhere a screen can be built round them, and waiting until they do.
 *
 * <p>Every in-world screen (a spatial menu, a crate opening, a room) is anchored where the player's feet
 * are and usually holds them still. Built round a player who is falling, it hangs in the air where they
 * happened to be, the hold keeps pulling the falling client back up every tick (the server's position
 * corrections, and its "floating too long" kick, which the hold cancels), and closing it drops them the
 * rest of the way. So a screen asked for in mid-air waits for the landing and opens on the ground.
 *
 * <pre>{@code
 * Footing.landed(plugin, player).thenAccept(landed -> {
 *     if (landed) openScreen(player);   // on the player's thread, on the ground
 *     else refuse(player);              // still in the air after the wait (the void, an elytra)
 * });
 * }</pre>
 *
 * @since 1.442.0
 */
public final class Footing {

    /** How long a screen waits for a falling player to land: a fall from build height takes about four seconds. */
    public static final long WAIT_TICKS = 100L;

    private Footing() {
    }

    /**
     * Whether a screen can be built round the player as they are: on the ground, or held up some other
     * way that does not pull them down (flying, riding, swimming, climbing).
     */
    public static boolean settled(@NotNull Player player) {
        if (player.isDead()) return false;
        return player.isOnGround() || player.isFlying() || player.isInsideVehicle() || player.isInWater()
                || player.isSwimming() || player.isClimbing() || player.isRiptiding();
    }

    /**
     * Completes {@code true} on the player's thread once they are {@link #settled}: at once when they
     * already are, otherwise when they land, checked every tick. {@code false} when {@link #WAIT_TICKS}
     * go by in the air, they leave, or the fall kills them. Call it on the player's thread.
     */
    public static @NotNull CompletableFuture<Boolean> landed(@NotNull Plugin plugin, @NotNull Player player) {
        if (settled(player)) return CompletableFuture.completedFuture(true);
        CompletableFuture<Boolean> landed = new CompletableFuture<>();
        long[] waited = {0};
        Tasks.of(plugin).runAtEntityTimer(player, 1L, 1L, task -> {
            if (!player.isOnline() || player.isDead()) {
                // Gone, or the fall killed them: nothing opens where they fell, nor where they respawn.
                task.cancel();
                landed.complete(false);
            } else if (settled(player)) {
                task.cancel();
                landed.complete(true);
            } else if (++waited[0] >= WAIT_TICKS) {
                task.cancel();
                landed.complete(false);
            }
        });
        return landed;
    }
}
