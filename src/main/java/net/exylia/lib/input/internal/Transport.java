package net.exylia.lib.input.internal;

import org.jetbrains.annotations.NotNull;

/**
 * One mechanism capable of displaying an input request.
 *
 * <p>Implementations are deliberately small and may depend on optional server
 * or protocol integrations. Unavailability is therefore reported with
 * {@code false}, not an exception, so the runtime can continue to a compatible
 * fallback instead of failing the request because one integration is absent.
 *
 * @since 1.31.0
 */
public interface Transport {

    /**
     * Attempts to display a session.
     *
     * <p>This method is always called on the thread that owns the player. That
     * prevents Folia ownership violations while implementations open an
     * inventory or send packets. Return {@code false} when the dependency,
     * protocol, request shape, or online player needed by this transport is not
     * available; the runtime will try the next transport. Expected
     * unavailability must never be thrown.
     *
     * @param session the pending request
     * @return {@code true} only when this transport took responsibility for the
     *         visible request
     */
    boolean show(@NotNull InputSession session);

    /**
     * Tears down anything this transport displayed.
     *
     * <p>Implementations must be idempotent and must never throw. Completion,
     * timeout, disconnect, replacement, and shutdown may race, so cleanup can
     * be requested after the UI already disappeared.
     *
     * @param session the session being removed
     */
    void close(@NotNull InputSession session);

    /**
     * Names the mechanism this implementation provides.
     *
     * @return its stable transport kind
     */
    @NotNull TransportKind kind();
}
