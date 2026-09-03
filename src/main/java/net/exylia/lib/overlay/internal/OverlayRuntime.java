package net.exylia.lib.overlay.internal;

import net.exylia.lib.action.Actions;
import net.exylia.lib.action.PluginActions;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.item.Items;
import net.exylia.lib.item.PluginItems;
import net.exylia.lib.overlay.OverlayDefinition;
import net.exylia.lib.item.Problems;
import net.exylia.lib.overlay.PluginOverlays;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.ui.UiSounds;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The overlay module's working parts.
 *
 * <p>Holds who is wearing what, and answers the packet listener's questions
 * from it. PacketEvents is only ever touched through {@link OverlaySink},
 * installed lazily on first use and only after the plugin is confirmed
 * present, so a server without it never resolves those classes.
 *
 * <p>A player wears one overlay at a time, whichever plugin put it there. Two
 * staff tools fighting over a hotbar would be a screen nobody can read, and
 * the loser would keep intercepting clicks for slots it no longer draws.
 */
public final class OverlayRuntime {

    private static final Map<String, Impl> BY_PLUGIN = new ConcurrentHashMap<>();

    /** Who is wearing an overlay right now. Read on Netty threads. */
    private static final Map<UUID, OverlayView> ACTIVE = new ConcurrentHashMap<>();

    /**
     * The same views by entity id, for the packets that name a player by
     * number rather than by uuid.
     *
     * <p>An equipment packet is addressed to whoever is watching and says only
     * which entity it is about, so the wearer cannot be found the way every
     * other lookup here finds them.
     */
    private static final Map<Integer, OverlayView> BY_ENTITY = new ConcurrentHashMap<>();

    private static volatile Plugin lib;
    private static volatile OverlaySink sink;
    private static volatile boolean tried;

    private OverlayRuntime() {
    }

    /** Remembers the library plugin; the listener is installed on first use. */
    public static void init(Plugin plugin) {
        lib = plugin;
    }

    public static boolean isAvailable() {
        return sink() != null;
    }

    /**
     * The sink, installed on first use.
     *
     * <p>The plugin-manager check is what keeps {@link OverlayPackets} from
     * ever being loaded on a server without PacketEvents.
     */
    static @Nullable OverlaySink sink() {
        if (!tried) {
            synchronized (OverlayRuntime.class) {
                if (!tried) {
                    tried = true;
                    if (Bukkit.getPluginManager().getPlugin("packetevents") != null) {
                        sink = OverlayPackets.install();
                    }
                }
            }
        }
        return sink;
    }

    /** Installs a sink directly and resets state. For tests. */
    static void install(@Nullable OverlaySink value) {
        shutdown();
        sink = value;
        tried = true;
    }

    public static PluginOverlays of(Plugin plugin) {
        return BY_PLUGIN.computeIfAbsent(plugin.getName(), ignored -> new Impl(plugin));
    }

    // ------------------------------------------------------------------
    // Answers for the packet listener. Cheap, lock-free, any thread.
    // ------------------------------------------------------------------

    /** Whether anybody at all is wearing one, checked before anything else. */
    public static boolean anyActive() {
        return !ACTIVE.isEmpty();
    }

    /**
     * Whether a window other than the player's own screen is open.
     *
     * <p>Their own inventory is not a window in this sense: nothing else is
     * using the screen, so the overlay is exactly what should be drawn there.
     * Neither is one of this library's menus, whose lower half is decoration.
     */
    static boolean hasWindowOpen(Player viewer) {
        InventoryType type = viewer.getOpenInventory().getType();
        if (type == InventoryType.CRAFTING || type == InventoryType.CREATIVE) {
            return false;
        }
        // A menu keeps the overlay: see OverlayListener.onOpen for why.
        return !net.exylia.lib.ui.Menus.isMenu(viewer.getOpenInventory().getTopInventory());
    }

    /** The overlay a player is wearing, or {@code null}. */
    public static @Nullable OverlayView viewOf(@Nullable UUID player) {
        return player == null ? null : ACTIVE.get(player);
    }

    /** The overlay the player with this entity id is wearing, or {@code null}. */
    public static @Nullable OverlayView viewOfEntity(int entityId) {
        return BY_ENTITY.get(entityId);
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** Takes a player's overlay off, whoever put it there. */
    public static void hide(Player viewer) {
        remove(viewer.getUniqueId(), true);
    }

    /**
     * Forgets a player, on quit.
     *
     * <p>No repaint: they are gone, and there was never anything on the server
     * to put back.
     */
    public static void forget(UUID player) {
        remove(player, false);
        OverlaySink current = sink;
        if (current != null) {
            current.forget(player);
        }
    }

    private static void remove(UUID player, boolean repaint) {
        OverlayView view = ACTIVE.remove(player);
        if (view == null) {
            return;
        }
        BY_ENTITY.remove(view.viewer().getEntityId(), view);
        view.close();
        Player viewer = view.viewer();
        if (!repaint || !viewer.isOnline()) {
            return;
        }
        // The view is out of both maps, so this states the real items.
        OverlaySink current = sink;
        if (current != null) {
            current.equipment(viewer);
        }
        // On the library's scheduler rather than the overlay's owner: the
        // commonest reason to be here is that the owner is being disabled, and
        // a plugin on its way down has no scheduler left to put a screen back
        // with.
        Plugin scheduler = lib != null ? lib : view.plugin();
        // The server says what it always believed, and with the overlay gone
        // nothing rewrites it on the way out.
        Tasks.of(scheduler).runAtEntity(viewer, viewer::updateInventory);
    }

    /** Takes off every overlay one plugin put on. */
    public static void release(String pluginName) {
        Impl impl = BY_PLUGIN.remove(pluginName);
        for (OverlayView view : List.copyOf(ACTIVE.values())) {
            if (view.plugin().getName().equals(pluginName)) {
                remove(view.id(), true);
            }
        }
        if (impl != null) {
            impl.definitions.clear();
        }
    }

    /** Takes off everything and drops the listener. */
    public static void shutdown() {
        for (String name : List.copyOf(BY_PLUGIN.keySet())) {
            release(name);
        }
        for (OverlayView view : List.copyOf(ACTIVE.values())) {
            remove(view.id(), false);
        }
        ACTIVE.clear();
        BY_ENTITY.clear();
        BY_PLUGIN.clear();
        OverlaySink current = sink;
        if (current != null) {
            current.close();
        }
        sink = null;
        tried = false;
    }

    /** How many players are wearing one. */
    public static int worn() {
        return ACTIVE.size();
    }

    /** One plugin's overlays. */
    private static final class Impl implements PluginOverlays {

        private final Plugin plugin;
        private final PluginItems items;
        private final PluginActions actions;
        private final Debug debug;
        private final Map<String, OverlayDefinition> definitions = new ConcurrentHashMap<>();

        private UiSounds defaults = UiSounds.DEFAULTS;

        private Impl(Plugin plugin) {
            this.plugin = plugin;
            this.items = Items.of(plugin);
            this.actions = Actions.of(plugin);
            this.debug = Debug.of(plugin);
            if (!isAvailable()) {
                debug.warn("PacketEvents is not installed: inventory overlays do nothing.");
            }
        }

        @Override
        public @NotNull Plugin plugin() {
            return plugin;
        }

        @Override
        public @NotNull PluginOverlays sounds(@NotNull UiSounds sounds) {
            this.defaults = sounds;
            return this;
        }

        @Override
        public @NotNull OverlayDefinition load(@NotNull String id,
                                               @NotNull ConfigurationSection config) {
            return load(id, config, (where, problem) ->
                    debug.warn("In overlay \"" + id + "\", " + where + ": " + problem));
        }

        @Override
        public @NotNull OverlayDefinition load(@NotNull String id,
                                               @NotNull ConfigurationSection config,
                                               @NotNull Problems problems) {
            OverlayDefinition definition = OverlayLoader.load(qualify(id), config,
                    actions::template, defaults, problems::found);
            register(definition);
            return definition;
        }

        @Override
        public @NotNull PluginOverlays register(@NotNull OverlayDefinition definition) {
            definitions.put(definition.id(), definition);
            return this;
        }

        @Override
        public @NotNull Optional<OverlayDefinition> definition(@NotNull String id) {
            OverlayDefinition byId = definitions.get(id);
            return Optional.ofNullable(byId != null ? byId : definitions.get(qualify(id)));
        }

        @Override
        public @NotNull Map<String, OverlayDefinition> definitions() {
            return Map.copyOf(definitions);
        }

        @Override
        public void unload() {
            definitions.clear();
        }

        @Override
        public boolean show(@NotNull Player viewer, @NotNull String id) {
            Optional<OverlayDefinition> definition = definition(id);
            if (definition.isEmpty()) {
                debug.warn("No overlay is registered as \"" + id + "\".");
                return false;
            }
            show(viewer, definition.get());
            return true;
        }

        @Override
        public void show(@NotNull Player viewer, @NotNull OverlayDefinition definition) {
            if (!isAvailable()) {
                return;
            }
            // Whoever was wearing one is taken off first, without the repaint:
            // the new overlay is about to draw over the same slots, and a
            // repaint between the two is a frame of the player's real items.
            OverlayView previous = ACTIVE.remove(viewer.getUniqueId());
            if (previous != null) {
                previous.close();
            }
            OverlayView view = new OverlayView(plugin, items, viewer, definition);
            ACTIVE.put(viewer.getUniqueId(), view);
            BY_ENTITY.put(viewer.getEntityId(), view);
            Tasks.of(plugin).runAtEntity(viewer, () -> {
                if (view.isClosed()) {
                    return;
                }
                if (hasWindowOpen(viewer)) {
                    // Shown while a chest, a menu or an inspected inventory is
                    // already up: the overlay waits for it to close rather than
                    // drawing over the half of the screen that window is using.
                    view.suspend();
                }
                view.draw();
                // After the draw, never before: this makes the server restate
                // the window the player has open, and restating it is how the
                // module learns which window that is and how big its top half
                // is — neither of which any packet says on its own. The
                // overlay's own items are already recorded, so the restatement
                // comes back rewritten rather than revealing the real ones.
                viewer.updateInventory();
                // And the players around them, who are told out of the real
                // inventory and would otherwise watch this player swing an item
                // they are not holding.
                OverlaySink current = sink;
                if (current != null) {
                    current.equipment(viewer);
                }
                view.startRefreshing();
            });
        }

        @Override
        public void hide(@NotNull Player viewer) {
            OverlayView view = viewOf(viewer.getUniqueId());
            if (view != null && view.plugin().getName().equals(plugin.getName())) {
                OverlayRuntime.hide(viewer);
            }
        }

        @Override
        public boolean isShowing(@NotNull Player viewer) {
            return showing(viewer).isPresent();
        }

        @Override
        public @NotNull Optional<OverlayDefinition> showing(@NotNull Player viewer) {
            OverlayView view = viewOf(viewer.getUniqueId());
            if (view == null || !view.plugin().getName().equals(plugin.getName())) {
                return Optional.empty();
            }
            return Optional.of(view.definition());
        }

        @Override
        public void refresh(@NotNull Player viewer) {
            OverlayView view = viewOf(viewer.getUniqueId());
            if (view != null && view.plugin().getName().equals(plugin.getName())) {
                Tasks.of(plugin).runAtEntity(viewer, view::draw);
            }
        }

        @Override
        public void hideAll() {
            for (OverlayView view : new ArrayList<>(ACTIVE.values())) {
                if (view.plugin().getName().equals(plugin.getName())) {
                    remove(view.id(), true);
                }
            }
        }

        /** Namespaces an unqualified id, the way menus do. */
        private String qualify(String id) {
            return id.indexOf(':') >= 0
                    ? id
                    : plugin.getName().toLowerCase(java.util.Locale.ROOT) + ":" + id;
        }
    }
}
