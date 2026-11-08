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
    private static final Map<UUID, Double> SENSITIVITY = new ConcurrentHashMap<>();
    /** Players who asked for reduced motion. */
    private static final java.util.Set<UUID> REDUCED = ConcurrentHashMap.newKeySet();
    private static final org.bukkit.NamespacedKey SPEED_KEY = new org.bukkit.NamespacedKey("exylialib", "screen_speed");
    private static final org.bukkit.NamespacedKey MOTION_KEY = new org.bukkit.NamespacedKey("exylialib", "screen_motion");
    private static volatile Plugin library;
    private static volatile boolean warned;

    private ScreenRuntime() {
    }

    /** Installs the close rules under the library's name. Called by the library on enable. */
    public static void init(Plugin library) {
        ScreenRuntime.library = library;
        library.getServer().getPluginManager().registerEvents(new Closer(), library);
        for (Player online : library.getServer().getOnlinePlayers()) {
            net.exylia.lib.task.Tasks.of(library).runAtEntity(online, () -> read(online));
        }
        try {
            settings(library);
        } catch (RuntimeException | LinkageError noSettings) {
            // Without the settings module (a unit test) the choices stay in code only.
        }
    }

    /** Cursor speed and reduced motion in /settings, under Interface beside the menu style. */
    private static void settings(Plugin library) {
        net.exylia.lib.settings.Settings.register(library, net.exylia.lib.settings.Setting
                .number("screen-cursor-speed", 1, 0.2, 3, 0.1)
                .category("interface")
                .icon("COMPASS")
                .name("Cursor speed")
                .description("How fast the cursor moves", "on full-screen menus.")
                .store(new net.exylia.lib.settings.Setting.Store() {
                    @Override
                    public String get(Player player) {
                        return String.format(java.util.Locale.ROOT, "%.1f", sensitivity(player.getUniqueId()));
                    }

                    @Override
                    public void set(Player player, String value) {
                        try {
                            sensitivity(player, Double.parseDouble(value));
                        } catch (NumberFormatException ignored) {
                            // A value the setting itself never writes.
                        }
                    }
                })
                .build());
        net.exylia.lib.settings.Settings.register(library, net.exylia.lib.settings.Setting
                .toggle("screen-reduced-motion", false)
                .category("interface")
                .icon("FEATHER")
                .name("Reduced motion")
                .description("Full-screen menus move only", "as much as they need to.")
                .store(new net.exylia.lib.settings.Setting.Store() {
                    @Override
                    public String get(Player player) {
                        return Boolean.toString(REDUCED.contains(player.getUniqueId()));
                    }

                    @Override
                    public void set(Player player, String value) {
                        reducedMotion(player, Boolean.parseBoolean(value));
                    }
                })
                .build());
    }

    /** On the player's thread: what they chose before, from their data. */
    private static void read(Player player) {
        var data = player.getPersistentDataContainer();
        Double speed = data.get(SPEED_KEY, org.bukkit.persistence.PersistentDataType.DOUBLE);
        if (speed != null) SENSITIVITY.put(player.getUniqueId(), speed);
        else SENSITIVITY.remove(player.getUniqueId());
        if (Boolean.TRUE.equals(data.get(MOTION_KEY, org.bukkit.persistence.PersistentDataType.BOOLEAN))) {
            REDUCED.add(player.getUniqueId());
        } else {
            REDUCED.remove(player.getUniqueId());
        }
    }

    /** Writes a choice into the player's data on their thread, so it survives restarts. */
    private static void store(Player player, java.util.function.Consumer<org.bukkit.persistence.PersistentDataContainer> write) {
        Plugin owner = library;
        if (owner == null || !owner.isEnabled()) return;
        net.exylia.lib.task.Tasks.of(owner).runAtEntity(player, () -> write.accept(player.getPersistentDataContainer()));
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
        ScreenPackets.install();
        close(player.getUniqueId());
        LiveScreen live = new LiveScreen(plugin, player, screen);
        OPEN.put(player.getUniqueId(), live);
        live.show();
        return live;
    }

    public static ScreenSession session(Plugin plugin, Player player) {
        LiveScreen live = OPEN.get(player.getUniqueId());
        return live != null && live.plugin() == plugin ? live : null;
    }

    static LiveScreen live(UUID player) {
        return OPEN.get(player);
    }

    /** Called by a session as it closes, so a newer one is never removed. */
    static void closed(LiveScreen live) {
        OPEN.remove(live.viewer().getUniqueId(), live);
    }

    private static void close(UUID player) {
        LiveScreen live = OPEN.get(player);
        if (live != null) live.close();
    }

    public static double sensitivity(UUID player) {
        return SENSITIVITY.getOrDefault(player, 1.0);
    }

    public static void sensitivity(UUID player, double value) {
        SENSITIVITY.put(player, value);
    }

    /** Sets and keeps a player's cursor speed, from 0.2 to 3. */
    public static void sensitivity(Player player, double value) {
        double clamped = Math.max(0.2, Math.min(3.0, value));
        SENSITIVITY.put(player.getUniqueId(), clamped);
        store(player, data -> data.set(SPEED_KEY, org.bukkit.persistence.PersistentDataType.DOUBLE, clamped));
    }

    /** Whether the player asked for reduced motion, or the server did for everyone. */
    public static boolean reducedMotion(UUID player) {
        return REDUCED.contains(player) || net.exylia.lib.screen.ScreenSettings.get().reducedMotion();
    }

    /** Whether the player themselves asked for reduced motion. */
    public static boolean reducedMotionChosen(UUID player) {
        return REDUCED.contains(player);
    }

    /** Sets and keeps a player's own reduced motion. */
    public static void reducedMotion(Player player, boolean reduced) {
        if (reduced) REDUCED.add(player.getUniqueId());
        else REDUCED.remove(player.getUniqueId());
        store(player, data -> {
            if (reduced) data.set(MOTION_KEY, org.bukkit.persistence.PersistentDataType.BOOLEAN, true);
            else data.remove(MOTION_KEY);
        });
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
            if (before == null) AS_CHEST.remove();
            else AS_CHEST.set(before);
        }
    }

    /** Whether a window close is {@code closeInventory()} called with nothing but the player's own inventory open. */
    static boolean closesScreen(org.bukkit.event.inventory.InventoryCloseEvent event) {
        return event.getReason() == org.bukkit.event.inventory.InventoryCloseEvent.Reason.PLUGIN
                && event.getView().getTopInventory().getType() == org.bukkit.event.inventory.InventoryType.CRAFTING;
    }

    static final class Closer implements Listener {

        /** Whoever joins stays out of sight of every open screen, as everyone online did when it opened. */
        @EventHandler(priority = EventPriority.MONITOR)
        public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
            Player joined = event.getPlayer();
            read(joined);
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
            SENSITIVITY.remove(event.getPlayer().getUniqueId());
            REDUCED.remove(event.getPlayer().getUniqueId());
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
            if (live != null && !live.away() && event.getPlayer().getUniqueId().equals(AS_CHEST.get())) live.close();
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onDamage(EntityDamageEvent event) {
            if (event.getEntity() instanceof Player player) close(player.getUniqueId());
        }
    }
}
