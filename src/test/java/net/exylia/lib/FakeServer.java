package net.exylia.lib;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * A minimal in-memory stand-in for the Bukkit server, built from dynamic proxies
 * so the task module can be driven for real in unit tests without booting a
 * server or pulling in a mocking framework.
 *
 * <p>Only the handful of methods the scheduler actually calls are implemented;
 * anything else returns a default value.
 */
public final class FakeServer {

    /** Tasks handed to the scheduler, in submission order. */
    static final List<Scheduled> SCHEDULED = new ArrayList<>();

    /** Players the server reports as online. */
    private static final List<org.bukkit.entity.Player> ONLINE = new ArrayList<>();

    private static boolean installed;
    private static boolean primaryThread = true;

    private FakeServer() {
    }

    /** A task the fake scheduler accepted, plus its cancellation state. */
    static final class Scheduled {
        final Runnable body;
        final boolean repeating;
        final long period;
        boolean cancelled;

        /** Ticks remaining before the body runs again. */
        long countdown;

        Scheduled(Runnable body, boolean repeating, long delay, long period) {
            this.period = Math.max(1, period);
            this.countdown = Math.max(0, delay);
            this.body = body;
            this.repeating = repeating;
        }

        /**
         * Simulates the server ticking this task once.
         *
         * <p>A one-shot task runs once and is then done, exactly as the real
         * scheduler behaves. Running it on every tick would make a test of
         * "how many times was this sent" measure the harness instead of the
         * code.
         */
        void tick() {
            if (cancelled) {
                return;
            }
            if (!repeating) {
                cancelled = true;
            }
            body.run();
        }

        /**
         * Simulates one server tick passing, which is not the same as running
         * the task.
         *
         * <p>Honours the delay and period, so a test of "how often was this
         * sent" measures the code rather than the harness. Tests that want to
         * drive a task directly, regardless of its schedule, call
         * {@link #tick()}.
         */
        void tickClock() {
            if (cancelled) {
                return;
            }
            if (countdown > 0) {
                countdown--;
                return;
            }
            countdown = repeating ? period - 1 : 0;
            tick();
        }
    }

    /**
     * Installs the fake server, once per JVM.
     *
     * <p>The private field is set directly rather than through
     * {@code Bukkit.setServer}, because that method resolves the server build
     * info through a {@code ServiceLoader} that only exists inside a real
     * server.
     */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        try {
            java.lang.reflect.Field field = Bukkit.class.getDeclaredField("server");
            field.setAccessible(true);
            field.set(null, newServer());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not install the fake server", exception);
        }
        installed = true;
    }

    /**
     * Runs every scheduled task once, as if the server ticked.
     *
     * <p>Effects are driven by repeating tasks, so this is how a test advances
     * a countdown without waiting in real time.
     *
     * @param times how many ticks to simulate
     */
    public static void tick(int times) {
        for (int i = 0; i < times; i++) {
            for (Scheduled scheduled : List.copyOf(SCHEDULED)) {
                scheduled.tickClock();
            }
        }
    }

    /** Returns how many tasks are scheduled and not cancelled. */
    public static int liveTasks() {
        int live = 0;
        for (Scheduled scheduled : SCHEDULED) {
            if (!scheduled.cancelled) {
                live++;
            }
        }
        return live;
    }

    /**
     * Returns how many repeating tasks are still running.
     *
     * <p>Separate from {@link #liveTasks()} because a one-shot task that has not
     * been ticked yet is not a leak, while a repeating one that outlives its
     * effect is.
     */
    public static int liveRepeatingTasks() {
        int live = 0;
        for (Scheduled scheduled : SCHEDULED) {
            if (!scheduled.cancelled && scheduled.repeating) {
                live++;
            }
        }
        return live;
    }

    /** Clears recorded tasks between tests. */
    public static void reset() {
        SCHEDULED.clear();
        ONLINE.clear();
        primaryThread = true;
    }

    /** Sets who the server reports as online. */
    public static void online(org.bukkit.entity.Player... players) {
        ONLINE.clear();
        ONLINE.addAll(List.of(players));
    }

    private static Object findPlayer(Object[] args) {
        if (args == null || args.length != 1 || !(args[0] instanceof java.util.UUID id)) {
            return null;
        }
        for (org.bukkit.entity.Player player : ONLINE) {
            if (player.getUniqueId().equals(id)) {
                return player;
            }
        }
        return null;
    }

    /**
     * A world, which a Location needs before it can measure a distance.
     *
     * <p>Identity is what Bukkit compares worlds by, so one instance per world
     * name is enough.
     */
    public static org.bukkit.World newWorld(String name) {
        return (org.bukkit.World) Proxy.newProxyInstance(
                FakeServer.class.getClassLoader(),
                new Class<?>[]{org.bukkit.World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "getUID" -> java.util.UUID.nameUUIDFromBytes(name.getBytes());
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "FakeWorld[" + name + "]";
                    default -> defaultValue(method.getReturnType());
                });
    }

    /** Controls what {@code Bukkit.isPrimaryThread()} reports. */
    static void setPrimaryThread(boolean value) {
        primaryThread = value;
    }

    private static Server newServer() {
        BukkitScheduler scheduler = newScheduler();
        Logger logger = Logger.getLogger("FakeServer");

        return (Server) Proxy.newProxyInstance(
                FakeServer.class.getClassLoader(),
                new Class<?>[]{Server.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getScheduler" -> scheduler;
                    case "getOnlinePlayers" -> List.copyOf(ONLINE);
                    case "getPlayer" -> findPlayer(args);
                    case "isPrimaryThread" -> primaryThread;
                    case "getLogger" -> logger;
                    case "getName" -> "FakeServer";
                    case "getVersion", "getBukkitVersion" -> "1.21.4";
                    case "toString" -> "FakeServer";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static BukkitScheduler newScheduler() {
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if (!name.startsWith("runTask")) {
                return defaultValue(method.getReturnType());
            }
            // Every runTask* overload takes the plugin first and the body
            // second; a delayed one then takes the delay, and a timer the
            // period after that.
            Runnable body = (Runnable) args[1];
            boolean repeating = name.contains("Timer");
            long delay = args.length > 2 && args[2] instanceof Long value ? value : 0;
            long period = args.length > 3 && args[3] instanceof Long value ? value : 1;
            Scheduled scheduled = new Scheduled(body, repeating, delay, period);
            SCHEDULED.add(scheduled);
            return newTask(scheduled);
        };
        return (BukkitScheduler) Proxy.newProxyInstance(
                FakeServer.class.getClassLoader(),
                new Class<?>[]{BukkitScheduler.class},
                handler);
    }

    private static BukkitTask newTask(Scheduled scheduled) {
        return (BukkitTask) Proxy.newProxyInstance(
                FakeServer.class.getClassLoader(),
                new Class<?>[]{BukkitTask.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "cancel" -> {
                        scheduled.cancelled = true;
                        yield null;
                    }
                    case "isCancelled" -> scheduled.cancelled;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "FakeTask";
                    default -> defaultValue(method.getReturnType());
                });
    }

    /** A plugin proxy whose logger swallows nothing, so failures stay visible. */
    public static Plugin newPlugin(String name) {
        return newPlugin(name, null);
    }

    /**
     * A plugin proxy with a data folder, for tests that touch config files.
     *
     * @param name       the plugin name
     * @param dataFolder the folder configs are written to, or {@code null}
     * @return the proxy
     */
    public static Plugin newPlugin(String name, java.io.File dataFolder) {
        Logger logger = Logger.getLogger(name);
        return (Plugin) Proxy.newProxyInstance(
                FakeServer.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "getLogger" -> logger;
                    case "isEnabled" -> true;
                    case "getDataFolder" -> dataFolder;
                    case "getDescription" ->
                            new org.bukkit.plugin.PluginDescriptionFile(name, "1.0-test",
                                    "test.Main");
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> name;
                    default -> defaultValue(method.getReturnType());
                });
    }

    /** Default return values for proxy methods nobody stubbed. */
    public static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == void.class) {
            return null;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0.0d;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        return (char) 0;
    }
}
