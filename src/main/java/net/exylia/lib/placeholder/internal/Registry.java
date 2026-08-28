package net.exylia.lib.placeholder.internal;

import net.exylia.lib.placeholder.Request;
import net.exylia.lib.placeholder.Resolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Every registered placeholder.
 *
 * <p>Two views of the same registrations, because a placeholder is asked for in
 * two ways that mean different things:
 *
 * <ul>
 *   <li><b>By name alone</b> — {@code %total_players%} in a scoreboard line.
 *       Nothing in that text says which plugin is meant, so one name can only
 *       have one answer: the last registration wins, and the takeover is
 *       reported.</li>
 *   <li><b>By plugin and name</b> — {@code %exyliaffa_total_players%} through
 *       PlaceholderAPI, where the identifier already says who is being asked.
 *       Two plugins registering {@code total_players} are two different
 *       placeholders there, and both must answer.</li>
 * </ul>
 *
 * <p>Keeping only the first view is what made
 * {@code %exyliasandbox_total_players%} stop working the moment ExyliaFFA
 * registered the same name: one flat slot, one owner, and the plugin that lost
 * it went silent under its own identifier.
 *
 * <p>Reads happen constantly and from many threads; writes happen when a plugin
 * enables or disables. {@link ConcurrentHashMap} fits exactly: lock-free reads,
 * and writes that do not block them.
 */
public final class Registry {

    /** Keyed by name alone: what {@code %name%} with nothing in front resolves to. */
    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();

    /** Keyed by owner and then by name: what one plugin registered, whoever else did. */
    private static final Map<String, Map<String, Entry>> BY_OWNER = new ConcurrentHashMap<>();

    /**
     * The plugin name behind a lower case one, so text can name its owner.
     *
     * <p>{@code BY_OWNER} is keyed by {@link org.bukkit.plugin.Plugin#getName()}
     * exactly, capitals and all, while a placeholder in a config file is folded
     * before anything looks at it. This is the bridge between the two, and it is
     * a map rather than a scan because it is consulted per placeholder.
     */
    private static final Map<String, String> OWNER_IDS = new ConcurrentHashMap<>();

    /** Names that failed, so a broken resolver is reported once and not per render. */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    /** Names already reported as stolen, so a reload loop does not repeat it. */
    private static final Set<String> REPORTED_OVERWRITE = ConcurrentHashMap.newKeySet();

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
     * <p>The same plugin re-registering a name is silent: that is a reload
     * replacing its own resolver, which is documented behaviour.
     *
     * <p>A <em>different</em> plugin taking the name over is reported once. The
     * map is flat and keyed by name alone, so this used to be a plain put: two
     * plugins picking {@code total_players} left whichever enabled last owning
     * the slot, and the other one's placeholder silently started answering with
     * a number from the wrong plugin. It is still allowed — the flat registry is
     * the design — but it no longer happens without a word.
     *
     * @param name  the full name, already lower case
     * @param entry the registration
     */
    public static void register(String name, Entry entry) {
        BY_OWNER.computeIfAbsent(entry.owner(), owner -> new ConcurrentHashMap<>())
                .put(name, entry);
        OWNER_IDS.put(entry.owner().toLowerCase(java.util.Locale.ROOT), entry.owner());
        Entry previous = ENTRIES.put(name, entry);
        if (previous != null && !previous.owner().equals(entry.owner())
                && REPORTED_OVERWRITE.add(name)) {
            Loggers.get().warning("Placeholder %" + name + "% is registered by both "
                    + previous.owner() + " and " + entry.owner()
                    + ". Both still answer under their own PlaceholderAPI identifier;"
                    + " written bare as %" + name + "%, it is " + entry.owner()
                    + " that answers, because a name with no plugin in front of it can"
                    + " only mean one thing. Rename one of them to remove the ambiguity."
                    + " This is reported once per name.");
        }
        REPORTED.remove(name);
        // Registering late clears the complaint, so a plugin that loads after
        // the first render is not blamed forever.
        REPORTED_UNKNOWN.remove(name);
        // A new registration can change how existing text splits into name and
        // arguments, so compiled templates are no longer trustworthy.
        TemplateCache.invalidate();
    }

    /** Removes a placeholder, whoever registered it. */
    public static void unregister(String name) {
        BY_OWNER.values().forEach(owned -> owned.remove(name));
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
        Map<String, Entry> owned = BY_OWNER.remove(owner);
        OWNER_IDS.remove(owner.toLowerCase(java.util.Locale.ROOT));
        List<String> names = new ArrayList<>();
        ENTRIES.forEach((name, entry) -> {
            if (entry.owner().equals(owner)) {
                names.add(name);
            }
        });
        // A name this plugin had taken over goes back to whoever else still
        // registers it, rather than to nobody: the other plugin never stopped
        // offering it, and it was only hidden while this one was enabled.
        names.forEach(name -> {
            ENTRIES.remove(name);
            heir(name).ifPresent(entry -> ENTRIES.put(name, entry));
        });
        int removed = owned == null ? names.size() : owned.size();
        if (removed > 0) {
            TemplateCache.invalidate();
        }
        return removed;
    }

    /** Another plugin that still registers a name, if there is one. */
    private static java.util.Optional<Entry> heir(String name) {
        return BY_OWNER.values().stream()
                .map(owned -> owned.get(name))
                .filter(java.util.Objects::nonNull)
                .findFirst();
    }

    /** Returns whether a name is registered. */
    public static boolean has(String name) {
        return ENTRIES.containsKey(name);
    }

    /**
     * Returns whether anything answers a name, bare or written with its plugin
     * in front.
     *
     * <p>What the compiler asks while it decides where a name ends and its
     * arguments begin, so {@code %exyliaffa_stats_top_kills_1%} splits at
     * {@code stats_top} the way the bare spelling does.
     */
    public static boolean known(String name) {
        return ENTRIES.containsKey(name) || qualified(name) != null;
    }

    /**
     * Returns the registration a name means, bare or qualified.
     *
     * <p>The bare name first: a plugin that really registered
     * {@code exyliaffa_something} owns that spelling, whoever else it looks
     * like.
     */
    public static Entry entry(String name) {
        Entry bare = ENTRIES.get(name);
        return bare != null ? bare : qualified(name);
    }

    /**
     * Returns what a name written with its plugin in front means, or
     * {@code null}.
     *
     * <p>One name can only have one bare owner, so two plugins registering
     * {@code total_players} leave one of them unreachable from a config file:
     * PlaceholderAPI can still be asked for {@code %exyliasandbox_total_players%}
     * because the identifier says who is meant, but that route needs
     * PlaceholderAPI installed and a viewer to ask about. Reading the same
     * spelling here makes it an ordinary placeholder — the way a plugin's own
     * files disambiguate themselves without renaming anything.
     *
     * <p>Every underscore is tried as the boundary rather than only the first,
     * because a plugin name is free to contain one; the loop stops at the first
     * owner that really registered the rest.
     */
    private static Entry qualified(String name) {
        int split = name.indexOf('_');
        while (split > 0) {
            String owner = OWNER_IDS.get(name.substring(0, split));
            if (owner != null) {
                Map<String, Entry> owned = BY_OWNER.get(owner);
                Entry entry = owned == null ? null : owned.get(name.substring(split + 1));
                if (entry != null) {
                    return entry;
                }
            }
            split = name.indexOf('_', split + 1);
        }
        return null;
    }

    /** Returns the entry for a name, or {@code null}. */
    public static Entry get(String name) {
        return ENTRIES.get(name);
    }

    /**
     * Returns what one plugin registered under a name, or {@code null}.
     *
     * <p>What PlaceholderAPI asks: the identifier in
     * {@code %exyliaffa_total_players%} names the plugin, so the answer must
     * come from that plugin whether or not it holds the bare name.
     *
     * @param owner the plugin name
     * @param name  the placeholder name
     * @return the registration, or {@code null}
     */
    public static Entry get(String owner, String name) {
        Map<String, Entry> owned = BY_OWNER.get(owner);
        return owned == null ? null : owned.get(name);
    }

    /**
     * Resolves one plugin's placeholder, turning any failure into "no value".
     *
     * @param owner   the plugin being asked
     * @param name    the placeholder name
     * @param request the request to pass along
     * @param logger  where to report a failure
     * @return the value, or {@code null} when there is none or it failed
     */
    public static Object resolve(String owner, String name, Request request, Logger logger) {
        return resolve(get(owner, name), name, request, logger);
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
        return resolve(ENTRIES.get(name), name, request, logger);
    }

    /** Runs one registration, reporting a failure once. */
    public static Object resolve(Entry entry, String name, Request request, Logger logger) {
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

    /** Names already reported as unknown, so a typo is mentioned once, not per tick. */
    private static final Set<String> REPORTED_UNKNOWN = ConcurrentHashMap.newKeySet();

    /**
     * Reports a placeholder nobody could resolve.
     *
     * <p>An unresolved placeholder used to fail in silence: the text kept the
     * {@code %name%} and the only way to notice was a player seeing it in chat,
     * which is how {@code %class%} reached a live server. The name is mentioned
     * once, because this is reached from scoreboards that render every tick.
     *
     * @param name   the placeholder that resolved to nothing
     * @param logger where to report it
     */
    public static void reportUnknown(String name, Logger logger) {
        if (REPORTED_UNKNOWN.add(name)) {
            logger.warning("Placeholder %" + name + "% is not registered by any plugin"
                    + " and no value was given for it, so it is left as written."
                    + " Register it with Placeholders.register, or pass it per message"
                    + " with Text.with(\"%" + name + "%\", value)."
                    + " This is reported once per name.");
        }
    }

    /**
     * Whether a name has already been called unregistered.
     *
     * <p>A seam: this is reported once for the life of the JVM, so a wrong
     * report is not just noise — it also silences the right one later.
     */
    static boolean wasReportedUnknownForTests(String name) {
        return REPORTED_UNKNOWN.contains(name);
    }

    /** Returns every registered name. */
    public static Set<String> names() {
        return Set.copyOf(ENTRIES.keySet());
    }

    /**
     * Returns the names registered by one plugin.
     *
     * <p>Read from the per-owner view, so a plugin whose name another plugin
     * also registered still lists it: PlaceholderAPI shows what
     * {@code %exyliaffa_…%} answers, and it answers that.
     */
    public static Set<String> namesOf(String owner) {
        Map<String, Entry> owned = BY_OWNER.get(owner);
        return owned == null ? Set.of() : Set.copyOf(owned.keySet());
    }

    /** Returns how many placeholders are registered. */
    public static int size() {
        return ENTRIES.size();
    }

    /** Drops everything. Used on shutdown and by tests. */
    public static void clear() {
        ENTRIES.clear();
        BY_OWNER.clear();
        OWNER_IDS.clear();
        REPORTED.clear();
        REPORTED_UNKNOWN.clear();
        REPORTED_OVERWRITE.clear();
        TemplateCache.invalidate();
    }
}
