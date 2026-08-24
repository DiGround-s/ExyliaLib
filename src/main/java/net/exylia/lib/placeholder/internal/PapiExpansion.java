package net.exylia.lib.placeholder.internal;

import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.clip.placeholderapi.expansion.Relational;
import net.exylia.lib.placeholder.Request;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

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
 * identifier.
 */
final class PapiExpansion extends PlaceholderExpansion implements Relational {

    private final String identifier;
    private final String owner;
    private final String author;
    private final String version;

    @SuppressWarnings("deprecation") // getDescription() is the portable one; see below.
    PapiExpansion(Plugin plugin) {
        this.owner = plugin.getName();
        this.identifier = owner.toLowerCase(Locale.ROOT);
        // Paper prefers getPluginMeta(), which does not exist on Spigot. The
        // deprecated call is the one that works on every platform.
        List<String> authors = plugin.getDescription().getAuthors();
        this.author = authors == null || authors.isEmpty() ? "Exylia" : authors.get(0);
        this.version = plugin.getDescription().getVersion();
    }

    /** Builds and registers an expansion for a plugin. */
    static Object create(Plugin plugin) {
        PapiExpansion expansion = new PapiExpansion(plugin);
        expansion.register();
        return expansion;
    }

    /** Unregisters a previously created expansion. */
    static void unregister(Object expansion) {
        if (expansion instanceof PapiExpansion papi) {
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
     * <p>PlaceholderAPI hands over the text without its own identifier prefix.
     * The registry stays keyed by the original plugin name for lifecycle cleanup,
     * while the expansion identifier is lower-case for PlaceholderAPI.
     *
     * <p>An expansion only answers for names its own plugin registered.
     * {@link Registry} is flat and keyed by name alone, so two plugins that pick
     * the same name share one slot: without this check
     * {@code %exyliaffa_total_players%} would run whichever plugin registered
     * last and hand back a real number from the wrong plugin. Anything not owned
     * here comes back as {@code null}, which is how PlaceholderAPI is told to
     * leave the text alone and let another expansion answer.
     */
    private String resolve(Player viewer, OfflinePlayer target, String params) {
        String text = "%" + params + "%";

        // Compiled against this plugin's names only, so the longest-prefix rule
        // that splits a name from its arguments cannot settle on a name someone
        // else owns: "stats_top_kills_1" has to find this plugin's
        // "stats_top_kills", not another plugin's "stats_top".
        List<Part> parts = TemplateCompiler.compile(text, this::owns);
        for (Part part : parts) {
            // No owned prefix matched, so the compiler kept the whole body as
            // the name. Not ours to answer.
            if (!part.isLiteral() && !owns(part.name())) {
                return null;
            }
        }

        String rendered = new CompiledTemplate(text, parts, Loggers.get())
                .renderFor(new Request(viewer, target, List.of(), Map.of()));
        // A placeholder this expansion does not know comes back unchanged, and
        // PlaceholderAPI expects null so it can leave the text alone.
        return rendered.equals(text) ? null : rendered;
    }

    /** Whether this expansion's plugin is the one that registered a name. */
    private boolean owns(String name) {
        Registry.Entry entry = Registry.get(name);
        return entry != null && entry.owner().equals(owner);
    }
}
