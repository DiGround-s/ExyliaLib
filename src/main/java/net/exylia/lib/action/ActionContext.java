package net.exylia.lib.action;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * What an action is acting on.
 *
 * <p>The common core is deliberately small: the player, an origin label, typed
 * data from the adapter, and a sequence scope. UI and Items add their own keys
 * without making this module depend on either one.
 *
 * <p>Instances are immutable. Use a builder once at the event boundary; a
 * sequence reuses the same instance and scope for every step.
 *
 * @since 1.20.0
 */
public final class ActionContext {
    private final Player player;
    private final String origin;
    private final Map<ActionKey<?>, Object> data;
    private final ActionScope scope;

    private ActionContext(Player player, String origin, Map<ActionKey<?>, Object> data,
                          ActionScope scope) {
        this.player = player;
        this.origin = origin;
        this.data = Map.copyOf(data);
        this.scope = scope;
    }

    public static @NotNull Builder forPlayer(@NotNull Player player) {
        return new Builder(player);
    }

    public @NotNull Player player() { return player; }
    public @NotNull String origin() { return origin; }
    public @NotNull ActionScope scope() { return scope; }

    public <T> @NotNull Optional<T> get(@NotNull ActionKey<T> key) {
        Object value = data.get(key);
        return value == null ? Optional.empty() : Optional.of(key.type().cast(value));
    }

    public <T> T require(@NotNull ActionKey<T> key) {
        return get(key).orElseThrow(() -> new IllegalStateException("Missing action context: " + key.name()));
    }

    /** Builds a context without copying its existing data repeatedly. */
    public static final class Builder {
        private final Player player;
        private String origin = "plugin";
        private final Map<ActionKey<?>, Object> data = new HashMap<>();
        private ActionScope scope = new ActionScope();

        private Builder(Player player) { this.player = player; }

        public @NotNull Builder origin(@NotNull String origin) {
            if (origin.isBlank()) throw new IllegalArgumentException("Action origin cannot be blank");
            this.origin = origin;
            return this;
        }

        public <T> @NotNull Builder put(@NotNull ActionKey<T> key, @NotNull T value) {
            if (!key.type().isInstance(value)) {
                throw new IllegalArgumentException(value + " is not " + key.type().getName());
            }
            data.put(key, value);
            return this;
        }

        public @NotNull Builder scope(@NotNull ActionScope scope) {
            this.scope = scope;
            return this;
        }

        public @NotNull ActionContext build() {
            return new ActionContext(player, origin, data, scope);
        }
    }
}
