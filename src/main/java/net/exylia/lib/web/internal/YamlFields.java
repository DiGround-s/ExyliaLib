package net.exylia.lib.web.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What the in-game screens know about a YAML file a plugin compiles itself: the schema
 * {@link YamlSource} infers from it, walked by path, and the values it holds, changed one key at
 * a time. Pure: no menu, no thread, no file, so it is tested on its own.
 *
 * <p>A path is the list of keys from the root. Through a block of named entries (a {@code map}),
 * the next part is an entry's id and the type below it is the entries' merged description, so
 * every entry offers every key any entry uses.
 */
final class YamlFields {

    /** Joins a path into one context value; no YAML key holds it. */
    static final String SEPARATOR = "\u001F";

    /** What a new entry may be called. */
    static final Pattern ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    /**
     * One key of a section.
     *
     * @param key      the key as written
     * @param comments the lines written above it
     * @param type     its inferred description
     * @param kind     how it is edited
     */
    record Node(String key, List<String> comments, JsonObject type, ConfigFields.Kind kind) {
    }

    private YamlFields() {
    }

    // ------------------------------------------------------------------ paths

    static String join(List<String> path) {
        return String.join(SEPARATOR, path);
    }

    static List<String> split(String joined) {
        return joined.isEmpty() ? List.of() : List.of(joined.split(SEPARATOR, -1));
    }

    static List<String> child(List<String> path, String key) {
        List<String> out = new ArrayList<>(path);
        out.add(key);
        return List.copyOf(out);
    }

    static List<String> parent(List<String> path) {
        return path.isEmpty() ? path : List.copyOf(path.subList(0, path.size() - 1));
    }

    // ------------------------------------------------------------------ shape

    /**
     * The description of what a path holds.
     *
     * @param root the file's schema, a {@code section}
     * @param path keys from the root
     * @return its type, or {@code null} when the schema has no such place
     */
    static @Nullable JsonObject type(JsonObject root, List<String> path) {
        JsonObject type = root;
        for (String part : path) {
            String kind = name(type);
            if ("map".equals(kind)) {
                type = type.getAsJsonObject("of");
            } else if ("section".equals(kind)) {
                JsonObject found = null;
                for (JsonElement element : type.getAsJsonArray("fields")) {
                    JsonObject field = element.getAsJsonObject();
                    if (field.get("key").getAsString().equals(part)) found = field.getAsJsonObject("type");
                }
                type = found;
            } else {
                return null;
            }
            if (type == null) return null;
        }
        return type;
    }

    /** The keys of a section's description, in the file's order; empty for anything else. */
    static List<Node> fields(@Nullable JsonObject section) {
        if (section == null || !"section".equals(name(section))) return List.of();
        List<Node> out = new ArrayList<>();
        for (JsonElement element : section.getAsJsonArray("fields")) {
            JsonObject field = element.getAsJsonObject();
            List<String> comments = new ArrayList<>();
            if (field.has("comments")) field.getAsJsonArray("comments").forEach(line -> comments.add(line.getAsString()));
            JsonObject type = field.getAsJsonObject("type");
            out.add(new Node(field.get("key").getAsString(), comments, type, kind(type)));
        }
        return out;
    }

    /** How a value of this description is edited. */
    static ConfigFields.Kind kind(@Nullable JsonObject type) {
        return switch (name(type)) {
            case "section" -> ConfigFields.Kind.SECTION;
            case "map" -> ConfigFields.Kind.ENTRIES;
            case "boolean" -> ConfigFields.Kind.TOGGLE;
            case "integer" -> ConfigFields.Kind.INTEGER;
            case "number" -> ConfigFields.Kind.DECIMAL;
            case "string" -> ConfigFields.Kind.TEXT;
            case "list" -> "string".equals(name(type.getAsJsonObject("of")))
                    ? ConfigFields.Kind.LINES : ConfigFields.Kind.LOCKED;
            default -> ConfigFields.Kind.LOCKED;
        };
    }

    private static String name(@Nullable JsonObject type) {
        return type == null || !type.has("type") ? "" : type.get("type").getAsString();
    }

    // ------------------------------------------------------------------ values

    /** What a path holds now, or {@code null} when nothing. */
    static @Nullable JsonElement value(JsonObject root, List<String> path) {
        JsonElement node = root;
        for (String part : path) {
            if (node == null || !node.isJsonObject()) return null;
            node = node.getAsJsonObject().get(part);
        }
        return node == null || node.isJsonNull() ? null : node;
    }

    /**
     * The values with one path set, the rest untouched; the original is not changed.
     *
     * @param root  what the file holds
     * @param path  where; blocks on the way are created
     * @param value what to hold there; {@code null} removes the key
     * @return a changed copy
     */
    static JsonObject with(JsonObject root, List<String> path, @Nullable JsonElement value) {
        JsonObject copy = root.deepCopy();
        if (path.isEmpty()) return value != null && value.isJsonObject() ? value.getAsJsonObject().deepCopy() : copy;
        JsonObject node = copy;
        for (String part : path.subList(0, path.size() - 1)) {
            JsonElement next = node.get(part);
            if (next == null || !next.isJsonObject()) {
                next = new JsonObject();
                node.add(part, next);
            }
            node = next.getAsJsonObject();
        }
        String last = path.get(path.size() - 1);
        if (value == null) node.remove(last);
        else node.add(last, value.deepCopy());
        return copy;
    }

    /**
     * The values with an entry added after the others: a copy of {@code from}, or empty.
     *
     * @param root  what the file holds
     * @param map   the block of entries
     * @param id    the new entry's id
     * @param from  the entry it starts as; {@code null} for the block's first, or empty when none
     * @return a changed copy
     */
    static JsonObject added(JsonObject root, List<String> map, String id, @Nullable String from) {
        JsonElement block = value(root, map);
        JsonObject entries = block != null && block.isJsonObject() ? block.getAsJsonObject() : new JsonObject();
        JsonElement sample = from != null ? entries.get(from)
                : entries.entrySet().stream().map(java.util.Map.Entry::getValue).findFirst().orElse(null);
        return with(root, child(map, id), sample != null && sample.isJsonObject() ? sample : new JsonObject());
    }

    /** The ids of a block's entries, in the file's order. */
    static List<String> ids(JsonObject root, List<String> map) {
        JsonElement block = value(root, map);
        return block != null && block.isJsonObject() ? List.copyOf(block.getAsJsonObject().keySet()) : List.of();
    }

    /** An id for a copy of {@code base} that the block does not use yet. */
    static String freeId(List<String> taken, String base) {
        String id = base + "-copy";
        for (int n = 2; taken.contains(id); n++) id = base + "-copy-" + n;
        return id;
    }

    /** A value as the screens show it: booleans, numbers, text, lines and entry counts. */
    static @Nullable Object plain(ConfigFields.Kind kind, @Nullable JsonElement value) {
        if (value == null) return null;
        return switch (kind) {
            case ENTRIES, SECTION -> value.isJsonObject() ? value.getAsJsonObject().size() : 0;
            case LINES -> {
                List<String> lines = new ArrayList<>();
                if (value.isJsonArray()) value.getAsJsonArray().forEach(line -> lines.add(text(line)));
                yield lines;
            }
            case TOGGLE -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean() : null;
            case INTEGER, DECIMAL -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() ? value.getAsNumber() : null;
            default -> text(value);
        };
    }

    private static String text(JsonElement value) {
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

    /** Lines as the file holds them. */
    static JsonArray lines(List<String> lines) {
        JsonArray out = new JsonArray();
        lines.forEach(line -> out.add(new JsonPrimitive(line)));
        return out;
    }
}
