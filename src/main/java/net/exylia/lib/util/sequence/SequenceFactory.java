package net.exylia.lib.util.sequence;

import net.exylia.lib.task.TaskScheduler;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Builds the types whose constructors are not public.
 *
 * <p>{@code Sequence} and {@code SequenceRun} are created by the library and
 * never by a consumer, so their constructors are package-private. The code that
 * creates them lives in {@code internal}, which is a different package, so this
 * is the one door between the two.
 *
 * <p>Not API. It is public only because Java has no other way to let one
 * package build another's package-private types, and it carries no method a
 * plugin has any reason to call.
 */
@ApiStatus.Internal
public final class SequenceFactory {

    private SequenceFactory() {
    }

    /** Wraps compiled steps as a sequence. */
    @ApiStatus.Internal
    public static @NotNull Sequence sequence(@NotNull List<SequenceStep> steps) {
        return new Sequence(steps);
    }

    /** Creates a run for a sequence about to start. */
    @ApiStatus.Internal
    public static @NotNull SequenceRun run(@NotNull TaskScheduler scheduler,
                                           @NotNull Runnable onFinish) {
        return new SequenceRun(scheduler, onFinish);
    }
}
