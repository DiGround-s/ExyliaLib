package net.exylia.lib.util.reward;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Reward lists as they are stored, and as ExyliaCommons stored them.
 *
 * <pre>{@code
 * String stored = RewardCodec.encode(rewards);   // into the column
 * List<RewardEntry> rewards = RewardCodec.decode(stored);
 * }</pre>
 *
 * <h2>This format is not a choice</h2>
 * Production databases already hold these rows: every configured event, capture
 * point and power-up in the ecosystem. ExyliaCommons wrote them with a bare
 * {@code Gson().toJson(List<RewardEntry>)} over a Lombok bean, so the field
 * names are that bean's fields, and they are fixed:
 *
 * <pre>{@code
 * [{"id":"…","name":"…","type":"ITEM","command":"…","itemSnapshot":"…",
 *   "message":"…","icon":"…","itemAmount":1,"chance":100.0,
 *   "condition":"…","permission":"…","deliveryMessage":"…","priority":0}]
 * }</pre>
 *
 * <p>Gson omits null fields, so a command reward carries no {@code itemSnapshot}
 * key at all. That is reproduced exactly: adding the key back would grow every
 * row, and {@code rewardsJson} is a {@code VARCHAR(8192)} in the tables that
 * already exist.
 *
 * <h2>What the new fields do to an old reader</h2>
 * The fields this library added &mdash; {@code value}, {@code currency},
 * {@code minAmount}, {@code maxAmount}, {@code weight} &mdash; are written
 * <em>only when they differ from their default</em>. A reward that a plugin
 * still on ExyliaCommons could have written is therefore byte-identical to what
 * it would have written, and Gson on the old side ignores any extra key it does
 * meet. A type it has never heard of deserialises to a null {@code type}, and
 * that plugin skips one reward rather than losing the list.
 *
 * <h2>An empty list is null, not {@code []}</h2>
 * Because that is what commons' {@code serializeCollection} did. A column that
 * suddenly held {@code []} where it used to hold {@code NULL} would read back
 * the same, but it would not compare the same, and something out there compares.
 *
 * @since 1.34.0
 */
public final class RewardCodec {

    private RewardCodec() {
        throw new AssertionError("No instances.");
    }

    // The names below are the field names of ExyliaCommons' RewardEntry bean.
    // Changing any of them orphans every row already written.
    private static final String ID = "id";
    private static final String NAME = "name";
    private static final String TYPE = "type";
    private static final String COMMAND = "command";
    private static final String ITEM_SNAPSHOT = "itemSnapshot";
    private static final String MESSAGE = "message";
    private static final String ICON = "icon";
    private static final String ITEM_AMOUNT = "itemAmount";
    private static final String CHANCE = "chance";
    private static final String CONDITION = "condition";
    private static final String PERMISSION = "permission";
    private static final String DELIVERY_MESSAGE = "deliveryMessage";
    private static final String PRIORITY = "priority";

    // Added by this library. Written only when set, so a reward the old module
    // could have produced still serialises to exactly what it produced.
    private static final String VALUE = "value";
    private static final String CURRENCY = "currency";
    private static final String MIN_AMOUNT = "minAmount";
    private static final String MAX_AMOUNT = "maxAmount";
    private static final String WEIGHT = "weight";

    /** Ignores what it cannot read, which is what a stored row deserves. */
    private static final BiConsumer<String, String> SILENT = (where, problem) -> { };

    // --------------------------------------------------------------- encoding

    /**
     * Writes a reward list the way the column expects it.
     *
     * @param rewards the rewards
     * @return the JSON array, or {@code null} for an empty list
     */
    public static @Nullable String encode(@NotNull List<RewardEntry> rewards) {
        if (rewards.isEmpty()) {
            return null;
        }
        JsonArray array = new JsonArray();
        for (RewardEntry entry : rewards) {
            array.add(toJson(entry));
        }
        return array.toString();
    }

    /**
     * Writes one reward.
     *
     * @param entry the reward
     * @return its JSON object
     */
    public static @NotNull String encode(@NotNull RewardEntry entry) {
        return toJson(entry).toString();
    }

    private static JsonObject toJson(RewardEntry entry) {
        JsonObject json = new JsonObject();
        // Written in the bean's declaration order: Gson preserves it, and a
        // human diffing two rows across the migration should see nothing move.
        json.addProperty(ID, entry.id());
        addIfPresent(json, NAME, entry.name());
        json.addProperty(TYPE, entry.type().name());
        addIfPresent(json, COMMAND, entry.command());
        addIfPresent(json, ITEM_SNAPSHOT, entry.itemSnapshot());
        addIfPresent(json, MESSAGE, entry.message());
        addIfPresent(json, ICON, entry.icon());
        json.addProperty(ITEM_AMOUNT, entry.itemAmount());
        json.addProperty(CHANCE, entry.chance());
        addIfPresent(json, CONDITION, entry.condition());
        addIfPresent(json, PERMISSION, entry.permission());
        addIfPresent(json, DELIVERY_MESSAGE, entry.deliveryMessage());
        json.addProperty(PRIORITY, entry.priority());

        // Everything past here is ours. Absent unless it carries meaning, so a
        // legacy-shaped reward round-trips to the same bytes it came from.
        addIfPresent(json, VALUE, entry.value());
        addIfPresent(json, CURRENCY, entry.currency());
        if (entry.isRanged()) {
            json.addProperty(MIN_AMOUNT, entry.minAmount());
            json.addProperty(MAX_AMOUNT, entry.maxAmount());
        }
        if (entry.weight() != 1.0) {
            json.addProperty(WEIGHT, entry.weight());
        }
        return json;
    }

    private static void addIfPresent(JsonObject json, String key, @Nullable String value) {
        if (value != null) {
            json.addProperty(key, value);
        }
    }

    // --------------------------------------------------------------- decoding

    /**
     * Reads a stored reward list, ignoring whatever it cannot understand.
     *
     * <p>For reading a database column, where nobody is watching a console.
     *
     * @param stored the column value, possibly {@code null}
     * @return the rewards, never {@code null}
     */
    public static @NotNull List<RewardEntry> decode(@Nullable String stored) {
        return decode(stored, SILENT);
    }

    /**
     * Reads a stored reward list, reporting what it had to skip.
     *
     * <p>For reading a config file, where somebody typed it and can fix it.
     *
     * @param stored   the stored value, possibly {@code null}
     * @param problems told where the trouble was and what it was
     * @return the rewards that could be read
     */
    public static @NotNull List<RewardEntry> decode(@Nullable String stored,
                                                    @NotNull BiConsumer<String, String> problems) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(stored);
        } catch (RuntimeException malformed) {
            problems.accept("rewards", "not valid JSON: " + malformed.getMessage());
            return List.of();
        }
        if (root.isJsonObject()) {
            RewardEntry single = fromJson(root.getAsJsonObject(), "reward", problems);
            return single == null ? List.of() : List.of(single);
        }
        if (!root.isJsonArray()) {
            problems.accept("rewards", "expected a list of rewards");
            return List.of();
        }
        JsonArray array = root.getAsJsonArray();
        List<RewardEntry> rewards = new ArrayList<>(array.size());
        for (int index = 0; index < array.size(); index++) {
            JsonElement element = array.get(index);
            if (!element.isJsonObject()) {
                problems.accept("rewards[" + index + "]", "not a reward");
                continue;
            }
            RewardEntry entry = fromJson(element.getAsJsonObject(), "rewards[" + index + "]", problems);
            if (entry != null) {
                rewards.add(entry);
            }
        }
        return List.copyOf(rewards);
    }

    /**
     * Reads the legacy command-only column.
     *
     * <p>Both pending-reward tables in the ecosystem carry a {@code commandsJson}
     * holding a plain {@code ["/give …"]}, written before rewards had types. Rows
     * from that era are still out there and still owed to a player.
     *
     * @param stored the {@code commandsJson} value, possibly {@code null}
     * @return one command reward per entry
     */
    public static @NotNull List<RewardEntry> decodeLegacyCommands(@Nullable String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(stored);
        } catch (RuntimeException malformed) {
            return List.of();
        }
        if (!root.isJsonArray()) {
            return List.of();
        }
        List<RewardEntry> rewards = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray()) {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                rewards.add(RewardEntry.command(element.getAsString()).build());
            }
        }
        return List.copyOf(rewards);
    }

    private static @Nullable RewardEntry fromJson(JsonObject json, String where,
                                                  BiConsumer<String, String> problems) {
        String rawType = string(json, TYPE);
        RewardType type = RewardType.parse(rawType);
        if (type == null) {
            // A row written by a newer library than the one reading it. Skipping
            // this reward keeps the rest of the list, which is the whole reason
            // an unknown type is reported rather than thrown.
            problems.accept(where, rawType == null
                    ? "has no type"
                    : "has an unknown type \"" + rawType + "\"");
            return null;
        }
        RewardEntry.Builder builder = RewardEntry.of(type);

        String id = string(json, ID);
        builder.id(id != null ? id : UUID.randomUUID().toString());
        builder.name(string(json, NAME));
        builder.command(string(json, COMMAND));
        builder.itemSnapshot(string(json, ITEM_SNAPSHOT));
        builder.message(string(json, MESSAGE));
        builder.icon(string(json, ICON));
        builder.value(string(json, VALUE));
        builder.currency(string(json, CURRENCY));
        builder.condition(string(json, CONDITION));
        builder.permission(string(json, PERMISSION));
        builder.deliveryMessage(string(json, DELIVERY_MESSAGE));

        builder.itemAmount(integer(json, ITEM_AMOUNT, 1));
        builder.chance(number(json, CHANCE, RewardEntry.ALWAYS));
        builder.weight(number(json, WEIGHT, 1.0));
        builder.priority(integer(json, PRIORITY, 0));

        Integer min = boxedInteger(json, MIN_AMOUNT);
        Integer max = boxedInteger(json, MAX_AMOUNT);
        if (min != null && max != null) {
            builder.amountBetween(min, max);
        } else if (min != null || max != null) {
            // Half a range is a typo, not a range. The fixed amount still holds,
            // so the reward is given rather than dropped.
            problems.accept(where, "has only one end of an amount range; using the fixed amount");
        }
        return builder.build();
    }

    private static @Nullable String string(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        return element.getAsString();
    }

    private static int integer(JsonObject json, String key, int fallback) {
        Integer value = boxedInteger(json, key);
        return value != null ? value : fallback;
    }

    private static @Nullable Integer boxedInteger(JsonObject json, String key) {
        JsonPrimitive primitive = primitive(json, key);
        if (primitive == null) {
            return null;
        }
        try {
            return primitive.getAsInt();
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static double number(JsonObject json, String key, double fallback) {
        JsonPrimitive primitive = primitive(json, key);
        if (primitive == null) {
            return fallback;
        }
        try {
            return primitive.getAsDouble();
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    private static @Nullable JsonPrimitive primitive(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        return element.getAsJsonPrimitive();
    }
}
