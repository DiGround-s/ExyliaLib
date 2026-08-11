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

    private static boolean installed;
    private static boolean primaryThread = true;

    private FakeServer() {
    }

    /** A task the fake scheduler accepted, plus its cancellation state. */
    static final class Scheduled {
        final Runnable body;
        final boolean repeating;
        boolean cancelled;

        Scheduled(Runnable body, boolean repeating) {
            this.body = body;
            this.repeating = repeating;
        }

        /** Simulates the server ticking this task once. */
        void tick() {
            if (!cancelled) {
                body.run();
            }
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

    /** Clears recorded tasks between tests. */
    static void reset() {
        SCHEDULED.clear();
        primaryThread = true;
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
            // Every runTask* overload takes the plugin first and the body second.
            Runnable body = (Runnable) args[1];
            Scheduled scheduled = new Scheduled(body, name.contains("Timer"));
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
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> name;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
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
