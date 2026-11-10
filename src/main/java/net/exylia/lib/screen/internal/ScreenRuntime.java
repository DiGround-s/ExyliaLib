package net.exylia.lib.screen.internal;

import net.exylia.lib.screen.Screen;
import net.exylia.lib.screen.ScreenSession;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

import net.kyori.adventure.text.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Who has which screen open, and what closes it. One screen per player across every plugin.
 *
 * <p>Internal: free to change without notice.
 */
public final class ScreenRuntime {

    private static final Map<UUID, LiveScreen> OPEN = new ConcurrentHashMap<>();
    /** Viewer to the players a closed screen hid who were offline then, with the plugin that hid them. */
    private static final Map<UUID, Map<UUID, Plugin>> REVEAL = new ConcurrentHashMap<>();

    /** Shows {@code hidden} to {@code viewer} again when they next join. */
    static void revealOnJoin(Player viewer, UUID hidden, Plugin plugin) {
        REVEAL.computeIfAbsent(viewer.getUniqueId(), id -> new ConcurrentHashMap<>()).put(hidden, plugin);
    }
    private static final Map<UUID, ChatHold> CHAT = new ConcurrentHashMap<>();
    private static volatile Plugin library;
    private static volatile boolean warned;

    private ScreenRuntime() {
    }

    /** Installs the close rules under the library's name. Called by the library on enable. */
    public static void init(Plugin library) {
        ScreenRuntime.library = library;
        library.getServer().getPluginManager().registerEvents(new Closer(), library);
        // A screen stands in front of the player's eyes; a camera looks from somewhere else, at a small panel in the world.
        net.exylia.lib.packet.internal.PacketRuntime.onCameraHeld(held -> {
            if (OPEN.containsKey(held.getUniqueId()) && library.isEnabled()) {
                net.exylia.lib.task.Tasks.of(library).runAtEntity(held, () -> close(held.getUniqueId()));
            }
        });
        if (net.exylia.lib.packet.internal.PacketRuntime.isAvailable()) ScreenPackets.install();
        for (Player online : library.getServer().getOnlinePlayers()) {
            CHAT.put(online.getUniqueId(), new ChatHold());
        }
    }

    public static ScreenSession open(Plugin plugin, Player player, Screen screen) {
        if (!net.exylia.lib.packet.Packets.isAvailable()) {
            if (!warned) {
                warned = true;
                plugin.getLogger().log(Level.WARNING, "Screens need PacketEvents; " + player.getName()
                        + "'s screen was not opened.");
            }
            return null;
        }
        if (refused(player)) return null;
        ScreenPackets.install();
        close(player.getUniqueId());
        LiveScreen live = new LiveScreen(plugin, player, screen);
        OPEN.put(player.getUniqueId(), live);
        live.show();
        return live;
    }

    /**
     * Whether a camera holds this player (a cinematic, a replay, a spectator's target), telling them so: a screen is
     * drawn at their eyes and could not be seen or clicked from there. It can be opened again once the view is back.
     */
    public static boolean refused(Player player) {
        if (!cameraHeld(player)) return false;
        net.exylia.lib.camera.internal.CameraRuntime.say(player, net.exylia.lib.text.Text.of(
                net.exylia.lib.text.Phrases.tr("{warning}Menus open again when this view ends.")).build());
        return true;
    }

    /** Whether a camera holds this player now. */
    public static boolean cameraHeld(Player player) {
        return net.exylia.lib.packet.Packets.isAvailable()
                && net.exylia.lib.packet.internal.PacketRuntime.cameraHeld(player.getUniqueId());
    }

    public static boolean hides(Player viewer, Player target) {
        LiveScreen live = OPEN.get(viewer.getUniqueId());
        return live != null && live.hides(target);
    }

    public static ScreenSession session(Plugin plugin, Player player) {
        LiveScreen live = OPEN.get(player.getUniqueId());
        return live != null && live.plugin() == plugin ? live : null;
    }

    /** Whether any plugin's screen is open for this player now. Any thread. */
    public static boolean isOpen(UUID player) {
        return OPEN.containsKey(player);
    }

    static LiveScreen live(UUID player) {
        return OPEN.get(player);
    }

    /** Called by a session as it closes, so a newer one is never removed. */
    static void closed(LiveScreen live) {
        if (OPEN.remove(live.viewer().getUniqueId(), live)) {
            CLOSED_AT.put(live.viewer().getUniqueId(), System.currentTimeMillis());
        }
    }

    /** When each player's last screen closed, for the anticheat grace after it. */
    private static final Map<UUID, Long> CLOSED_AT = new ConcurrentHashMap<>();
    /** How long after a screen closes its turned-back camera and cancelled clicks may still trip an anticheat. */
    private static final long SHIELD_GRACE_MS = 3000L;

    /**
     * Whether an anticheat flag on {@code player} comes from a screen or a held camera rather than from play: the
     * cursor is the player's rotation, clicks land on a packet-only entity at the eyes and their swings are dropped,
     * and the camera is turned back by teleports, all of which aim and combat checks read as cheating. Nobody can
     * fight from behind a screen (the world is sealed and other players hidden), so these flags are only noise.
     * Any thread.
     */
    public static boolean shielded(UUID player) {
        if (OPEN.containsKey(player) || net.exylia.lib.packet.internal.PacketRuntime.cameraHeld(player)) return true;
        Long closed = CLOSED_AT.get(player);
        if (closed == null) return false;
        if (System.currentTimeMillis() - closed < SHIELD_GRACE_MS) return true;
        CLOSED_AT.remove(player, closed);
        return false;
    }

    /** What a screen quiets while it is up. Safe to repeat: a screen coming back from an input does. */
    static void quietBegin(Player player) {
        net.exylia.lib.screen.ScreenSettings settings = net.exylia.lib.screen.ScreenSettings.get();
        if (settings.hideSidebar()) {
            net.exylia.lib.camera.internal.CameraRuntime.screenSidebar(player, true);
        }
        ChatHold chat = CHAT.get(player.getUniqueId());
        if (settings.quietChat() && chat != null && chat.begin(System.currentTimeMillis())) {
            ScreenPackets.pushChatOut(player);
        }
    }

    /** Gives it all back, whatever the settings say by now. */
    static void quietEnd(Player player) {
        net.exylia.lib.camera.internal.CameraRuntime.screenSidebar(player, false);
        ChatHold chat = CHAT.get(player.getUniqueId());
        if (chat != null) chat.end().forEach(player::sendMessage);
    }

    /** On a network thread. A line {@code player}'s client draws. */
    static void chatSeen(UUID player, Component line) {
        ChatHold chat = CHAT.get(player);
        if (chat != null) chat.shown(line, System.currentTimeMillis());
    }

    /** On a network thread. A chat line that could wait reached {@code player}: whether it must. */
    static boolean chatLine(UUID player, Component line) {
        ChatHold chat = CHAT.get(player);
        if (chat == null) return false;
        if (!chat.hold(line)) {
            chat.shown(line, System.currentTimeMillis());
            return false;
        }
        LiveScreen live = OPEN.get(player);
        if (live != null) live.answer(line);
        return true;
    }

    private static boolean holdChat(Player viewer, io.papermc.paper.event.player.AsyncChatEvent event) {
        ChatHold chat = CHAT.get(viewer.getUniqueId());
        if (chat == null || !chat.holding()) return false;
        return chat.hold(event.renderer().render(event.getPlayer(), event.getPlayer().displayName(), event.message(), viewer));
    }

    /** A screen closed: a moment later, so hopping from one screen to the next does not flicker. */
    static void quietEndSoon(Player player) {
        Plugin owner = library;
        if (owner == null || !owner.isEnabled()) {
            quietEnd(player);
            return;
        }
        net.exylia.lib.task.Tasks.of(owner).runAtEntityLater(player, 2L, () -> {
            LiveScreen open = OPEN.get(player.getUniqueId());
            if (open == null || open.away()) quietEnd(player);
        });
    }

    private static void close(UUID player) {
        LiveScreen live = OPEN.get(player);
        if (live != null) live.close();
    }


    /** Redraws every screen {@code plugin} has open whose session passes {@code which}. */
    public static void redrawAll(Plugin plugin, java.util.function.Predicate<ScreenSession> which) {
        for (LiveScreen live : OPEN.values()) {
            if (live.plugin() != plugin || !live.open()) continue;
            try {
                if (which.test(live)) live.redraw();
            } catch (RuntimeException failure) {
                plugin.getLogger().log(Level.WARNING, "A redrawAll test failed", failure);
            }
        }
    }

    /**
     * Steps the player's open screen aside until {@code done} completes, as {@link ScreenSession#aside} does; for
     * inputs started while a screen is up. Nothing when none is open.
     */
    public static void asideUntil(UUID player, java.util.concurrent.CompletionStage<?> done) {
        LiveScreen live = OPEN.get(player);
        if (live != null && !live.away()) live.aside(() -> done);
    }

    /** Closes the screens a disabled plugin opened. Called by the library. */
    public static void release(Plugin plugin) {
        ScreenRoutes.release(plugin);
        for (LiveScreen live : List.copyOf(OPEN.values())) {
            if (live.plugin() == plugin) live.close();
        }
    }

    /** Closes every screen, on library shutdown. */
    public static void releaseAll() {
        for (LiveScreen live : List.copyOf(OPEN.values())) live.close();
    }

    /** The player whose screen click is running as a chest click now, on this thread. */
    private static final ThreadLocal<UUID> AS_CHEST = new ThreadLocal<>();

    /**
     * Runs a screen click that stands for a click in a chest menu (a chest-skin card, or a plugin action a routed
     * screen calls with the menu behind it): code written for the chest closes the menu with
     * {@code closeInventory()} before a game or a prompt, and that close closes the screen. A
     * {@code closeInventory()} made anywhere else (a screen's own action, a state change) leaves it up.
     */
    public static void asChest(UUID viewer, Runnable click) {
        UUID before = AS_CHEST.get();
        AS_CHEST.set(viewer);
        try {
            click.run();
        } finally {
            if (before == null) {
                AS_CHEST.remove();
                // A close the click asked for happens once it finished: when the click went on to ask something
                // (closeInventory() then a prompt, as chest code does) the screen steps aside for it and comes
                // back after the answer instead (1.517.0).
                LiveScreen asked = CLOSE_AFTER.get();
                CLOSE_AFTER.remove();
                if (asked != null && OPEN.get(viewer) == asked && !asked.away()
                        && !net.exylia.lib.input.internal.InputRuntime.hasActive(viewer)) {
                    asked.close();
                }
            } else {
                AS_CHEST.set(before);
            }
        }
    }

    /** The screen a chest click closed with closeInventory(), closed when that click ends. */
    private static final ThreadLocal<LiveScreen> CLOSE_AFTER = new ThreadLocal<>();

    /** Whether a window close is {@code closeInventory()} called with nothing but the player's own inventory open. */
    static boolean closesScreen(org.bukkit.event.inventory.InventoryCloseEvent event) {
        return event.getReason() == org.bukkit.event.inventory.InventoryCloseEvent.Reason.PLUGIN
                && event.getView().getTopInventory().getType() == org.bukkit.event.inventory.InventoryType.CRAFTING;
    }

    static final class Closer implements Listener {

        /**
         * Chat is sent per viewer and signed, so a viewer is taken off the list and the packet never exists. What they
         * would have read is rendered for them and waits with the system messages. Last in line, so the text, the
         * renderer and the viewers are final and a cancelled message is not held.
         */
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onChat(io.papermc.paper.event.player.AsyncChatEvent event) {
            try {
                event.viewers().removeIf(viewer -> viewer instanceof Player other && !other.equals(event.getPlayer())
                        && holdChat(other, event));
            } catch (UnsupportedOperationException fixedList) {
                // A plugin called this event with a list that cannot change: that chat is shown.
            }
        }

        /** Whoever joins stays out of sight of every open screen, as everyone online did when it opened. */
        @EventHandler(priority = EventPriority.MONITOR)
        public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
            Player joined = event.getPlayer();
            ScreenPreferences.joined(joined);
            CHAT.put(joined.getUniqueId(), new ChatHold());
            for (LiveScreen screen : OPEN.values()) screen.hideLater(joined);
            REVEAL.forEach((viewerId, pending) -> {
                Plugin owner = pending.remove(joined.getUniqueId());
                if (pending.isEmpty()) REVEAL.remove(viewerId, pending);
                Player viewer = org.bukkit.Bukkit.getPlayer(viewerId);
                if (owner == null || viewer == null || !owner.isEnabled()) return;
                net.exylia.lib.task.Tasks.of(owner).runAtEntity(viewer, () -> {
                    if (joined.isOnline()) viewer.showPlayer(owner, joined);
                });
            });
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onQuit(PlayerQuitEvent event) {
            close(event.getPlayer().getUniqueId());
            CHAT.remove(event.getPlayer().getUniqueId());
            // A new login starts with nobody hidden.
            REVEAL.remove(event.getPlayer().getUniqueId());
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onDeath(PlayerDeathEvent event) {
            close(event.getEntity().getUniqueId());
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onWorld(PlayerChangedWorldEvent event) {
            close(event.getPlayer().getUniqueId());
        }

        /** The screen stands where the eyes were: a real move (a match found, a warp) leaves it behind. */
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onTeleport(PlayerTeleportEvent event) {
            if (event.getTo() == null || event.getFrom().getWorld() != event.getTo().getWorld()
                    || event.getFrom().distanceSquared(event.getTo()) > 1) {
                close(event.getPlayer().getUniqueId());
            }
        }

        /**
         * A window opened over a screen (a chest, an anvil, another plugin's inventory) covers it and takes the
         * input, so the screen closes. Not while it stepped aside for an input, which may be such a window.
         */
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onInventory(org.bukkit.event.inventory.InventoryOpenEvent event) {
            LiveScreen live = OPEN.get(event.getPlayer().getUniqueId());
            if (live != null && !live.away()) live.close();
        }

        /**
         * {@code player.closeInventory()} with no window open (a menu drawn on a screen never opens one) closes the
         * screen, as it would the chest: an action that closes the menu before a game, a teleport or a prompt
         * takes over works in both menu styles. Only the plugin's own close of the player's own inventory: a real
         * window closing (an input's, another plugin's) is not the screen, and nothing closes while it stepped
         * aside for an input. Since 1.514.0 only inside {@link #asChest}: a click written for a chest; a
         * closeInventory() from anywhere else leaves the screen alone.
         */
        @EventHandler(priority = EventPriority.MONITOR)
        public void onInventoryClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
            if (!closesScreen(event)) return;
            LiveScreen live = OPEN.get(event.getPlayer().getUniqueId());
            // Only a close made by the screen's own click (an action that closes the menu before a game or a
            // prompt): plugins also call closeInventory() defensively from state changes, which must not.
            if (live != null && !live.away() && event.getPlayer().getUniqueId().equals(AS_CHEST.get())) CLOSE_AFTER.set(live);
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onDamage(EntityDamageEvent event) {
            if (event.getEntity() instanceof Player player) close(player.getUniqueId());
        }
    }
}
