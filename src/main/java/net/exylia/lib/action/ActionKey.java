package net.exylia.lib.action;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * A typed key contributed by a consumer module.
 *
 * <p>The action core does not import menu clicks, item hands or projectiles.
 * UI can define {@code ActionKey<ClickType> CLICK}; Items can define
 * {@code ActionKey<ItemStack> ITEM}. A handler then gets compile-time types
 * instead of spelling {@code context.getData("hitPlayer", Player.class)}.
 *
 * @param <T> the value type
 * @since 1.20.0
 */
public final class ActionKey<T> {
    private final String name;
    private final Class<T> type;

    private ActionKey(String name, Class<T> type) {
        this.name = name;
        this.type = type;
    }

    public static <T> @NotNull ActionKey<T> of(@NotNull String name, @NotNull Class<T> type) {
        if (name.isBlank()) throw new IllegalArgumentException("Action key cannot be blank");
        return new ActionKey<>(name, Objects.requireNonNull(type, "type"));
    }

    public @NotNull String name() { return name; }
    public @NotNull Class<T> type() { return type; }

    @Override public String toString() { return name + '<' + type.getSimpleName() + '>'; }
}
