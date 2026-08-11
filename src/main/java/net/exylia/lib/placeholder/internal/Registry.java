package net.exylia.lib.placeholder.internal;

import net.exylia.lib.placeholder.Request;
import net.exylia.lib.placeholder.Resolver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Every registered placeholder, in one map.
 *
 * <p>One map rather than one per kind. Resolution is a single lookup, and there
 * is no ordering question about which registry to consult first.
 *
 * <p>Reads happen constantly and from many threads; writes happen when a plugin
 * enables or disables. {@link ConcurrentHashMap} fits exactly: lock-free reads,
 * and writes that do not block them.
 */
public final class Registry {

    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();

    /** Names that failed, so a broken resolver is reported once and not per render. */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private Registry() {
    }

    /**
     * A registered placeholder.
     *
     * @param resolver   produces the value
     * @param owner      the plugin that registered it, for cleanup
     * @param async      whether it is safe to call off the main thread
     * @param description what it does, shown in diagnostics
     */
    public record Entry(Resolver resolver, String owner, boolean async, String description) {
    }

    /**
     * Registers a placeholder, replacing any previous one with the same name.
     *
     * @param name  the full name, already lower case
     * @param entry the registration
     */
    public static void register(String name, Entry entry) {
        ENTRIES.put(name, entry);
        REPORTED.remove(name);
        // A new registration can change how existing text splits into name and
        // arguments, so compiled templates are no longer trustworthy.
        TemplateCache.invalidate();
    }

    /** Removes a placeholder. */
    public static void unregister(String name) {
        if (ENTRIES.remove(name) != null) {
            TemplateCache.invalidate();
        }
    }

    /**
     * Removes everything a plugin registered.
     *
     * @param owner the plugin name
     * @return how many placeholders were removed
     */
    public static int unregisterAll(String owner) {
        List<String> names = new ArrayList<>();
        ENTRIES.forEach((name, entry) -> {
            if (entry.owner().equals(owner)) {
                names.add(name);
            }
        });
        names.forEach(ENTRIES::remove);
        if (!names.isEmpty()) {
            TemplateCache.invalidate();
        }
        return names.size();
    }

    /** Returns whether a name is registered. */
    public static boolean has(String name) {
        return ENTRIES.containsKey(name);
    }

    /** Returns the entry for a name, or {@code null}. */
    public static Entry get(String name) {
        return ENTRIES.get(name);
    }

    /**
     * Resolves a placeholder, turning any failure into "no value".
     *
     * <p>One broken resolver must not take down a scoreboard, so an exception is
     * logged the first time and swallowed afterwards.
     *
     * @param name    the placeholder name
     * @param request the request to pass along
     * @param logger  where to report a failure
     * @return the value, or {@code null} when there is none or it failed
     */
    public static Object resolve(String name, Request request, Logger logger) {
        Entry entry = ENTRIES.get(name);
        if (entry == null) {
            return null;
        }
        try {
            return entry.resolver().resolve(request);
        } catch (Throwable throwable) {
            if (REPORTED.add(name)) {
                logger.log(Level.WARNING,
                        "Placeholder %" + name + "% from " + entry.owner()
                                + " threw an exception and will be shown as empty. "
                                + "This is reported once.", throwable);
            }
            return null;
        }
    }

    /** Returns every registered name. */
    public static Set<String> names() {
        return Set.copyOf(ENTRIES.keySet());
    }

    /** Returns the names registered by one plugin. */
    public static Set<String> namesOf(String owner) {
        Set<String> result = new HashSet<>();
        ENTRIES.forEach((name, entry) -> {
            if (entry.owner().equals(owner)) {
                result.add(name);
            }
        });
        return Set.copyOf(result);
    }

    /** Returns how many placeholders are registered. */
    public static int size() {
        return ENTRIES.size();
    }

    /** Drops everything. Used on shutdown and by tests. */
    public static void clear() {
        ENTRIES.clear();
        REPORTED.clear();
        TemplateCache.invalidate();
    }
}
