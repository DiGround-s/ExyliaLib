package net.exylia.lib.web.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.exylia.lib.config.Time;
import net.exylia.lib.effect.Ticks;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.Duration;
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
 * every entry offers every key any entry uses; through a {@code list}, the next part is an index.
 *
 * <p>A record file's schema ({@code RecordJson}) has the same shape, with {@code enum} and
 * {@code duration} on top, so a record's maps and lists are walked and edited here too.
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
            if ("map".equals(kind) || "list".equals(kind)) {
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
            case "enum" -> type.has("class") && "Material".equals(type.get("class").getAsString())
                    ? ConfigFields.Kind.MATERIAL : ConfigFields.Kind.CHOICE;
            case "duration" -> ConfigFields.Kind.TIME;
            case "list" -> switch (name(type.getAsJsonObject("of"))) {
                case "string" -> ConfigFields.Kind.LINES;
                case "section" -> ConfigFields.Kind.ENTRIES;
                default -> ConfigFields.Kind.LOCKED;
            };
            default -> ConfigFields.Kind.LOCKED;
        };
    }

    /** Whether a description is a list, whose entries are positions rather than ids. */
    static boolean isList(@Nullable JsonObject type) {
        return "list".equals(name(type));
    }

    /** Whether every step of a path is a section: a place a record screen can draw on its own. */
    static boolean sections(JsonObject root, List<String> path) {
        JsonObject type = root;
        for (String part : path) {
            if (!"section".equals(name(type))) return false;
            JsonObject field = field(type, part);
            if (field == null) return false;
            type = field.getAsJsonObject("type");
        }
        return "section".equals(name(type));
    }

    private static @Nullable JsonObject field(JsonObject section, String key) {
        for (JsonElement element : section.getAsJsonArray("fields")) {
            JsonObject field = element.getAsJsonObject();
            if (field.get("key").getAsString().equals(key)) return field;
        }
        return null;
    }

    static String name(@Nullable JsonObject type) {
        return type == null || !type.has("type") ? "" : type.get("type").getAsString();
    }

    // ------------------------------------------------------------------ values

    /** What a path holds now, or {@code null} when nothing. */
    static @Nullable JsonElement value(JsonObject root, List<String> path) {
        JsonElement node = root;
        for (String part : path) {
            if (node == null) return null;
            node = at(node, part);
        }
        return node == null || node.isJsonNull() ? null : node;
    }

    /** What one key of a block, or one position of a list, holds. */
    private static @Nullable JsonElement at(JsonElement node, String part) {
        if (node.isJsonObject()) return node.getAsJsonObject().get(part);
        if (!node.isJsonArray()) return null;
        int index = index(part);
        return index >= 0 && index < node.getAsJsonArray().size() ? node.getAsJsonArray().get(index) : null;
    }

    private static int index(String part) {
        try {
            return Integer.parseInt(part);
        } catch (NumberFormatException notIndex) {
            return -1;
        }
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
        JsonElement node = copy;
        for (String part : path.subList(0, path.size() - 1)) {
            JsonElement next = at(node, part);
            if (next == null || !(next.isJsonObject() || next.isJsonArray())) {
                next = new JsonObject();
                put(node, part, next);
            }
            node = next;
        }
        String last = path.get(path.size() - 1);
        if (value == null) remove(node, last);
        else put(node, last, value.deepCopy());
        return copy;
    }

    private static void put(JsonElement node, String part, JsonElement value) {
        if (node.isJsonObject()) {
            node.getAsJsonObject().add(part, value);
            return;
        }
        JsonArray list = node.getAsJsonArray();
        int index = index(part);
        if (index >= 0 && index < list.size()) list.set(index, value);
        else if (index == list.size()) list.add(value);
    }

    private static void remove(JsonElement node, String part) {
        if (node.isJsonObject()) {
            node.getAsJsonObject().remove(part);
            return;
        }
        int index = index(part);
        if (node.isJsonArray() && index >= 0 && index < node.getAsJsonArray().size()) node.getAsJsonArray().remove(index);
    }

    /**
     * The values with an element put into a list at a position, the ones from there on moved one
     * down; past the end appends. The original is not changed.
     */
    static JsonObject inserted(JsonObject root, List<String> list, int index, JsonElement element) {
        JsonElement found = value(root, list);
        JsonArray now = found != null && found.isJsonArray() ? found.getAsJsonArray() : new JsonArray();
        JsonArray out = new JsonArray();
        int at = Math.max(0, Math.min(index, now.size()));
        for (int i = 0; i < now.size(); i++) {
            if (i == at) out.add(element.deepCopy());
            out.add(now.get(i).deepCopy());
        }
        if (at == now.size()) out.add(element.deepCopy());
        return with(root, list, out);
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

    /** The ids of a block's entries, in the file's order; a list's are its positions. */
    static List<String> ids(JsonObject root, List<String> map) {
        JsonElement block = value(root, map);
        if (block != null && block.isJsonArray()) {
            List<String> positions = new ArrayList<>();
            for (int i = 0; i < block.getAsJsonArray().size(); i++) positions.add(String.valueOf(i));
            return positions;
        }
        return block != null && block.isJsonObject() ? List.copyOf(block.getAsJsonObject().keySet()) : List.of();
    }

    /**
     * What a new value of this description starts as when nothing better is known: a block for a
     * section (a record's binder fills it with the record's defaults), the first choice, zero.
     */
    static JsonElement blank(@Nullable JsonObject type) {
        return switch (name(type)) {
            case "boolean" -> new JsonPrimitive(false);
            case "integer", "number" -> new JsonPrimitive(0);
            case "string" -> new JsonPrimitive("");
            case "duration" -> "written".equals(unitName(type)) ? new JsonPrimitive("0s") : new JsonPrimitive(0);
            case "enum" -> type.has("values") && !type.getAsJsonArray("values").isEmpty()
                    ? type.getAsJsonArray("values").get(0).deepCopy() : new JsonPrimitive("");
            case "list" -> new JsonArray();
            default -> new JsonObject();
        };
    }

    // ------------------------------------------------------------------ record types

    /**
     * The Java type a path of a record file holds, walked alongside its schema: a component by
     * the {@code name} its field carries, a map's value type, a list's element type.
     *
     * @param root   the file's record class
     * @param schema the file's schema
     * @param path   keys from the root; ids and positions where the schema has a map or a list
     * @return the type, or {@code null} when it cannot be followed
     */
    static @Nullable Type javaType(Class<?> root, JsonObject schema, List<String> path) {
        Type type = root;
        JsonObject node = schema;
        for (String part : path) {
            String kind = name(node);
            if ("section".equals(kind)) {
                JsonObject field = field(node, part);
                Class<?> owner = type instanceof Class<?> raw ? raw : null;
                if (field == null || owner == null || !owner.isRecord() || !field.has("name")) return null;
                RecordComponent found = null;
                for (RecordComponent component : owner.getRecordComponents()) {
                    if (component.getName().equals(field.get("name").getAsString())) found = component;
                }
                if (found == null) return null;
                type = found.getGenericType();
                node = field.getAsJsonObject("type");
            } else if (("map".equals(kind) || "list".equals(kind)) && type instanceof ParameterizedType generic) {
                Type[] arguments = generic.getActualTypeArguments();
                type = arguments[arguments.length - 1];
                node = node.getAsJsonObject("of");
            } else {
                return null;
            }
        }
        return type;
    }

    // ------------------------------------------------------------------ choices, times, materials

    /** The choice after (or before) the one held, wrapping, as the file writes it. */
    static JsonElement next(JsonObject type, @Nullable JsonElement current, int direction) {
        JsonArray values = type.has("values") ? type.getAsJsonArray("values") : new JsonArray();
        if (values.isEmpty()) return current == null ? new JsonPrimitive("") : current;
        String now = current == null ? "" : text(current);
        int at = 0;
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i).getAsString().equalsIgnoreCase(now)) at = i;
        }
        return values.get(Math.floorMod(at + direction, values.size())).deepCopy();
    }

    /** A material as the file writes it: the spelling its schema lists, its name when it lists none. */
    static JsonPrimitive material(JsonObject type, String picked) {
        String name = picked.trim().replace('-', '_');
        if (type.has("values")) {
            for (JsonElement value : type.getAsJsonArray("values")) {
                if (value.getAsString().replace('-', '_').equalsIgnoreCase(name)) return value.getAsJsonPrimitive();
            }
        }
        return new JsonPrimitive(picked.trim());
    }

    /** The length of time a duration value holds: written out ({@code 1m30s}), or a number in the key's unit. */
    static Duration duration(JsonObject type, @Nullable JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) return Duration.ZERO;
        Time.Unit unit = unit(type);
        if (value.getAsJsonPrimitive().isNumber()) {
            return Duration.ofMillis(Math.max(0, Math.round(value.getAsDouble() * (unit == null ? 1000 : unit.millis()))));
        }
        return Duration.ofMillis(Math.max(0, Ticks.toMillis(Ticks.parse(value.getAsString(), 0))));
    }

    /** A length of time as this key holds it in the file. */
    static JsonPrimitive time(JsonObject type, Duration duration) {
        Time.Unit unit = unit(type);
        if (unit == null) return new JsonPrimitive(Ticks.write(duration.toMillis()));
        double amount = duration.toMillis() / (double) unit.millis();
        return amount == Math.rint(amount) ? new JsonPrimitive((long) amount) : new JsonPrimitive(amount);
    }

    private static @Nullable Time.Unit unit(@Nullable JsonObject type) {
        String unit = unitName(type);
        if (unit.isEmpty() || unit.equals("written")) return null;
        try {
            return Time.Unit.valueOf(unit.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private static String unitName(@Nullable JsonObject type) {
        return type != null && type.has("unit") ? type.get("unit").getAsString() : "";
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
            case ENTRIES, SECTION -> value.isJsonObject() ? value.getAsJsonObject().size()
                    : value.isJsonArray() ? value.getAsJsonArray().size() : 0;
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
