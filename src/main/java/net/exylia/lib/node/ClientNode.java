package net.exylia.lib.node;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A block or an item that only one player sees and clicks.
 *
 * <p>It is two entities on that player's client and nothing on the server: a
 * display that draws it and a click box the size of a block. The server never
 * ticks it, so a farm of hundreds per player costs packets when something
 * changes and nothing in between. It is sent while its viewer stands within
 * the range of the {@link ClientNodes} that made it and taken off their screen
 * past it.
 *
 * <p>Every method may be called from any thread.
 */
public interface ClientNode {

    /** The one player who sees it. */
    @NotNull Player viewer();

    /**
     * Where it stands: the block's corner for a block, the item's centre for an
     * item.
     *
     * @return a copy
     */
    @NotNull Location location();

    /** Draws it as this block from now on: a crop's next stage, a block that changed. */
    void look(@NotNull BlockData block);

    /** Draws it as this item from now on. */
    void look(@NotNull ItemStack item);

    /**
     * Outlines it in a colour, or takes the outline off.
     *
     * @param color the outline, or {@code null} for none
     */
    void glow(@Nullable Color color);

    /**
     * Grows or shrinks it, standing on its base, gliding over a few ticks.
     * {@code 0} hides it while it stays clickable, {@code 1} is full size.
     *
     * @param size  the size, {@code 1} being a whole block
     * @param ticks how long the change glides, {@code 0} for at once
     */
    void scale(float size, int ticks);

    /** Shrinks it a little and springs it back: the answer to a click. */
    void pop();

    /** Takes it off its viewer's screen for good. */
    void remove();

    /** Whether {@link #remove()} was called, or its viewer or plugin went away. */
    boolean isRemoved();
}
