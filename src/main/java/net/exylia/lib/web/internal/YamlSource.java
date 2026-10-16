package net.exylia.lib.web.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A YAML file the plugin reads itself, such as a catalogue of cosmetics or a menu: no record
 * describes it, so the schema is inferred from what the file holds, and the plugin's own
 * reader is the trust boundary ({@link #check}).
 *
 * <p>Inference: a block whose children are all blocks is a map of named entries, and the
 * entries' keys are merged, so the browser offers every key any entry uses when one is added;
 * a key whose type differs between entries is {@code any}. Comments above a key become its
 * help text and survive the write, as do the header and footer.
 *
 * <p>Read from disk on every snapshot and apply, on the thread the admin's command runs on, as
 * the plugin's own reload would; these are files an admin edits by hand, measured in
 * kilobytes.
 */
final class YamlSource implements Source {

    /** Keys may hold dots (an id like {@code v1.2}), so paths are split on a character no key uses. */
    private static final char SEPARATOR = '\u001F';

    private final File file;
    private final String name;
    private volatile Function<ConfigurationSection, List<String>> check = yaml -> List.of();

    YamlSource(File file, String name) {
        this.file = file;
        this.name = name;
    }

    void check(Function<ConfigurationSection, List<String>> check) {
        this.check = check;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public JsonObject schema(String pluginName) {
        YamlConfiguration yaml = read();
        JsonObject out = section(toJson(yaml), yaml, "");
        out.add("comments", strings(yaml.options().getHeader()));
        return out;
    }

    @Override
    public JsonObject values(String pluginName) {
        return toJson(read());
    }

    @Override
    public Prepared prepare(String pluginName, JsonObject values) {
        YamlConfiguration current = read();
        YamlConfiguration next = blank();
        next.options().setHeader(current.options().getHeader());
        next.options().setFooter(current.options().getFooter());
        write(values, next);
        for (String path : next.getKeys(true)) {
            if (current.contains(path)) {
                next.setComments(path, current.getComments(path));
                next.setInlineComments(path, current.getInlineComments(path));
            }
        }
        List<String> problems;
        try {
            // The plugin reads the text about to be written, with the separator its loader uses.
            YamlConfiguration asWritten = new YamlConfiguration();
            asWritten.loadFromString(next.saveToString());
            problems = check.apply(asWritten);
        } catch (InvalidConfigurationException failure) {
            problems = List.of(failure.getMessage());
        } catch (RuntimeException failure) {
            problems = List.of(failure.toString());
        }
        if (problems != null && !problems.isEmpty()) {
            return Prepared.refused(problems.get(0));
        }
        return Prepared.now(toJson(next), () -> save(next));
    }

    // ------------------------------------------------------------------
    // File
    // ------------------------------------------------------------------

    private static YamlConfiguration blank() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().pathSeparator(SEPARATOR).parseComments(true);
        // SnakeYAML folds any string past 80 columns onto several lines, which is most lore.
        yaml.options().width(Integer.MAX_VALUE);
        return yaml;
    }

    YamlConfiguration read() {
        YamlConfiguration yaml = blank();
        if (!file.isFile()) {
            return yaml;
        }
        try {
            yaml.loadFromString(Files.readString(file.toPath(), StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException failure) {
            throw new IllegalStateException(name + ".yml could not be read: " + failure.getMessage(), failure);
        }
        return yaml;
    }

    private void save(YamlConfiguration yaml) {
        try {
            Path target = file.toPath();
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), file.getName(), ".tmp");
            Files.writeString(temporary, yaml.saveToString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new IllegalStateException(name + ".yml could not be written: " + failure.getMessage(), failure);
        }
    }

    // ------------------------------------------------------------------
    // YAML <-> JSON
    // ------------------------------------------------------------------

    static JsonObject toJson(ConfigurationSection section) {
        JsonObject out = new JsonObject();
        for (String key : section.getKeys(false)) {
            out.add(key, toJson(section.get(key)));
        }
        return out;
    }

    private static JsonElement toJson(@Nullable Object value) {
        if (value == null) {
            return JsonNull.INSTANCE;
        }
        if (value instanceof ConfigurationSection section) {
            return toJson(section);
        }
        if (value instanceof Map<?, ?> map) {
            JsonObject out = new JsonObject();
            map.forEach((key, item) -> out.add(String.valueOf(key), toJson(item)));
            return out;
        }
        if (value instanceof List<?> list) {
            JsonArray out = new JsonArray();
            list.forEach(item -> out.add(toJson(item)));
            return out;
        }
        if (value instanceof Boolean bool) {
            return new JsonPrimitive(bool);
        }
        if (value instanceof Number number) {
            return new JsonPrimitive(number);
        }
        if (value instanceof ConfigurationSerializable serializable) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put(ConfigurationSerialization.SERIALIZED_TYPE_KEY,
                    ConfigurationSerialization.getAlias(serializable.getClass()));
            map.putAll(serializable.serialize());
            return toJson(map);
        }
        return new JsonPrimitive(String.valueOf(value));
    }

    private static void write(JsonObject values, ConfigurationSection into) {
        for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
            JsonElement value = entry.getValue();
            if (value == null || value.isJsonNull()) {
                continue;
            }
            if (value.isJsonObject()) {
                write(value.getAsJsonObject(), into.createSection(entry.getKey()));
            } else {
                into.set(entry.getKey(), plain(value));
            }
        }
    }

    /** A JSON value as SnakeYAML writes it: maps, lists, strings, booleans, whole or decimal numbers. */
    private static @Nullable Object plain(JsonElement value) {
        if (value.isJsonNull()) {
            return null;
        }
        if (value.isJsonObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            value.getAsJsonObject().entrySet().forEach(entry -> {
                Object item = plain(entry.getValue());
                if (item != null) {
                    map.put(entry.getKey(), item);
                }
            });
            return map;
        }
        if (value.isJsonArray()) {
            List<Object> list = new ArrayList<>();
            value.getAsJsonArray().forEach(item -> {
                Object plain = plain(item);
                if (plain != null) {
                    list.add(plain);
                }
            });
            return list;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isNumber()) {
            BigDecimal number = primitive.getAsBigDecimal();
            if (number.signum() == 0 || number.stripTrailingZeros().scale() <= 0) {
                try {
                    long whole = number.longValueExact();
                    return whole == (int) whole ? (Object) (int) whole : (Object) whole;
                } catch (ArithmeticException tooLarge) {
                    return number.doubleValue();
                }
            }
            return number.doubleValue();
        }
        return primitive.getAsString();
    }

    // ------------------------------------------------------------------
    // Schema inference
    // ------------------------------------------------------------------

    private static JsonObject section(JsonObject values, YamlConfiguration yaml, String prefix) {
        JsonObject out = new JsonObject();
        out.addProperty("type", "section");
        out.add("comments", new JsonArray());
        JsonArray fields = new JsonArray();
        for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
            String path = prefix.isEmpty() ? entry.getKey() : prefix + SEPARATOR + entry.getKey();
            JsonObject field = new JsonObject();
            field.addProperty("key", entry.getKey());
            field.add("comments", strings(yaml.getComments(path)));
            field.add("type", infer(entry.getValue(), yaml, path));
            fields.add(field);
        }
        out.add("fields", fields);
        return out;
    }

    private static JsonObject infer(JsonElement value, YamlConfiguration yaml, String path) {
        JsonObject out = new JsonObject();
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            boolean entries = !object.isEmpty() && object.entrySet().stream().allMatch(item -> item.getValue().isJsonObject());
            if (!entries) {
                return section(object, yaml, path);
            }
            JsonObject of = null;
            for (Map.Entry<String, JsonElement> item : object.entrySet()) {
                JsonObject described = section(item.getValue().getAsJsonObject(), yaml, path + SEPARATOR + item.getKey());
                of = of == null ? described : merge(of, described);
            }
            out.addProperty("type", "map");
            out.add("of", of);
            return out;
        }
        if (value.isJsonArray()) {
            JsonObject of = null;
            for (JsonElement item : value.getAsJsonArray()) {
                JsonObject described = item.isJsonObject() ? section(item.getAsJsonObject(), yaml, path) : infer(item, yaml, path);
                of = of == null ? described : merge(of, described);
            }
            out.addProperty("type", "list");
            out.add("of", of == null ? primitive("string") : of);
            return out;
        }
        if (value.isJsonNull()) {
            return primitive("any");
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive("boolean");
        }
        if (primitive.isNumber()) {
            BigDecimal number = primitive.getAsBigDecimal();
            return primitive(number.signum() == 0 || number.stripTrailingZeros().scale() <= 0 ? "integer" : "number");
        }
        return primitive("string");
    }

    /** Two descriptions of the same place: the union of their keys, {@code any} where they disagree. */
    static JsonObject merge(JsonObject left, JsonObject right) {
        String a = left.get("type").getAsString();
        String b = right.get("type").getAsString();
        if (!a.equals(b)) {
            boolean numeric = (a.equals("integer") || a.equals("number")) && (b.equals("integer") || b.equals("number"));
            return primitive(numeric ? "number" : "any");
        }
        switch (a) {
            case "section" -> {
                Map<String, JsonObject> fields = new LinkedHashMap<>();
                for (JsonObject side : List.of(left, right)) {
                    for (JsonElement element : side.getAsJsonArray("fields")) {
                        JsonObject field = element.getAsJsonObject();
                        String key = field.get("key").getAsString();
                        JsonObject known = fields.get(key);
                        if (known == null) {
                            fields.put(key, field.deepCopy());
                            continue;
                        }
                        known.add("type", merge(known.getAsJsonObject("type"), field.getAsJsonObject("type")));
                        if (known.getAsJsonArray("comments").isEmpty()) {
                            known.add("comments", field.getAsJsonArray("comments"));
                        }
                    }
                }
                JsonObject out = left.deepCopy();
                JsonArray merged = new JsonArray();
                fields.values().forEach(merged::add);
                out.add("fields", merged);
                return out;
            }
            case "list", "map" -> {
                JsonObject out = new JsonObject();
                out.addProperty("type", a);
                out.add("of", merge(left.getAsJsonObject("of"), right.getAsJsonObject("of")));
                return out;
            }
            default -> {
                return left;
            }
        }
    }

    private static JsonObject primitive(String type) {
        JsonObject out = new JsonObject();
        out.addProperty("type", type);
        return out;
    }

    private static JsonArray strings(@Nullable List<String> lines) {
        JsonArray out = new JsonArray();
        if (lines != null) {
            for (String line : lines) {
                if (line != null && !line.isBlank()) {
                    out.add(line.strip());
                }
            }
        }
        return out;
    }
}
