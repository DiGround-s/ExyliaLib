package net.exylia.lib.screen;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Functions a plugin adds to the expression language of screens, chest menu rules ({@code if}, {@code require}) and
 * 3D menus: {@code data('coins') >= 10} after {@code register(plugin, "data", ...)}.
 *
 * <p>Register from {@code onEnable}, before the files that call the function are read: a file read earlier reports it
 * as unknown. Released when the plugin disables; an expression that still calls it then answers {@code null}.
 *
 * @since 1.552.0
 */
public final class ExpressionFunctions {

    /** One function: the viewer it is evaluated for ({@code null} off a server) and its arguments, already evaluated. */
    @FunctionalInterface
    public interface Function {
        /**
         * @return a number ({@code Double}, {@code Integer}...), a {@code Boolean}, text, or {@code null}
         */
        @Nullable Object call(@Nullable Player viewer, @NotNull List<Object> args);
    }

    private record Registered(String plugin, Function function) {
    }

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*");
    private static final Map<String, Registered> FUNCTIONS = new ConcurrentHashMap<>();

    private ExpressionFunctions() {
        throw new AssertionError("No instances.");
    }

    /**
     * Adds {@code name(...)} to every expression.
     *
     * @throws IllegalArgumentException if the name is not lower-case words, is built in, or another plugin has it
     */
    public static void register(@NotNull Plugin plugin, @NotNull String name, @NotNull Function function) {
        String key = name.trim().toLowerCase(Locale.ROOT);
        if (!NAME.matcher(key).matches()) throw new IllegalArgumentException(name + "() is not a function name");
        if (net.exylia.lib.screen.internal.Expr.isBuiltIn(key)) {
            throw new IllegalArgumentException(key + "() is built in");
        }
        Registered mine = new Registered(plugin.getName(), function);
        Registered held = FUNCTIONS.merge(key, mine, (old, fresh) -> old.plugin().equals(fresh.plugin()) ? fresh : old);
        if (held != mine) throw new IllegalArgumentException(key + "() is already registered by " + held.plugin());
    }

    /** The function registered as {@code name}, or {@code null}. */
    public static @Nullable Function get(@NotNull String name) {
        Registered registered = FUNCTIONS.get(name);
        return registered == null ? null : registered.function();
    }

    /** Forgets a plugin's functions; lifecycle calls this. */
    public static void release(@NotNull String pluginName) {
        FUNCTIONS.values().removeIf(registered -> registered.plugin().equals(pluginName));
    }

    /** Forgets every function. */
    public static void releaseAll() {
        FUNCTIONS.clear();
    }
}
