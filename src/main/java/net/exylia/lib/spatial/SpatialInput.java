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
    static final String HOTBAR_ID = "spatial";

    private final Plugin plugin;
    private final TaskScheduler tasks;
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
        SpatialPointer pointer = new SpatialPointer(this, player);
        Deque<SpatialPointer> stack = STACKS.computeIfAbsent(player.getUniqueId(), id -> new ArrayDeque<>());
        synchronized (stack) {
            stack.addLast(pointer);
        }
        pointer.timer = tasks.runAtEntityTimer(player, 1L, 2L, pointer::poll);
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
        Deque<SpatialPointer> stack = STACKS.get(pointer.viewer.getUniqueId());
        if (stack == null) return;
        synchronized (stack) {
            stack.remove(pointer);
            if (stack.isEmpty()) STACKS.remove(pointer.viewer.getUniqueId(), stack);
        }
    }

    private static void endAll(UUID player, SpatialPointer.End reason) {
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
    }

    /** Forgets everything, for shutdown. Called by the library. */
    public static void releaseAll() {
        BY_PLUGIN.clear();
        STACKS.clear();
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

        private static void refuse(Player player, Cancellable event) {
            if (active(player).isPresent()) event.setCancelled(true);
        }

        @EventHandler(priority = EventPriority.LOW)
        public void onSwing(PlayerAnimationEvent event) {
            if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
            SpatialPointer pointer = top(event.getPlayer());
            if (pointer != null) pointer.click(SpatialClick.Kind.LEFT);
        }

        @EventHandler(priority = EventPriority.LOW)
        public void onInteract(PlayerInteractEvent event) {
            Player player = event.getPlayer();
            if (active(player).isEmpty()) return;
            event.setCancelled(true);
            SpatialPointer pointer = top(player);
            if (pointer == null || event.getHand() != EquipmentSlot.HAND) return;
            Action action = event.getAction();
            boolean right = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
            if (right || action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
                pointer.click(right ? SpatialClick.Kind.RIGHT : SpatialClick.Kind.LEFT);
            }
        }

        @EventHandler(priority = EventPriority.LOW)
        public void onInteractEntity(PlayerInteractEntityEvent event) {
            Player player = event.getPlayer();
            if (active(player).isEmpty()) return;
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
            SpatialPointer pointer = top(event.getPlayer());
            // Let the slot follow the wheel: a cancelled change loses the notches sent before the client
            // hears of it. The pointer puts the slot back once the wheel rests.
            if (pointer != null) pointer.scroll(SpatialPointer.notch(event.getPreviousSlot(), event.getNewSlot()));
        }

        @EventHandler(priority = EventPriority.LOW)
        public void onSneak(PlayerToggleSneakEvent event) {
            if (!event.isSneaking()) return;
            SpatialPointer pointer = top(event.getPlayer());
            if (pointer != null) pointer.sneak();
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
