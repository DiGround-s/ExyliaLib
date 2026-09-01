package net.exylia.lib.overlay.internal;

import net.exylia.lib.action.ActionCall;
import net.exylia.lib.action.ActionContext;
import net.exylia.lib.action.ActionExecution;
import net.exylia.lib.action.ActionResult;
import net.exylia.lib.action.ActionSequence;
import net.exylia.lib.action.ActionStep;
import net.exylia.lib.action.ActionTemplate;
import net.exylia.lib.command.CommandLine;
import net.exylia.lib.command.Commands;
import net.exylia.lib.effect.Effects;
import net.exylia.lib.item.PluginItems;
import net.exylia.lib.overlay.OverlayDefinition;
import net.exylia.lib.overlay.OverlayKeys;
import net.exylia.lib.overlay.OverlaySlots;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.ui.ClickBindings;
import net.exylia.lib.ui.ClickKind;
import net.exylia.lib.ui.UiItem;
import net.exylia.lib.ui.UiRefresh;
import net.exylia.lib.ui.UiSounds;
import net.exylia.lib.ui.internal.Conditions;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One player's overlay, and everything it currently shows.
 *
 * <p>Two threads read this. Drawing and pressing happen on the player's
 * thread, as any inventory work must; the substitution that keeps the server
 * from painting over the overlay happens on a Netty thread, because that is
 * where a packet is. So what is drawn is held in a concurrent map and read
 * without a lock: a redraw that lands mid-packet shows the previous item for
 * one frame, which is what a redraw looks like anyway.
 */
public final class OverlayView {

    private final Plugin plugin;
    private final PluginItems items;
    private final Player viewer;
    private final UUID id;
    private final OverlayDefinition definition;

    /**
     * What the client is showing in each slot we own.
     *
     * <p>Absent means the slot is ours and empty, which is not the same as the
     * slot not being ours: the first draws air over the player's real item,
     * the second leaves the real item alone.
     */
    private final Map<Integer, ItemStack> drawn = new ConcurrentHashMap<>();

    /** What each drawn slot is, so a press knows what it pressed. */
    private final Map<Integer, UiItem> live = new ConcurrentHashMap<>();

    private volatile TaskHandle refresher;
    private volatile boolean closed;

    /** When this player's last press on something in the world was bound, in nanoseconds. */
    private volatile long worldPress = Long.MIN_VALUE / 2;

    /** What that press was, so only the same press swallows its own tail. */
    private volatile ClickKind worldPressKind;

    OverlayView(Plugin plugin, PluginItems items, Player viewer, OverlayDefinition definition) {
        this.plugin = plugin;
        this.items = items;
        this.viewer = viewer;
        this.id = viewer.getUniqueId();
        this.definition = definition;
    }

    public Plugin plugin() {
        return plugin;
    }

    public Player viewer() {
        return viewer;
    }

    public UUID id() {
        return id;
    }

    public OverlayDefinition definition() {
        return definition;
    }

    public boolean isClosed() {
        return closed;
    }

    /**
     * Returns whether this overlay covers a slot.
     *
     * <p>Asked on the Netty thread for every inventory packet, so it does no
     * work: the answer is a field read and a set lookup.
     *
     * @param index the inventory index
     * @return whether the slot is the overlay's rather than the player's
     */
    public boolean owns(int index) {
        return definition.owns(index);
    }

    /**
     * What the client should be showing in a slot we own.
     *
     * @param index the inventory index
     * @return the item, or {@code null} when the slot is ours and empty
     */
    public @Nullable ItemStack itemAt(int index) {
        return drawn.get(index);
    }

    /** The slot definition behind a press, or {@code null} when there is none. */
    public @Nullable UiItem pressedAt(int index) {
        return live.get(index);
    }

    /** One tick: the window a client's several packets for one press arrive in. */
    private static final long ONE_PRESS_NANOS = 50_000_000L;

    /**
     * Remembers a press on something in the world, so what the client sends
     * after it is known for what it is.
     *
     * @param kind the press that was bound
     */
    public void markWorldPress(ClickKind kind) {
        worldPressKind = kind;
        worldPress = System.nanoTime();
    }

    /**
     * Whether a press in the air is really the tail of the one just bound.
     *
     * <p>A click at something sends more than one packet. Right-clicking a
     * block the client cannot use sends the block press and then, having
     * predicted nothing happened, an air press for the same hand; clicking a
     * block or an entity sends the click and then the swing of the arm that
     * went with it. Each is one press the player made, so binding both halves
     * runs every action twice — which is what a staff member sees as a message
     * printed twice.
     *
     * <p>Held on the view rather than the packet listener because it is one
     * player's press: two staff members clicking in the same tick are two
     * presses, and only their own last click can swallow either one.
     *
     * @param kind the press the tail would be bound as
     * @return whether the same press was already bound within the last tick
     */
    public boolean repeatsWorldPress(ClickKind kind) {
        return kind == worldPressKind && System.nanoTime() - worldPress < ONE_PRESS_NANOS;
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    /**
     * Draws every slot the overlay owns.
     *
     * <p>Only what changed is sent. A staff hotbar of five fixed tools on a
     * one-second timer should cost nothing after the first draw, and a slot
     * whose item came out identical is a packet for an item already there.
     */
    void draw() {
        if (closed) {
            return;
        }
        for (int index = 0; index < OverlaySlots.SIZE; index++) {
            if (definition.owns(index)) {
                drawSlot(index);
            }
        }
    }

    /** Redraws only the slots that can actually differ from what is on screen. */
    private void tickRefresh() {
        if (closed || !viewer.isOnline()) {
            return;
        }
        if (definition.refresh().mode() == UiRefresh.Mode.FULL) {
            draw();
            return;
        }
        for (Map.Entry<Integer, UiItem> slot : definition.items().entrySet()) {
            if (slot.getValue().isDynamic()) {
                drawSlot(slot.getKey());
            }
        }
    }

    /**
     * Draws one slot.
     *
     * <p>A slot whose condition fails is drawn empty rather than skipped. On a
     * screen a hidden button leaves the background behind it; here there is no
     * background, only the player's real item, and revealing that is exactly
     * what an overlay must never do by accident.
     */
    private void drawSlot(int index) {
        UiItem item = definition.items().get(index);
        ItemStack rendered = item != null && passes(item) ? render(item) : null;
        if (item == null || rendered == null) {
            live.remove(index);
        } else {
            live.put(index, item);
        }
        ItemStack previous = drawn.get(index);
        if (Objects.equals(previous, rendered)) {
            return;
        }
        if (rendered == null) {
            drawn.remove(index);
        } else {
            drawn.put(index, rendered);
        }
        OverlaySink sink = OverlayRuntime.sink();
        if (sink != null) {
            sink.slot(viewer, index, rendered);
        }
    }

    private ItemStack render(UiItem item) {
        return items.renderIcon(item.item(), viewer, Map.of(), java.util.Set.of());
    }

    private boolean passes(UiItem item) {
        String condition = item.condition();
        return condition == null
                || Conditions.test(net.exylia.lib.text.Text.of(condition).forPlayer(viewer).plain());
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** Starts the redraw timer, if the overlay asked for one and can change. */
    void startRefreshing() {
        if (!definition.refresh().isTimed() || !definition.isDynamic()) {
            return;
        }
        long interval = definition.refresh().interval();
        refresher = Tasks.of(plugin).runAtEntityTimer(viewer, interval, interval, handle -> {
            if (closed || !viewer.isOnline()) {
                handle.cancel();
                refresher = null;
                return;
            }
            tickRefresh();
        });
    }

    /**
     * Stops the overlay and forgets what it drew.
     *
     * <p>Nothing has to be undone on the server, which is the whole point of
     * the module: the real inventory was never written to, so putting it back
     * is a matter of asking the server to say what it already believes.
     */
    void close() {
        closed = true;
        TaskHandle handle = refresher;
        if (handle != null) {
            handle.cancel();
            refresher = null;
        }
        drawn.clear();
        live.clear();
    }

    // ------------------------------------------------------------------
    // Presses
    // ------------------------------------------------------------------

    /**
     * Runs what a slot is bound to.
     *
     * <p>Always called on the player's thread: a press arrives on a Netty
     * thread and is handed over before it gets here, because an action may do
     * anything at all and none of it is safe where a packet is decoded.
     *
     * @param index  the inventory index that was pressed
     * @param kind   how it was pressed
     * @param target the entity it was used on, when there was one
     * @param block  the block it was used on, when there was one
     */
    public void press(int index, ClickKind kind, @Nullable Entity target, @Nullable Block block) {
        if (closed || !viewer.isOnline()) {
            return;
        }
        // A slot the overlay owns but draws nothing in falls back to what the
        // overlay says an empty hand does; usually nothing, and then this
        // returns below.
        UiItem item = live.get(index);
        ClickBindings bound = item != null ? item.bindings() : definition.emptyHand();
        List<ActionTemplate> actions = bound.forClick(kind);
        List<CommandLine> commands = bound.commandsForClick(kind);
        if (actions.isEmpty() && commands.isEmpty()) {
            return;
        }
        play(definition.sounds().click());
        if (!actions.isEmpty()) {
            runActions(index, kind, target, block, actions);
        }
        if (!commands.isEmpty()) {
            Commands.of(plugin).run(commands, viewer, Map.of());
        }
        if (definition.refresh().isOnClick()) {
            drawSlot(index);
        }
    }

    private void runActions(int index, ClickKind kind, @Nullable Entity target,
                            @Nullable Block block, List<ActionTemplate> actions) {
        ActionContext.Builder context = ActionContext.forPlayer(viewer)
                .origin("overlay")
                .put(OverlayKeys.OVERLAY, definition.id())
                .put(OverlayKeys.SLOT, index)
                .put(OverlayKeys.CLICK, kind);
        if (target != null) {
            context.put(OverlayKeys.TARGET, target);
        }
        if (block != null) {
            context.put(OverlayKeys.BLOCK, block);
        }

        List<ActionStep> steps = new ArrayList<>(actions.size());
        for (ActionTemplate template : actions) {
            ActionCall call = template.resolveOrNoop(viewer, Map.of());
            steps.add(new ActionStep(call, 0));
        }
        ActionExecution execution = ActionSequence.of(plugin, steps).execute(context.build());

        // Back on the player's thread: a sequence can finish on another one,
        // and a refusal that makes no sound is the commonest "the tool is
        // broken" report there is.
        execution.result().thenAccept(result -> {
            if (result != null && !closed) {
                Tasks.of(plugin).runAtEntity(viewer, () -> announce(result));
            }
        });
    }

    private void announce(ActionResult result) {
        UiSounds sounds = definition.sounds();
        play(switch (result.status()) {
            case DENIED -> sounds.denied();
            case FAILED -> sounds.failed();
            default -> null;
        });
    }

    private void play(String sound) {
        if (sound != null && !sound.isBlank()) {
            Effects.soundFrom(sound).show(viewer);
        }
    }
}
