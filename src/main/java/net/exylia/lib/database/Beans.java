package net.exylia.lib.database;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import net.exylia.lib.util.loot.LootCodec;
import net.exylia.lib.util.loot.LootEntry;
import net.exylia.lib.util.sequence.EffectCodec;
import net.exylia.lib.util.sequence.EffectEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A text column holding a JSON array of plain objects, written and read back.
 *
 * <pre>{@code
 * String stored = Beans.encode(zone.powerUps());            // into the column
 * List<PowerUpEntry> read = Beans.decode(stored, PowerUpEntry.class);
 * }</pre>
 *
 * <h2>Why these are not list columns</h2>
 * The database module writes a list of a type it has a codec for as a JSON
 * array of <em>encoded strings</em>, and refuses a list of anything else. These
 * columns hold a JSON array of <em>objects</em> — the shape every row written
 * by ExyliaCommons-era plugins already has on disk (mines' blocks and levels,
 * power-up zones) — so the column stays text and is encoded here, byte for byte.
 *
 * <h2>The two library types nested inside them</h2>
 * A bean may carry {@link LootEntry} and {@link EffectEntry} values, which are
 * the library's types. Both go through the library's own codecs rather than plain Gson: an
 * effect stored in the older forty-field shape is translated on the way in,
 * and a loot entry does not depend on the library's field names never changing.
 *
 * <p>Plain Gson otherwise: a bean is a class or record with fields Gson can
 * reflect on. Thread-safe.
 *
 * @since 1.266.0
 */
public final class Beans {

    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(LootEntry.class,
                    (com.google.gson.JsonSerializer<LootEntry>) (entry, type, context) ->
                            JsonParser.parseString(LootCodec.encode(entry)))
            .registerTypeAdapter(LootEntry.class,
                    (com.google.gson.JsonDeserializer<LootEntry>) (json, type, context) ->
                            first(LootCodec.decode(wrap(json))))
            .registerTypeAdapter(EffectEntry.class,
                    (com.google.gson.JsonSerializer<EffectEntry>) (entry, type, context) ->
                            JsonParser.parseString(EffectCodec.encode(List.of(entry)))
                                    .getAsJsonArray().get(0))
            .registerTypeAdapter(EffectEntry.class,
                    (com.google.gson.JsonDeserializer<EffectEntry>) (json, type, context) ->
                            first(EffectCodec.decode(wrap(json))))
            .create();

    private Beans() {
        throw new AssertionError("No instances.");
    }

    /**
     * Writes a list of beans the way the column holds it.
     *
     * @param values what to write
     * @return the JSON array, or {@code null} for an empty list
     */
    public static String encode(List<?> values) {
        return values == null || values.isEmpty() ? null : GSON.toJson(values);
    }

    /**
     * Reads a list of beans back.
     *
     * @param stored  the column value, possibly {@code null}
     * @param element what the list holds
     * @return the beans, never {@code null}; empty for anything unreadable,
     *         because a stored row has nobody watching a console
     */
    public static <T> List<T> decode(String stored, Class<T> element) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        try {
            List<T> read = GSON.fromJson(stored,
                    TypeToken.getParameterized(List.class, element).getType());
            if (read == null) {
                return List.of();
            }
            List<T> cleaned = new ArrayList<>(read.size());
            for (T value : read) {
                if (value != null) {
                    cleaned.add(value);
                }
            }
            return Collections.unmodifiableList(cleaned);
        } catch (RuntimeException unreadable) {
            return List.of();
        }
    }

    /** One stored object as the one-element array the codecs read. */
    private static String wrap(JsonElement json) {
        JsonArray array = new JsonArray(1);
        array.add(json);
        return array.toString();
    }

    private static <T> T first(List<T> values) {
        return values.isEmpty() ? null : values.get(0);
    }
}
