package net.exylia.lib.web.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.exylia.lib.config.internal.RecordJson;
import net.exylia.lib.internal.LibrarySettings;
import net.exylia.lib.placeholder.Placeholders;
import net.exylia.lib.platform.Platform;
import net.exylia.lib.text.Colors;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the editor is told when a session opens: the server, its palette, the Exylia plugins
 * installed, every subject with its schema and values, and what the placeholders in those
 * values read as right now, so the preview can show {@code %player_name%} as a name.
 *
 * <p>Built where the command runs, because placeholders resolve there; the upload is the
 * caller's to move off the thread. Never carries a player's data beyond the name of the admin
 * who opened it, and never an address.
 */
final class Snapshot {

    static final int VERSION = 1;
    /** A snapshot larger than this is refused before it is sent: nothing a config file should be. */
    static final int MAX_BYTES = 4 * 1024 * 1024;
    /** The most placeholders resolved for the preview; a file with more shows the rest as written. */
    static final int MAX_PLACEHOLDERS = 300;
    /**
     * Anything between two percent signs on one line, as the template compiler reads it: a name
     * may carry dots, colons, braces of a nested PlaceholderAPI placeholder, formats and spaces.
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("%[^%\\n]+%");
    private static final Pattern EXYLIA = Pattern.compile("^Exylia[A-Za-z0-9_-]{1,48}$");

    private Snapshot() {
    }

    /** One subject's schema, values and hash, as the editor and the apply step both see them. */
    record Described(SubjectImpl subject, JsonObject values, String hash) {

        static Described of(SubjectImpl subject) {
            JsonObject values = ValueCodec.toJson(subject.type(), subject.plugin().getName(), subject.file().get());
            return new Described(subject, values, ValueCodec.hash(values));
        }
    }

    static JsonObject build(String serverId, String libraryVersion, LibrarySettings settings,
                            CommandSender issuer, List<SubjectImpl> subjects, Set<String> editablePlugins) {
        JsonObject out = new JsonObject();
        out.addProperty("version", VERSION);
        out.add("server", server(serverId, libraryVersion, settings));
        out.add("issuer", issuer(issuer));
        out.add("plugins", plugins(editablePlugins));

        JsonArray described = new JsonArray();
        Set<String> tokens = new LinkedHashSet<>();
        for (SubjectImpl subject : subjects) {
            Described item = Described.of(subject);
            JsonObject entry = new JsonObject();
            entry.addProperty("key", subject.key());
            entry.addProperty("plugin", subject.plugin().getName());
            entry.addProperty("id", subject.id());
            entry.addProperty("title", subject.title());
            entry.addProperty("kind", subject.kind());
            entry.addProperty("file", subject.file().name());
            JsonArray sensitive = new JsonArray();
            subject.sensitive().forEach(sensitive::add);
            entry.add("sensitive", sensitive);
            entry.add("schema", RecordJson.schema(subject.type(), subject.plugin().getName()));
            entry.add("values", item.values());
            entry.addProperty("hash", item.hash());
            described.add(entry);
            collect(item.values(), tokens);
        }
        out.add("subjects", described);
        out.add("placeholders", resolve(tokens, issuer instanceof Player player ? player : null));
        return out;
    }

    private static JsonObject server(String serverId, String libraryVersion, LibrarySettings settings) {
        JsonObject server = new JsonObject();
        server.addProperty("id", serverId);
        server.addProperty("platform", Platform.current().name().toLowerCase(Locale.ROOT));
        server.addProperty("software", Bukkit.getName() + " " + Bukkit.getVersion());
        server.addProperty("bukkit", Bukkit.getBukkitVersion());
        server.addProperty("lib", libraryVersion);
        server.addProperty("language", settings.language());
        server.addProperty("smallText", settings.smallText());
        server.add("palette", palette());
        return server;
    }

    static JsonObject palette() {
        JsonObject palette = new JsonObject();
        for (String token : Colors.names()) {
            TextColor color = Colors.get(token);
            if (color != null) {
                palette.addProperty(token, color.asHexString());
            }
        }
        return palette;
    }

    private static JsonObject issuer(CommandSender sender) {
        JsonObject issuer = new JsonObject();
        issuer.addProperty("name", sender.getName());
        issuer.addProperty("player", sender instanceof Player player ? player.getUniqueId().toString() : null);
        return issuer;
    }

    /** Every enabled Exylia plugin, editable or not, so the editor can say what it cannot edit yet. */
    private static JsonArray plugins(Set<String> editable) {
        Map<String, JsonObject> found = new LinkedHashMap<>();
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            String name = plugin.getName();
            if (!plugin.isEnabled() || !EXYLIA.matcher(name).matches()) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("name", name);
            // getDescription() is deprecated on Paper but is the one call that exists on Spigot too.
            entry.addProperty("version", plugin.getDescription().getVersion());
            entry.addProperty("editable", editable.contains(name));
            found.put(name, entry);
        }
        JsonArray out = new JsonArray();
        found.values().forEach(out::add);
        return out;
    }

    static void collect(@Nullable JsonElement element, Set<String> into) {
        if (element == null || element.isJsonNull() || into.size() >= MAX_PLACEHOLDERS) {
            return;
        }
        if (element.isJsonPrimitive()) {
            if (element.getAsJsonPrimitive().isString()) {
                Matcher matcher = PLACEHOLDER.matcher(element.getAsString());
                while (matcher.find() && into.size() < MAX_PLACEHOLDERS) {
                    into.add(matcher.group());
                }
            }
            return;
        }
        if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(item -> collect(item, into));
            return;
        }
        element.getAsJsonObject().entrySet().forEach(entry -> collect(entry.getValue(), into));
    }

    /** What each placeholder reads as for the admin; one that stays as written is left out. */
    private static JsonObject resolve(Set<String> tokens, @Nullable Player viewer) {
        JsonObject out = new JsonObject();
        for (String token : tokens) {
            try {
                String value = Placeholders.apply(token, viewer);
                if (!value.equals(token)) {
                    out.addProperty(token, value);
                }
            } catch (RuntimeException failure) {
                // A placeholder that throws is that plugin's bug; the preview shows it as written.
            }
        }
        return out;
    }
}
