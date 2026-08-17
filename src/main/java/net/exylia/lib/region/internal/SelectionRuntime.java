package net.exylia.lib.region.internal;

import net.exylia.lib.debug.Debug;
import net.exylia.lib.region.BlockPosition;
import net.exylia.lib.region.SelectionOptions;
import net.exylia.lib.region.SelectionResult;
import net.exylia.lib.region.SelectionSession;
import net.exylia.lib.region.SelectionState;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Shared owner-scoped selection registry and state machine. */
@ApiStatus.Internal
public final class SelectionRuntime {

    private static final Object LOCK = new Object();
    private static final Map<Key, Session> BY_OWNER_PLAYER = new HashMap<>();
    private static final Map<UUID, Session> BY_PLAYER = new HashMap<>();

    private SelectionRuntime() {
    }

    /**
     * Starts a session while enforcing one globally active selector per player.
     *
     * @param plugin exact owning plugin
     * @param playerId selected player's UUID
     * @param options immutable selection options
     * @return new active session
     * @throws IllegalStateException if any plugin already has an active selector for the player
     */
    public static @NotNull SelectionSession begin(@NotNull Plugin plugin,
                                                   @NotNull UUID playerId,
                                                   @NotNull SelectionOptions options) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(options, "options");
        synchronized (LOCK) {
            Session active = BY_PLAYER.get(playerId);
            if (active != null) {
                throw new IllegalStateException("Player already has an active selection owned by "
                        + active.owner());
            }
            Key key = new Key(plugin.getName(), playerId);
            Session session = new Session(key, plugin, options);
            BY_OWNER_PLAYER.put(key, session);
            BY_PLAYER.put(playerId, session);
            return session;
        }
    }

    /** Returns one owner's active session for a player, if present. */
    public static @NotNull Optional<SelectionSession> selection(@NotNull String owner,
                                                                 @NotNull UUID playerId) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(playerId, "playerId");
        synchronized (LOCK) {
            return Optional.ofNullable(BY_OWNER_PLAYER.get(new Key(owner, playerId)));
        }
    }

    /** Cancels one owner's active session for a player. */
    public static boolean cancel(@NotNull String owner, @NotNull UUID playerId) {
        Session session;
        synchronized (LOCK) {
            session = BY_OWNER_PLAYER.get(new Key(Objects.requireNonNull(owner, "owner"),
                    Objects.requireNonNull(playerId, "playerId")));
        }
        return session != null && session.cancel();
    }

    /** Returns the uniquely routed active session for a player. */
    static @Nullable Session routed(@NotNull UUID playerId) {
        synchronized (LOCK) {
            return BY_PLAYER.get(Objects.requireNonNull(playerId, "playerId"));
        }
    }

    /** Applies an exact block corner to the globally routed session. */
    public static boolean select(@NotNull UUID playerId, boolean first,
                                 @NotNull BlockPosition position) {
        Session session = routed(playerId);
        return session != null && session.select(first, position);
    }

    /** Cancels all active sessions for the exact case-sensitive owner name. */
    public static int release(@NotNull String owner) {
        Objects.requireNonNull(owner, "owner");
        Session[] sessions;
        synchronized (LOCK) {
            sessions = BY_OWNER_PLAYER.entrySet().stream()
                    .filter(entry -> entry.getKey().owner().equals(owner))
                    .map(Map.Entry::getValue)
                    .toArray(Session[]::new);
        }
        int cancelled = 0;
        for (Session session : sessions) {
            if (session.cancel()) cancelled++;
        }
        return cancelled;
    }

    /** Cancels every active selection session. */
    public static void releaseAll() {
        Session[] sessions;
        synchronized (LOCK) {
            sessions = BY_PLAYER.values().toArray(Session[]::new);
        }
        for (Session session : sessions) session.cancel();
    }

    private static void remove(Session session) {
        synchronized (LOCK) {
            BY_OWNER_PLAYER.remove(session.key, session);
            BY_PLAYER.remove(session.playerId(), session);
        }
    }

    private record Key(String owner, UUID playerId) {
        private Key {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(playerId, "playerId");
        }
    }

    /** Internal mutable state behind the immutable public session view. */
    static final class Session implements SelectionSession {
        private final Key key;
        private final Plugin plugin;
        private final SelectionOptions options;
        private final CompletableFuture<SelectionResult> completion = new CompletableFuture<>();
        private final CompletionStage<SelectionResult> exposed = completion.minimalCompletionStage();

        private SelectionState state = SelectionState.ACTIVE;
        private BlockPosition first;
        private BlockPosition second;

        private Session(Key key, Plugin plugin, SelectionOptions options) {
            this.key = key;
            this.plugin = plugin;
            this.options = options;
        }

        @Override
        public @NotNull UUID playerId() {
            return key.playerId();
        }

        @Override
        public @NotNull String owner() {
            return key.owner();
        }

        @Override
        public synchronized @NotNull SelectionState state() {
            return state;
        }

        @Override
        public synchronized @NotNull Optional<BlockPosition> first() {
            return Optional.ofNullable(first);
        }

        @Override
        public synchronized @NotNull Optional<BlockPosition> second() {
            return Optional.ofNullable(second);
        }

        @Override
        public @NotNull CompletionStage<SelectionResult> result() {
            return exposed;
        }

        @NotNull SelectionOptions options() {
            return options;
        }

        private boolean select(boolean selectingFirst, BlockPosition position) {
            SelectionResult completed = null;
            synchronized (this) {
                if (state != SelectionState.ACTIVE) return false;
                if (selectingFirst) {
                    first = position;
                } else {
                    second = position;
                    if (first != null) {
                        if (options.requireSameWorld() && !first.world().equals(second.world())) {
                            Debug.of(plugin).debug("Region selection corners for " + playerId()
                                    + " are in different worlds; selection remains active.");
                        } else {
                            state = SelectionState.COMPLETED;
                            completed = new SelectionResult(first.world(), first, second);
                        }
                    }
                }
            }
            if (completed != null) {
                remove(this);
                completion.complete(completed);
            }
            return true;
        }

        @Override
        public boolean cancel() {
            synchronized (this) {
                if (state != SelectionState.ACTIVE) return false;
                state = SelectionState.CANCELLED;
            }
            remove(this);
            completion.completeExceptionally(new CancellationException("Selection cancelled"));
            return true;
        }

        @Override
        public void close() {
            cancel();
        }
    }
}
