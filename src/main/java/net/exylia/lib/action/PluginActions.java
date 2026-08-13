package net.exylia.lib.action;

import net.exylia.lib.action.internal.ActionRegistry;
import net.exylia.lib.task.Tasks;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The action namespace owned by one plugin.
 *
 * <p>Obtained from {@link Actions#of(Plugin, String)}. Registrations are removed
 * automatically when the plugin disables; compiled calls retained by an old
 * menu are deactivated too, so they cannot invoke a dead classloader.
 *
 * @since 1.20.0
 */
public final class PluginActions {
    private final Plugin plugin;
    private final String namespace;

    PluginActions(Plugin plugin, String namespace) {
        this.plugin = plugin;
        this.namespace = new ActionId(namespace, "validation").namespace();
    }

    public @NotNull String namespace() { return namespace; }

    /** Registers a handler which decides itself whether it completes now or later. */
    public @NotNull PluginActions register(@NotNull String id, @NotNull ActionHandler handler) {
        ActionRegistry.register(new ActionId(namespace, id), plugin.getName(), handler);
        return this;
    }

    /** Registers an immediate handler. It runs directly, with no scheduler task. */
    public @NotNull PluginActions registerSync(@NotNull String id, @NotNull ActionHandler.Sync handler) {
        return register(id, ActionHandler.sync(handler));
    }

    /**
     * Registers blocking work such as HTTP, database or file I/O.
     *
     * <p>The handler is moved through ExyliaLib Tasks; no private executor is
     * created. The returned result completes the action sequence normally.
     */
    public @NotNull PluginActions registerAsync(@NotNull String id,
                                                @NotNull ActionHandler.Sync handler) {
        return register(id, (context, arguments) -> {
            CompletableFuture<ActionResult> result = new CompletableFuture<>();
            Tasks.of(plugin).runAsync(() -> {
                try { result.complete(handler.execute(context, arguments)); }
                catch (Throwable error) { result.complete(ActionResult.failed(error)); }
            });
            return result;
        });
    }

    /**
     * Compiles a string whose action may only be known when it is shown.
     *
     * <p>For a menu row whose button carries the id of the thing in that row,
     * or resolves to nothing for a row the viewer cannot act on. A template
     * with no placeholders is compiled once here and costs nothing later.
     *
     * @param raw the action string, possibly containing placeholders
     * @return the template
     */
    public @NotNull ActionTemplate template(@NotNull String raw) {
        return new ActionTemplate(raw, namespace);
    }

    /** Compiles one string now; execution thereafter is a direct call. */
    public @NotNull ActionCall compile(@NotNull String raw) {
        return ActionCompiler.compile(raw, namespace);
    }

    /** Compiles a sequential list with no delays. */
    public @NotNull ActionSequence compile(@NotNull Collection<String> actions) {
        List<ActionStep> steps = new ArrayList<>(actions.size());
        for (String action : actions) steps.add(new ActionStep(compile(action), 0));
        return new ActionSequence(plugin, steps);
    }

    /** Creates a sequence builder, including delayed steps when needed. */
    public @NotNull ActionSequence.Builder sequence() {
        return new ActionSequence.Builder(plugin, this);
    }

    /** Removes one registration owned by this plugin. */
    public boolean unregister(@NotNull String id) {
        return ActionRegistry.unregister(new ActionId(namespace, id), plugin.getName());
    }

    /** Removes every action registered by this plugin. */
    public int unregisterAll() { return ActionRegistry.release(plugin.getName()); }
}
