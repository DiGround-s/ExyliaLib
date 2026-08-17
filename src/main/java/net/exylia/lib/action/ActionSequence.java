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

    /**
     * Builds a sequence from steps that are already compiled.
     *
     * <p>For a trigger that resolved its own templates because only it knows
     * what to resolve them against — a menu button, whose action names the row
     * it was drawn in. Everything else should use {@link Builder}, which
     * compiles as it goes.
     *
     * @param plugin who owns the sequence, for scheduling and release
     * @param steps  what to run, in order
     * @return the sequence
     */
    public static @NotNull ActionSequence of(@NotNull Plugin plugin,
                                             @NotNull List<ActionStep> steps) {
        return new ActionSequence(plugin, steps);
    }

    public @NotNull List<ActionStep> steps() { return steps; }

    /**
     * Executes the sequence.
     *
     * <p>The handle can stop what has not run yet, which is what a menu does
     * when it closes. Empty sequences succeed without scheduling anything.
     *
     * @param context what the actions act on
     * @return the running sequence
     */
    public @NotNull ActionExecution execute(@NotNull ActionContext context) {
        ActionExecution execution = new ActionExecution();
        advance(0, context, execution);
        return execution;
    }

    private void advance(int index, ActionContext context, ActionExecution execution) {
        if (execution.isCancelled()) {
            return;
        }
        if (index >= steps.size()) {
            execution.finish(ActionResult.success());
            return;
        }
        ActionStep step = steps.get(index);
        Runnable execute = () -> {
            execution.arrived();
            if (execution.isCancelled()) {
                // Cancelled while this step's delay was running out.
                return;
            }
            step.bind(context.scope());
            step.call().execute(context).whenComplete((outcome, error) -> {
                if (execution.isCancelled()) {
                    return;
                }
                ActionResult actual = error == null ? outcome : ActionResult.failed(error);
                if (actual != null && actual.continues()) {
                    Runnable next = () -> advance(index + 1, context, execution);
                    // An async handler completes on its worker. The next action
                    // may touch an inventory or entity, so resume at the player
                    // rather than leaking the worker thread into the rest of
                    // the chain.
                    if (Tasks.of(plugin).isOwnedBy(context.player())) {
                        next.run();
                    } else {
                        Tasks.of(plugin).runAtEntity(context.player(), next, null);
                    }
                } else {
                    execution.finish(actual == null
                            ? ActionResult.failed("Action returned no result") : actual);
                }
            });
        };
        if (step.delayTicks() == 0) {
            execute.run();
        } else {
            execution.awaiting(Tasks.of(plugin)
                    .runAtEntityLater(context.player(), step.delayTicks(), execute));
        }
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
