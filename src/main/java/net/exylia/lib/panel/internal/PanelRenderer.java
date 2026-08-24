package net.exylia.lib.panel.internal;

import org.jetbrains.annotations.ApiStatus;

/**
 * Where the engine announces what it drew, and the seam tests listen on.
 *
 * <p>What a panel put in a slot is otherwise only observable as an
 * {@code ItemStack}, and an {@code ItemStack} cannot be built without a running
 * server — its class initialiser reaches the registry. Announcing the decision
 * separately is what lets a test assert that save landed in slot 45 rather than
 * 49 with no server at all, which is the whole point of keeping slots in a
 * layout file instead of in control flow.
 *
 * <p>The sink is a static field on purpose, mirroring
 * {@code ItemRenderer.components}: it is a hole for a test to reach through,
 * not per-panel state, and it holds no player.
 */
@ApiStatus.Internal
public final class PanelRenderer {

    /** Drops what it is told. Production draws through this and pays nothing. */
    private static final DrawSink NONE = (slot, kind, entry) -> {
    };

    private static volatile DrawSink sink = NONE;

    private PanelRenderer() {
        throw new AssertionError("No instances.");
    }

    /**
     * Told once per slot the engine draws.
     *
     * <p>Deliberately not an {@code ItemStack}: the assertion a test wants is
     * which control went where, and the stack is the part that needs a server.
     */
    public interface DrawSink {

        /**
         * @param slot  where it went
         * @param kind  what control it is
         * @param entry what the row is about, or {@code null} for chrome
         */
        void drew(int slot, ControlKind kind, Object entry);
    }

    /**
     * Test seam: installs a sink, returning the one it replaced.
     *
     * <p>Returning the previous rather than offering a reset lets a test nest
     * or restore exactly what was there, which matters because tests in one
     * class share this field.
     *
     * @param replacement the new sink, or {@code null} to draw silently
     * @return the sink that was installed before
     */
    public static DrawSink sink(DrawSink replacement) {
        DrawSink previous = sink;
        sink = replacement == null ? NONE : replacement;
        return previous;
    }

    /** Announces a drawn slot. Called by the engine, never by a consumer. */
    static void drew(int slot, ControlKind kind, Object entry) {
        sink.drew(slot, kind, entry);
    }
}
