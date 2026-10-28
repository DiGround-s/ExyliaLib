package net.exylia.lib.ability;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/**
 * A typed value carried by an {@link AbilityContext}.
 *
 * <pre>{@code
 * static final ContextKey<Integer> COMBO = ContextKey.of("combo", Integer.class);
 * abilities.fire(player, "MINE_DROP", ctx -> ctx.with(COMBO, 3));
 * }</pre>
 *
 * <p>Keys compare by identity: hold them in constants.
 *
 * @param <T> the value's type
 * @since 1.385.0
 */
public final class ContextKey<T> {

    /**
     * What the event drops, as a list effects may change. Set by KILL (the
     * death's own drop list) and, on demand, by BLOCK_BREAK, where the library
     * takes the drops over and spawns them once after every ability ran.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static final ContextKey<List<ItemStack>> DROPS = new ContextKey<>("drops", (Class) List.class);

    /** When true, the drops the library spawns go to the holder's inventory instead; overflow drops once. */
    public static final ContextKey<Boolean> DROPS_TO_INVENTORY = new ContextKey<>("drops-to-inventory", Boolean.class);

    private final String name;
    private final Class<T> type;

    private ContextKey(String name, Class<T> type) {
        this.name = name;
        this.type = type;
    }

    /**
     * A new key.
     *
     * @param name a readable name, for debugging only
     * @param type the value's type
     * @param <T>  the value's type
     * @return the key
     */
    public static <T> @NotNull ContextKey<T> of(@NotNull String name, @NotNull Class<T> type) {
        return new ContextKey<>(Objects.requireNonNull(name, "name"), Objects.requireNonNull(type, "type"));
    }

    /** The readable name. */
    public @NotNull String name() {
        return name;
    }

    /** The value's type. */
    public @NotNull Class<T> type() {
        return type;
    }

    @Override
    public String toString() {
        return "ContextKey[" + name + "]";
    }
}
