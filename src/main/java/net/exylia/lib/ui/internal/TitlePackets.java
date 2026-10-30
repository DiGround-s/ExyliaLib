package net.exylia.lib.ui.internal;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCloseWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenWindow;
import net.exylia.lib.task.Tasks;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

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
 *
 * <p>Learned late, too: the open packet is encoded on the network thread, so a
 * retitle sent in the same tick a window opened used to read the id of the
 * window before it. The client then opened an empty window under the new
 * title and ignored every item the server sent to the real one. So a retitle
 * goes only to the window the server has open right now, and waits for that
 * window's own open packet when it has not been seen yet.
 */
final class TitlePackets extends PacketListenerAbstract {

    /** Sent for a container the player does not have open. */
    private static final int NO_CONTAINER = -1;

    /** The player's own inventory, which is never one of ours. */
    private static final int PLAYER_INVENTORY = 0;

    private static final Map<UUID, Integer> CONTAINERS = new ConcurrentHashMap<>();

    /** A retitle waiting for its window's open packet to go out. */
    private record Pending(int containerId, int size, Component title, Plugin plugin) {
    }

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    /** What to do with a retitle, given the window the server has open and the last one seen sent. */
    enum Decision { SEND, WAIT, DROP }

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
                // Peeked: the wrapper would decode the window's title to hand back its id.
                int id = net.exylia.lib.packet.internal.PacketPeek.leadingVarInt(event,
                        e -> new WrapperPlayServerOpenWindow(e).getContainerId());
                CONTAINERS.put(player.getUniqueId(), id);
                Pending pending = PENDING.get(player.getUniqueId());
                if (pending != null && pending.containerId() == id
                        && PENDING.remove(player.getUniqueId(), pending)) {
                    // Back on the player's thread, and checked again there: the
                    // window may have changed again by the time it runs.
                    Tasks.of(pending.plugin()).runAtEntity(player,
                            () -> retitle(player, pending.size(), pending.title(), pending.plugin()));
                }
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
                PENDING.remove(player.getUniqueId());
            }
        }
    }

    /**
     * Sends a new title for the window a player has open.
     *
     * <p>Only to the window the server has open for them right now. When that
     * window's open packet has not gone out yet, the retitle waits for it and
     * is sent then; when the window cannot be told, it is dropped, because a
     * retitle sent to any other window blanks the screen.
     *
     * @param player who is looking
     * @param size   how many slots the window has
     * @param title  what it should now say
     * @param plugin whose scheduler a waiting retitle runs on
     * @return whether the packet went out or is waiting to
     */
    static boolean retitle(Player player, int size, Component title, Plugin plugin) {
        try {
            int open = ServerWindows.open(player);
            int seen = CONTAINERS.getOrDefault(player.getUniqueId(), NO_CONTAINER);
            switch (decide(open, seen)) {
                case DROP -> {
                    return false;
                }
                case WAIT -> {
                    Pending pending = new Pending(open, size, title, plugin);
                    PENDING.put(player.getUniqueId(), pending);
                    // The packet may have gone out between the read and the put,
                    // and then nothing would ever pick this up.
                    if (CONTAINERS.getOrDefault(player.getUniqueId(), NO_CONTAINER) == open
                            && PENDING.remove(player.getUniqueId(), pending)) {
                        return retitle(player, size, title, plugin);
                    }
                    return true;
                }
                case SEND -> {
                    // A retitle for an older window that never got its turn is moot now.
                    PENDING.remove(player.getUniqueId());
                }
            }
            User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
            if (user == null) {
                return false;
            }
            user.sendPacket(new WrapperPlayServerOpenWindow(open, chestType(size), title));
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

    /**
     * Whether a retitle may go out now.
     *
     * <p>Package-private so the rule can be exercised without a server: send
     * only when the last window seen going out is the one the server has open.
     *
     * @param open the window the server has open, or {@link #NO_CONTAINER} when unknown
     * @param seen the last window seen sent, or {@link #NO_CONTAINER}
     */
    static Decision decide(int open, int seen) {
        if (open == NO_CONTAINER || open == PLAYER_INVENTORY) {
            return Decision.DROP;
        }
        return open == seen ? Decision.SEND : Decision.WAIT;
    }

    /** Forgets a player who left. */
    static void forget(UUID player) {
        CONTAINERS.remove(player);
        PENDING.remove(player);
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

    /**
     * The id of the window the server has open for a player.
     *
     * <p>Bukkit never hands it out, so it is read off the server player by
     * name: {@code containerMenu.containerId}, which Paper keeps under those
     * Mojang names at runtime. Anything unexpected reads as unknown, and an
     * unknown window is never retitled.
     */
    static final class ServerWindows {

        private static volatile java.lang.reflect.Method handle;
        private static volatile java.lang.reflect.Field menu;
        private static volatile java.lang.reflect.Field id;

        private ServerWindows() {
        }

        static int open(Player player) {
            try {
                if (id == null) {
                    java.lang.reflect.Method h = player.getClass().getMethod("getHandle");
                    java.lang.reflect.Field m = h.getReturnType().getField("containerMenu");
                    java.lang.reflect.Field i = m.getType().getField("containerId");
                    handle = h;
                    menu = m;
                    id = i;
                }
                Object container = menu.get(handle.invoke(player));
                return container == null ? NO_CONTAINER : id.getInt(container);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unknown) {
                return NO_CONTAINER;
            }
        }
    }
}
