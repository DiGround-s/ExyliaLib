package net.exylia.lib.input.internal;

import net.exylia.lib.input.InputOutcome;
import net.exylia.lib.input.InputResult;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Static lifecycle and concurrency core for pending input requests.
 *
 * <p>There is at most one active session per player. Replacement uses an atomic
 * map update, while each session independently arbitrates terminal events, so a
 * new request cannot silently strand the future returned by the old one.
 * Transport calls never occur while {@link #LOCK} is held: opening a menu can
 * synchronously fire server events, and holding a registry lock across that
 * re-entry would deadlock registration or shutdown.
 *
 * <h2>Threading</h2>
 * A transport is shown, closed, and its result future is completed through
 * {@link TaskScheduler#runAtEntity(org.bukkit.entity.Entity, Runnable)} whenever
 * the player exists. Packet listeners and async chat may call
 * {@link InputSession#complete(Object)} directly; dependent callbacks still run
 * on the player's owning thread, preventing Bukkit access from a packet or chat
 * thread. If the player no longer exists, cleanup and delivery use the global
 * scheduler because no entity thread remains to target.
 *
 * @since 1.31.0
 */
public final class InputRuntime {

    private static final List<String> BUILT_INS = List.of(
            "net.exylia.lib.input.internal.DialogTransport",
            "net.exylia.lib.input.internal.BedrockTransport",
            "net.exylia.lib.input.internal.SearchTransport",
            "net.exylia.lib.input.internal.MenuTransport",
            "net.exylia.lib.input.internal.ChatTransport"
    );

    private static final ConcurrentMap<UUID, InputSession> ACTIVE = new ConcurrentHashMap<>();
    private static final Object LOCK = new Object();

    private static volatile Plugin libraryPlugin;
    private static volatile Logger logger = Logger.getLogger("ExyliaLib");
    private static volatile List<Transport> transports = List.of();
    private static volatile boolean shuttingDown;

    private InputRuntime() {
        throw new AssertionError("No instances.");
    }

    /**
     * Connects the runtime to the library plugin and discovers built-in
     * transports in priority order.
     *
     * <p>Discovery is reflective so an optional transport whose protocol
     * dependency is absent cannot prevent the input module from loading. Test
     * transports installed before initialization are retained.
     *
     * @param plugin ExyliaLib's plugin instance, which owns all runtime tasks
     */
    public static void init(@NotNull Plugin plugin) {
        java.util.Objects.requireNonNull(plugin, "plugin");
        synchronized (LOCK) {
            libraryPlugin = plugin;
            logger = plugin.getLogger();
            shuttingDown = false;
            if (transports.isEmpty()) {
                transports = discover(plugin);
            }
        }
    }

    /**
     * Submits a session and returns its result future.
     *
     * <p>The map replacement happens before either request is displayed. The
     * previous session therefore receives {@link InputOutcome#REPLACED} even
     * when both submissions race, instead of being silently lost as in the old
     * callback implementation. Display is then dispatched to the player's
     * owning thread and the preferred kinds are tried in order. Missing kinds,
     * {@code false} returns, and isolated transport failures continue to the
     * next fallback.
     *
     * @param session   request state
     * @param preferred transport kinds in desired fallback order; an empty list
     *                  uses the installed registry order
     * @param <T>       answer type established by the public request
     * @return the same future held by the session
     */
    public static <T> @NotNull CompletableFuture<InputResult<T>> submit(
            @NotNull InputSession session, @NotNull List<TransportKind> preferred) {
        java.util.Objects.requireNonNull(session, "session");
        java.util.Objects.requireNonNull(preferred, "preferred");
        CompletableFuture<InputResult<T>> future = session.future();
        Plugin plugin;
        InputSession previous = null;
        boolean accepted;
        synchronized (LOCK) {
            plugin = libraryPlugin;
            accepted = plugin != null && !shuttingDown;
            if (accepted) {
                previous = ACTIVE.put(session.playerId(), session);
            }
        }
        if (!accepted) {
            session.end(InputOutcome.SHUT_DOWN);
            return future;
        }
        if (previous != null && previous != session) {
            previous.end(InputOutcome.REPLACED);
        }

        Player player = Bukkit.getPlayer(session.playerId());
        if (player == null || !player.isOnline()) {
            session.end(InputOutcome.UNAVAILABLE);
            return future;
        }

        List<Transport> candidates = candidates(preferred);
        try {
            Tasks.of(plugin).runAtEntity(player,
                    () -> display(session, player, candidates),
                    () -> session.end(InputOutcome.UNAVAILABLE));
        } catch (Throwable schedulingFailure) {
            logger.log(Level.WARNING, "Could not schedule input session " + session.id()
                    + " on the player's thread.", schedulingFailure);
            session.end(InputOutcome.UNAVAILABLE);
        }
        return future;
    }

    private static void display(InputSession session, Player player, List<Transport> candidates) {
        if (ACTIVE.get(session.playerId()) != session || session.terminalResult() != null) {
            return;
        }
        if (!player.isOnline()) {
            session.end(InputOutcome.UNAVAILABLE);
            return;
        }

        for (Transport candidate : candidates) {
            if (session.terminalResult() != null || ACTIVE.get(session.playerId()) != session) {
                return;
            }
            try {
                if (candidate.show(session)) {
                    session.shownBy(candidate);
                    if (session.terminalResult() == null) {
                        scheduleTimeout(session);
                    }
                    return;
                }
            } catch (Throwable failure) {
                logger.log(Level.WARNING, "Input transport " + candidate.kind()
                        + " failed to show session " + session.id() + "; trying fallback.", failure);
            }
        }
        session.end(InputOutcome.UNAVAILABLE);
    }

    private static void scheduleTimeout(InputSession session) {
        Plugin plugin = libraryPlugin;
        if (plugin == null || shuttingDown) {
            session.end(InputOutcome.SHUT_DOWN);
            return;
        }
        long ticks = timeoutTicks(session.timeout());
        TaskHandle handle;
        Player current = Bukkit.getPlayer(session.playerId());
        if (current != null) {
            handle = Tasks.of(plugin).runAtEntityLater(current, ticks,
                    () -> session.end(InputOutcome.TIMED_OUT));
        } else {
            handle = Tasks.of(plugin).runLater(ticks,
                    () -> session.end(InputOutcome.TIMED_OUT));
        }
        session.timeoutTask(handle);
    }

    /** Ends a player's current request because the player left. */
    public static void forget(@NotNull UUID player) {
        InputSession session = ACTIVE.get(java.util.Objects.requireNonNull(player, "player"));
        if (session != null) {
            session.end(InputOutcome.DISCONNECTED);
        }
    }

    /**
     * Ends every request owned by a disabling plugin, preventing futures and
     * callbacks from retaining its classloader.
     *
     * @param pluginName exact Bukkit plugin name
     */
    public static void releasePlugin(@NotNull String pluginName) {
        java.util.Objects.requireNonNull(pluginName, "pluginName");
        for (InputSession session : List.copyOf(ACTIVE.values())) {
            if (session.pluginName().equals(pluginName)) {
                session.end(InputOutcome.SHUT_DOWN);
            }
        }
    }

    /** Ends and forgets every request during library shutdown. */
    public static void shutdown() {
        Plugin plugin;
        synchronized (LOCK) {
            shuttingDown = true;
            plugin = libraryPlugin;
        }
        for (InputSession session : List.copyOf(ACTIVE.values())) {
            session.end(InputOutcome.SHUT_DOWN);
        }
        synchronized (LOCK) {
            if (libraryPlugin == plugin) {
                libraryPlugin = null;
                transports = List.of();
            }
        }
    }

    /** Returns the active session for a player, or {@code null}. */
    public static @Nullable InputSession active(@NotNull UUID player) {
        return ACTIVE.get(java.util.Objects.requireNonNull(player, "player"));
    }

    /** Returns whether a player currently has a request awaiting termination. */
    public static boolean hasActive(@NotNull UUID player) {
        return ACTIVE.containsKey(java.util.Objects.requireNonNull(player, "player"));
    }

    /** Ends a player's current request as an explicit cancellation. */
    public static void cancel(@NotNull UUID player) {
        InputSession session = ACTIVE.get(java.util.Objects.requireNonNull(player, "player"));
        if (session != null) {
            session.end(InputOutcome.CANCELLED);
        }
    }

    /**
     * Installs a complete ordered registry for tests. No lock is held while a
     * subsequently submitted session calls any installed fake.
     */
    static void installTransports(@NotNull List<Transport> replacements) {
        java.util.Objects.requireNonNull(replacements, "replacements");
        List<Transport> copy = List.copyOf(replacements);
        EnumSet<TransportKind> kinds = EnumSet.noneOf(TransportKind.class);
        for (Transport transport : copy) {
            if (!kinds.add(transport.kind())) {
                throw new IllegalArgumentException("Duplicate transport kind: " + transport.kind());
            }
        }
        synchronized (LOCK) {
            transports = copy;
        }
    }

    /** Restores an uninitialized, empty runtime for isolated tests. */
    static void clearForTests() {
        for (InputSession session : List.copyOf(ACTIVE.values())) {
            session.end(InputOutcome.SHUT_DOWN);
        }
        synchronized (LOCK) {
            ACTIVE.clear();
            transports = List.of();
            libraryPlugin = null;
            logger = Logger.getLogger("ExyliaLib");
            shuttingDown = false;
        }
    }

    static void finished(InputSession session, InputResult<Object> result) {
        ACTIVE.remove(session.playerId(), session);
        session.cancelTimeout();

        Plugin plugin = libraryPlugin;
        Runnable delivery = () -> deliver(session, result);
        if (plugin == null) {
            delivery.run();
            return;
        }
        Player player = Bukkit.getPlayer(session.playerId());
        try {
            if (player != null) {
                Tasks.of(plugin).runAtEntity(player, delivery,
                        () -> Tasks.of(plugin).run(delivery));
            } else {
                Tasks.of(plugin).run(delivery);
            }
        } catch (Throwable schedulingFailure) {
            logger.log(Level.WARNING, "Could not schedule input result for session "
                    + session.id() + "; delivering on the current thread.", schedulingFailure);
            delivery.run();
        }
    }

    private static void deliver(InputSession session, InputResult<Object> result) {
        Transport transport = session.transport();
        if (transport != null) {
            try {
                transport.close(session);
            } catch (Throwable failure) {
                logger.log(Level.WARNING, "Input transport " + transport.kind()
                        + " violated its no-throw close contract for session " + session.id() + '.', failure);
            }
        }
        try {
            session.rawFuture().complete(result);
        } catch (Throwable callbackFailure) {
            logger.log(Level.WARNING, "An input result callback failed for session "
                    + session.id() + '.', callbackFailure);
        }
    }

    private static List<Transport> candidates(List<TransportKind> preferred) {
        List<Transport> installed = transports;
        if (preferred.isEmpty()) {
            return installed;
        }
        List<Transport> ordered = new ArrayList<>(preferred.size());
        EnumSet<TransportKind> seen = EnumSet.noneOf(TransportKind.class);
        for (TransportKind kind : preferred) {
            java.util.Objects.requireNonNull(kind, "preferred contains null");
            if (!seen.add(kind)) {
                continue;
            }
            for (Transport transport : installed) {
                if (transport.kind() == kind) {
                    ordered.add(transport);
                    break;
                }
            }
        }
        return List.copyOf(ordered);
    }

    private static List<Transport> discover(Plugin plugin) {
        List<Transport> found = new ArrayList<>(BUILT_INS.size());
        for (String className : BUILT_INS) {
            try {
                Class<?> type = Class.forName(className);
                Object instance = construct(type, plugin);
                if (instance instanceof Transport transport) {
                    found.add(transport);
                    logger.info("Input: registered " + transport.kind() + " transport.");
                }
            } catch (ClassNotFoundException absent) {
                // Another source set or optional integration did not provide it.
            } catch (Throwable failure) {
                logger.log(Level.WARNING, "Input: transport " + className
                        + " is present but could not be initialized; it will be unavailable.", failure);
            }
        }
        return List.copyOf(found);
    }

    private static Object construct(Class<?> type, Plugin plugin) throws ReflectiveOperationException {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor(Plugin.class);
            constructor.setAccessible(true);
            return constructor.newInstance(plugin);
        } catch (NoSuchMethodException noPluginConstructor) {
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        }
    }

    private static long timeoutTicks(Duration timeout) {
        long seconds = timeout.getSeconds();
        int nanos = timeout.getNano();
        if (seconds > (Long.MAX_VALUE - 19L) / 20L) {
            return Long.MAX_VALUE;
        }
        long whole = seconds * 20L;
        long partial = (nanos + 49_999_999L) / 50_000_000L;
        return Math.max(1L, whole + partial);
    }
}
