package net.exylia.lib.ui.internal;

import net.exylia.lib.action.ActionCall;
import net.exylia.lib.action.ActionContext;
import net.exylia.lib.action.ActionExecution;
import net.exylia.lib.action.ActionResult;
import net.exylia.lib.action.ActionSequence;
import net.exylia.lib.action.ActionStep;
import net.exylia.lib.action.ActionTemplate;
import net.exylia.lib.command.CommandLine;
import net.exylia.lib.command.Commands;
import net.exylia.lib.ui.ClickKind;
import net.exylia.lib.ui.ClickPolicy;
import net.exylia.lib.ui.UiDefinition;
import net.exylia.lib.ui.UiEntry;
import net.exylia.lib.ui.UiItem;
import net.exylia.lib.ui.UiKeys;
import net.exylia.lib.ui.UiSounds;
import org.bukkit.GameMode;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns what the client did into what the menu meant.
 *
 * <p>Every decision here is made against the {@link Session}, never against the
 * item the client says it clicked. A packet carries a slot number and a click
 * type and nothing else that can be trusted; the server already knows what it
 * drew there, so that is what it uses.
 *
 * <p>One listener for the whole library rather than one per plugin: an
 * inventory event is fired once, and the holder says whose menu it is.
 */
public final class MenuListener implements Listener {

    /**
     * Handles a click in one of our windows.
     *
     * <p>At {@code HIGH} rather than {@code MONITOR}: the event has to be
     * cancelled, and a monitor listener must not change anything. A
     * non-spectator cancellation is still respected; spectator mode pre-cancels
     * inventory interaction before this listener can run.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        Session session = MenuRuntime.sessionOf(top);
        if (session == null || !(event.getWhoClicked() instanceof Player viewer)) {
            return;
        }
        if (!handlesCancelledClick(event.isCancelled(), viewer.getGameMode() == GameMode.SPECTATOR)) {
            return;
        }

        int slot = event.getRawSlot();
        ClickPolicy.Decision decision = ClickPolicy.decide(true,
                event.getClickedInventory() == top, event.isShiftClick(),
                event.getClick() == ClickType.DOUBLE_CLICK, slot, session.inputSlots());

        if (decision == ClickPolicy.Decision.ALLOW || decision == ClickPolicy.Decision.IGNORE) {
            return;
        }
        // A button is never picked up. Cancelled before anything is run: a
        // handler that throws must not leave the item in the player's hand.
        event.setCancelled(true);
        if (decision == ClickPolicy.Decision.CANCEL) {
            return;
        }

        // They are interacting, so they have stopped watching. Waiting for a
        // button to finish appearing is the complaint every animated menu earns.
        session.skipAnimation();

        Rendered rendered = session.renderedAt(slot);
        if (rendered == null || rendered.item() == null) {
            return;
        }
        ClickKind kind = kindOf(event.getClick());
        if (kind == null) {
            return;
        }
        handle(session, viewer, slot, kind, rendered, event.getCurrentItem());
    }

    /**
     * Bukkit pre-cancels inventory interaction while a player is spectating.
     *
     * <p>That cancellation is the game mode's item-movement restriction, not a
     * veto of a button action. Other pre-cancelled clicks remain untouched.
     */
    static boolean handlesCancelledClick(boolean cancelled, boolean spectator) {
        return !cancelled || spectator;
    }

    /**
     * Stops a drag from writing over buttons.
     *
     * <p>A drag touches several slots at once, so it is refused unless every
     * slot it touches belongs to the player.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        Session session = MenuRuntime.sessionOf(top);
        if (session == null) {
            return;
        }
        if (ClickPolicy.refuseDrag(top.getSize(), event.getRawSlots(), session.inputSlots())) {
            event.setCancelled(true);
        }
    }

    /**
     * Releases a menu when its window closes.
     *
     * <p>At {@code MONITOR}: by now the close is final, and anything the menu
     * started has to stop whether or not another plugin was interested.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        Session session = MenuRuntime.sessionOf(event.getInventory());
        if (session == null) {
            return;
        }
        HumanEntity who = event.getPlayer();
        session.runtime().closed(session);
        if (who instanceof Player viewer) {
            runClose(session, viewer);
        }
    }

    /** Runs what a button is bound to. */
    private void handle(Session session, Player viewer, int slot, ClickKind kind,
                        Rendered rendered, ItemStack clicked) {
        UiItem item = rendered.item();
        List<ActionTemplate> actions = item.bindings().forClick(kind);
        List<CommandLine> commands = item.bindings().commandsForClick(kind);
        if (actions.isEmpty() && commands.isEmpty()) {
            return;
        }

        session.runtime().play(viewer, session.definition().sounds().click());

        Map<String, Object> data = dataFor(session, rendered);
        if (!actions.isEmpty()) {
            runActions(session, viewer, slot, kind, rendered, clicked, actions, data);
        }
        if (!commands.isEmpty()) {
            Commands.of(session.runtime().plugin()).run(commands, viewer, data);
        }

        // A button that toggles something has to show the new state, and what
        // changed it has not necessarily finished by the time we get here.
        session.refreshAfterClick(slot);
    }

    /**
     * Says out loud how a button ended.
     *
     * <p>A button that refuses should sound like it did. Without this a denied
     * click is indistinguishable from a click that worked, which is the single
     * most common "the menu is broken" report.
     */
    private static void announce(Session session, Player viewer, ActionResult result) {
        UiSounds sounds = session.definition().sounds();
        String sound = switch (result.status()) {
            case DENIED -> sounds.denied();
            case FAILED -> sounds.failed();
            // SUCCESS already played the click, and STOP is an ordinary early
            // finish rather than a refusal.
            default -> null;
        };
        session.runtime().play(viewer, sound);
    }

    /**
     * Runs a button's actions in order.
     *
     * <p>As a sequence rather than one at a time, so a {@code STOP} or a
     * {@code DENIED} ends the rest — which is what makes "check, then act"
     * expressible in a config file. The execution is registered with the
     * session, so a delayed step does not outlive the screen.
     */
    private void runActions(Session session, Player viewer, int slot, ClickKind kind,
                            Rendered rendered, ItemStack clicked,
                            List<ActionTemplate> actions, Map<String, Object> data) {
        ActionContext.Builder context = ActionContext.forPlayer(viewer)
                .origin("menu")
                .put(UiKeys.SESSION, session)
                .put(UiKeys.MENU, session.menuId())
                .put(UiKeys.SLOT, slot)
                .put(UiKeys.CLICK, kind);
        if (clicked != null) {
            context.put(UiKeys.ITEM, clicked);
        }
        if (rendered.section() != null) {
            context.put(UiKeys.PAGE, session.page(rendered.section()));
        }
        UiEntry entry = rendered.entry();
        if (entry != null && entry.value() != null) {
            context.put(UiKeys.ENTRY, entry.value());
        }

        List<ActionStep> steps = new ArrayList<>(actions.size());
        for (ActionTemplate template : actions) {
            ActionCall call = template.resolveOrNoop(viewer, data);
            steps.add(new ActionStep(call, 0));
        }
        ActionExecution execution =
                ActionSequence.of(session.runtime().plugin(), steps).execute(context.build());
        session.cancelOnClose(execution);

        // Back on the player's thread: a sequence can finish on another one,
        // and playing a sound is not something to do from wherever it landed.
        execution.result().thenAccept(result -> {
            if (result != null && session.isOpen()) {
                session.runtime().atPlayer(viewer, () -> announce(session, viewer, result));
            }
        });
    }

    /** Runs a menu's close actions, if it has any. */
    private void runClose(Session session, Player viewer) {
        UiDefinition definition = session.definition();
        if (definition.closeActions().isEmpty()) {
            return;
        }
        ActionContext context = ActionContext.forPlayer(viewer)
                .origin("menu")
                .put(UiKeys.MENU, definition.id())
                .build();
        // Deliberately not registered with the session: it is already closed,
        // and cancelling these on close would cancel them on being started.
        ActionSequence.of(session.runtime().plugin(),
                compile(session, definition.closeActions(), viewer)).execute(context);
    }

    private List<ActionStep> compile(Session session, List<String> raw, Player viewer) {
        List<ActionStep> steps = new ArrayList<>(raw.size());
        for (String line : raw) {
            steps.add(new ActionStep(
                    net.exylia.lib.action.Actions.of(session.runtime().plugin())
                            .template(line).resolveOrNoop(viewer, Map.of()), 0));
        }
        return steps;
    }

    /**
     * What a row's values look like to an action or a command.
     *
     * <p>So {@code "practice:select %kit_id%"} written in a template resolves
     * to the kit of the row that was clicked, rather than to nothing.
     */
    private static Map<String, Object> dataFor(Session session, Rendered rendered) {
        Map<String, Object> data = new HashMap<>(session.context());
        UiEntry entry = rendered.entry();
        if (entry != null) {
            data.putAll(entry.values());
        }
        return data;
    }

    /**
     * Reads a Bukkit click as one of ours.
     *
     * <p>Kinds we do not model return {@code null} and do nothing. A creative
     * middle-click on a button should not be guessed into a left click.
     *
     * <p>A double-click is one of those. The click before it already ran the
     * button, so delivering the pair would run it twice — a toggle clicked
     * quickly would turn itself back off — and the click is refused outright by
     * {@link ClickPolicy} before it reaches here.
     */
    private static ClickKind kindOf(ClickType click) {
        return switch (click) {
            case LEFT -> ClickKind.LEFT;
            case RIGHT -> ClickKind.RIGHT;
            case SHIFT_LEFT -> ClickKind.SHIFT_LEFT;
            case SHIFT_RIGHT -> ClickKind.SHIFT_RIGHT;
            case MIDDLE, CREATIVE -> ClickKind.MIDDLE;
            case DROP -> ClickKind.DROP;
            case CONTROL_DROP -> ClickKind.CONTROL_DROP;
            case NUMBER_KEY -> ClickKind.NUMBER_KEY;
            case SWAP_OFFHAND -> ClickKind.SWAP;
            default -> null;
        };
    }
}
