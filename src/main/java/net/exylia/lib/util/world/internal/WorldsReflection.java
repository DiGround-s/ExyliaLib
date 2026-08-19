package net.exylia.lib.util.world.internal;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * The reflection the two Worlds backends share.
 *
 * <p>Two constraints shape this class, and both of them were paid for with a
 * real failure rather than guessed at:
 *
 * <p><b>Every class is loaded through the Worlds plugin's own classloader.</b>
 * Paper gives each plugin its own, so a plugin that does not declare Worlds as
 * a dependency cannot see {@code net.thenextlvl.worlds.*} through a plain
 * {@link Class#forName(String)} — detection would report "not installed" on a
 * server that has it installed. Going through the plugin instance also frees us
 * from load order and from anything a {@code paper-plugin.yml} would have to
 * declare.
 *
 * <p><b>Members are resolved with {@link MethodHandles.Lookup}, never with
 * {@link Class#getMethod}.</b> {@code getMethod} and {@code getDeclaredMethods}
 * make the JVM resolve the descriptor of <em>every</em> method on the class.
 * {@code WorldsProvider} declares {@code default GroupProvider groupProvider()},
 * whose return type belongs to the separate and optional <b>PerWorlds</b>
 * plugin: on a server without PerWorlds, asking for a completely unrelated
 * method throws {@link NoClassDefFoundError} for
 * {@code net/thenextlvl/perworlds/GroupProvider}. {@code findVirtual} and
 * {@code findStatic} resolve only the one descriptor asked for, so a missing
 * optional dependency behind a method we never call stays harmless.
 *
 * <p>Every failure surfaces as {@link BackendUnavailableException}, so a backend
 * can abandon its own construction cleanly and let the next generation be tried.
 */
final class WorldsReflection {

    /** The Bukkit plugin name Worlds registers under; identical in 3.x and 4.x. */
    static final String PLUGIN_NAME = "Worlds";

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    private WorldsReflection() {
        throw new AssertionError("No instances.");
    }

    /**
     * Says that a backend cannot bind against the installed Worlds version.
     *
     * <p>Unchecked so a backend constructor reads as straight-line code, and
     * caught only by the detector. Neither stack trace nor suppression is
     * recorded: this is control flow during a probe, not a crash.
     */
    static final class BackendUnavailableException extends RuntimeException {

        BackendUnavailableException(String message) {
            super(message, null, false, false);
        }

        BackendUnavailableException(String message, Throwable cause) {
            super(message, cause, false, false);
        }
    }

    /**
     * Returns the installed Worlds plugin, or {@code null} when it is absent or
     * disabled.
     *
     * <p>Not cached: a plugin can be enabled or disabled while the server runs,
     * and this is only ever consulted during one-shot detection.
     */
    static Plugin worldsPlugin() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        return plugin != null && plugin.isEnabled() ? plugin : null;
    }

    /**
     * Loads a Worlds API class through the Worlds plugin's classloader.
     *
     * <p>Initialization is asked for on purpose ({@code initialize = true}): a
     * class whose static initializer cannot run — a preset holder needing a
     * transitive library that is absent, say — then fails here, during
     * detection, rather than the first time a world is built.
     *
     * @param plugin    the Worlds plugin, whose classloader sees its own API
     * @param className the fully qualified API class name
     * @return the loaded class
     * @throws BackendUnavailableException when the class is absent or cannot be
     *                                     initialized, which is how a different
     *                                     API generation announces itself
     */
    static Class<?> require(Plugin plugin, String className) {
        try {
            return Class.forName(className, true, plugin.getClass().getClassLoader());
        } catch (ClassNotFoundException | LinkageError | RuntimeException e) {
            throw new BackendUnavailableException("cannot load " + className, e);
        }
    }

    /**
     * Resolves a virtual or interface method into a {@link MethodHandle}.
     *
     * <p>Only the descriptor asked for is resolved, so an unrelated method
     * mentioning an absent optional plugin never triggers classloading.
     *
     * @param owner      the declaring class or interface
     * @param name       the method name
     * @param returnType the exact declared return type
     * @param parameters the exact declared parameter types
     * @return the handle
     * @throws BackendUnavailableException when no method with that exact
     *                                     signature exists, which is precisely
     *                                     how an incompatible release is caught
     */
    static MethodHandle virtual(Class<?> owner, String name, Class<?> returnType, Class<?>... parameters) {
        try {
            return LOOKUP.findVirtual(owner, name, MethodType.methodType(returnType, parameters));
        } catch (NoSuchMethodException | IllegalAccessException | LinkageError | RuntimeException e) {
            throw new BackendUnavailableException("missing method " + owner.getName() + '#' + name, e);
        }
    }

    /**
     * Resolves a method that only some releases of a generation have.
     *
     * <p>{@code Level.Builder#legacyName} arrived in Worlds 4.1.0, for example.
     * Returns {@code null} when it is absent, so the backend can do without it
     * instead of rejecting the whole generation.
     *
     * @param owner      the declaring class or interface
     * @param name       the method name
     * @param returnType the exact declared return type
     * @param parameters the exact declared parameter types
     * @return the handle, or {@code null} when the release does not have it
     */
    static MethodHandle optionalVirtual(Class<?> owner, String name, Class<?> returnType, Class<?>... parameters) {
        try {
            return virtual(owner, name, returnType, parameters);
        } catch (BackendUnavailableException e) {
            return null;
        }
    }

    /**
     * Resolves a static method into a {@link MethodHandle}.
     *
     * @param owner      the declaring class
     * @param name       the method name
     * @param returnType the exact declared return type
     * @param parameters the exact declared parameter types
     * @return the handle
     * @throws BackendUnavailableException when no static method with that exact
     *                                     signature exists
     */
    static MethodHandle staticMethod(Class<?> owner, String name, Class<?> returnType, Class<?>... parameters) {
        try {
            return LOOKUP.findStatic(owner, name, MethodType.methodType(returnType, parameters));
        } catch (NoSuchMethodException | IllegalAccessException | LinkageError | RuntimeException e) {
            throw new BackendUnavailableException("missing static method " + owner.getName() + '#' + name, e);
        }
    }

    /**
     * Reads a public static field, which in this API is always a constant such
     * as a preset or a generator type.
     *
     * @param owner the declaring class
     * @param name  the constant name
     * @param type  the exact declared field type
     * @return the constant's value
     * @throws BackendUnavailableException when the constant is absent or cannot
     *                                     be read
     */
    static Object staticField(Class<?> owner, String name, Class<?> type) {
        try {
            return LOOKUP.findStaticGetter(owner, name, type).invoke();
        } catch (Throwable t) {
            throw new BackendUnavailableException("missing constant " + owner.getName() + '.' + name, t);
        }
    }
}
