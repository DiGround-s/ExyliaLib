package net.exylia.lib.region;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Owner-scoped interactive block selection without retaining a Bukkit player.
 *
 * <p>Only the runtime can complete the result. Cancelling or closing an active session completes
 * its stage exceptionally with {@link java.util.concurrent.CancellationException}.
 *
 * @since 1.23.0
 */
public interface SelectionSession extends AutoCloseable {

    /**
     * Returns the selected player's UUID.
     *
     * @return player UUID
     */
    @NotNull UUID playerId();

    /**
     * Returns the exact case-sensitive plugin owner name.
     *
     * @return owner plugin name
     */
    @NotNull String owner();

    /**
     * Returns the current lifecycle state.
     *
     * @return session state
     */
    @NotNull SelectionState state();

    /**
     * Returns the exact left-clicked corner when selected.
     *
     * @return first corner
     */
    @NotNull Optional<BlockPosition> first();

    /**
     * Returns the exact right-clicked corner when selected.
     *
     * @return second corner
     */
    @NotNull Optional<BlockPosition> second();

    /**
     * Returns a read-only asynchronous view of the eventual result.
     *
     * @return completion stage that succeeds only for a completed selection
     */
    @NotNull CompletionStage<SelectionResult> result();

    /**
     * Accepts the two corners this session is holding.
     *
     * <p>What a shift + left-click does, exposed so a plugin driving its own
     * screen can accept a selection from a button. Does nothing unless the
     * session is in {@link SelectionState#AWAITING_CONFIRMATION}.
     *
     * @return {@code true} when this call completed the selection
     * @since 1.56.0
     */
    boolean confirm();

    /**
     * Cancels this session if it remains active.
     *
     * @return {@code true} when this call changed the state
     */
    boolean cancel();

    /** Cancels this session if it remains active. */
    @Override
    void close();
}
