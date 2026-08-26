package net.exylia.lib.clan.internal;

import org.bukkit.Bukkit;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The reflection every clan provider does, written once.
 *
 * <p>Each supported clan plugin lives behind its own API classes, and none of
 * them are on our compile classpath: a server that runs one of them must not
 * force every other server to ship the other seven. So providers reach their
 * plugin by name, and every call that cannot be made answers {@code null}
 * rather than throwing. A provider reads that as "the plugin does not have
 * this", which is the same answer it would give for a clan that does not
 * exist.
 *
 * <p>Nothing here logs. A failed lookup on a plugin that is not installed is
 * the normal case, and a warning per call would drown the console.
 */
final class Reflect {

    /** Resolved classes, so a hot path does not re-enter the classloader. */
    private static final Map<String, Optional<Class<?>>> CLASSES = new ConcurrentHashMap<>();

    private Reflect() {
    }

    /** Returns whether a plugin is installed and running. */
    static boolean pluginEnabled(String name) {
        try {
            return Bukkit.getPluginManager().isPluginEnabled(name);
        } catch (Throwable e) {
            return false;
        }
    }

    /** Returns the class, or {@code null} when it is not on the classpath. */
    static Class<?> type(String name) {
        return CLASSES.computeIfAbsent(name, n -> {
            try {
                return Optional.of(Class.forName(n));
            } catch (Throwable e) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    /** Calls a static method, or returns {@code null} when anything fails. */
    static Object statically(String className, String method, Object... args) {
        Class<?> cls = type(className);
        return cls == null ? null : invoke(cls, null, method, args);
    }

    /** Calls an instance method, or returns {@code null} when anything fails. */
    static Object call(Object target, String method, Object... args) {
        return target == null ? null : invoke(target.getClass(), target, method, args);
    }

    /**
     * Calls the first of several method names that exists.
     *
     * <p>Plugin APIs rename things between majors — {@code relationTo} became
     * {@code relationWith}, {@code getUuid} became {@code uniqueId} — and a
     * provider that names both keeps working across the rename.
     */
    static Object callAny(Object target, String[] names, Object... args) {
        for (String name : names) {
            Object result = call(target, name, args);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private static Object invoke(Class<?> cls, Object target, String name, Object[] args) {
        try {
            Method method = find(cls, name, args);
            if (method == null) {
                return null;
            }
            return method.invoke(target, args);
        } catch (Throwable e) {
            return null;
        }
    }

    private static Method find(Class<?> cls, String name, Object[] args) {
        for (Method method : cls.getMethods()) {
            if (!method.getName().equals(name)) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length != args.length || !accepts(params, args)) {
                continue;
            }
            // A public method declared on a non-public implementation class is
            // not callable until it is opened, which is the common shape for
            // plugin managers hidden behind an interface.
            try {
                method.setAccessible(true);
            } catch (Throwable ignored) {
                // Left as found; the invoke below reports the real outcome.
            }
            return method;
        }
        return null;
    }

    private static boolean accepts(Class<?>[] params, Object[] args) {
        for (int i = 0; i < params.length; i++) {
            Object arg = args[i];
            if (arg == null) {
                if (params[i].isPrimitive()) {
                    return false;
                }
                continue;
            }
            if (!box(params[i]).isInstance(arg)) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == boolean.class) return Boolean.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return type;
    }

    // ------------------------------------------------------------------
    // Reading values back
    // ------------------------------------------------------------------

    /** Unwraps an {@link Optional} the plugin returned, or passes a value through. */
    static Object unwrap(Object value) {
        if (value instanceof Optional<?> optional) {
            return optional.orElse(null);
        }
        return value;
    }

    /** Calls a getter and unwraps whatever optional it hands back. */
    static Object get(Object target, String method, Object... args) {
        return unwrap(call(target, method, args));
    }

    /** Calls the first getter that exists and unwraps its answer. */
    static Object getAny(Object target, String[] names, Object... args) {
        return unwrap(callAny(target, names, args));
    }

    /** Reads a string getter, or {@code null}. */
    static String string(Object target, String... names) {
        Object value = getAny(target, names);
        return value == null ? null : String.valueOf(value);
    }

    /** Reads an id that the plugin may model as a {@link UUID} or a string. */
    static UUID uuid(Object target, String... names) {
        return toUuid(getAny(target, names));
    }

    /** Converts a plugin's idea of a player id into ours, or {@code null}. */
    static UUID toUuid(Object value) {
        if (value instanceof UUID id) {
            return id;
        }
        if (value instanceof String text) {
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    /** Reads a numeric getter, or zero. */
    static double number(Object target, String... names) {
        Object value = getAny(target, names);
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return 0;
    }

    /** Reads a boolean getter, or {@code false}. */
    static boolean flag(Object target, String... names) {
        return getAny(target, names) instanceof Boolean b && b;
    }

    /** Reads a collection getter, empty when it is missing or not a collection. */
    static Collection<?> collection(Object target, String... names) {
        Object value = getAny(target, names);
        if (value instanceof Collection<?> collection) {
            return collection;
        }
        if (value instanceof Map<?, ?> map) {
            return map.keySet();
        }
        return List.of();
    }

    /** Reads a map getter, empty when it is missing or not a map. */
    static Map<?, ?> map(Object target, String... names) {
        Object value = getAny(target, names);
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    /** Returns whether a player id belongs to someone online right now. */
    static boolean isOnline(UUID player) {
        try {
            return Bukkit.getPlayer(player) != null;
        } catch (Throwable e) {
            return false;
        }
    }
}
