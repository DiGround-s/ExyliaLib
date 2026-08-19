package net.exylia.lib.input.internal;

import java.util.List;

/**
 * Lets tests in other packages install a fake way of asking a question.
 *
 * <p>Lives here for the same reason {@code DebugCapture} lives beside
 * {@code Debug}: the registry seams are package-private on purpose, and the
 * production API should not grow a setter just so a test in another module can
 * reach them. The wizard module is the caller that needs it &mdash; a wizard is
 * a chain of inputs, so there is no way to drive one end to end without being
 * able to answer the questions it asks.
 */
public final class TestTransports {

    private TestTransports() {
    }

    /**
     * Replaces the whole registry with the given transports, in order.
     *
     * @param replacements what should try to show a request
     */
    public static void install(List<Transport> replacements) {
        InputRuntime.installTransports(replacements);
    }

    /**
     * Connects the runtime to a plugin, which it needs before it will show
     * anything at all.
     *
     * <p>Without it every request ends as {@code SHUT_DOWN} before a transport
     * is consulted, so a caller sees a question that was never asked.
     *
     * @param plugin the plugin whose scheduler runs the runtime's work
     */
    public static void init(org.bukkit.plugin.Plugin plugin) {
        InputRuntime.init(plugin);
    }

    /** Ends everything pending and restores an uninitialised runtime. */
    public static void clear() {
        InputRuntime.clearForTests();
    }
}
