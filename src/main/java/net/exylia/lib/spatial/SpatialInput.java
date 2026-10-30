package net.exylia.lib.spatial;

import net.exylia.lib.action.ActionResult;
import net.exylia.lib.action.Actions;
import net.exylia.lib.action.PluginActions;
import net.exylia.lib.item.Item;
import net.exylia.lib.overlay.OverlayDefinition;
import net.exylia.lib.overlay.OverlayKeys;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.tuner.Tuner;
import net.exylia.lib.ui.ClickBindings;
import net.exylia.lib.ui.ClickKind;
import net.exylia.lib.ui.UiItem;
import net.exylia.lib.ui.UiSounds;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The input every in-world screen shares: what a player points at, clicks, scrolls and sneaks, for one
 * plugin. One {@link SpatialPointer} per player per plugin.
 *
 * <pre>{@code
 * SpatialInput input = SpatialInput.of(this);
 * SpatialPointer pointer = input.attach(player);                 // on the player's thread
 * pointer.add(Hotspot.cells(plane).onPress(click -> play(click.cell())));
 * Overlays.of(this).show(player, input.hotbar(leaveItem));       // clicks keep arriving under the overlay
 * }</pre>
 *
 * <p>While a pointer is attached the library cancels what the player's clicks would do to the world
 * (using a block, breaking one, hitting an entity) and hands each click over once: a swing and the
 * interaction it causes are one press. The wheel arrives as -1 or +1 a notch; the hotbar slot follows
 * the wheel, so no notch is lost at any ping, and is put back once it rests. Quitting, dying, changing
 * worlds and the plugin's disable end a pointer, each with its own {@link SpatialPointer.End}. While a
 * {@link Tuner} session runs for the player, the tuner has their gestures and pointers hear nothing.
 *
 * @since 1.406.0
 */
public final class SpatialInput {

    private static final Map<String, SpatialInput> BY_PLUGIN = new ConcurrentHashMap<>();
    /** Every player's pointers, newest last: the newest takes the input. */
    private static final Map<UUID, Deque<SpatialPointer>> STACKS = new ConcurrentHashMap<>();
    /** Every player's observing pointers, which never take input from an attached one. */
    private static final Map<UUID, List<SpatialPointer>> OBSERVERS = new ConcurrentHashMap<>();
    static final String HOTBAR_ID = "spatial";

    private final Plugin plugin;
    final TaskScheduler tasks;
    private final PluginActions actions;

    private SpatialInput(Plugin plugin) {
        this.plugin = plugin;
        this.tasks = Tasks.of(plugin);
        // The overlay swallows every click made over a slot it owns and runs that slot's actions: under
        // the hotbar, the board's clicks come back through these.
        this.actions = Actions.of(plugin, plugin.getName().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9_]", "") + "_spatial");
        actions.registerSync("click", (context, args) -> {
            ClickKind kind = context.get(OverlayKeys.CLICK).orElse(ClickKind.LEFT);
            boolean right = kind == ClickKind.RIGHT || kind == ClickKind.SHIFT_RIGHT;
            mine(context.player()).ifPresent(pointer -> pointer.click(right ? SpatialClick.Kind.RIGHT : SpatialClick.Kind.LEFT));
            return ActionResult.success();
        });
        actions.registerSync("leave", (context, args) -> {
            mine(context.player()).ifPresent(SpatialPointer::leave);
            return ActionResult.success();
        });
    }

    /** The plugin's input, made on first use. */
    public static @NotNull SpatialInput of(@NotNull Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        return BY_PLUGIN.computeIfAbsent(plugin.getName(), name -> new SpatialInput(plugin));
    }

    public @NotNull Plugin plugin() {
        return plugin;
    }

    /**
     * Gives a player a pointer, replacing the one this plugin gave them before ({@link SpatialPointer.End#REPLACED}).
     * Call it on the player's thread; the crosshair is polled there every two ticks from now on.
     */
    public @NotNull SpatialPointer attach(@NotNull Player player) {
        Objects.requireNonNull(player, "player");
        pointer(player).ifPresent(old -> old.end(SpatialPointer.End.REPLACED));
        SpatialPointer pointer = new SpatialPointer(this, player, false);
        Deque<SpatialPointer> stack = STACKS.computeIfAbsent(player.getUniqueId(), id -> new ArrayDeque<>());
        synchronized (stack) {
            stack.addLast(pointer);
        }
        pointer.start();
        return pointer;
    }

    /**
     * Gives a player an observing pointer at a public board (a station passers-by bet at, each with
     * their own hover): only clicks that land on one of its hotspots are consumed, every other click,
     * swing, break and use passes through to the world; the hotbar and the wheel are left alone (a
     * hotspot that scrolls still hears the wheel); nothing is frozen. It hears nothing while the player
     * has an {@linkplain #attach attached} pointer or a tuner session. A player may have several (one
     * per board near); each is ended by {@link SpatialPointer#detach()}, quitting, dying, changing worlds
     * or the plugin's disable. Callable from any thread; it polls on the player's.
     *
     * @since 1.410.0
     */
    public @NotNull SpatialPointer observe(@NotNull Player player) {
        Objects.requireNonNull(player, "player");
        SpatialPointer pointer = new SpatialPointer(this, player, true);
        OBSERVERS.computeIfAbsent(player.getUniqueId(), id -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(pointer);
        pointer.start();
        return pointer;
    }

    /** The pointer this plugin gave a player, if it is still attached. */
    public @NotNull Optional<SpatialPointer> pointer(@NotNull Player player) {
        Deque<SpatialPointer> stack = STACKS.get(player.getUniqueId());
        if (stack == null) return Optional.empty();
        synchronized (stack) {
            return stack.stream().filter(pointer -> pointer.input == this).findFirst();
        }
    }

    /** The pointer that takes a player's input now, whichever plugin attached it. */
    public static @NotNull Optional<SpatialPointer> active(@NotNull Player player) {
        Deque<SpatialPointer> stack = STACKS.get(player.getUniqueId());
        if (stack == null) return Optional.empty();
        synchronized (stack) {
            return Optional.ofNullable(stack.peekLast());
        }
    }

    /** This plugin's pointer for a player, only while it is the one taking their input. */
    private Optional<SpatialPointer> mine(Player player) {
        return active(player).filter(pointer -> pointer.input == this);
    }

    /**
     * The hotbar to show while a pointer is attached ({@code Overlays.of(plugin).show(player, ...)}):
     * the player's own items hidden, nothing picked up, {@code leave} in the last slot. Left-clicking
     * anywhere is a left click on the screen; right-clicking a blank slot is a right click; the leave
     * item's right-click runs the pointer's {@link SpatialPointer#onLeave}.
     */
    public @NotNull OverlayDefinition hotbar(@NotNull Item leave) {
        String ns = actions.namespace();
        ClickBindings blank = new ClickBindings.Builder()
                .add("left,shift_left,right,shift_right: " + ns + ":click", actions::template).build();
        ClickBindings exit = new ClickBindings.Builder()
                .add("left,shift_left: " + ns + ":click", actions::template)
                .add("right,shift_right: " + ns + ":leave", actions::template).build();
        // Its own click sound would talk over the screen's.
        UiSounds sounds = new UiSounds(UiSounds.DEFAULTS.open(), UiSounds.DEFAULTS.close(), null,
                UiSounds.DEFAULTS.denied(), UiSounds.DEFAULTS.failed());
        return OverlayDefinition.of(HOTBAR_ID).hideRest().pickup(false).sounds(sounds).emptyHand(blank)
                .slot(8, UiItem.of(leave).bindings(exit).build())
                .build();
    }

    void forget(SpatialPointer pointer) {
        if (pointer.observing) {
            List<SpatialPointer> observers = OBSERVERS.get(pointer.viewer.getUniqueId());
            if (observers != null) {
                observers.remove(pointer);
                if (observers.isEmpty()) OBSERVERS.remove(pointer.viewer.getUniqueId(), observers);
            }
            return;
        }
        Deque<SpatialPointer> stack = STACKS.get(pointer.viewer.getUniqueId());
        if (stack == null) return;
        synchronized (stack) {
            stack.remove(pointer);
            if (stack.isEmpty()) STACKS.remove(pointer.viewer.getUniqueId(), stack);
        }
    }

    private static void endAll(UUID player, SpatialPointer.End reason) {
        List<SpatialPointer> observers = OBSERVERS.get(player);
        if (observers != null) List.copyOf(observers).forEach(pointer -> pointer.end(reason));
        Deque<SpatialPointer> stack = STACKS.get(player);
        if (stack == null) return;
        List<SpatialPointer> pointers;
        synchronized (stack) {
            pointers = List.copyOf(stack);
        }
        for (int i = pointers.size() - 1; i >= 0; i--) pointers.get(i).end(reason);
    }

    /** Ends a disabled plugin's pointers. Called by the library. */
    public static void release(@NotNull Plugin plugin) {
        SpatialInput input = BY_PLUGIN.remove(plugin.getName());
        if (input == null) return;
        for (Deque<SpatialPointer> stack : List.copyOf(STACKS.values())) {
            List<SpatialPointer> pointers;
            synchronized (stack) {
                pointers = stack.stream().filter(pointer -> pointer.input == input).toList();
            }
            pointers.forEach(pointer -> pointer.end(SpatialPointer.End.DISABLED));
        }
        for (List<SpatialPointer> observers : List.copyOf(OBSERVERS.values())) {
            observers.stream().filter(pointer -> pointer.input == input).toList()
                    .forEach(pointer -> pointer.end(SpatialPointer.End.DISABLED));
        }
    }

    /** Forgets everything, for shutdown. Called by the library. */
    public static void releaseAll() {
        BY_PLUGIN.clear();
        STACKS.clear();
        OBSERVERS.clear();
    }

    /**
     * The one listener every plugin's pointers share, registered by the library. Internal: consumers
     * never register it.
     */
    public static final class Listen implements Listener {

        private static @Nullable SpatialPointer top(Player player) {
            if (Tuner.active(player).isPresent()) return null;
            return active(player).orElse(null);
        }

        /** The player's observers, when no attached pointer or tuner has them; empty otherwise. */
        private static List<SpatialPointer> observers(Player player) {
            List<SpatialPointer> observers = OBSERVERS.get(player.getUniqueId());
            if (observers == null || observers.isEmpty() || active(player).isPresent()
                    || Tuner.active(player).isPresent()) return List.of();
            return observers;
        }

        /** Whether one of the player's observers has a hotspot under their crosshair. */
        private static boolean aimed(List<SpatialPointer> observers) {
            boolean aimed = false;
            for (SpatialPointer pointer : observers) aimed |= pointer.aimed();
            return aimed;
        }

        private static void refuse(Player player, Cancellable event) {
            if (active(player).isPresent() || aimed(observers(player))) event.setCancelled(true);
        }

        @EventHandler(priority = EventPriority.LOW)
        public void onSwing(PlayerAnimationEvent event) {
            if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
            SpatialPointer pointer = top(event.getPlayer());
            if (pointer != null) pointer.click(SpatialClick.Kind.LEFT);
            else observers(event.getPlayer()).forEach(observer -> observer.click(SpatialClick.Kind.LEFT));
        }

        @EventHandler(priority = EventPriority.LOW)
        public void onInteract(PlayerInteractEvent event) {
            Player player = event.getPlayer();
            Action action = event.getAction();
            boolean right = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
            boolean click = right || action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK;
            SpatialClick.Kind kind = right ? SpatialClick.Kind.RIGHT : SpatialClick.Kind.LEFT;
            if (active(player).isEmpty()) {
                List<SpatialPointer> observers = observers(player);
                if (observers.isEmpty() || !aimed(observers)) return;
                event.setCancelled(true);
                if (click && event.getHand() == EquipmentSlot.HAND) observers.forEach(observer -> observer.click(kind));
                return;
            }
            event.setCancelled(true);
            SpatialPointer pointer = top(player);
            if (pointer == null || event.getHand() != EquipmentSlot.HAND) return;
            if (click) pointer.click(kind);
        }

        @EventHandler(priority = EventPriority.LOW)
        public void onInteractEntity(PlayerInteractEntityEvent event) {
            Player player = event.getPlayer();
            if (active(player).isEmpty()) {
                List<SpatialPointer> observers = observers(player);
                if (observers.isEmpty() || !aimed(observers)) return;
                event.setCancelled(true);
                if (event.getHand() == EquipmentSlot.HAND) observers.forEach(observer -> observer.click(SpatialClick.Kind.RIGHT));
                return;
            }
            event.setCancelled(true);
            SpatialPointer pointer = top(player);
            if (pointer != null && event.getHand() == EquipmentSlot.HAND) pointer.click(SpatialClick.Kind.RIGHT);
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        public void onHit(EntityDamageByEntityEvent event) {
            if (event.getDamager() instanceof Player player) refuse(player, event);
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        public void onBreak(BlockBreakEvent event) {
            refuse(event.getPlayer(), event);
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        public void onPlace(BlockPlaceEvent event) {
            refuse(event.getPlayer(), event);
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        public void onWheel(PlayerItemHeldEvent event) {
            Player player = event.getPlayer();
            int notch = SpatialPointer.notch(event.getPreviousSlot(), event.getNewSlot());
            SpatialPointer pointer = top(player);
            // Let the slot follow the wheel: a cancelled change loses the notches sent before the client
            // hears of it. The pointer puts the slot back once the wheel rests.
            if (pointer != null) pointer.scroll(notch);
            else observers(player).forEach(observer -> observer.scroll(notch));
        }

        @EventHandler(priority = EventPriority.LOW)
        public void onSneak(PlayerToggleSneakEvent event) {
            if (!event.isSneaking()) return;
            SpatialPointer pointer = top(event.getPlayer());
            if (pointer != null) pointer.sneak();
            else observers(event.getPlayer()).forEach(SpatialPointer::sneak);
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onQuit(PlayerQuitEvent event) {
            endAll(event.getPlayer().getUniqueId(), SpatialPointer.End.QUIT);
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onDeath(PlayerDeathEvent event) {
            endAll(event.getEntity().getUniqueId(), SpatialPointer.End.DEATH);
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onWorld(PlayerChangedWorldEvent event) {
            endAll(event.getPlayer().getUniqueId(), SpatialPointer.End.WORLD);
        }
    }
}
