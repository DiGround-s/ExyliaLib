package net.exylia.lib.util.loot;

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
 * Loot tables as they are stored, and as ExyliaCommons stored them.
 *
 * <pre>{@code
 * String stored = LootCodec.encode(entries);   // into the column
 * List<LootEntry> entries = LootCodec.decode(stored);
 * }</pre>
 *
 * <h2>This format is not a choice</h2>
 * Production databases already hold these rows: every loot chest template,
 * every item spawner, every event loot table in the ecosystem. ExyliaCommons
 * stored them by handing the list to a bare {@code new Gson()}, so the field
 * names are its Lombok bean's fields, in its declaration order, and they are
 * fixed:
 *
 * <pre>{@code
 * [{"id":"…","type":"ITEM","itemSnapshot":"bytes:…","minAmount":1,
 *   "maxAmount":3,"weight":50.0}]
 * }</pre>
 *
 * <p>Gson omits null fields, so an item entry carries no {@code command} key at
 * all and an entry with no tier carries no {@code tier}. That is reproduced
 * exactly: adding the keys back would grow every row against the
 * {@code VARCHAR(65535)} those tables already declare, and a human diffing two
 * rows across the migration should see nothing move.
 *
 * <h2>An empty list is null, not {@code []}</h2>
 * Because that is what commons' {@code serializeCollection} did, and a column
 * that suddenly held {@code []} where it used to hold {@code NULL} would read
 * back the same but would not compare the same.
 *
 * <h2>What a missing type means</h2>
 * Entries written before commands existed have no {@code type} key. They meant
 * items, so a missing or unreadable type reads as {@link LootType#ITEM} rather
 * than losing the entry — the same defensive default commons wrote, for the
 * same rows.
 *
 * @since 1.56.0
 */
public final class LootCodec {

    private LootCodec() {
        throw new AssertionError("No instances.");
    }

    // The names below are the field names of ExyliaCommons' LootEntry bean.
    // Changing any of them orphans every row already written.
    private static final String ID = "id";
    private static final String TYPE = "type";
    private static final String ITEM_SNAPSHOT = "itemSnapshot";
    private static final String MIN_AMOUNT = "minAmount";
    private static final String MAX_AMOUNT = "maxAmount";
    private static final String COMMAND = "command";
    private static final String WEIGHT = "weight";
    private static final String TIER = "tier";

    /** Ignores what it cannot read, which is what a stored row deserves. */
    private static final BiConsumer<String, String> SILENT = (where, problem) -> { };

    // --------------------------------------------------------------- encoding

    /**
     * Writes a loot table the way the column expects it.
     *
     * @param entries the entries
     * @return the JSON array, or {@code null} for an empty list
     */
    public static @Nullable String encode(@NotNull List<LootEntry> entries) {
        if (entries.isEmpty()) {
            return null;
        }
        JsonArray array = new JsonArray();
        for (LootEntry entry : entries) {
            array.add(toJson(entry));
        }
        return array.toString();
    }

    /**
     * Writes one entry.
     *
     * @param entry the entry
     * @return its JSON object
     */
    public static @NotNull String encode(@NotNull LootEntry entry) {
        return toJson(entry).toString();
    }

    private static JsonObject toJson(LootEntry entry) {
        JsonObject json = new JsonObject();
        // The bean's declaration order. Gson preserves it and so do we.
        json.addProperty(ID, entry.id());
        json.addProperty(TYPE, entry.type().name());
        addIfPresent(json, ITEM_SNAPSHOT, entry.itemSnapshot());
        json.addProperty(MIN_AMOUNT, entry.minAmount());
        json.addProperty(MAX_AMOUNT, entry.maxAmount());
        addIfPresent(json, COMMAND, entry.command());
        json.addProperty(WEIGHT, entry.weight());
        addIfPresent(json, TIER, entry.tier());
        return json;
    }

    private static void addIfPresent(JsonObject json, String key, @Nullable String value) {
        if (value != null) {
            json.addProperty(key, value);
        }
    }

    // --------------------------------------------------------------- decoding

    /**
     * Reads a stored loot table, ignoring whatever it cannot understand.
     *
     * <p>For reading a database column, where nobody is watching a console.
     *
     * @param stored the column value, possibly {@code null}
     * @return the entries, never {@code null}
     */
    public static @NotNull List<LootEntry> decode(@Nullable String stored) {
        return decode(stored, SILENT);
    }

    /**
     * Reads a stored loot table, reporting what it had to skip.
     *
     * <p>For reading a config file, where somebody typed it and can fix it.
     *
     * @param stored   the stored value, possibly {@code null}
     * @param problems told where the trouble was and what it was
     * @return the entries that could be read
     */
    public static @NotNull List<LootEntry> decode(@Nullable String stored,
                                                  @NotNull BiConsumer<String, String> problems) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(stored);
        } catch (RuntimeException malformed) {
            problems.accept("loot", "not valid JSON: " + malformed.getMessage());
            return List.of();
        }
        if (root.isJsonObject()) {
            LootEntry single = fromJson(root.getAsJsonObject(), "loot", problems);
            return single == null ? List.of() : List.of(single);
        }
        if (!root.isJsonArray()) {
            problems.accept("loot", "expected a list of entries");
            return List.of();
        }
        JsonArray array = root.getAsJsonArray();
        List<LootEntry> entries = new ArrayList<>(array.size());
        for (int index = 0; index < array.size(); index++) {
            JsonElement element = array.get(index);
            String where = "loot[" + index + "]";
            if (!element.isJsonObject()) {
                problems.accept(where, "not an entry");
                continue;
            }
            LootEntry entry = fromJson(element.getAsJsonObject(), where, problems);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return List.copyOf(entries);
    }

    private static @Nullable LootEntry fromJson(JsonObject json,
                                                String where,
                                                BiConsumer<String, String> problems) {
        LootType type = type(json, where, problems);
        LootEntry.Builder builder = LootEntry.of(type)
                .id(string(json, ID) != null ? string(json, ID) : UUID.randomUUID().toString())
                .itemSnapshot(string(json, ITEM_SNAPSHOT))
                .command(string(json, COMMAND))
                .tier(string(json, TIER));

        int min = number(json, MIN_AMOUNT, 1);
        int max = number(json, MAX_AMOUNT, min);
        builder.minAmount(min).maxAmount(max);
        builder.weight(decimal(json, WEIGHT, LootEntry.DEFAULT_WEIGHT));

        LootEntry entry = builder.build();
        if (entry.isItem() && entry.itemSnapshot() == null) {
            // Not dropped: an entry whose item somebody has yet to pick is
            // exactly what an editor is for, and dropping it would lose the row
            // the moment the table was saved back.
            problems.accept(where, "an item entry with no item");
        }
        if (entry.isCommand() && entry.command() == null) {
            problems.accept(where, "a command entry with no command");
        }
        return entry;
    }

    /**
     * The type, defaulting to {@link LootType#ITEM}.
     *
     * <p>Both because that is what a row from before command entries meant, and
     * because a type this version has never heard of costs the entry's payload,
     * not the whole table.
     */
    private static LootType type(JsonObject json, String where, BiConsumer<String, String> problems) {
        String name = string(json, TYPE);
        if (name == null) {
            return LootType.ITEM;
        }
        try {
            return LootType.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            problems.accept(where, "unknown type \"" + name + "\", read as ITEM");
            return LootType.ITEM;
        }
    }

    private static @Nullable String string(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        return element.getAsString();
    }

    private static int number(JsonObject json, String key, int fallback) {
        JsonElement element = json.get(key);
        if (element instanceof JsonPrimitive primitive && primitive.isNumber()) {
            return primitive.getAsInt();
        }
        return fallback;
    }

    private static double decimal(JsonObject json, String key, double fallback) {
        JsonElement element = json.get(key);
        if (element instanceof JsonPrimitive primitive && primitive.isNumber()) {
            return primitive.getAsDouble();
        }
        return fallback;
    }
}
