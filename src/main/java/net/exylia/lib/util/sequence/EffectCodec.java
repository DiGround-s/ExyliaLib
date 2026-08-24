package net.exylia.lib.util.sequence;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Effect lists as they are stored, and as ExyliaCommons stored them.
 *
 * <pre>{@code
 * String stored = EffectCodec.encode(effects);          // into the column
 * List<EffectEntry> effects = EffectCodec.decode(stored);
 * }</pre>
 *
 * <h2>Reading rows written by ExyliaCommons</h2>
 * Its effect entries were a forty-field bean covering eight effect types, and
 * this library's are ten fields over a sequence. The two are not the same shape
 * and cannot be: the new payload does more, and reproducing the old one would
 * mean reproducing the {@code switch} it existed to feed.
 *
 * <p>So {@link #decode} reads both. A row carrying a {@code type} key is a
 * commons row and is translated — its particle, sound, potion, firework, title,
 * action bar or message becomes the sequence line that plays the same thing —
 * and its gating comes across untouched. Nothing has to be re-authored, and what
 * is written back is the new shape.
 *
 * <p>Translation is one way on purpose. Writing the old form again would pin
 * every effect to the eight types it knew, which is the ceiling this replaced.
 *
 * @since 1.57.0
 */
public final class EffectCodec {

    private static final String ID = "id";
    private static final String NAME = "name";
    private static final String ICON = "icon";
    private static final String LINES = "lines";
    private static final String CHANCE = "chance";
    private static final String CONDITION = "condition";
    private static final String PERMISSION = "permission";
    private static final String PRIORITY = "priority";
    private static final String DELAY_TICKS = "delayTicks";
    private static final String RADIUS = "radius";

    /** The key that tells a commons row from one of ours. */
    private static final String LEGACY_TYPE = "type";

    /** Ignores what it cannot read, which is what a stored row deserves. */
    private static final BiConsumer<String, String> SILENT = (where, problem) -> { };

    private EffectCodec() {
        throw new AssertionError("No instances.");
    }

    // --------------------------------------------------------------- encoding

    /**
     * Writes an effect list the way the column expects it.
     *
     * @param effects the effects
     * @return the JSON array, or {@code null} for an empty list
     */
    public static @Nullable String encode(@NotNull List<EffectEntry> effects) {
        if (effects.isEmpty()) {
            return null;
        }
        JsonArray array = new JsonArray();
        for (EffectEntry entry : effects) {
            array.add(toJson(entry));
        }
        return array.toString();
    }

    private static JsonObject toJson(EffectEntry entry) {
        JsonObject json = new JsonObject();
        json.addProperty(ID, entry.id());
        addIfPresent(json, NAME, entry.name());
        addIfPresent(json, ICON, entry.icon());
        JsonArray lines = new JsonArray();
        entry.lines().forEach(lines::add);
        json.add(LINES, lines);
        json.addProperty(CHANCE, entry.chance());
        addIfPresent(json, CONDITION, entry.condition());
        addIfPresent(json, PERMISSION, entry.permission());
        json.addProperty(PRIORITY, entry.priority());
        json.addProperty(DELAY_TICKS, entry.delayTicks());
        // Infinity is not JSON. The whole world is written as the word, which is
        // also what somebody editing the column by hand would write.
        json.addProperty(RADIUS, entry.radius() == EffectEntry.WHOLE_WORLD
                ? "world" : String.valueOf(entry.radius()));
        return json;
    }

    private static void addIfPresent(JsonObject json, String key, @Nullable String value) {
        if (value != null) {
            json.addProperty(key, value);
        }
    }

    // --------------------------------------------------------------- decoding

    /**
     * Reads a stored effect list, ignoring whatever it cannot understand.
     *
     * <p>Reads both shapes: rows written by this library, and rows written by
     * ExyliaCommons, which are translated on the way in.
     *
     * @param stored the column value, possibly {@code null}
     * @return the effects, never {@code null}
     */
    public static @NotNull List<EffectEntry> decode(@Nullable String stored) {
        return decode(stored, SILENT);
    }

    /**
     * The same, reporting what it had to skip or could only partly translate.
     *
     * @param stored   the column value
     * @param problems told where the trouble was and what it was
     * @return the effects that could be read
     */
    public static @NotNull List<EffectEntry> decode(@Nullable String stored,
                                                    @NotNull BiConsumer<String, String> problems) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(stored);
        } catch (RuntimeException malformed) {
            problems.accept("effects", "not valid JSON: " + malformed.getMessage());
            return List.of();
        }
        if (!root.isJsonArray()) {
            problems.accept("effects", "expected a list of effects");
            return List.of();
        }
        JsonArray array = root.getAsJsonArray();
        List<EffectEntry> effects = new ArrayList<>(array.size());
        for (int index = 0; index < array.size(); index++) {
            JsonElement element = array.get(index);
            String where = "effects[" + index + "]";
            if (!element.isJsonObject()) {
                problems.accept(where, "not an effect");
                continue;
            }
            JsonObject json = element.getAsJsonObject();
            EffectEntry entry = json.has(LEGACY_TYPE)
                    ? fromLegacy(json, where, problems)
                    : fromJson(json);
            if (entry != null) {
                effects.add(entry);
            }
        }
        return List.copyOf(effects);
    }

    private static EffectEntry fromJson(JsonObject json) {
        List<String> lines = new ArrayList<>();
        JsonElement stored = json.get(LINES);
        if (stored != null && stored.isJsonArray()) {
            for (JsonElement line : stored.getAsJsonArray()) {
                if (line.isJsonPrimitive()) {
                    lines.add(line.getAsString());
                }
            }
        }
        return EffectEntry.of(lines)
                .id(string(json, ID) != null ? string(json, ID) : UUID.randomUUID().toString())
                .name(string(json, NAME))
                .icon(string(json, ICON))
                .chance(decimal(json, CHANCE, EffectEntry.ALWAYS))
                .condition(string(json, CONDITION))
                .permission(string(json, PERMISSION))
                .priority((int) number(json, PRIORITY, 0))
                .delayTicks(number(json, DELAY_TICKS, 0))
                .radius(radius(json))
                .build();
    }

    private static double radius(JsonObject json) {
        String written = string(json, RADIUS);
        if (written == null) {
            return EffectEntry.DEFAULT_RADIUS;
        }
        if (written.equalsIgnoreCase("world")) {
            return EffectEntry.WHOLE_WORLD;
        }
        try {
            return Double.parseDouble(written);
        } catch (NumberFormatException notANumber) {
            return EffectEntry.DEFAULT_RADIUS;
        }
    }

    // ---------------------------------------------------------------- legacy

    /**
     * Translates one ExyliaCommons effect entry.
     *
     * <p>The gating comes across as it is. The payload — whichever of the forty
     * fields its {@code type} pointed at — becomes the sequence line that plays
     * the same thing. A type nobody recognises keeps its gating and arrives with
     * nothing to play, drawn as unfinished in the editor rather than dropped:
     * the row said something, and losing it silently is worse than showing it
     * empty.
     */
    private static @Nullable EffectEntry fromLegacy(JsonObject json, String where,
                                                    BiConsumer<String, String> problems) {
        String type = string(json, LEGACY_TYPE);
        List<String> lines = legacyLines(json, type == null ? "" : type.toUpperCase(Locale.ROOT));
        if (lines.isEmpty()) {
            problems.accept(where, "an effect of type \"" + type
                    + "\" that carried nothing this library can play");
        }
        return EffectEntry.of(lines)
                .id(string(json, ID) != null ? string(json, ID) : UUID.randomUUID().toString())
                .name(string(json, NAME))
                .icon(string(json, ICON))
                .chance(decimal(json, CHANCE, EffectEntry.ALWAYS))
                .condition(string(json, CONDITION))
                .permission(string(json, PERMISSION))
                .priority((int) number(json, PRIORITY, 0))
                .delayTicks(number(json, DELAY_TICKS, 0))
                .radius(legacyRadius(json))
                .build();
    }

    private static List<String> legacyLines(JsonObject json, String type) {
        return switch (type) {
            case "PARTICLE" -> line(particleLine(json));
            case "SOUND" -> line(soundLine(json));
            case "POTION" -> line(potionLine(json));
            case "FIREWORK" -> line(fireworkLine(json));
            case "TITLE" -> line(titleLine(json));
            case "ACTIONBAR" -> line(prefixed("[ACTION_BAR] ", string(json, "actionbar")));
            case "MESSAGE" -> messageLines(json);
            case "SEQUENCE" -> strings(json, "sequence");
            default -> List.of();
        };
    }

    private static String particleLine(JsonObject json) {
        String particle = string(json, "particle");
        if (particle == null) {
            return null;
        }
        StringBuilder line = new StringBuilder("[PARTICLE] ").append(particle);
        append(line, "count", number(json, "particleCount", 1));
        double x = decimal(json, "offsetX", 0.0);
        double y = decimal(json, "offsetY", 0.0);
        double z = decimal(json, "offsetZ", 0.0);
        if (x != 0.0 || y != 0.0 || z != 0.0) {
            line.append(";offset:").append(x).append(',').append(y).append(',').append(z);
        }
        double extra = decimal(json, "particleExtra", 0.0);
        if (extra != 0.0) {
            line.append(";speed:").append(extra);
        }
        String colour = string(json, "particleColor");
        if (colour != null) {
            line.append(";color:").append(colour);
            double size = decimal(json, "dustSize", 1.0);
            if (size != 1.0) {
                line.append(";size:").append(size);
            }
        }
        String block = string(json, "particleBlockMaterial");
        if (block != null) {
            line.append(";block:").append(block);
        }
        return line.toString();
    }

    private static String soundLine(JsonObject json) {
        String sound = string(json, "sound");
        if (sound == null) {
            return null;
        }
        return "[SOUND] " + sound + ';' + decimal(json, "soundVolume", 1.0)
                + ';' + decimal(json, "soundPitch", 1.0);
    }

    private static String potionLine(JsonObject json) {
        String potion = string(json, "potion");
        if (potion == null) {
            return null;
        }
        // Both are ticks on both sides, so nothing is converted here.
        return "[POTION] " + potion + ';' + number(json, "potionDurationTicks", 200)
                + ';' + number(json, "potionAmplifier", 0);
    }

    private static String fireworkLine(JsonObject json) {
        StringBuilder line = new StringBuilder("[FIREWORK]");
        List<String> colours = strings(json, "fireworkColors");
        if (!colours.isEmpty()) {
            line.append(";color:").append(colours.get(0));
        }
        List<String> fades = strings(json, "fireworkFadeColors");
        if (!fades.isEmpty()) {
            line.append(";fade:").append(fades.get(0));
        }
        String type = string(json, "fireworkType");
        if (type != null) {
            line.append(";type:").append(type);
        }
        line.append(";trail:").append(flag(json, "fireworkTrail", false));
        line.append(";flicker:").append(flag(json, "fireworkFlicker", false));
        line.append(";power:").append(number(json, "fireworkPower", 1));
        return line.toString();
    }

    /**
     * A title line.
     *
     * <p>The one unit conversion in the whole importer: commons stored the three
     * times in ticks and the sequence notation writes them in seconds, because a
     * file that says {@code 0.5} means half a second everywhere else in it.
     */
    private static String titleLine(JsonObject json) {
        String title = string(json, "title");
        String subtitle = string(json, "subtitle");
        if (title == null && subtitle == null) {
            return null;
        }
        return "[TITLE] " + (title == null ? "" : title)
                + ';' + (subtitle == null ? "" : subtitle)
                + ';' + seconds(number(json, "titleFadeIn", 10))
                + ';' + seconds(number(json, "titleStay", 70))
                + ';' + seconds(number(json, "titleFadeOut", 20));
    }

    private static List<String> messageLines(JsonObject json) {
        List<String> lines = new ArrayList<>();
        String single = string(json, "message");
        if (single != null) {
            lines.add("[MESSAGE] " + single);
        }
        for (String message : strings(json, "messages")) {
            lines.add("[MESSAGE] " + message);
        }
        return lines;
    }

    /**
     * What the old scope enum meant, as a radius.
     *
     * <p>{@code PLAYER} is the player alone; {@code RADIUS} is the number beside
     * it; {@code GLOBAL} is the world, which is as far as a sequence anchored to
     * a location reaches; and {@code NEARBY} and {@code LOCATION} both meant
     * "around here", which is the default radius.
     */
    private static double legacyRadius(JsonObject json) {
        String scope = string(json, "scope");
        if (scope == null) {
            return EffectEntry.DEFAULT_RADIUS;
        }
        return switch (scope.toUpperCase(Locale.ROOT)) {
            case "PLAYER" -> 0.0;
            case "RADIUS" -> decimal(json, "radius", EffectEntry.DEFAULT_RADIUS);
            case "GLOBAL" -> EffectEntry.WHOLE_WORLD;
            default -> EffectEntry.DEFAULT_RADIUS;
        };
    }

    // ------------------------------------------------------------------

    private static List<String> line(@Nullable String value) {
        return value == null ? List.of() : List.of(value);
    }

    private static @Nullable String prefixed(String prefix, @Nullable String value) {
        return value == null ? null : prefix + value;
    }

    private static void append(StringBuilder line, String name, long value) {
        if (value > 1) {
            line.append(';').append(name).append(':').append(value);
        }
    }

    private static String seconds(long ticks) {
        return String.valueOf(ticks / 20.0);
    }

    private static @Nullable String string(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        String value = element.getAsString();
        return value.isBlank() ? null : value;
    }

    private static List<String> strings(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonElement value : element.getAsJsonArray()) {
            if (value.isJsonPrimitive()) {
                values.add(value.getAsString());
            }
        }
        return values;
    }

    private static long number(JsonObject json, String key, long fallback) {
        JsonElement element = json.get(key);
        return element instanceof JsonPrimitive primitive && primitive.isNumber()
                ? primitive.getAsLong() : fallback;
    }

    private static double decimal(JsonObject json, String key, double fallback) {
        JsonElement element = json.get(key);
        return element instanceof JsonPrimitive primitive && primitive.isNumber()
                ? primitive.getAsDouble() : fallback;
    }

    private static boolean flag(JsonObject json, String key, boolean fallback) {
        JsonElement element = json.get(key);
        return element instanceof JsonPrimitive primitive && primitive.isBoolean()
                ? primitive.getAsBoolean() : fallback;
    }
}
