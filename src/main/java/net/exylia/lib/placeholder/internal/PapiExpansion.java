package net.exylia.lib.placeholder.internal;

import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.PlaceholderAPIPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.clip.placeholderapi.expansion.Relational;
import net.exylia.lib.placeholder.Request;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The PlaceholderAPI expansion generated for a plugin.
 *
 * <p>Every class in this file touches PlaceholderAPI types, which is why it is
 * separate from {@link PapiBridge}: the JVM only loads it once the plugin has
 * been confirmed present, so a server without PlaceholderAPI never sees a
 * missing class.
 *
 * <p>A plugin never writes an expansion by hand. Registering a placeholder with
 * ExyliaLib is enough for it to appear in PlaceholderAPI under the plugin's own
 * identifier, its name in lower case.
 *
 * <p>A plugin that asked for an alias with {@code Placeholders.identifier} gets
 * a second expansion over the same registrations: {@code %practice_stats_kills%}
 * and {@code %exyliapracticecore_stats_kills%} are two ways to write one
 * placeholder.
 */
final class PapiExpansion extends PlaceholderExpansion implements Relational {

    private final String identifier;
    /** The plugin's own name in lower case, whatever this expansion answers as. */
    private final String ownerId;
    private final String owner;
    private final String author;
    private final String version;

    /**
     * Compiled forms of the names this expansion has been asked for.
     *
     * <p>PlaceholderAPI asks for the same handful of names once per player per
     * scoreboard frame, and every one of them used to be split into name,
     * arguments, format and fallback from scratch. Splitting depends on which
     * names this plugin owns, so the cache belongs to the expansion rather than
     * to the shared {@link TemplateCache} — and it is dropped for the same
     * reason that one is, whenever a registration changes what a name splits
     * into.
     *
     * <p>Bounded and expiring: a config that writes a placeholder with a player
     * name in it generates a new key per player, and must not be able to grow
     * this without limit.
     */
    private final Cache<String, List<Part>> compiled = Caffeine.newBuilder()
            .maximumSize(2048)
            .expireAfterAccess(Duration.ofMinutes(10))
            .build();

    PapiExpansion(Plugin plugin) {
        this(plugin, plugin.getName());
    }

    @SuppressWarnings("deprecation") // getDescription() is the portable one; see below.
    PapiExpansion(Plugin plugin, String identifier) {
        this.owner = plugin.getName();
        this.ownerId = owner.toLowerCase(Locale.ROOT);
        this.identifier = identifier.toLowerCase(Locale.ROOT);
        // Paper prefers getPluginMeta(), which does not exist on Spigot. The
        // deprecated call is the one that works on every platform.
        List<String> authors = plugin.getDescription().getAuthors();
        this.author = authors == null || authors.isEmpty() ? "Exylia" : authors.get(0);
        this.version = plugin.getDescription().getVersion();
    }

    /**
     * Builds and registers an expansion of a plugin under one identifier.
     *
     * <p>PlaceholderAPI registers over an expansion that already holds the
     * identifier, silently unregistering it. That is right for the plugin's own
     * name, where the holder is a stale copy of this one, and wrong for an
     * alias, where it would be somebody else's expansion. So an alias only goes
     * in over nothing or over this plugin's own earlier alias.
     *
     * @return the expansion, or {@code null} when it was not registered
     */
    static Object create(Plugin plugin, String identifier) {
        PapiExpansion expansion = new PapiExpansion(plugin, identifier);
        if (!expansion.identifier.equals(expansion.ownerId)) {
            PlaceholderExpansion holder = PlaceholderAPIPlugin.getInstance()
                    .getLocalExpansionManager().getExpansion(expansion.identifier);
            if (holder != null && !(holder instanceof PapiExpansion papi
                    && papi.owner.equals(expansion.owner))) {
                return null;
            }
        }
        return expansion.register() ? expansion : null;
    }

    /** Drops a previously created expansion's compiled names. */
    static void invalidate(Object expansion) {
        if (expansion instanceof PapiExpansion papi) {
            papi.compiled.invalidateAll();
        }
    }

    /**
     * Unregisters a previously created expansion.
     *
     * <p>Only while it is still the one PlaceholderAPI holds: unregistering
     * removes whatever sits under the identifier, which may by now be another
     * plugin's expansion.
     */
    static void unregister(Object expansion) {
        if (expansion instanceof PapiExpansion papi && papi.isRegistered()) {
            papi.unregister();
        }
    }

    /** Runs PlaceholderAPI over a piece of text. */
    static String apply(Player player, String text) {
        return PlaceholderAPI.setPlaceholders(player, text);
    }

    @Override
    public String getIdentifier() {
        return identifier;
    }

    @Override
    public String getAuthor() {
        return author;
    }

    @Override
    public String getVersion() {
        return version;
    }

    @Override
    public boolean persist() {
        // ExyliaLib owns the lifetime of these placeholders, so the expansion
        // must survive a PlaceholderAPI reload rather than being dropped.
        return true;
    }

    @Override
    public List<String> getPlaceholders() {
        List<String> names = new ArrayList<>(Registry.namesOf(owner));
        names.sort(String::compareTo);
        return names;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        Player viewer = player != null ? player.getPlayer() : null;
        return resolve(viewer, player, params);
    }

    @Override
    public String onPlaceholderRequest(Player viewer, Player target, String params) {
        return resolve(viewer, target, params);
    }

    /**
     * Resolves one placeholder for PlaceholderAPI.
     *
     * <p>PlaceholderAPI hands over the text without its own identifier prefix,
     * and that identifier is this plugin: {@code %exyliaffa_total_players%}
     * arrives here as {@code total_players} and is a question addressed to
     * ExyliaFFA. So it is answered from what ExyliaFFA registered, never from
     * the flat by-name registry — another plugin registering the same name owns
     * the bare {@code %total_players%}, but it does not own this identifier and
     * must not answer under it.
     *
     * <p>That is the whole bug this shape exists for: with one flat slot,
     * whichever plugin enabled last answered for everybody, and every other
     * plugin's {@code %<its own name>_total_players%} went quiet.
     *
     * <p>Anything this plugin did not register comes back as {@code null},
     * which is how PlaceholderAPI is told to leave the text alone.
     */
    private String resolve(Player viewer, OfflinePlayer target, String params) {
        String answer = answer(viewer, target, params);
        if (answer != null) {
            return answer;
        }
        // A group whose prefix is already the plugin's own name registers
        // "exyliaevents_team_color", while PlaceholderAPI strips that same word
        // as the identifier and asks for "team_color". Without this the whole
        // group answered nothing, and writing %exyliaevents_exyliaevents_...%
        // is not what anybody has in their config. The plugin's name, not this
        // expansion's identifier: an alias does not change what the group is
        // called.
        return params.startsWith(ownerId + "_")
                ? null
                : answer(viewer, target, ownerId + "_" + params);
    }

    /** Resolves one name against what this plugin registered. */
    private String answer(Player viewer, OfflinePlayer target, String params) {
        String text = "%" + params + "%";

        // Compiled against this plugin's names only, so the longest-prefix rule
        // that splits a name from its arguments cannot settle on a name someone
        // else owns: "stats_top_kills_1" has to find this plugin's
        // "stats_top_kills", not another plugin's "stats_top".
        List<Part> parts = compiled.getIfPresent(params);
        if (parts == null) {
            parts = TemplateCompiler.compile(text, this::owns);
            compiled.put(params, parts);
        }
        StringBuilder answer = new StringBuilder(text.length() + 16);
        boolean answered = false;
        for (Part part : parts) {
            if (part.isLiteral()) {
                answer.append(part.literal());
                continue;
            }
            // No owned prefix matched, so the compiler kept the whole body as
            // the name. Not ours to answer.
            if (!owns(part.name())) {
                return null;
            }
            Object value = Registry.resolve(owner, part.name(),
                    new Request(viewer, target, part.args(), Map.of()), Loggers.get());
            if (value == null) {
                // A resolver that has no value for this player is not an
                // answer, unless the text said what to write instead.
                if (part.fallback() == null) {
                    return null;
                }
                answer.append(part.fallback());
            } else {
                answer.append(Formats.apply(value, part.format()));
            }
            answered = true;
        }
        return answered ? answer.toString() : null;
    }

    /** Whether this expansion's plugin is one of the plugins that registered a name. */
    private boolean owns(String name) {
        return Registry.get(owner, name) != null;
    }

}
