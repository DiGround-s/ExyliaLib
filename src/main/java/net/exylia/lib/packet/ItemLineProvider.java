package net.exylia.lib.packet;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The lines one plugin writes under an item, for one viewer.
 *
 * <p>Asked for every non-empty slot on its way to a player, on a packet
 * thread: it must be cheap, must not touch the world, and must read only
 * what is safe to read from any thread.
 *
 * @since 1.203.0
 */
@FunctionalInterface
public interface ItemLineProvider {

    /**
     * Returns the lines to write under this item, or {@code null} for none.
     *
     * <p>They are drawn as given, except that a line with no italic of its own
     * is set upright, the way an item name is.
     *
     * @param viewer the player it is being sent to
     * @param item   a copy of the item as the server has it, always one of it:
     *               the lines must be the same for every stack of the same
     *               item, or the client stops stacking them and a click shows
     *               a duplicate until the server corrects it
     * @param place  where it is drawn
     * @return the lines, or {@code null}
     */
    @Nullable List<Component> lines(@NotNull Player viewer, @NotNull ItemStack item, @NotNull ItemPlace place);
}
