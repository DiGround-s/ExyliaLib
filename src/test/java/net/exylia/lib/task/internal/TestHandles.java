package net.exylia.lib.task.internal;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Test-only bridge into the package-private parts of {@link TrackedHandle}.
 *
 * <p>Lives in the same package as the class under test so the production API does
 * not have to widen its visibility just to be testable.
 */
public final class TestHandles {

    private TestHandles() {
    }

    /** A handle together with the registry that tracks it. */
    public record Fixture(TrackedHandle handle, Set<TrackedHandle> registry) {
    }

    /**
     * Creates a handle backed by a fresh registry, mirroring how
     * {@link AbstractTaskScheduler} builds one.
     *
     * @param repeating whether the handle represents a timer
     * @return the handle and its registry
     */
    public static Fixture create(boolean repeating) {
        Set<TrackedHandle> registry =
                Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
        TrackedHandle handle = new TrackedHandle(registry, repeating);
        registry.add(handle);
        return new Fixture(handle, registry);
    }

    /** Attaches a platform task canceller to a handle. */
    public static void bind(TrackedHandle handle, Runnable canceller) {
        handle.bind(canceller);
    }

    /** Marks a one-shot handle as finished. */
    public static void complete(TrackedHandle handle) {
        handle.complete();
    }
}
