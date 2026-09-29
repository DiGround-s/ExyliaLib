package net.exylia.lib.packet;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Lines written under an item's lore on its way to one viewer.
 *
 * <p>A price, a skin's name, how many uses are left: something a player should
 * read on the item without the item carrying it. The lines travel on the
 * packet only; the item on the server never has them, so no other plugin
 * reading its lore finds them and nothing has to be taken off again.
 *
 * <pre>{@code
 * Packets.of(this).itemLines().provider((viewer, item, place) ->
 *         place.isMenu() ? null : worth.linesFor(item));
 * }</pre>
 *
 * <h2>Every plugin's lines, in order</h2>
 * One provider per plugin. Their lines are appended one after the other, in
 * the order the plugins registered, under whatever lore the item has; a
 * plugin's provider goes when it is disabled.
 *
 * <h2>Where they are drawn</h2>
 * Every slot the client is sent: the viewer's own inventory, their cursor, and
 * any window they open. {@link ItemPlace} says which, so a provider can leave a
 * plugin's menu alone.
 *
 * <h2>Creative players see none</h2>
 * A client in creative hands every slot it holds straight back to the server,
 * which would write the lines onto the real item. Nothing is decorated for
 * them, and their inventory is sent again when they enter or leave creative.
 *
 * <h2>Limits</h2>
 * The client believes the lines are there. A click in survival sends the
 * decorated item back as what the client thinks it holds; the server keeps its
 * own and answers with the slot again, which is decorated again. Lines already
 * on screen stay until the slot is sent again: call {@link #refresh} when the
 * provider's answer changes for everybody.
 *
 * @since 1.203.0
 */
public interface ItemLines {

    /**
     * Registers this plugin's provider, replacing its previous one.
     *
     * @param provider the provider
     */
    void provider(@NotNull ItemLineProvider provider);

    /** Drops this plugin's provider; the next slots sent carry none of its lines. */
    void clearProvider();

    /**
     * Sends a viewer's inventory and open window again, so what is drawn
     * follows the providers as they answer now. Safe from any thread.
     *
     * @param viewer the player
     */
    void refresh(@NotNull Player viewer);

    /** {@link #refresh} for every player online. */
    void refreshAll();
}
