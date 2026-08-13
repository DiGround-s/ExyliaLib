package net.exylia.lib.action.internal;

import net.exylia.lib.action.ActionArguments;
import net.exylia.lib.action.ActionHandler;
import net.exylia.lib.action.ActionId;
import net.exylia.lib.action.ActionResult;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Shared registry: one library installation, one action namespace space. */
public final class ActionRegistry {
    private static final Map<ActionId, RegisteredAction> ACTIONS = new ConcurrentHashMap<>();
    private static final RegisteredAction NOOP = new RegisteredAction(
            new ActionId("exylialib", "noop"), "ExyliaLib",
            ActionHandler.sync((context, arguments) -> ActionResult.stop("no action")));

    private ActionRegistry() { }

    public static RegisteredAction register(ActionId id, String owner, ActionHandler handler) {
        RegisteredAction action = new RegisteredAction(id, owner, handler);
        RegisteredAction previous = ACTIONS.putIfAbsent(id, action);
        if (previous != null) {
            throw new IllegalStateException("Action " + id + " is already registered by " + previous.owner());
        }
        return action;
    }

    /** The shared do-nothing registration, used for blank and {@code none}. */
    public static RegisteredAction noop() {
        return NOOP;
    }

    public static Optional<RegisteredAction> get(ActionId id) {
        return Optional.ofNullable(ACTIONS.get(id));
    }

    public static boolean unregister(ActionId id, String owner) {
        RegisteredAction existing = ACTIONS.get(id);
        if (existing == null || !existing.owner().equals(owner)) return false;
        if (ACTIONS.remove(id, existing)) {
            existing.deactivate();
            return true;
        }
        return false;
    }

    public static int release(String owner) {
        int removed = 0;
        for (Map.Entry<ActionId, RegisteredAction> entry : ACTIONS.entrySet()) {
            RegisteredAction action = entry.getValue();
            if (action.owner().equals(owner) && ACTIONS.remove(entry.getKey(), action)) {
                action.deactivate();
                removed++;
            }
        }
        return removed;
    }

    public static void releaseAll() {
        ACTIONS.values().forEach(RegisteredAction::deactivate);
        ACTIONS.clear();
    }

    public static int size() { return ACTIONS.size(); }
}
