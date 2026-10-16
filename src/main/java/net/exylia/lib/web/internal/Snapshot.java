package net.exylia.lib.web.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the editor is told: on open, an index of the server and every Exylia plugin with the
 * subjects the sender may edit (metadata only); then, plugin by plugin, those subjects' schemas
 * and values, the enum lists their schemas share, and what the placeholders in their values read
 * as right now, so the preview can show {@code %player_name%} as a name.
 *
 * <p>Threads: the index and the subjects read from a file are built off the main thread; the
 * subjects whose values live in the plugin's memory ({@link Source#global()}) and the
 * placeholders are built on the global thread. Never carries a player's data beyond the name of
 * the admin who opened it, and never an address.
 */
final class Snapshot {

    static final int VERSION = 2;
    /** The most the editor takes per request, decompressed: a body over it is refused before it is sent. */
    static final int MAX_BYTES = 32 * 1024 * 1024;
    /** The most placeholders resolved for the preview; a file with more shows the rest as written. */
    static final int MAX_PLACEHOLDERS = 300;
    /**
     * Anything between two percent signs on one line, as the template compiler reads it: a name
     * may carry dots, colons, braces of a nested PlaceholderAPI placeholder, formats and spaces.
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("%[^%\\n]+%");
    static final Pattern EXYLIA = Pattern.compile("^Exylia[A-Za-z0-9_-]{1,48}$");

    private Snapshot() {
    }

    /** One subject's schema, values and hash, as the editor and the apply step both see them. */
    record Described(SubjectImpl subject, JsonObject values, String hash) {

        static Described of(SubjectImpl subject) {
            JsonObject values = subject.source().values(subject.plugin().getName());
            return new Described(subject, values, ValueCodec.hash(values));
        }
    }

    /** A plugin as the index lists it, read where the plugin manager may be asked. */
    record Listed(String name, String version) {
    }

    /** Every enabled Exylia plugin, editable or not, so the editor can say what it cannot edit. */
    static List<Plugin> exyliaPlugins() {
        List<Plugin> out = new ArrayList<>();
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            if (plugin.isEnabled() && EXYLIA.matcher(plugin.getName()).matches()) {
                out.add(plugin);
            }
        }
        return out;
    }

    static Listed listed(Plugin plugin) {
        // getDescription() is deprecated on Paper but is the one call that exists on Spigot too.
        return new Listed(plugin.getName(), plugin.getDescription().getVersion());
    }

    /** The session's opening body: the server, the issuer, and each plugin with its subjects' metadata. */
    static JsonObject index(String serverId, String libraryVersion, LibrarySettings settings, CommandSender issuer,
                            List<Listed> plugins, Map<String, List<SubjectImpl>> subjects) {
        JsonObject out = new JsonObject();
        out.addProperty("version", VERSION);
        out.add("server", server(serverId, libraryVersion, settings));
        out.add("issuer", issuer(issuer));
        JsonArray listed = new JsonArray();
        for (Listed plugin : plugins) {
            List<SubjectImpl> own = subjects.getOrDefault(plugin.name(), List.of());
            JsonObject entry = new JsonObject();
            entry.addProperty("name", plugin.name());
            entry.addProperty("version", plugin.version());
            entry.addProperty("editable", !own.isEmpty());
            JsonArray described = new JsonArray();
            own.forEach(subject -> described.add(meta(subject)));
            entry.add("subjects", described);
            listed.add(entry);
        }
        out.add("plugins", listed);
        return out;
    }

    private static JsonObject meta(SubjectImpl subject) {
        JsonObject entry = new JsonObject();
        entry.addProperty("key", subject.key());
        entry.addProperty("id", subject.id());
        entry.addProperty("title", subject.title());
        entry.addProperty("kind", subject.kind());
        entry.addProperty("group", subject.group());
        entry.addProperty("file", subject.source().name());
        JsonArray sensitive = new JsonArray();
        subject.sensitive().forEach(sensitive::add);
        entry.add("sensitive", sensitive);
        return entry;
    }

    /** One subject whole: its metadata, schema, values and hash. Reads the source. */
    static JsonObject subject(SubjectImpl subject) {
        Described item = Described.of(subject);
        JsonObject entry = meta(subject);
        entry.addProperty("plugin", subject.plugin().getName());
        entry.add("schema", subject.source().schema(subject.plugin().getName()));
        entry.add("values", item.values());
        entry.addProperty("hash", item.hash());
        return entry;
    }

    /** What every placeholder in these subjects' values reads as for the issuer. On the global thread. */
    static JsonObject placeholders(List<JsonObject> subjects, CommandSender issuer) {
        Set<String> tokens = new LinkedHashSet<>();
        subjects.forEach(subject -> collect(subject.get("values"), tokens));
        return resolve(tokens, issuer instanceof Player player ? player : null);
    }

    /** One plugin's upload: its subjects, the enum lists their schemas share, and the placeholders. */
    static JsonObject payload(List<JsonObject> subjects, JsonObject placeholders) {
        JsonObject enums = new JsonObject();
        JsonArray described = new JsonArray();
        for (JsonObject subject : subjects) {
            dedupe(subject.get("schema"), enums);
            described.add(subject);
        }
        JsonObject out = new JsonObject();
        out.add("subjects", described);
        out.add("enums", enums);
        out.add("placeholders", placeholders);
        return out;
    }

    /**
     * Moves the values of every enum node that names its class into {@code enums[class]}, once.
     * A class whose name another enum already took with different values keeps its own inline,
     * so two {@code Type} enums of different records never read each other's list.
     */
    static void dedupe(JsonElement node, JsonObject enums) {
        if (node == null || node.isJsonNull() || node.isJsonPrimitive()) {
            return;
        }
        if (node.isJsonArray()) {
            node.getAsJsonArray().forEach(item -> dedupe(item, enums));
            return;
        }
        JsonObject object = node.getAsJsonObject();
        JsonElement type = object.get("type");
        if (type != null && type.isJsonPrimitive() && "enum".equals(type.getAsString())
                && object.get("class") instanceof JsonPrimitive name && object.get("values") instanceof JsonArray values) {
            JsonElement known = enums.get(name.getAsString());
            if (known == null) {
                enums.add(name.getAsString(), values);
                object.remove("values");
            } else if (known.equals(values)) {
                object.remove("values");
            }
            return;
        }
        object.entrySet().forEach(entry -> dedupe(entry.getValue(), enums));
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
