package net.exylia.lib.block;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
 * @param event    the interact event behind the click, or {@code null} when the
 *                 click was not made through one (since 1.458.0). A block
 *                 registered with {@code vanilla(true)} keeps its material from
 *                 acting on this one click with
 *                 {@code event.setUseInteractedBlock(Event.Result.DENY)}: a
 *                 chest that is refilling stays shut while the rest open.
 * @since 1.110.0
 */
public record BlockClick(@NotNull Player player, @NotNull Block block,
                         @NotNull BlockButton button, boolean sneaking,
                         @Nullable PlayerInteractEvent event) {

    /** A click with no event behind it, as built before 1.458.0. */
    public BlockClick(@NotNull Player player, @NotNull Block block, @NotNull BlockButton button, boolean sneaking) {
        this(player, block, button, sneaking, null);
    }

    /** Where the block is. */
    public @NotNull Location location() {
        return block.getLocation();
    }
}
