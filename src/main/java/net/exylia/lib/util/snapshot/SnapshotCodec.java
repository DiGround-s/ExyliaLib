package net.exylia.lib.util.snapshot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.GameMode;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;

/**
 * Snapshots as they are stored, and as ExyliaCommons stored them.
 *
 * <pre>{@code
 * String stored = SnapshotCodec.encode(snapshot);   // into the column
 * Snapshot back = SnapshotCodec.decode(stored);     // out of it
 * }</pre>
 *
 * <h2>This format is not a choice</h2>
 * Rows written by ExyliaCommons are in production right now: every player who
 * was in an FFA arena, an event or a sandbox when the server last restarted has
 * one, and it holds everything they own. ExyliaCommons wrote a flat JSON object
 * whose keys are the fields of a Lombok bean, so those names are fixed:
 *
 * <pre>{@code
 * {"gameMode":"SURVIVAL","armor":[null,null,null,"rO0…"],"inventory":[…],
 *  "offHand":null,"health":20.0,"maxHealth":20.0,"foodLevel":20,
 *  "saturation":5.0,"level":30,"exp":0.5,
 *  "potionEffects":[{"type":"SPEED","duration":600,"amplifier":1,
 *                    "ambient":false,"particles":true,"icon":true}],
 *  "allowFlight":false,"flying":false,"flySpeed":0.1}
 * }</pre>
 *
 * <p>Each item is its own Base64 string &mdash; {@code serializeAsBytes} through
 * {@code Base64}, an empty slot as JSON {@code null}. This is deliberately
 * <em>not</em> the library's {@code ItemStack[]} codec, which writes the whole
 * array through a {@code BukkitObjectOutputStream} as one string. The two are
 * incompatible, and the one already in the database wins.
 *
 * <h2>What the new keys do to an old reader</h2>
 * {@code enderChest} and {@code physical} are added by this library and
 * ExyliaCommons never wrote them. Its deserialiser reads by key and ignores
 * anything it does not know, so a row written here still restores an inventory
 * on a server still running commons. In the other direction a row written by
 * commons simply has no ender chest and no physical state, which
 * {@link Snapshot#has} reports and a restore skips &mdash; it does not decode
 * to {@code null}.
 *
 * <h2>One unreadable part costs that part</h2>
 * ExyliaCommons wrapped the whole deserialiser in one {@code catch (Exception)}
 * that returned {@code null}, so a single item written by a version of the
 * server that no longer exists discarded the player's entire inventory, armour,
 * experience and health along with it, silently. Here an item that cannot be
 * read becomes an empty slot, an effect that cannot be read is dropped, and
 * everything else in the snapshot survives &mdash; and the problem is reported
 * rather than swallowed.
 *
 * @since 1.34.0
 */
public final class SnapshotCodec {

    private SnapshotCodec() {
        throw new AssertionError("No instances.");
    }

    // The names below are ExyliaCommons' SnapshotData field names. Changing any
    // of them orphans every row already written.
    private static final String GAME_MODE = "gameMode";
    private static final String ARMOR = "armor";
    private static final String INVENTORY = "inventory";
    private static final String OFF_HAND = "offHand";
    private static final String HEALTH = "health";
    private static final String MAX_HEALTH = "maxHealth";
    private static final String FOOD_LEVEL = "foodLevel";
    private static final String SATURATION = "saturation";
    private static final String LEVEL = "level";
    private static final String EXP = "exp";
    private static final String POTION_EFFECTS = "potionEffects";
    private static final String ALLOW_FLIGHT = "allowFlight";
    private static final String FLYING = "flying";
    private static final String FLY_SPEED = "flySpeed";

    // Inside a potion effect, again commons' field names.
    private static final String EFFECT_TYPE = "type";
    private static final String EFFECT_DURATION = "duration";
    private static final String EFFECT_AMPLIFIER = "amplifier";
    private static final String EFFECT_AMBIENT = "ambient";
    private static final String EFFECT_PARTICLES = "particles";
    private static final String EFFECT_ICON = "icon";

    // Added by this library. Commons ignores any key it does not know, so
    // writing them costs an old reader nothing.
    private static final String ENDER_CHEST = "enderChest";
    private static final String PHYSICAL = "physical";
    private static final String FIRE_TICKS = "fireTicks";
    private static final String REMAINING_AIR = "remainingAir";
    private static final String VELOCITY_X = "velocityX";
    private static final String VELOCITY_Y = "velocityY";
    private static final String VELOCITY_Z = "velocityZ";
    private static final String WALK_SPEED = "walkSpeed";
    private static final String INVULNERABLE = "invulnerable";

    /** Ignores what it cannot read, which is what a bare decode asks for. */
    private static final Consumer<String> SILENT = problem -> { };

    /**
     * How one item becomes text and back.
     *
     * <p>A seam rather than a straight call, because {@code ItemStack} cannot be
     * serialised without a running server: the class initialises a registry that
     * only exists inside one. Without this the wire format &mdash; the one thing
     * in this module that a mistake in costs a production inventory &mdash;
     * could only be tested by starting a server, which means in practice it
     * would not be tested.
     */
    interface ItemIo {

        boolean isEmpty(@NotNull ItemStack stack);

        @Nullable String encode(@NotNull ItemStack stack);

        @Nullable ItemStack decode(@NotNull String stored);
    }

    /** Bukkit's own versioned bytes, Base64'd. What commons wrote. */
    private static final ItemIo BUKKIT = new ItemIo() {

        @Override
        public boolean isEmpty(@NotNull ItemStack stack) {
            return stack.getType().isAir();
        }

        @Override
        public @Nullable String encode(@NotNull ItemStack stack) {
            return Base64.getEncoder().encodeToString(stack.serializeAsBytes());
        }

        @Override
        public @Nullable ItemStack decode(@NotNull String stored) {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(stored));
        }
    };

    private static volatile ItemIo items = BUKKIT;

    /** Test seam: how an item is encoded, without a server to do it. */
    static void setItems(@NotNull ItemIo replacement) {
        items = replacement;
    }

    /** Test seam: back to Bukkit's own form. */
    static void resetItems() {
        items = BUKKIT;
    }

    // --------------------------------------------------------------- encoding

    /**
     * Writes a snapshot the way the column expects it.
     *
     * @param snapshot the snapshot
     * @return the JSON object, never {@code null}
     */
    public static @NotNull String encode(@NotNull Snapshot snapshot) {
        JsonObject json = new JsonObject();

        // In the order ExyliaCommons put them, which makes a stored row and the
        // documentation above read the same way. Gson parses by key, so order
        // has never been part of the contract in either direction.
        if (snapshot.gameMode() == null) {
            json.add(GAME_MODE, com.google.gson.JsonNull.INSTANCE);
        } else {
            json.addProperty(GAME_MODE, snapshot.gameMode().name());
        }
        json.add(ARMOR, items(snapshot.armor()));
        json.add(INVENTORY, items(snapshot.inventory()));
        json.add(OFF_HAND, item(snapshot.offHand()));
        json.addProperty(HEALTH, snapshot.health());
        json.addProperty(MAX_HEALTH, snapshot.maxHealth());
        json.addProperty(FOOD_LEVEL, snapshot.foodLevel());
        json.addProperty(SATURATION, snapshot.saturation());
        json.addProperty(LEVEL, snapshot.level());
        json.addProperty(EXP, snapshot.exp());
        json.add(POTION_EFFECTS, effects(snapshot.potionEffects()));
        json.addProperty(ALLOW_FLIGHT, snapshot.allowFlight());
        json.addProperty(FLYING, snapshot.flying());
        json.addProperty(FLY_SPEED, snapshot.flySpeed());

        // Only when captured. A snapshot read from a commons row and written
        // back out again is byte-identical to what commons would have written,
        // rather than growing two keys it never had.
        if (snapshot.enderChest() != null) {
            json.add(ENDER_CHEST, items(snapshot.enderChest()));
        }
        Snapshot.Physical physical = snapshot.physical();
        if (physical != null) {
            JsonObject state = new JsonObject();
            state.addProperty(FIRE_TICKS, physical.fireTicks());
            state.addProperty(REMAINING_AIR, physical.remainingAir());
            state.addProperty(VELOCITY_X, physical.velocityX());
            state.addProperty(VELOCITY_Y, physical.velocityY());
            state.addProperty(VELOCITY_Z, physical.velocityZ());
            state.addProperty(WALK_SPEED, physical.walkSpeed());
            state.addProperty(INVULNERABLE, physical.invulnerable());
            json.add(PHYSICAL, state);
        }
        return json.toString();
    }

    private static JsonElement items(ItemStack @Nullable [] items) {
        if (items == null) {
            return com.google.gson.JsonNull.INSTANCE;
        }
        JsonArray array = new JsonArray();
        for (ItemStack stack : items) {
            array.add(item(stack));
        }
        return array;
    }

    private static JsonElement item(@Nullable ItemStack stack) {
        String encoded = encodeItem(stack);
        return encoded == null ? com.google.gson.JsonNull.INSTANCE
                : new com.google.gson.JsonPrimitive(encoded);
    }

    /**
     * Encodes one item the way a stored slot holds it.
     *
     * <p>Bukkit's own versioned byte form, Base64'd &mdash; exactly what
     * ExyliaCommons' {@code ItemStackSerializer} produced, and exactly what the
     * library's single-{@code ItemStack} codec produces. An empty slot has no
     * representation and is stored as absent.
     *
     * @param stack the item, possibly {@code null}
     * @return the Base64 form, or {@code null} for an empty slot
     */
    public static @Nullable String encodeItem(@Nullable ItemStack stack) {
        if (stack == null) {
            return null;
        }
        try {
            if (items.isEmpty(stack)) {
                return null;
            }
            return items.encode(stack);
        } catch (RuntimeException | LinkageError unwritable) {
            return null;
        }
    }

    /**
     * Whether an item is an empty slot rather than a thing.
     *
     * <p>Here rather than at each call site because "empty" and "has no stored
     * form" are the same question, and this is where the second one is answered.
     *
     * @param stack the item, possibly {@code null}
     * @return whether it is nothing
     */
    public static boolean isEmpty(@Nullable ItemStack stack) {
        if (stack == null) {
            return true;
        }
        try {
            return items.isEmpty(stack);
        } catch (RuntimeException | LinkageError unreadable) {
            return true;
        }
    }

    /**
     * Decodes one stored slot.
     *
     * @param stored the Base64 form
     * @return the item, or {@code null} when it cannot be read
     */
    public static @Nullable ItemStack decodeItem(@NotNull String stored) {
        try {
            return items.decode(stored);
        } catch (RuntimeException | LinkageError unreadable) {
            return null;
        }
    }

    private static JsonArray effects(List<Snapshot.Effect> effects) {
        JsonArray array = new JsonArray();
        for (Snapshot.Effect effect : effects) {
            JsonObject json = new JsonObject();
            json.addProperty(EFFECT_TYPE, effect.type());
            json.addProperty(EFFECT_DURATION, effect.duration());
            json.addProperty(EFFECT_AMPLIFIER, effect.amplifier());
            json.addProperty(EFFECT_AMBIENT, effect.ambient());
            json.addProperty(EFFECT_PARTICLES, effect.particles());
            json.addProperty(EFFECT_ICON, effect.icon());
            array.add(json);
        }
        return array;
    }

    // --------------------------------------------------------------- decoding

    /**
     * Reads a stored snapshot, ignoring whatever it cannot read.
     *
     * @param stored the column's contents
     * @return the snapshot, or {@code null} when the text is not a snapshot
     */
    public static @Nullable Snapshot decode(@Nullable String stored) {
        return decode(stored, SILENT);
    }

    /**
     * Reads a stored snapshot and says what it had to skip.
     *
     * <p>The reporting form, used by the store so a broken item reaches the
     * console once instead of disappearing. Only a row that is not JSON at all
     * comes back {@code null}; anything else yields a snapshot with the
     * unreadable parts missing.
     *
     * @param stored   the column's contents
     * @param problems told about each part that had to be skipped
     * @return the snapshot, or {@code null} when the text is not a snapshot
     */
    public static @Nullable Snapshot decode(@Nullable String stored,
                                            @NotNull Consumer<String> problems) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        JsonObject json;
        try {
            JsonElement parsed = JsonParser.parseString(stored);
            if (!parsed.isJsonObject()) {
                problems.accept("the stored snapshot is not a JSON object");
                return null;
            }
            json = parsed.getAsJsonObject();
        } catch (RuntimeException malformed) {
            problems.accept("the stored snapshot is not valid JSON: " + malformed.getMessage());
            return null;
        }

        GameMode gameMode = null;
        String mode = string(json, GAME_MODE);
        if (mode != null) {
            try {
                gameMode = GameMode.valueOf(mode);
            } catch (IllegalArgumentException unknown) {
                // A game mode this server does not have costs the game mode and
                // nothing else. Commons threw here, which cost the whole row.
                problems.accept("the game mode \"" + mode + "\" is not one this server has");
            }
        }

        return new Snapshot(
                gameMode,
                items(json, INVENTORY, problems),
                items(json, ARMOR, problems),
                slot(json, OFF_HAND, problems),
                items(json, ENDER_CHEST, problems),
                number(json, HEALTH, 0.0d).doubleValue(),
                number(json, MAX_HEALTH, 0.0d).doubleValue(),
                number(json, FOOD_LEVEL, 0).intValue(),
                number(json, SATURATION, 0f).floatValue(),
                number(json, LEVEL, 0).intValue(),
                number(json, EXP, 0f).floatValue(),
                effects(json, problems),
                bool(json, ALLOW_FLIGHT, false),
                bool(json, FLYING, false),
                number(json, FLY_SPEED, 0f).floatValue(),
                physical(json, problems));
    }

    private static ItemStack @Nullable [] items(JsonObject json, String key,
                                                Consumer<String> problems) {
        JsonElement element = json.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonArray()) {
            return null;
        }
        JsonArray array = element.getAsJsonArray();
        ItemStack[] items = new ItemStack[array.size()];
        for (int slot = 0; slot < array.size(); slot++) {
            JsonElement stored = array.get(slot);
            if (stored == null || stored.isJsonNull() || !stored.isJsonPrimitive()) {
                continue;
            }
            ItemStack read = decodeItem(stored.getAsString());
            if (read == null) {
                // The whole point of the rewrite: one item nobody can read costs
                // its own slot, not the player's entire inventory.
                problems.accept("slot " + slot + " of " + key
                        + " could not be read and is empty; the rest is intact");
                continue;
            }
            items[slot] = read;
        }
        return items;
    }

    private static @Nullable ItemStack slot(JsonObject json, String key,
                                            Consumer<String> problems) {
        String stored = string(json, key);
        if (stored == null) {
            return null;
        }
        ItemStack read = decodeItem(stored);
        if (read == null) {
            problems.accept(key + " could not be read and is empty; the rest is intact");
        }
        return read;
    }

    private static List<Snapshot.Effect> effects(JsonObject json, Consumer<String> problems) {
        JsonElement element = json.get(POTION_EFFECTS);
        if (element == null || element.isJsonNull() || !element.isJsonArray()) {
            return List.of();
        }
        List<Snapshot.Effect> effects = new ArrayList<>();
        for (JsonElement entry : element.getAsJsonArray()) {
            if (entry == null || !entry.isJsonObject()) {
                continue;
            }
            JsonObject effect = entry.getAsJsonObject();
            String type = string(effect, EFFECT_TYPE);
            if (type == null) {
                problems.accept("a stored potion effect has no type and was skipped");
                continue;
            }
            effects.add(new Snapshot.Effect(type,
                    number(effect, EFFECT_DURATION, 0).intValue(),
                    number(effect, EFFECT_AMPLIFIER, 0).intValue(),
                    bool(effect, EFFECT_AMBIENT, false),
                    bool(effect, EFFECT_PARTICLES, false),
                    bool(effect, EFFECT_ICON, false)));
        }
        return effects;
    }

    private static Snapshot.@Nullable Physical physical(JsonObject json, Consumer<String> problems) {
        JsonElement element = json.get(PHYSICAL);
        if (element == null || element.isJsonNull()) {
            // A row written by ExyliaCommons. Absent, not zeroed: a zeroed
            // physical state would set every restored player's walk speed to
            // zero and stand them still.
            return null;
        }
        if (!element.isJsonObject()) {
            problems.accept("the stored physical state is not an object and was skipped");
            return null;
        }
        JsonObject state = element.getAsJsonObject();
        return new Snapshot.Physical(
                number(state, FIRE_TICKS, 0).intValue(),
                number(state, REMAINING_AIR, 0).intValue(),
                number(state, VELOCITY_X, 0.0d).doubleValue(),
                number(state, VELOCITY_Y, 0.0d).doubleValue(),
                number(state, VELOCITY_Z, 0.0d).doubleValue(),
                number(state, WALK_SPEED, 0f).floatValue(),
                bool(state, INVULNERABLE, false));
    }

    private static @Nullable String string(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        return element.getAsString();
    }

    private static Number number(JsonObject json, String key, Number fallback) {
        JsonElement element = json.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsNumber();
        } catch (RuntimeException notANumber) {
            return fallback;
        }
    }

    private static boolean bool(JsonObject json, String key, boolean fallback) {
        JsonElement element = json.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsBoolean();
        } catch (RuntimeException notABoolean) {
            return fallback;
        }
    }
}
