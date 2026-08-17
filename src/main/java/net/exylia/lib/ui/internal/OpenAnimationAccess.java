package net.exylia.lib.ui.internal;

import net.exylia.lib.ui.UiAnimationSpec;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

/**
 * Opens {@link OpenAnimation} to its tests.
 *
 * <p>The animation itself stays package-private: it is an implementation
 * detail and nothing outside the runtime should reach for it. Its frames are
 * pure arithmetic over a grid, though, and a menu missing slots is a bug that
 * only shows up on a live server — so the arithmetic is worth testing directly.
 */
@ApiStatus.Internal
public final class OpenAnimationAccess {

    private OpenAnimationAccess() {
    }

    /** The frames of an animation, for tests. */
    public static List<List<Integer>> frames(UiAnimationSpec spec, int size) {
        return OpenAnimation.frames(spec, size);
    }

    /**
     * The frames of an animation, worked out afresh.
     *
     * <p>Bypasses the cache. A test that sabotages the arithmetic and then
     * reads a cached answer proves nothing, and one that shares a cache with
     * every other test depends on which ran first.
     */
    public static List<List<Integer>> uncached(UiAnimationSpec spec, int size) {
        return OpenAnimation.compute(spec, size);
    }

    /** Whether a name is one the library can draw. */
    public static boolean isKnown(String type) {
        return OpenAnimation.isKnown(type);
    }
}
