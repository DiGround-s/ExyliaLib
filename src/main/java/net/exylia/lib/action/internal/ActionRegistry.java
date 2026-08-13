package net.exylia.lib.action.internal;

import net.exylia.lib.action.ActionArguments;
import net.exylia.lib.action.ActionCall;
import net.exylia.lib.action.ActionHandler;
import net.exylia.lib.action.ActionId;
import net.exylia.lib.action.ActionResult;

import java.util.Locale;
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

    public static ActionCall compile(String raw, String defaultNamespace) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty() || isNoop(trimmed)) {
            return new ActionCall(NOOP.id(), ActionArguments.empty(), NOOP);
        }
        int space = firstWhitespace(trimmed);
        String idText = space < 0 ? trimmed : trimmed.substring(0, space);
        String tail = space < 0 ? "" : trimmed.substring(space + 1).trim();
        ActionId id = idText.indexOf(':') >= 0
                ? ActionId.parse(idText)
                : new ActionId(defaultNamespace, idText);
        RegisteredAction action = ACTIONS.get(id);
        if (action == null) {
            throw new IllegalArgumentException("Unknown action: " + id);
        }
        return new ActionCall(id, ActionArguments.parse(tail), action);
    }

    private static boolean isNoop(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        return lower.equals("none") || lower.equals("noop") || lower.equals("none:");
    }

    private static int firstWhitespace(String text) {
        for (int i = 0; i < text.length(); i++) if (Character.isWhitespace(text.charAt(i))) return i;
        return -1;
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
