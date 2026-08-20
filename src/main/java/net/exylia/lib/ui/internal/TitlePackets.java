package net.exylia.lib.ui.internal;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCloseWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenWindow;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rewrites the title of a window somebody already has open.
 *
 * <p>The only class in the module that names PacketEvents types, so a server
 * without it never loads this one.
 *
 * <p>Bukkit has no way to do this: a title is an argument to
 * {@code createInventory} and is read once, when the window is built. The
 * client will accept a second "open window" for a container it already has
 * open, and treats it as a retitle rather than a new screen — the slots stay
 * put and nothing flickers.
 *
 * <p>Which container that is has to be learned by listening, because the id is
 * assigned by the server as the packet goes out and is never handed to the
 * plugin that asked for the window.
 */
final class TitlePackets extends PacketListenerAbstract {

    /** Sent for a container the player does not have open. */
    private static final int NO_CONTAINER = -1;

    /** The player's own inventory, which is never one of ours. */
    private static final int PLAYER_INVENTORY = 0;

    private static final Map<UUID, Integer> CONTAINERS = new ConcurrentHashMap<>();

    private TitlePackets() {
        // Lowest priority: this only reads ids off packets other plugins may
        // still rewrite, and reading last means reading what was actually sent.
        super(PacketListenerPriority.LOWEST);
    }

    /** Starts listening, and returns whether it could. */
    static boolean install() {
        if (!ready()) {
            return false;
        }
        PacketEvents.getAPI().getEventManager().registerListener(new TitlePackets());
        return true;
    }

    private static boolean ready() {
        try {
            return PacketEvents.getAPI() != null && PacketEvents.getAPI().isLoaded();
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Play.Server.OPEN_WINDOW) {
            Player player = event.getPlayer();
            if (player != null) {
                CONTAINERS.put(player.getUniqueId(),
                        new WrapperPlayServerOpenWindow(event).getContainerId());
            }
            return;
        }
        if (event.getPacketType() == PacketType.Play.Server.CLOSE_WINDOW) {
            Player player = event.getPlayer();
            // Window zero is the player's own inventory, which closing does not
            // mean they left a menu.
            if (player != null
                    && new WrapperPlayServerCloseWindow(event).getWindowId() != PLAYER_INVENTORY) {
                CONTAINERS.remove(player.getUniqueId());
            }
        }
    }

    /**
     * Sends a new title for whatever window a player has open.
     *
     * @param player who is looking
     * @param size   how many slots the window has
     * @param title  what it should now say
     * @return whether the packet went out
     */
    static boolean retitle(Player player, int size, Component title) {
        try {
            int containerId = CONTAINERS.getOrDefault(player.getUniqueId(), NO_CONTAINER);
            if (containerId == NO_CONTAINER || containerId == PLAYER_INVENTORY) {
                return false;
            }
            User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
            if (user == null) {
                return false;
            }
            user.sendPacket(new WrapperPlayServerOpenWindow(containerId, chestType(size), title));
            // The client empties the window it is retitling, so the contents
            // have to be sent again or the player is left looking at a hole.
            player.updateInventory();
            return true;
        } catch (Throwable ignored) {
            // A title that did not change is worth nothing next to a menu that
            // stopped working, so this never escapes.
            return false;
        }
    }

    /** Forgets a player who left. */
    static void forget(UUID player) {
        CONTAINERS.remove(player);
    }

    /**
     * The window type a chest of this size is.
     *
     * <p>Only chests: those are the ones whose size is configured, and the only
     * ones a retitle is ever asked for. Anything else keeps the size it was
     * opened with, so sending the wrong type would resize the screen.
     */
    private static int chestType(int size) {
        int rows = Math.max(1, Math.min(6, size / 9));
        return rows - 1;
    }
}
