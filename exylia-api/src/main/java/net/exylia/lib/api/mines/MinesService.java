package net.exylia.lib.api.mines;

import net.exylia.lib.api.ExyliaAPI;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.UUID;

/**
 * Reading and driving ExyliaMines.
 *
 * <pre>{@code
 * ExyliaAPI.get(MinesService.class).ifPresent(mines -> {
 *     MineBreakResult result = mines.breakBlock(player, block);
 *     if (result == MineBreakResult.UNCLAIMED) block.breakNaturally(tool);
 * });
 * }</pre>
 *
 * <p>Registered with Bukkit's {@link org.bukkit.plugin.ServicesManager} when
 * ExyliaMines enables. Reach it through {@link ExyliaAPI#get(Class)}, and treat
 * an empty result as "this server has no mines" rather than as a failure.
 *
 * <h2>What is here</h2>
 * A mine's break, so a plugin breaking blocks for a player gets the mine's
 * loot and regeneration instead of a hole, and the two questions a placeholder
 * or a scoreboard asks: which mine is here, and when it resets. Creating and
 * editing mines is an administrator's job and happens in game.
 *
 * @since 1.8.0
 */
public interface MinesService {

    /**
     * Breaks a block the way a player's own swing inside a mine would.
     *
     * <p>For plugins that break blocks on a player's behalf — a 3x3 pickaxe, a
     * vein miner. Inside a mine the server's break event is always cancelled and
     * the mine removes the block itself, so breaking a block there any other way
     * skips the mine's permission, loot and regeneration. Hand every block here
     * first, and break it yourself only on {@link MineBreakResult#UNCLAIMED}.
     *
     * <p>The mine checks its permission, fires
     * {@link net.exylia.lib.api.mines.event.MineBlockBreakEvent}, and breaks the
     * block with the item in the player's main hand: its enchantments shape the
     * drops and it takes the durability. The player is told nothing when the
     * mine refuses — a caller refused on several blocks at once is the one that
     * knows whether that is worth a message.
     *
     * <p>Call it on the thread that owns the block.
     *
     * @param player who is breaking the block
     * @param block  the block
     * @return what the mine made of it, {@link MineBreakResult#UNCLAIMED} when no
     *         mine owns it
     */
    @NotNull
    MineBreakResult breakBlock(@NotNull Player player, @NotNull Block block);

    /**
     * The enabled mine whose area holds a spot.
     *
     * @param location the spot
     * @return the mine's id, or empty when no enabled mine covers it
     */
    @NotNull
    Optional<String> mineAt(@NotNull Location location);

    /**
     * The mine a player is standing in.
     *
     * @param player the player
     * @return the mine's id, or empty when they are in none
     */
    @NotNull
    Optional<String> currentMine(@NotNull UUID player);

    /**
     * How long until a mine refills as a whole.
     *
     * @param mineId the mine
     * @return the seconds left, or {@code -1} for a mine that does not refill as
     *         a whole: unknown, disabled, without an area, or realistic, which
     *         grows each block back on its own
     */
    long secondsUntilReset(@NotNull String mineId);
}
