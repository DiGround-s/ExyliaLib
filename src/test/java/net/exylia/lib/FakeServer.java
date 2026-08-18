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

    /**
     * Whether asynchronous tasks really run on another thread.
     *
     * <p>Off by default, because most tests want to inspect a scheduled task
     * rather than race it. Modules that fetch things turn it on: their whole
     * behaviour is what happens while a lookup is in flight.
     */
    private static volatile boolean asyncRunsForReal;

    /** Where asynchronous tasks really run, so async behaviour is testable. */
    static final java.util.concurrent.ExecutorService ASYNC =
            java.util.concurrent.Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "FakeServer-async");
                thread.setDaemon(true);
                return thread;
            });

    /** Players the server reports as online. */
    private static final List<org.bukkit.entity.Player> ONLINE = new ArrayList<>();

    /**
     * Worlds the server reports as loaded.
     *
     * <p>Anything that stores a location has to resolve a world by name to read
     * one back, so a test of that path needs the server to know about one.
     */
    private static final List<org.bukkit.World> WORLDS = new ArrayList<>();

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

    /** Makes {@code runAsync} execute on a real thread until the next reset. */
    public static void runAsyncForReal() {
        asyncRunsForReal = true;
    }

    /** Clears recorded tasks between tests. */
    public static void reset() {
        asyncRunsForReal = false;
        SCHEDULED.clear();
        ONLINE.clear();
        WORLDS.clear();
        EVENTS.clear();
        LISTENERS.clear();
        CONSOLE_MESSAGES.clear();
        CONSOLE_COMMANDS.clear();
        consoleAcceptsCommands = true;
        primaryThread = true;
        // Effect owners are per-plugin, and the plugins of a finished test do
        // not exist any more: left behind, they make the next test's effects
        // ambiguous.
        net.exylia.lib.effect.internal.EffectRuntime.releaseAll();
    }

    /** Sets which worlds the server reports as loaded. */
    public static void worlds(org.bukkit.World... worlds) {
        WORLDS.clear();
        WORLDS.addAll(List.of(worlds));
    }

    private static Object findWorld(Object[] args) {
        if (args == null || args.length != 1 || !(args[0] instanceof String name)) {
            return null;
        }
        for (org.bukkit.World world : WORLDS) {
            if (world.getName().equals(name)) {
                return world;
            }
        }
        return null;
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

    private static final List<String> CONSOLE_MESSAGES = new java.util.concurrent.CopyOnWriteArrayList<>();

    private static final List<String> CONSOLE_COMMANDS = new java.util.concurrent.CopyOnWriteArrayList<>();

    private static volatile boolean consoleAcceptsCommands = true;

    private static final org.bukkit.command.ConsoleCommandSender CONSOLE =
            (org.bukkit.command.ConsoleCommandSender) Proxy.newProxyInstance(
                    FakeServer.class.getClassLoader(),
                    new Class<?>[]{org.bukkit.command.ConsoleCommandSender.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("sendMessage")) {
                            CONSOLE_MESSAGES.add(args[0] instanceof net.kyori.adventure.text.Component c
                                    ? net.kyori.adventure.text.serializer.plain
                                            .PlainTextComponentSerializer.plainText().serialize(c)
                                    : String.valueOf(args[0]));
                            return null;
                        }
                        if (method.getName().equals("getName")) {
                            return "CONSOLE";
                        }
                        return defaultValue(method.getReturnType());
                    });

    /** The console, for tests that check what a non-player receives. */
    public static org.bukkit.command.ConsoleCommandSender consoleSender() {
        return CONSOLE;
    }

    /** Everything the console was sent, in order. */
    public static List<String> consoleMessages() {
        return List.copyOf(CONSOLE_MESSAGES);
    }

    /** Every command dispatched from the console, in order. */
    public static List<String> consoleCommands() {
        return List.copyOf(CONSOLE_COMMANDS);
    }

    /** Makes console dispatch report failure, as an unknown command would. */
    public static void consoleRejectsCommands() {
        consoleAcceptsCommands = false;
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
                    case "getPluginManager" -> PLUGIN_MANAGER;
                    case "getConsoleSender" -> CONSOLE;
                    case "dispatchCommand" -> {
                        CONSOLE_COMMANDS.add(String.valueOf(args[1]));
                        yield consoleAcceptsCommands;
                    }
                    case "getOnlinePlayers" -> List.copyOf(ONLINE);
                    case "getWorlds" -> List.copyOf(WORLDS);
                    case "getWorld" -> findWorld(args);
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

    /** Listeners the code under test registered, for opt-in dispatch. */
    private static final List<org.bukkit.event.Listener> LISTENERS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Every event the code under test fired, in order. */
    private static final List<org.bukkit.event.Event> EVENTS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * A plugin manager that records events rather than dispatching them.
     *
     * <p>Nothing here has real listeners, and a module that fires an event is
     * announcing something: recording is what lets a test assert on the
     * announcement without standing up a server.
     */
    private static final org.bukkit.plugin.PluginManager PLUGIN_MANAGER =
            (org.bukkit.plugin.PluginManager) Proxy.newProxyInstance(
                    FakeServer.class.getClassLoader(),
                    new Class<?>[]{org.bukkit.plugin.PluginManager.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("callEvent")) {
                            EVENTS.add((org.bukkit.event.Event) args[0]);
                            return null;
                        }
                        if (method.getName().equals("registerEvents")) {
                            LISTENERS.add((org.bukkit.event.Listener) args[0]);
                            return null;
                        }
                        return defaultValue(method.getReturnType());
                    });


    /**
     * Delivers one event to the listeners that registered for it.
     *
     * <p>Opt-in on purpose: most tests want events recorded rather than acted
     * on, and dispatching everywhere would change what they assert. A test that
     * needs a listener to run asks for it.
     *
     * @param event the event to deliver
     */
    public static void dispatch(org.bukkit.event.Event event) {
        for (org.bukkit.event.Listener listener : LISTENERS) {
            for (java.lang.reflect.Method method : listener.getClass().getMethods()) {
                if (!method.isAnnotationPresent(org.bukkit.event.EventHandler.class)) {
                    continue;
                }
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length != 1 || !parameters[0].isInstance(event)) {
                    continue;
                }
                try {
                    method.invoke(listener, event);
                } catch (ReflectiveOperationException failed) {
                    throw new IllegalStateException(
                            "A listener threw while handling " + event.getClass().getSimpleName(),
                            failed.getCause() != null ? failed.getCause() : failed);
                }
            }
        }
    }

    /** Every event fired since the last reset, in order. */
    public static List<org.bukkit.event.Event> events() {
        return List.copyOf(EVENTS);
    }

    /** Every fired event of one kind, in order. */
    public static <T extends org.bukkit.event.Event> List<T> events(Class<T> type) {
        List<T> found = new ArrayList<>();
        for (org.bukkit.event.Event event : EVENTS) {
            if (type.isInstance(event)) {
                found.add(type.cast(event));
            }
        }
        return List.copyOf(found);
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
            if (asyncRunsForReal && name.contains("Asynchronously") && !repeating && delay == 0) {
                // A real server runs these on another thread rather than on a
                // tick. Tests of anything that fetches need them to actually
                // run, and running them for real is also what lets a test put
                // two of them in flight at once.
                ASYNC.execute(() -> {
                    if (!scheduled.cancelled) {
                        body.run();
                    }
                });
            }
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
