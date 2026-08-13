package net.exylia.lib.action;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Code behind a registered action.
 *
 * <p>The handler receives arguments already compiled and may finish now or
 * later. A synchronous action returns {@link #completed(ActionResult)} and
 * allocates no scheduler task; an async action returns its real stage. This is
 * one interface rather than parallel SyncAction/AsyncAction hierarchies.
 *
 * @since 1.20.0
 */
@FunctionalInterface
public interface ActionHandler {
    @NotNull CompletionStage<ActionResult> execute(@NotNull ActionContext context,
                                                   @NotNull ActionArguments arguments);

    /** Adapts a direct handler without scheduling it. */
    static @NotNull ActionHandler sync(@NotNull Sync handler) {
        return (context, arguments) -> completed(handler.execute(context, arguments));
    }

    static @NotNull CompletableFuture<ActionResult> completed(@NotNull ActionResult result) {
        return CompletableFuture.completedFuture(result);
    }

    @FunctionalInterface
    interface Sync {
        @NotNull ActionResult execute(@NotNull ActionContext context,
                                      @NotNull ActionArguments arguments);
    }
}
