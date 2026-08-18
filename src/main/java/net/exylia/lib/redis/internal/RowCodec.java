package net.exylia.lib.redis.internal;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.exylia.lib.database.internal.ColumnModel;
import net.exylia.lib.database.internal.EntityModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Turns a record into the text Redis holds, and back.
 *
 * <p>Deliberately not reflection over the record. It goes through the same
 * {@link EntityModel} the database goes through, so a value reaches Redis in
 * exactly the form it reaches a column: an {@code ItemStack} as the Base64 its
 * codec produces, a {@code UUID} as its text, a {@code Location} as the string
 * the SQL layer would store. That matters more than it looks — ExyliaCommons
 * cached with bare Gson while writing with its serializers, so the same field
 * had two representations and only one of them survived a round trip through a
 * custom codec.
 *
 * <p>It also means the payload is made of what {@link ColumnModel#storedType()}
 * allows and nothing else: a number, a boolean, a string or null. There is no
 * type to guess on the way back and no class name to resolve, so a payload
 * written by another plugin's copy of another version still reads.
 *
 * <h2>A payload outlives the record that wrote it</h2>
 * Columns are addressed by name and missing ones are absent rather than fatal,
 * which is what {@link EntityModel#read(java.util.function.Function)} already
 * promises for a table that gained a column. A cached payload written before a
 * plugin update is the same situation, so it reads instead of poisoning every
 * lookup until the TTL expires.
 */
final class RowCodec {

    /** Marks a payload's shape, so a later format can be told apart from this one. */
    private static final String VERSION_FIELD = "v";
    private static final String COLUMNS_FIELD = "c";
    private static final int VERSION = 1;

    private RowCodec() {
        throw new AssertionError("No instances.");
    }

    /**
     * Serialises one record.
     *
     * @param model    the compiled model, which does the encoding
     * @param instance the record
     * @param <T>      the record type
     * @return the payload
     */
    static <T> @NotNull String encode(@NotNull EntityModel<T> model, @NotNull T instance) {
        Map<String, Object> values = model.valuesByName(instance);

        JsonObject columns = new JsonObject();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            columns.add(entry.getKey(), toJson(entry.getValue()));
        }

        JsonObject payload = new JsonObject();
        payload.addProperty(VERSION_FIELD, VERSION);
        payload.add(COLUMNS_FIELD, columns);
        return payload.toString();
    }

    /**
     * Rebuilds one record, or {@code null} when the payload cannot be trusted.
     *
     * <p>Never throws. A payload that is malformed, of a version this build does
     * not know, or that the record's own constructor rejects, is treated as a
     * cache miss: the caller goes to the database and gets the right answer.
     * Failing loudly here would turn one bad key into an outage for a record
     * type, which is the opposite of what a cache is for.
     *
     * @param model   the compiled model, which does the decoding
     * @param payload the stored text
     * @param <T>     the record type
     * @return the record, or {@code null} to treat this as a miss
     */
    static <T> @Nullable T decode(@NotNull EntityModel<T> model, @NotNull String payload) {
        try {
            JsonElement parsed = JsonParser.parseString(payload);
            if (!parsed.isJsonObject()) {
                return null;
            }
            JsonObject object = parsed.getAsJsonObject();
            if (!object.has(VERSION_FIELD) || object.get(VERSION_FIELD).getAsInt() != VERSION) {
                return null;
            }
            JsonObject columns = object.getAsJsonObject(COLUMNS_FIELD);
            if (columns == null) {
                return null;
            }
            return model.read(name -> fromJson(columns.get(name), model.column(name)));
        } catch (Throwable unreadable) {
            return null;
        }
    }

    private static JsonElement toJson(@Nullable Object stored) {
        if (stored == null) {
            return com.google.gson.JsonNull.INSTANCE;
        }
        if (stored instanceof Number number) {
            return new JsonPrimitive(number);
        }
        if (stored instanceof Boolean flag) {
            return new JsonPrimitive(flag);
        }
        // Everything a codec produces is text, and everything else the model
        // hands over is one of the two above. A type outside that set would be
        // one the SQL layer could not bind either.
        return new JsonPrimitive(String.valueOf(stored));
    }

    /**
     * Reads one column back into the form the model's decoder expects.
     *
     * <p>The column's {@link ColumnModel#storedType()} is what decides, not what
     * JSON happened to parse. JSON has one number type, so a {@code long}
     * written as {@code 3} comes back as a {@code double} unless it is asked for
     * by the type that stores it — and a {@code long} timestamp read as a
     * {@code double} loses precision above 2^53, silently, on values that are
     * already in that range for a millisecond clock.
     */
    private static @Nullable Object fromJson(@Nullable JsonElement element, @Nullable ColumnModel column) {
        if (element == null || element.isJsonNull() || column == null) {
            return null;
        }
        Class<?> stored = column.storedType();
        if (stored == String.class) {
            return element.getAsString();
        }
        if (stored == boolean.class || stored == Boolean.class) {
            return element.getAsBoolean();
        }
        if (stored == long.class || stored == Long.class) {
            return element.getAsLong();
        }
        if (stored == int.class || stored == Integer.class) {
            return element.getAsInt();
        }
        if (stored == double.class || stored == Double.class) {
            return element.getAsDouble();
        }
        if (stored == float.class || stored == Float.class) {
            return element.getAsFloat();
        }
        if (stored == short.class || stored == Short.class) {
            return element.getAsShort();
        }
        if (stored == byte.class || stored == Byte.class) {
            return element.getAsByte();
        }
        // A stored type outside that set reaches the model as text, which its
        // own coercion handles the same way a driver returning a String would.
        return element.getAsString();
    }
}
