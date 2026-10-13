package net.exylia.lib.metrics.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The errors waiting for the next report, grouped the way the stats server
 * counts them: one entry per identical plugin, type and stack.
 *
 * <p>Capped at {@link #MAX_GROUPS}. A group that arrives once the cap is
 * reached is dropped, while the groups already held keep counting, so a
 * plugin failing the same way in a loop costs one entry rather than the lot.
 */
final class ErrorGroups {

    static final int MAX_GROUPS = 50;
    static final int MAX_COUNT = 1_000_000;
    static final int MAX_MESSAGE = 500;
    static final int MAX_STACK = 16_000;

    private final Map<List<String>, JsonObject> groups = new LinkedHashMap<>();

    synchronized void add(String plugin, String version, String phase, Throwable error) {
        if (foreign(error)) {
            return;
        }
        String type = error.getClass().getName();
        String stack = trim(stackOf(error), MAX_STACK);
        List<String> key = List.of(plugin, type, stack);
        JsonObject group = groups.get(key);
        if (group != null) {
            group.addProperty("count", Math.min(MAX_COUNT, group.get("count").getAsInt() + 1));
            return;
        }
        if (groups.size() >= MAX_GROUPS) {
            return;
        }
        group = new JsonObject();
        group.addProperty("plugin", plugin);
        group.addProperty("pluginVersion", version);
        group.addProperty("phase", phase);
        group.addProperty("type", type);
        group.addProperty("message", trim(error.getMessage() == null ? "" : error.getMessage(), MAX_MESSAGE));
        group.addProperty("stack", stack);
        group.addProperty("count", 1);
        groups.put(key, group);
    }

    synchronized boolean isEmpty() {
        return groups.isEmpty();
    }

    /**
     * Takes every group out, keeping only those of the listed plugins.
     *
     * @param plugins the plugins the report lists
     * @return the groups, in the order they first happened
     */
    synchronized JsonArray drain(Set<String> plugins) {
        JsonArray drained = new JsonArray();
        for (Iterator<JsonObject> it = groups.values().iterator(); it.hasNext(); ) {
            JsonObject group = it.next();
            if (plugins.contains(group.get("plugin").getAsString())) {
                drained.add(group);
            }
            it.remove();
        }
        return drained;
    }

    /**
     * Whether a failure is the server's environment rather than plugin code.
     * The operator already gets a console line for each; the stack trace fixes
     * nothing, and one cause would otherwise arrive as a "bug" per table or
     * call site that happened to hit it.
     */
    static boolean foreign(Throwable error) {
        // A database that stopped answering.
        if (net.exylia.lib.database.internal.Outages.is(error)
                // An embedded H2 file that is damaged, read-only or closed: a full
                // disk, folder permissions or corruption, said once by the storage.
                || net.exylia.lib.database.internal.H2Guard.isDamage(error)) {
            return true;
        }
        for (Throwable cause = error; cause != null && cause.getCause() != cause; cause = cause.getCause()) {
            // Wrong credentials or missing grants in the plugin's database settings
            // (MySQL/MariaDB 1044 and 1045, SQLSTATE class 28).
            if (cause instanceof SQLException sql && (sql.getErrorCode() == 1044 || sql.getErrorCode() == 1045
                    || (sql.getSQLState() != null && sql.getSQLState().startsWith("28")))) {
                return true;
            }
            // A jar replaced or unloaded under the running server: its class loader
            // can no longer read its own file, whichever plugin happened to call it.
            if (cause instanceof IllegalStateException && "zip file closed".equals(cause.getMessage())) {
                return true;
            }
        }
        return false;
    }

    private static String stackOf(Throwable error) {
        StringWriter out = new StringWriter();
        error.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    private static String trim(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
