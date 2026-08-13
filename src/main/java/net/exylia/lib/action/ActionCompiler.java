package net.exylia.lib.action;

import net.exylia.lib.action.internal.ActionRegistry;
import net.exylia.lib.action.internal.RegisteredAction;

import java.util.Locale;

/**
 * Turns an action string into a resolved call.
 *
 * <p>Lives in the public package rather than in {@code internal} so that
 * {@link ActionCall}'s constructor can stay package-private: a consumer must
 * not be able to build a call around a handler the library did not resolve,
 * and must not hold an internal registration type.
 */
final class ActionCompiler {

    private ActionCompiler() {
    }

    /**
     * Compiles one action string.
     *
     * @param raw              the string as written in configuration
     * @param defaultNamespace the namespace an unqualified id belongs to
     * @return the compiled call, or a no-op for blank, {@code none} and
     *         {@code noop}
     */
    static ActionCall compile(String raw, String defaultNamespace) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty() || isNoop(trimmed)) {
            RegisteredAction noop = ActionRegistry.noop();
            return new ActionCall(noop.id(), ActionArguments.empty(), noop);
        }
        int space = firstWhitespace(trimmed);
        String idText = space < 0 ? trimmed : trimmed.substring(0, space);
        String tail = space < 0 ? "" : trimmed.substring(space + 1).trim();
        ActionId id = idText.indexOf(':') >= 0
                ? ActionId.parse(idText)
                : new ActionId(defaultNamespace, idText);
        RegisteredAction action = ActionRegistry.get(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown action: " + id));
        return new ActionCall(id, ActionArguments.parse(tail), action);
    }

    /**
     * Returns whether a string means "do nothing".
     *
     * <p>Menus build action strings from placeholders, and a placeholder with
     * nothing to offer resolves to {@code none}. Treating that as an unknown
     * action, as the old system did, turned an ordinary state into an error.
     */
    private static boolean isNoop(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        return lower.equals("none") || lower.equals("noop");
    }

    private static int firstWhitespace(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}
