package net.exylia.lib.analytics;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.UUID;

/**
 * Optional bridge to Exylia Analytics' custom events. Without Analytics on the server every call does nothing,
 * and a failure never reaches the caller. Event names are {@code [a-z0-9_.]{1,48}}, at most 16 props, values are
 * strings (200 chars at most), numbers or booleans.
 *
 * <p>Reflective on purpose: the Analytics API jar is not on any public repository, so the lib compiles without it.
 */
public final class Analytics {

    private static final MethodHandle PLAYER;
    private static final MethodHandle SERVER;

    static {
        MethodHandle player = null, server = null;
        try {
            Class<?> api = Class.forName("net.exylia.analytics.api.ExyliaAnalytics");
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            player = lookup.findStatic(api, "track", MethodType.methodType(void.class, UUID.class, String.class, Map.class));
            server = lookup.findStatic(api, "track", MethodType.methodType(void.class, String.class, Map.class));
        } catch (ReflectiveOperationException | LinkageError absent) {
            // Analytics is not installed: stay a no-op.
        }
        PLAYER = player;
        SERVER = server;
    }

    private Analytics() {
    }

    public static boolean available() {
        return PLAYER != null;
    }

    /** One event about a player. Any thread. */
    public static void track(UUID player, String name, Map<String, Object> props) {
        if (PLAYER == null) return;
        try {
            PLAYER.invoke(player, name, props);
        } catch (Throwable ignored) {
            // Analytics never breaks the caller.
        }
    }

    /** One server-scoped event. Any thread. */
    public static void track(String name, Map<String, Object> props) {
        if (SERVER == null) return;
        try {
            SERVER.invoke(name, props);
        } catch (Throwable ignored) {
            // Analytics never breaks the caller.
        }
    }
}
