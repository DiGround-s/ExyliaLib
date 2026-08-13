package net.exylia.lib.action;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Objects;

/**
 * The unambiguous, namespaced name of an action.
 *
 * <p>Simple ids are intentionally not resolved globally: the old system made
 * them work until a second plugin registered the same id, at which point both
 * stopped resolving. Configs should say {@code practice:join_queue}, never
 * merely {@code join_queue}.
 *
 * @param namespace who owns the action
 * @param value     its name inside that namespace
 * @since 1.20.0
 */
public record ActionId(@NotNull String namespace, @NotNull String value) {

    public ActionId {
        namespace = normalise(namespace, "namespace");
        value = normalise(value, "value");
    }

    /** Parses {@code namespace:value}. */
    public static @NotNull ActionId parse(@NotNull String raw) {
        int colon = raw.indexOf(':');
        if (colon <= 0 || colon == raw.length() - 1 || raw.indexOf(':', colon + 1) >= 0) {
            throw new IllegalArgumentException("Action id must be namespace:value, got: " + raw);
        }
        return new ActionId(raw.substring(0, colon), raw.substring(colon + 1));
    }

    private static String normalise(String value, String part) {
        Objects.requireNonNull(value, part);
        String lower = value.trim().toLowerCase(Locale.ROOT);
        if (lower.isEmpty() || !lower.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid action " + part + ": " + value);
        }
        return lower;
    }

    @Override public @NotNull String toString() {
        return namespace + ':' + value;
    }
}
