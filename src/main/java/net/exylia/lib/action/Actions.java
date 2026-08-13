package net.exylia.lib.action;

import net.exylia.lib.action.internal.ActionRegistry;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * Shared, compiled actions for menus, items and any other event boundary.
 *
 * <pre>{@code
 * PluginActions actions = Actions.of(this, "practice");
 * actions.registerSync("join_queue", (context, args) -> {
 *     queues.join(context.player(), args.string(0));
 *     return ActionResult.success();
 * });
 *
 * // Compile while loading YAML, not when the player clicks.
 * ActionCall click = actions.compile("practice:join_queue boxing");
 * click.execute(ActionContext.forPlayer(player).origin("menu").build());
 * }</pre>
 *
 * <p>The core knows nothing about menu clicks or item hands. Those modules
 * parse their trigger syntax and contribute typed {@link ActionKey}s; this
 * module only registers, compiles and executes.
 *
 * @since 1.20.0
 */
public final class Actions {
    private Actions() { }

    /** Uses a namespace derived from the plugin name. */
    public static @NotNull PluginActions of(@NotNull Plugin plugin) {
        String namespace = plugin.getName().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.-]", "");
        return new PluginActions(plugin, namespace);
    }

    /** Uses an explicit stable namespace, recommended for public YAML. */
    public static @NotNull PluginActions of(@NotNull Plugin plugin,
                                            @NotNull String namespace) {
        return new PluginActions(plugin, namespace);
    }

    /** Compiles a fully namespaced action independently of its owner. */
    public static @NotNull ActionCall compile(@NotNull String raw) {
        return ActionRegistry.compile(raw, "global");
    }

    /** Removes everything owned by a plugin; normally lifecycle calls this. */
    public static int release(@NotNull String pluginName) {
        return ActionRegistry.release(pluginName);
    }

    /** Removes every registration. */
    public static void releaseAll() { ActionRegistry.releaseAll(); }

    /** Number of registered actions, for diagnostics. */
    public static int registered() { return ActionRegistry.size(); }
}
