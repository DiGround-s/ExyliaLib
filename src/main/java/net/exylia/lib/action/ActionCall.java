package net.exylia.lib.action;

import net.exylia.lib.action.internal.RegisteredAction;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * An action string resolved and tokenised once.
 *
 * <p>Hold this on a menu item or special-item definition. Execution is a
 * direct handler call: no parsing, namespace resolution, registry lookup,
 * middleware traversal, UUID or timing allocation occurs on click.
 *
 * @since 1.20.0
 */
public final class ActionCall {
    private final ActionId id;
    private final ActionArguments arguments;
    private final RegisteredAction action;

    /**
     * Not public: the registration is an internal type, and a consumer holding
     * one could keep a disabled plugin's handler alive. Compiled through
     * {@link PluginActions#compile(String)}.
     */
    ActionCall(ActionId id, ActionArguments arguments, RegisteredAction action) {
        this.id = id;
        this.arguments = arguments;
        this.action = action;
    }

    public @NotNull ActionId id() { return id; }
    public @NotNull ActionArguments arguments() { return arguments; }

    /**
     * Executes the already-resolved handler. A run that ends in
     * {@link ActionResult.Status#FAILED} is logged here, so no adapter can lose it.
     */
    public @NotNull CompletionStage<ActionResult> execute(@NotNull ActionContext context) {
        try {
            CompletionStage<ActionResult> stage = action.handler().execute(context, arguments);
            if (stage == null) {
                return ActionHandler.completed(report(ActionResult.failed(
                        "Action " + id + " returned no result")));
            }
            return stage.exceptionally(error -> ActionResult.failed(unwrap(error)))
                    .thenApply(this::report);
        } catch (Throwable error) {
            return ActionHandler.completed(report(ActionResult.failed(error)));
        }
    }

    private ActionResult report(ActionResult result) {
        if (result == null) {
            action.warnFailed("no result", null);
        } else if (result.status() == ActionResult.Status.FAILED) {
            action.warnFailed(result.reason(), result.error());
        }
        return result;
    }

    private static Throwable unwrap(Throwable error) {
        if ((error instanceof java.util.concurrent.CompletionException
                || error instanceof java.util.concurrent.ExecutionException)
                && error.getCause() != null) return error.getCause();
        return error;
    }

    @Override public String toString() {
        return id + (arguments.isEmpty() ? "" : " " + String.join(" ", arguments.values()));
    }
}
