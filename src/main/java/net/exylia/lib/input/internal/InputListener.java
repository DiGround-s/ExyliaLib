package net.exylia.lib.input.internal;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Single Bukkit listener routing input events to the active transport.
 *
 * <p>The listener contains no parsing or inventory policy. Keeping one listener
 * for ExyliaLib avoids registering one handler per request, while checking the
 * session's concrete transport prevents an old window or late chat packet from
 * being delivered to whichever request replaced it.
 *
 * @since 1.31.0
 */
public final class InputListener implements Listener {

    /**
     * Removes an answer from public chat before handing its component to chat.
     *
     * <p>{@code HIGHEST} lets ordinary chat policy run first but still cancels
     * before broadcast. Cancelled events are not ignored because an input answer
     * remains private even when another plugin already cancelled normal chat.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncChatEvent event) {
        InputSession session = InputRuntime.active(event.getPlayer().getUniqueId());
        ChatTransport transport = transport(session, ChatTransport.class);
        if (transport == null || session.transportKind() != TransportKind.CHAT) {
            return;
        }
        event.setCancelled(true);
        transport.accept(session, event);
    }

    /** Routes clicks; both transports cancel first and fail closed. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof InsertWindow window
                && event.getWhoClicked() instanceof Player viewer) {
            window.click(viewer, event);
            return;
        }
        MenuTransport menu = MenuTransport.transportOf(event.getView().getTopInventory());
        if (menu != null) {
            menu.click(event);
            return;
        }
        SearchTransport search = searchFor(event);
        if (search != null) {
            search.click(event);
        }
    }

    /** Routes drags so no drag variant can write into an input window. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof InsertWindow) {
            InsertWindow.drag(event);
            return;
        }
        MenuTransport menu = MenuTransport.transportOf(event.getView().getTopInventory());
        if (menu != null) {
            menu.drag(event);
            return;
        }
        SearchTransport search = searchFor(event);
        if (search != null) {
            search.drag(event);
        }
    }

    /** Routes final close notification after other inventory handlers have run. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder(false) instanceof InsertWindow window) {
            // Whatever was lent to the window goes back, on every ending.
            window.release(event.getPlayer() instanceof Player viewer ? viewer : null);
            return;
        }
        MenuTransport menu = MenuTransport.transportOf(event.getInventory());
        if (menu != null) {
            menu.closed(event);
            return;
        }
        SearchTransport search = searchFor(event);
        if (search != null) {
            search.closed(event);
        }
    }

    /**
     * Feeds every keystroke of the anvil's rename box to the search.
     *
     * <p>This is what makes the search reactive: the client sends the box's
     * contents as it is typed, and nothing else in Bukkit reports it. Without
     * this handler the anvil would only ever report its text once, on confirm,
     * which is the unresponsive behaviour dialogs already have.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        if (!(event.getView().getPlayer() instanceof Player player)) {
            return;
        }
        SearchTransport transport = SearchTransport.anvilTransportOf(player, event.getView());
        if (transport != null) {
            transport.prepare(event);
        }
    }

    /**
     * Finds the search that owns an inventory event.
     *
     * <p>Two lookups, because the two windows a search uses are owned
     * differently. The results chest carries our own holder, so it is
     * recognised by identity. The anvil's top inventory is created by the
     * server and carries no holder of ours, so it can only be recognised by the
     * view the player currently has open — which is why the player is needed.
     */
    private static @Nullable SearchTransport searchFor(InventoryEvent event) {
        SearchTransport byHolder = SearchTransport.transportOf(event.getView().getTopInventory());
        if (byHolder != null) {
            return byHolder;
        }
        return event.getView().getPlayer() instanceof Player player
                ? SearchTransport.anvilTransportOf(player, event.getView())
                : null;
    }

    private static <T extends Transport> @Nullable T transport(
            @Nullable InputSession session, Class<T> type) {
        if (session == null) {
            return null;
        }
        Transport transport = session.transport();
        return type.isInstance(transport) ? type.cast(transport) : null;
    }
}
