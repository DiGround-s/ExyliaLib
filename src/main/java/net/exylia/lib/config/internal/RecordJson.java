package net.exylia.lib.config.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.exylia.lib.config.Comment;
import net.exylia.lib.config.ConfigIssue;
import net.exylia.lib.config.Time;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A config record as JSON: its schema, so a browser can draw a form for a file it has never
 * seen, and its values, both ways, through the same binder the YAML file goes through.
 *
 * <p>The bridge the web editor stands on. It lives here, next to {@link SchemaNode}, because
 * the public {@code Schema} drops the value side of a map and a file of holograms is a map;
 * walking the analysed node is the only way to describe it, and the node stays in this package.
 *
 * <h2>Schema</h2>
 * <pre>
 * {"type":"section","comments":[...],"fields":[
 *   {"key":"view-distance","name":"viewDistance","comments":[...],"type":{"type":"number"}},
 *   {"key":"lines","type":{"type":"list","of":{"type":"string"}}},
 *   {"key":"holograms","type":{"type":"map","of":{"type":"section","fields":[...]}}}
 * ]}
 * </pre>
 * Value kinds: {@code string}, {@code boolean}, {@code integer}, {@code number},
 * {@code duration} (with the {@code unit} a bare number is read in, or {@code written} for a
 * {@link Duration}), {@code enum} (with its {@code values} as the file writes them),
 * {@code list}, {@code map}, {@code section}, and {@code any} for a type only shown as text.
 * A section that is one of the library's records carries its {@code class}, relative to
 * {@code net.exylia.lib} ({@code hologram.HologramConfig.Properties}).
 *
 * <h2>Values</h2>
 * {@link #write} renders the record into an in-memory configuration exactly as it would be
 * into the file, and that tree becomes JSON. {@link #read} pours JSON into a tree and binds it,
 * so a change from the browser gets the same coercions, the same fallback for a key left out,
 * the same pruning of a key the record does not know, and the same issues for a value of the
 * wrong shape. There is one way to read a config, and the browser is not a second one.
 *
 * @since 1.304.0
 */
public final class RecordJson {

    /** Deeper than this and a record is referring to itself; the editor shows text. */
    private static final int MAX_DEPTH = 8;
    private static final String LIBRARY = "net.exylia.lib.";

    private RecordJson() {
    }

    /** The schema of a record declared by a plugin. */
    public static JsonObject schema(Class<?> type, String pluginName) {
        return section(SchemaCache.of(type, pluginName), 0);
    }

    /** A record's values, keyed as the file writes them. */
    public static JsonObject write(Class<?> type, String pluginName, Object record) {
        MemoryConfiguration tree = new MemoryConfiguration();
        Binder.write(tree, SchemaCache.of(type, pluginName), record, record, "");
        return section(tree);
    }

    /**
     * A record from values shaped as {@link #write} shapes them.
     *
     * @param current what the file holds now: the fallback for a key the values leave out
     * @param file    the file's name, for the issues
     * @param issues  collects what was wrong; a {@code MISSING_KEY} or {@code UNKNOWN_KEY} is
     *                not a reason to refuse, anything else is
     */
    public static <T> T read(Class<?> type, String pluginName, T current, JsonObject values, String file,
                             List<ConfigIssue> issues) {
        MemoryConfiguration tree = new MemoryConfiguration();
        pour(tree, values);
        return Binder.read(tree, SchemaCache.of(type, pluginName), current, "", file, issues);
    }

    // ------------------------------------------------------------------
    // Schema
    // ------------------------------------------------------------------

    private static JsonObject section(SchemaNode node, int depth) {
        JsonObject out = new JsonObject();
        out.addProperty("type", "section");
        libraryType(out, node.type());
        out.add("comments", strings(node.comments()));
        JsonArray fields = new JsonArray();
        for (SchemaNode.SchemaComponent component : node.components()) {
            JsonObject field = new JsonObject();
            field.addProperty("key", component.key());
            field.addProperty("name", component.name());
            field.add("comments", strings(component.comments()));
            if (component.isSection()) {
                field.add("type", section(component.nested(), depth + 1));
            } else if (component.isMap()) {
                JsonObject map = new JsonObject();
                map.addProperty("type", "map");
                SchemaNode.MapEntry entry = component.map();
                map.add("of", entry.nested() != null
                        ? section(entry.nested(), depth + 1)
                        : value(entry.type(), entry.generic(), null, depth + 1));
                field.add("type", map);
            } else {
                field.add("type", value(component.type(), component.generic(), component.unit(), depth + 1));
            }
            fields.add(field);
        }
        out.add("fields", fields);
        return out;
    }

    /** A value type, from the erased class and, for a list, its generic form. */
    private static JsonObject value(Class<?> type, Type generic, Time.Unit unit, int depth) {
        JsonObject out = new JsonObject();
        if (unit != null) {
            out.addProperty("type", "duration");
            out.addProperty("unit", unit.name().toLowerCase(Locale.ROOT));
            return out;
        }
        if (type == Duration.class) {
            out.addProperty("type", "duration");
            out.addProperty("unit", "written");
            return out;
        }
        if (type == String.class || type == Character.class || type == char.class) {
            out.addProperty("type", "string");
        } else if (type == boolean.class || type == Boolean.class) {
            out.addProperty("type", "boolean");
        } else if (type == int.class || type == Integer.class || type == long.class || type == Long.class
                || type == short.class || type == Short.class || type == byte.class || type == Byte.class) {
            out.addProperty("type", "integer");
        } else if (type == double.class || type == Double.class || type == float.class || type == Float.class) {
            out.addProperty("type", "number");
        } else if (type.isEnum()) {
            out.addProperty("type", "enum");
            // Lets the editor tell a block or item picker from a short list of choices.
            out.addProperty("class", type.getSimpleName());
            JsonArray values = new JsonArray();
            for (Object constant : type.getEnumConstants()) {
                values.add(((Enum<?>) constant).name().toLowerCase(Locale.ROOT).replace('_', '-'));
            }
            out.add("values", values);
        } else if (List.class.isAssignableFrom(type)) {
            out.addProperty("type", "list");
            Type element = argument(generic, 0);
            out.add("of", value(erase(element), element, null, depth + 1));
        } else if (Map.class.isAssignableFrom(type)) {
            out.addProperty("type", "map");
            Type element = argument(generic, 1);
            out.add("of", value(erase(element), element, null, depth + 1));
        } else if (type.isRecord() && depth <= MAX_DEPTH) {
            // A record inside a list is not a schema node (it needs no defaults), so it is
            // described from its components, the same way the binder writes it.
            return record(type, depth);
        } else {
            out.addProperty("type", "any");
            out.addProperty("class", type.getSimpleName());
        }
        return out;
    }

    private static JsonObject record(Class<?> type, int depth) {
        JsonObject out = new JsonObject();
        out.addProperty("type", "section");
        libraryType(out, type);
        out.add("comments", strings(List.of()));
        JsonArray fields = new JsonArray();
        for (RecordComponent component : type.getRecordComponents()) {
            JsonObject field = new JsonObject();
            field.addProperty("key", SchemaCache.keyOf(component));
            field.addProperty("name", component.getName());
            JsonArray comments = new JsonArray();
            for (Comment comment : component.getAnnotationsByType(Comment.class)) {
                comments.add(comment.value());
            }
            field.add("comments", comments);
            field.add("type", value(component.getType(), component.getGenericType(),
                    SchemaCache.unitOf(component), depth + 1));
            fields.add(field);
        }
        out.add("fields", fields);
        return out;
    }

    /**
     * Names a section that is one of the library's own records ({@code hologram.HologramConfig.Properties}),
     * so the editor draws it with that type's screen in whichever plugin's file it appears.
     * A plugin's own records stay unnamed: only the library's types mean the same everywhere.
     */
    private static void libraryType(JsonObject out, Class<?> type) {
        String name = type.getCanonicalName();
        if (name != null && name.startsWith(LIBRARY)) {
            out.addProperty("class", name.substring(LIBRARY.length()));
        }
    }

    private static Type argument(Type generic, int index) {
        if (generic instanceof ParameterizedType parameterized) {
            Type[] arguments = parameterized.getActualTypeArguments();
            if (index < arguments.length) {
                return arguments[index];
            }
        }
        return Object.class;
    }

    private static Class<?> erase(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw) {
            return raw;
        }
        return Object.class;
    }

    private static JsonArray strings(List<String> lines) {
        JsonArray out = new JsonArray();
        for (String line : lines) {
            out.add(line);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Tree <-> JSON
    // ------------------------------------------------------------------

    private static JsonObject section(ConfigurationSection section) {
        JsonObject out = new JsonObject();
        for (String key : section.getKeys(false)) {
            out.add(key, element(section.get(key)));
        }
        return out;
    }

    private static JsonElement element(Object raw) {
        if (raw == null) {
            return JsonNull.INSTANCE;
        }
        if (raw instanceof ConfigurationSection section) {
            return section(section);
        }
        if (raw instanceof Map<?, ?> map) {
            JsonObject out = new JsonObject();
            map.forEach((key, value) -> out.add(String.valueOf(key), element(value)));
            return out;
        }
        if (raw instanceof Iterable<?> list) {
            JsonArray out = new JsonArray();
            for (Object item : list) {
                out.add(element(item));
            }
            return out;
        }
        if (raw instanceof Boolean flag) {
            return new JsonPrimitive(flag);
        }
        if (raw instanceof Number number) {
            return new JsonPrimitive(number);
        }
        if (raw instanceof Enum<?> constant) {
            return new JsonPrimitive(constant.name().toLowerCase(Locale.ROOT).replace('_', '-'));
        }
        return new JsonPrimitive(String.valueOf(raw));
    }

    private static void pour(ConfigurationSection into, JsonObject values) {
        for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
            JsonElement value = entry.getValue();
            if (value == null || value.isJsonNull()) {
                continue;
            }
            if (value.isJsonObject()) {
                pour(into.createSection(entry.getKey()), value.getAsJsonObject());
            } else {
                into.set(entry.getKey(), plain(value));
            }
        }
    }

    /** A JSON value as the Java value the binder reads from a YAML tree. */
    private static Object plain(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (value.isJsonObject()) {
            // A block inside a list: the binder reads a record element from a map.
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
                Object item = plain(entry.getValue());
                if (item != null) {
                    map.put(entry.getKey(), item);
                }
            }
            return map;
        }
        if (value.isJsonArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonElement item : value.getAsJsonArray()) {
                Object converted = plain(item);
                if (converted != null) {
                    list.add(converted);
                }
            }
            return list;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isNumber()) {
            String text = primitive.getAsString();
            if (text.matches("-?\\d{1,18}")) {
                return Long.parseLong(text);
            }
            return primitive.getAsDouble();
        }
        return primitive.getAsString();
    }
}
