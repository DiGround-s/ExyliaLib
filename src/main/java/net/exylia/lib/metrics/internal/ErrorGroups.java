package net.exylia.lib.metrics.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.PrintWriter;
import java.io.StringWriter;
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
        // A database that stopped answering is not a bug in the plugin, and one
        // outage would otherwise arrive as a "bug" per table being written.
        if (net.exylia.lib.database.internal.Outages.is(error)) {
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

    private static String stackOf(Throwable error) {
        StringWriter out = new StringWriter();
        error.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    private static String trim(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
