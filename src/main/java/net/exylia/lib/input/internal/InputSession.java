package net.exylia.lib.input.internal;

import net.exylia.lib.input.InputOutcome;
import net.exylia.lib.input.InputResult;
import net.exylia.lib.task.TaskHandle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The state of one pending input request.
 *
 * <p>A session owns the future returned to the caller and one atomic terminal
 * slot. Packet responses, inventory close events, async chat, and timeouts can
 * arrive concurrently; only the thread that changes that slot from
 * {@code null} wins. This prevents a dialog response followed by its close
 * packet from delivering both {@code COMPLETED} and {@code CANCELLED}.
 *
 * <p>Terminal delivery is delegated to {@link InputRuntime}, which removes the
 * session, cancels its timeout, closes its transport, and completes the future
 * on the player's owning thread.
 *
 * @since 1.31.0
 */
public final class InputSession {

    private final UUID id;
    private final String pluginName;
    private final UUID playerId;
    private final Object request;
    private final Duration timeout;
    private final Instant createdAt;
    private final CompletableFuture<InputResult<Object>> future;
    private final AtomicReference<InputResult<Object>> terminal = new AtomicReference<>();
    private final AtomicReference<TaskHandle> timeoutTask = new AtomicReference<>();

    private volatile TransportKind transportKind;
    private volatile Transport transport;

    /**
     * Minimal contract public request implementations may use to expose their
     * timeout without making the runtime depend on a particular builder class.
     * The complete request remains available through {@link #request()} so each
     * transport can inspect the public request type it understands.
     */
    public interface Pending {

        /**
         * Maximum time the request may remain pending.
         *
         * @return a positive timeout
         */
        @NotNull Duration timeout();
    }

    /**
     * Creates a session around a request implementing {@link Pending}.
     *
     * @param pluginName owner used for disable-time cleanup
     * @param playerId   player allowed to have this request active
     * @param request    public request object consumed by transports
     */
    public InputSession(@NotNull String pluginName, @NotNull UUID playerId,
                        @NotNull Pending request) {
        this(UUID.randomUUID(), pluginName, playerId, request, request.timeout(),
                new CompletableFuture<>());
    }

    /**
     * Creates a session when the public request keeps timeout configuration
     * separately. This overload lets request builders integrate without
     * implementing an internal marker interface.
     *
     * @param pluginName owner used for disable-time cleanup
     * @param playerId   player allowed to have this request active
     * @param request    public request object consumed by transports
     * @param timeout    maximum pending duration
     */
    public InputSession(@NotNull String pluginName, @NotNull UUID playerId,
                        @NotNull Object request, @NotNull Duration timeout) {
        this(UUID.randomUUID(), pluginName, playerId, request, timeout,
                new CompletableFuture<>());
    }

    /**
     * Full constructor used by deterministic tests and adapters that already
     * allocate the stage returned by their public {@code open()} method.
     *
     * @param id         stable session identity
     * @param pluginName owner used for disable-time cleanup
     * @param playerId   target player
     * @param request    public request object
     * @param timeout    maximum pending duration
     * @param future     future returned to the caller
     */
    public InputSession(@NotNull UUID id, @NotNull String pluginName,
                        @NotNull UUID playerId, @NotNull Object request,
                        @NotNull Duration timeout,
                        @NotNull CompletableFuture<InputResult<Object>> future) {
        this.id = java.util.Objects.requireNonNull(id, "id");
        this.pluginName = requireText(pluginName, "pluginName");
        this.playerId = java.util.Objects.requireNonNull(playerId, "playerId");
        this.request = java.util.Objects.requireNonNull(request, "request");
        this.timeout = requireTimeout(timeout);
        this.future = java.util.Objects.requireNonNull(future, "future");
        this.createdAt = Instant.now();
    }

    public @NotNull UUID id() {
        return id;
    }

    public @NotNull String pluginName() {
        return pluginName;
    }

    public @NotNull UUID playerId() {
        return playerId;
    }

    /**
     * Returns the public request object. Transports should check its public type
     * and return {@code false} from {@link Transport#show(InputSession)} when
     * they cannot represent it.
     *
     * @return the original request
     */
    public @NotNull Object request() {
        return request;
    }

    public @NotNull Duration timeout() {
        return timeout;
    }

    public @NotNull Instant createdAt() {
        return createdAt;
    }

    public @Nullable TransportKind transportKind() {
        return transportKind;
    }

    /**
     * Returns the result stage with the value type expected by the public
     * request. The request builder establishes that type when constructing the
     * session; the runtime preserves the value unchanged.
     *
     * @param <T> requested answer type
     * @return the future returned by {@code open()}
     */
    @SuppressWarnings("unchecked")
    public <T> @NotNull CompletableFuture<InputResult<T>> future() {
        return (CompletableFuture<InputResult<T>>) (CompletableFuture<?>) future;
    }

    /**
     * Completes with an answer if no competing terminal event won first.
     *
     * @param value parsed and validated answer
     * @return {@code true} when this call won terminal delivery
     */
    public boolean complete(@NotNull Object value) {
        return finish(InputResult.completed(java.util.Objects.requireNonNull(value, "value")));
    }

    /**
     * Ends without an answer if no competing terminal event won first.
     *
     * @param outcome reason the request ended; cannot be {@code COMPLETED}
     * @return {@code true} when this call won terminal delivery
     */
    public boolean end(@NotNull InputOutcome outcome) {
        return finish(InputResult.ended(java.util.Objects.requireNonNull(outcome, "outcome")));
    }

    boolean finish(InputResult<Object> result) {
        if (!terminal.compareAndSet(null, result)) {
            return false;
        }
        InputRuntime.finished(this, result);
        return true;
    }

    @Nullable InputResult<Object> terminalResult() {
        return terminal.get();
    }

    void shownBy(Transport shownTransport) {
        this.transport = shownTransport;
        this.transportKind = shownTransport.kind();
    }

    @Nullable Transport transport() {
        return transport;
    }

    void timeoutTask(TaskHandle handle) {
        if (!timeoutTask.compareAndSet(null, handle)) {
            handle.cancel();
            throw new IllegalStateException("A session may schedule only one timeout task.");
        }
        if (terminal.get() != null) {
            cancelTimeout();
        }
    }

    void cancelTimeout() {
        TaskHandle handle = timeoutTask.getAndSet(null);
        if (handle != null) {
            handle.cancel();
        }
    }

    CompletableFuture<InputResult<Object>> rawFuture() {
        return future;
    }

    private static String requireText(String value, String name) {
        java.util.Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static Duration requireTimeout(Duration timeout) {
        java.util.Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        return timeout;
    }
}
