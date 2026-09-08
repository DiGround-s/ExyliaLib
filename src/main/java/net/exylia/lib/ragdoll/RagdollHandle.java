package net.exylia.lib.ragdoll;

import net.exylia.lib.display.DisplayHandle;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * A body that is currently in pieces on somebody's screen.
 *
 * <p>Held only by code that might want it gone early &mdash; a preview the
 * player closed, an arena being reset. A body left alone clears itself when its
 * life is up.
 *
 * @since 1.120.0
 */
public final class RagdollHandle {

    private final List<DisplayHandle> pieces;

    @ApiStatus.Internal
    public RagdollHandle(@NotNull List<DisplayHandle> pieces) {
        this.pieces = List.copyOf(pieces);
    }

    /**
     * Removes every piece now, rather than when its life is up.
     *
     * <p>Safe from any thread and safe to call twice.
     */
    public void remove() {
        for (DisplayHandle piece : pieces) {
            piece.remove();
        }
    }

    /** Whether any of it is still on somebody's screen. */
    public boolean isShowing() {
        for (DisplayHandle piece : pieces) {
            if (piece.isShowing()) {
                return true;
            }
        }
        return false;
    }

    /** How many displays it put on screen. */
    public int pieces() {
        return pieces.size();
    }
}
