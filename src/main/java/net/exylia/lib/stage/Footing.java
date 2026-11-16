package net.exylia.lib.stage;

import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.teleport.TeleportResult;
import net.exylia.lib.util.teleport.Teleports;
import net.exylia.lib.util.teleport.internal.BackHistory;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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

    /**
     * How far down {@link #ground} looks for a floor, in blocks: a jump, a step off a ledge, a short drop.
     * A longer fall (the void, a cliff) is left to play out.
     */
    public static final double GROUND_REACH = 8.0;

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
        if (floorUnder(player) != null) {
            // Brought straight down rather than waited for; a refused move still waits for the landing.
            return ground(plugin, player).thenCompose(stood -> stood ? CompletableFuture.completedFuture(true)
                    : waitForLanding(plugin, player));
        }
        return waitForLanding(plugin, player);
    }

    /**
     * Brings a player who is in mid-air (a jump, a step off a ledge) straight down onto the floor under them,
     * before something holds them still: held in the air, they float where everyone sees them and the
     * server counts them as flying. Moved through {@link Teleports}, with no fall damage and no
     * {@code /back} entry; not when they fly, glide, swim, climb, ride, play in creative or spectator,
     * wait out a teleport's countdown, or have no floor within {@link #GROUND_REACH} (or only water or
     * lava under them).
     *
     * <p>Completes on the player's thread: {@code true} once they stand on the floor, {@code false} when
     * nothing was moved or the move was refused. Already complete when there was nothing to do, so a
     * caller that goes on in {@code thenRun} carries on in the same call. Call it on the player's thread.
     *
     * @since 1.616.0
     */
    public static @NotNull CompletableFuture<Boolean> ground(@NotNull Plugin plugin, @NotNull Player player) {
        Location floor = floorUnder(player);
        if (floor == null) return CompletableFuture.completedFuture(false);
        player.setFallDistance(0);
        CompletableFuture<Boolean> grounded = new CompletableFuture<>();
        Teleports.of(plugin).to(player, floor).warmup(0).start().future().whenComplete((result, failure) -> {
            boolean moved = result == TeleportResult.SUCCESS;
            Runnable finish = () -> {
                if (moved) {
                    // A step down, not a place to go back to: the player's /back stays the place before.
                    BackHistory.pop(player.getUniqueId(), Integer.MAX_VALUE);
                    player.setFallDistance(0);
                    player.setVelocity(new Vector());
                }
                grounded.complete(moved);
            };
            if (Bukkit.isOwnedByCurrentRegion(player)) finish.run();
            else Tasks.of(plugin).runAtEntity(player, finish, () -> grounded.complete(false));
        });
        return grounded;
    }

    /** Where {@link #ground} would put the player, or {@code null} when it leaves them as they are. */
    static @Nullable Location floorUnder(Player player) {
        if (player.isDead() || settled(player) || player.isGliding()
                || player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR
                || Teleports.isWarmingUp(player)) {
            return null;
        }
        Location feet = player.getLocation();
        World world = feet.getWorld();
        if (world == null) return null;
        // The middle and the four corners of the feet: a player over a block's edge stands on that block.
        double half = player.getWidth() / 2 - 0.01;
        double[][] columns = {{0, 0}, {half, half}, {half, -half}, {-half, half}, {-half, -half}};
        RayTraceResult top = null;
        for (double[] column : columns) {
            Location from = feet.clone().add(column[0], 0, column[1]);
            RayTraceResult hit = world.rayTraceBlocks(from, new Vector(0, -1, 0), GROUND_REACH,
                    FluidCollisionMode.ALWAYS, true);
            if (hit != null && (top == null || hit.getHitPosition().getY() > top.getHitPosition().getY())) top = hit;
        }
        if (top == null || top.getHitBlock() != null && top.getHitBlock().isLiquid()) return null;
        double drop = feet.getY() - top.getHitPosition().getY();
        if (drop < 0.05) return null;
        // Nothing in the way of the body where it would stand (a ledge it is half under).
        BoundingBox body = player.getBoundingBox().shift(0, -drop, 0).expand(-0.01);
        if (world.hasCollisionsIn(body)) return null;
        Location floor = feet.clone();
        floor.setY(top.getHitPosition().getY());
        return floor;
    }

    private static CompletableFuture<Boolean> waitForLanding(Plugin plugin, Player player) {
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
