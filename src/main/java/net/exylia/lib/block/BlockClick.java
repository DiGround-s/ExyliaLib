package net.exylia.lib.block;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * A click a player made on a registered block.
 *
 * <p>Handed to the handler on the thread that owns the block, so the world can
 * be read and written from it directly.
 *
 * @param player   who clicked
 * @param block    what they clicked
 * @param button   which button they used
 * @param sneaking whether they were sneaking, so one block can offer two actions
 * @since 1.110.0
 */
public record BlockClick(@NotNull Player player, @NotNull Block block,
                         @NotNull BlockButton button, boolean sneaking) {

    /** Where the block is. */
    public @NotNull Location location() {
        return block.getLocation();
    }
}
