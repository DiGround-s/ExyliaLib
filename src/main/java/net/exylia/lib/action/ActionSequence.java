package net.exylia.lib.action;

import net.exylia.lib.task.Tasks;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Compiled actions executed in order with one shared context and scope.
 *
 * <p>Only SUCCESS advances. STOP, DENIED and FAILED finish the sequence with
 * that result. A delayed continuation is scheduled at the player, so inventory
 * or entity actions resume on the correct Folia region; an immediate sequence
 * allocates no task at all.
 *
 * @since 1.20.0
 */
public final class ActionSequence {
    private final Plugin plugin;
    private final List<ActionStep> steps;

    ActionSequence(Plugin plugin, List<ActionStep> steps) {
        this.plugin = plugin;
        this.steps = List.copyOf(steps);
    }

    public @NotNull List<ActionStep> steps() { return steps; }

    /** Executes the sequence. Empty sequences succeed. */
    public @NotNull CompletableFuture<ActionResult> execute(@NotNull ActionContext context) {
        CompletableFuture<ActionResult> result = new CompletableFuture<>();
        advance(0, context, result);
        return result;
    }

    private void advance(int index, ActionContext context, CompletableFuture<ActionResult> result) {
        if (index >= steps.size()) {
            result.complete(ActionResult.success());
            return;
        }
        ActionStep step = steps.get(index);
        Runnable execute = () -> {
            step.bind(context.scope());
            step.call().execute(context).whenComplete((outcome, error) -> {
            ActionResult actual = error == null ? outcome : ActionResult.failed(error);
            if (actual != null && actual.continues()) {
                Runnable next = () -> advance(index + 1, context, result);
                // An async handler completes on its worker. The next action may
                // touch an inventory or entity, so resume at the player rather
                // than leaking the worker thread into the rest of the chain.
                if (Tasks.of(plugin).isOwnedBy(context.player())) next.run();
                else Tasks.of(plugin).runAtEntity(context.player(), next, null);
            } else result.complete(actual == null
                    ? ActionResult.failed("Action returned no result") : actual);
            });
        };
        if (step.delayTicks() == 0) execute.run();
        else Tasks.of(plugin).runAtEntityLater(context.player(), step.delayTicks(), execute);
    }

    /** Builds sequences while loading structured item configuration. */
    public static final class Builder {
        private final Plugin plugin;
        private final PluginActions actions;
        private final List<ActionStep> steps = new ArrayList<>();

        Builder(Plugin plugin, PluginActions actions) {
            this.plugin = plugin;
            this.actions = actions;
        }

        public @NotNull Builder then(@NotNull String action) {
            return then(action, 0);
        }

        public @NotNull Builder then(@NotNull String action, long delayTicks) {
            steps.add(new ActionStep(actions.compile(action), delayTicks));
            return this;
        }

        /** Adds a step with one typed value, typically that step's config record. */
        public <T> @NotNull Builder then(@NotNull String action, long delayTicks,
                                         @NotNull ActionKey<T> key, @NotNull T value) {
            if (!key.type().isInstance(value)) {
                throw new IllegalArgumentException(value + " is not " + key.type().getName());
            }
            steps.add(new ActionStep(actions.compile(action), delayTicks, java.util.Map.of(key, value)));
            return this;
        }

        public @NotNull ActionSequence build() {
            return new ActionSequence(plugin, steps);
        }
    }
}
