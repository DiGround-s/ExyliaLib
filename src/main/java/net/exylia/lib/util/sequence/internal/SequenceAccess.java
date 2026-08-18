package net.exylia.lib.util.sequence.internal;

import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.util.sequence.Sequence;
import net.exylia.lib.util.sequence.SequenceRun;
import net.exylia.lib.util.sequence.SequenceStep;
import net.exylia.lib.util.sequence.SequenceTarget;
import net.exylia.lib.util.sequence.Shape;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/**
 * What the public package is allowed to reach in this one.
 *
 * <p>{@code Sequence} and {@code SequenceRun} have package-private constructors
 * so nobody outside builds one by hand, and the internals that must build them
 * live in a different package. This is the single seam between the two, rather
 * than making those constructors public and hoping.
 */
public final class SequenceAccess {

    private SequenceAccess() {
    }

    /** A compiler over the given shapes. */
    public static @NotNull SequenceCompiler compiler(@NotNull Map<String, Shape> shapes,
                                                     @NotNull SequenceCompiler.Problems problems) {
        return new SequenceCompiler(shapes, problems);
    }

    /** Wraps compiled steps as a sequence. */
    public static @NotNull Sequence sequence(@NotNull List<SequenceStep> steps) {
        return net.exylia.lib.util.sequence.SequenceFactory.sequence(steps);
    }

    /** Starts a sequence. */
    public static @NotNull SequenceRun play(@NotNull Sequence sequence,
                                            @NotNull SequenceTarget target,
                                            @NotNull TaskScheduler scheduler,
                                            @NotNull Runnable onFinish) {
        return SequenceRuntime.play(sequence, target, scheduler, onFinish);
    }

    /** The shapes the library ships with. */
    public static @NotNull Map<String, Shape> builtInShapes() {
        return Shapes.builtIn();
    }
}
