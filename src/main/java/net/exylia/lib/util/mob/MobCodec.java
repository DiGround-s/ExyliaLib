package net.exylia.lib.util.mob;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.exylia.lib.util.Effects;
import net.exylia.lib.util.Effects.ParsedEffect;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * The parts of a {@link MobTemplate} as a consumer stores them, one column each.
 *
 * <pre>{@code
 * row.skills(MobCodec.encodeSkills(template.skills()));
 * List<MobSkill> skills = MobCodec.decodeSkills(row.skills(),
 *         (where, problem) -> debug.warn("Mob " + row.id() + ", " + where + ": " + problem));
 * }</pre>
 *
 * <p>Equipment and rewards are not here: equipment is items and stores the way
 * the plugin already stores items, and rewards have {@link net.exylia.lib.util.reward.RewardCodec}.
 *
 * <h2>The shapes</h2>
 * <pre>{@code
 * skills      [{"trigger":"INTERVAL","type":"LEAP","cooldown":8.0,"amount":1.2}, ...]
 * attributes  {"max_health":80.0,"attack_damage":7.0}
 * flags       ["NO_SUN_BURN","NO_VANILLA_DROPS"]
 * effects     ["SPEED|2|infinite","FIRE_RESISTANCE|1|infinite"]
 * behaviour   {"hits":40,"hitCooldown":0.5,"lifetime":300.0,"roam":12.0}
 * look        {"variant":"CREAMY","body":"CYCLE","glow":"RANDOM","aura":"confetti"}
 * }</pre>
 *
 * <p>A field that holds its default is not written, times are seconds, and an
 * empty part is stored as {@code null}, not as {@code []}. A chance or
 * threshold out of {@code 0-1} reads clamped into it.
 *
 * <h2>Reading is tolerant</h2>
 * An unknown trigger, type or flag, an attribute that is not a number and an
 * effect line nobody can read cost that one piece, which is reported and
 * skipped. The rest of the column is still read: a template whose one skill
 * was written by a newer plugin must not lose its other nine.
 *
 * @since 1.192.0
 */
public final class MobCodec {

    private static final String TRIGGER = "trigger";
    private static final String TYPE = "type";
    private static final String CHANCE = "chance";
    private static final String COOLDOWN = "cooldown";
    private static final String THRESHOLD = "threshold";
    private static final String RADIUS = "radius";
    private static final String AMOUNT = "amount";
    private static final String DURATION = "duration";
    private static final String TEXT = "text";
    private static final String EFFECT = "effect";
    private static final String HITS = "hits";
    private static final String HIT_COOLDOWN = "hitCooldown";
    private static final String LIFETIME = "lifetime";
    private static final String ROAM = "roam";
    private static final String VARIANT = "variant";
    private static final String BODY = "body";
    private static final String GLOW = "glow";
    private static final String AURA = "aura";

    private static final double DEFAULT_CHANCE = 1;
    private static final double DEFAULT_THRESHOLD = 0.3;

    /** Ignores what it cannot read, which is what a stored row deserves. */
    private static final BiConsumer<String, String> SILENT = (where, problem) -> { };

    private MobCodec() {
        throw new AssertionError("No instances.");
    }

    // ------------------------------------------------------------------ skills

    /**
     * Writes a skill list.
     *
     * @param skills the skills
     * @return the JSON array, or {@code null} for none
     */
    public static @Nullable String encodeSkills(@NotNull List<MobSkill> skills) {
        if (skills.isEmpty()) return null;
        JsonArray array = new JsonArray();
        for (MobSkill skill : skills) {
            JsonObject json = new JsonObject();
            json.addProperty(TRIGGER, skill.trigger().name());
            json.addProperty(TYPE, skill.type().name());
            if (skill.chance() != DEFAULT_CHANCE) json.addProperty(CHANCE, skill.chance());
            if (!skill.cooldown().isZero()) json.addProperty(COOLDOWN, seconds(skill.cooldown()));
            if (skill.threshold() != DEFAULT_THRESHOLD) json.addProperty(THRESHOLD, skill.threshold());
            if (skill.radius() != 0) json.addProperty(RADIUS, skill.radius());
            if (skill.amount() != 0) json.addProperty(AMOUNT, skill.amount());
            if (!skill.duration().isZero()) json.addProperty(DURATION, seconds(skill.duration()));
            if (!skill.text().isEmpty()) json.addProperty(TEXT, skill.text());
            if (!skill.effect().isEmpty()) json.addProperty(EFFECT, skill.effect());
            array.add(json);
        }
        return array.toString();
    }

    /** Reads a stored skill list, ignoring what it cannot understand. */
    public static @NotNull List<MobSkill> decodeSkills(@Nullable String stored) {
        return decodeSkills(stored, SILENT);
    }

    /**
     * Reads a stored skill list, reporting what it had to skip.
     *
     * @param stored   the column value, possibly {@code null}
     * @param problems told where the trouble was and what it was
     * @return the skills that could be read
     */
    public static @NotNull List<MobSkill> decodeSkills(@Nullable String stored,
                                                       @NotNull BiConsumer<String, String> problems) {
        JsonArray array = array(stored, "skills", problems);
        List<MobSkill> skills = new ArrayList<>(array.size());
        for (int index = 0; index < array.size(); index++) {
            String where = "skills[" + index + "]";
            JsonElement element = array.get(index);
            if (!element.isJsonObject()) {
                problems.accept(where, "not a skill");
                continue;
            }
            JsonObject json = element.getAsJsonObject();
            MobSkill.Trigger trigger = constant(MobSkill.Trigger.class, string(json, TRIGGER));
            MobSkill.Type type = constant(MobSkill.Type.class, string(json, TYPE));
            if (trigger == null || type == null) {
                problems.accept(where, "unknown trigger or type " + string(json, TRIGGER) + "/" + string(json, TYPE));
                continue;
            }
            skills.add(new MobSkill(trigger, type,
                    number(json, CHANCE, DEFAULT_CHANCE),
                    duration(number(json, COOLDOWN, 0)),
                    number(json, THRESHOLD, DEFAULT_THRESHOLD),
                    number(json, RADIUS, 0),
                    number(json, AMOUNT, 0),
                    duration(number(json, DURATION, 0)),
                    string(json, TEXT),
                    string(json, EFFECT)));
        }
        return List.copyOf(skills);
    }

    // --------------------------------------------------------------- behaviour

    /**
     * Writes a behaviour.
     *
     * @param behaviour the behaviour
     * @return the JSON object, or {@code null} for {@link MobBehaviour#NONE}
     * @since 1.195.0
     */
    public static @Nullable String encodeBehaviour(@NotNull MobBehaviour behaviour) {
        if (behaviour.equals(MobBehaviour.NONE)) return null;
        JsonObject json = new JsonObject();
        if (behaviour.hits() != 0) json.addProperty(HITS, behaviour.hits());
        if (!behaviour.hitCooldown().isZero()) json.addProperty(HIT_COOLDOWN, seconds(behaviour.hitCooldown()));
        if (!behaviour.lifetime().isZero()) json.addProperty(LIFETIME, seconds(behaviour.lifetime()));
        if (behaviour.roam() != 0) json.addProperty(ROAM, behaviour.roam());
        return json.toString();
    }

    /** Reads a stored behaviour, ignoring what it cannot understand. @since 1.195.0 */
    public static @NotNull MobBehaviour decodeBehaviour(@Nullable String stored) {
        return decodeBehaviour(stored, SILENT);
    }

    /**
     * Reads a stored behaviour, reporting what it had to skip. A field that is
     * missing or not a number keeps its default.
     *
     * @param stored   the column value, possibly {@code null}
     * @param problems told where the trouble was and what it was
     * @return the behaviour, {@link MobBehaviour#NONE} for nothing readable
     * @since 1.195.0
     */
    public static @NotNull MobBehaviour decodeBehaviour(@Nullable String stored,
                                                        @NotNull BiConsumer<String, String> problems) {
        JsonObject json = object(stored, "behaviour", problems);
        if (json == null) return MobBehaviour.NONE;
        double hits = number(json, HITS, 0);
        return new MobBehaviour((int) Math.min(Integer.MAX_VALUE, Math.max(0, hits)),
                duration(number(json, HIT_COOLDOWN, 0)),
                duration(number(json, LIFETIME, 0)),
                number(json, ROAM, 0));
    }

    // -------------------------------------------------------------------- look

    /**
     * Writes a look.
     *
     * @param look the look
     * @return the JSON object, or {@code null} for {@link MobLook#NONE}
     * @since 1.195.0
     */
    public static @Nullable String encodeLook(@NotNull MobLook look) {
        if (look.equals(MobLook.NONE)) return null;
        JsonObject json = new JsonObject();
        if (!look.variant().isEmpty()) json.addProperty(VARIANT, look.variant());
        if (!look.body().isEmpty()) json.addProperty(BODY, look.body());
        if (!look.glow().isEmpty()) json.addProperty(GLOW, look.glow());
        if (!look.aura().isEmpty()) json.addProperty(AURA, look.aura());
        return json.toString();
    }

    /** Reads a stored look, ignoring what it cannot understand. @since 1.195.0 */
    public static @NotNull MobLook decodeLook(@Nullable String stored) {
        return decodeLook(stored, SILENT);
    }

    /**
     * Reads a stored look, reporting what it had to skip. The names are not
     * checked here: a variant or aura the server does not know is the engine's
     * to report as the mob spawns.
     *
     * @param stored   the column value, possibly {@code null}
     * @param problems told where the trouble was and what it was
     * @return the look, {@link MobLook#NONE} for nothing readable
     * @since 1.195.0
     */
    public static @NotNull MobLook decodeLook(@Nullable String stored, @NotNull BiConsumer<String, String> problems) {
        JsonObject json = object(stored, "look", problems);
        if (json == null) return MobLook.NONE;
        return new MobLook(string(json, VARIANT), string(json, BODY), string(json, GLOW), string(json, AURA));
    }

    // -------------------------------------------------------------- attributes

    /**
     * Writes attribute base values.
     *
     * @param attributes values by attribute key
     * @return the JSON object, or {@code null} for none
     */
    public static @Nullable String encodeAttributes(@NotNull Map<String, Double> attributes) {
        if (attributes.isEmpty()) return null;
        JsonObject json = new JsonObject();
        // Sorted, so the same values always store as the same text.
        attributes.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> json.addProperty(entry.getKey(), entry.getValue()));
        return json.toString();
    }

    /** Reads stored attributes, ignoring what it cannot understand. */
    public static @NotNull Map<String, Double> decodeAttributes(@Nullable String stored) {
        return decodeAttributes(stored, SILENT);
    }

    /**
     * Reads stored attributes, reporting what it had to skip.
     *
     * <p>Keys are normalised: {@code minecraft:max_health} and the pre-1.21.2
     * {@code generic.max_health} both read as {@code max_health}.
     *
     * @param stored   the column value, possibly {@code null}
     * @param problems told where the trouble was and what it was
     * @return the values that could be read
     */
    public static @NotNull Map<String, Double> decodeAttributes(@Nullable String stored,
                                                                @NotNull BiConsumer<String, String> problems) {
        if (stored == null || stored.isBlank()) return Map.of();
        JsonElement root = parse(stored, "attributes", problems);
        if (root == null) return Map.of();
        if (!root.isJsonObject()) {
            problems.accept("attributes", "expected an object of values");
            return Map.of();
        }
        Map<String, Double> attributes = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
            String key = attributeKey(entry.getKey());
            JsonElement value = entry.getValue();
            Double number = value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                    ? value.getAsDouble() : null;
            if (key.isEmpty() || number == null || !Double.isFinite(number)) {
                problems.accept("attributes." + entry.getKey(), "not a number");
                continue;
            }
            attributes.put(key, number);
        }
        return Map.copyOf(attributes);
    }

    /**
     * An attribute key as the library stores it: lower case, no namespace for
     * vanilla, no {@code generic.} prefix.
     *
     * @param written the key as written
     * @return the key
     */
    public static @NotNull String attributeKey(@NotNull String written) {
        String key = written.trim().toLowerCase(Locale.ROOT);
        if (key.startsWith("minecraft:")) key = key.substring("minecraft:".length());
        int dot = key.indexOf('.');
        if (dot >= 0 && key.indexOf(':') < 0) key = key.substring(dot + 1);
        return key;
    }

    // ------------------------------------------------------------------- flags

    /**
     * Writes flags.
     *
     * @param flags the flags
     * @return the JSON array in declaration order, or {@code null} for none
     */
    public static @Nullable String encodeFlags(@NotNull Set<MobFlag> flags) {
        if (flags.isEmpty()) return null;
        JsonArray array = new JsonArray();
        for (MobFlag flag : EnumSet.copyOf(flags)) array.add(flag.name());
        return array.toString();
    }

    /** Reads stored flags, ignoring what it cannot understand. */
    public static @NotNull Set<MobFlag> decodeFlags(@Nullable String stored) {
        return decodeFlags(stored, SILENT);
    }

    /**
     * Reads stored flags, reporting what it had to skip.
     *
     * @param stored   the column value, possibly {@code null}
     * @param problems told where the trouble was and what it was
     * @return the flags that could be read
     */
    public static @NotNull Set<MobFlag> decodeFlags(@Nullable String stored,
                                                    @NotNull BiConsumer<String, String> problems) {
        JsonArray array = array(stored, "flags", problems);
        Set<MobFlag> flags = EnumSet.noneOf(MobFlag.class);
        for (int index = 0; index < array.size(); index++) {
            JsonElement element = array.get(index);
            MobFlag flag = element.isJsonPrimitive() ? constant(MobFlag.class, element.getAsString()) : null;
            if (flag == null) {
                problems.accept("flags[" + index + "]", "unknown flag " + element);
                continue;
            }
            flags.add(flag);
        }
        return flags.isEmpty() ? Set.of() : Set.copyOf(flags);
    }

    // ----------------------------------------------------------------- effects

    /**
     * Writes potion effects as their config lines.
     *
     * @param effects the effects
     * @return the JSON array of lines, or {@code null} for none
     */
    public static @Nullable String encodeEffects(@NotNull List<ParsedEffect> effects) {
        if (effects.isEmpty()) return null;
        JsonArray array = new JsonArray();
        for (ParsedEffect effect : effects) array.add(effect.line());
        return array.toString();
    }

    /** Reads stored potion effects, ignoring what it cannot understand. */
    public static @NotNull List<ParsedEffect> decodeEffects(@Nullable String stored) {
        return decodeEffects(stored, SILENT);
    }

    /**
     * Reads stored potion effects, reporting what it had to skip.
     *
     * @param stored   the column value, possibly {@code null}
     * @param problems told where the trouble was and what it was
     * @return the effects that could be read
     */
    public static @NotNull List<ParsedEffect> decodeEffects(@Nullable String stored,
                                                            @NotNull BiConsumer<String, String> problems) {
        JsonArray array = array(stored, "effects", problems);
        List<ParsedEffect> effects = new ArrayList<>(array.size());
        for (int index = 0; index < array.size(); index++) {
            JsonElement element = array.get(index);
            ParsedEffect effect = element.isJsonPrimitive() ? Effects.parse(element.getAsString()) : null;
            if (effect == null) {
                problems.accept("effects[" + index + "]", "not an effect line: " + element);
                continue;
            }
            effects.add(effect);
        }
        return List.copyOf(effects);
    }

    // --------------------------------------------------------------- internals

    private static JsonArray array(@Nullable String stored, String where, BiConsumer<String, String> problems) {
        if (stored == null || stored.isBlank()) return new JsonArray();
        JsonElement root = parse(stored, where, problems);
        if (root == null) return new JsonArray();
        if (!root.isJsonArray()) {
            problems.accept(where, "expected a list");
            return new JsonArray();
        }
        return root.getAsJsonArray();
    }

    private static @Nullable JsonObject object(@Nullable String stored, String where,
                                               BiConsumer<String, String> problems) {
        if (stored == null || stored.isBlank()) return null;
        JsonElement root = parse(stored, where, problems);
        if (root == null) return null;
        if (!root.isJsonObject()) {
            problems.accept(where, "expected an object");
            return null;
        }
        return root.getAsJsonObject();
    }

    private static @Nullable JsonElement parse(String stored, String where, BiConsumer<String, String> problems) {
        try {
            return JsonParser.parseString(stored);
        } catch (RuntimeException malformed) {
            problems.accept(where, "not valid JSON: " + malformed.getMessage());
            return null;
        }
    }

    private static <E extends Enum<E>> @Nullable E constant(Class<E> type, @Nullable String name) {
        if (name == null) return null;
        try {
            return Enum.valueOf(type, name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private static @NotNull String string(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static double number(JsonObject json, String key, double fallback) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return fallback;
        return value.getAsDouble();
    }

    private static Duration duration(double seconds) {
        return Double.isFinite(seconds) && seconds > 0 ? Duration.ofMillis(Math.round(seconds * 1000)) : Duration.ZERO;
    }

    private static double seconds(Duration duration) {
        return duration.toMillis() / 1000.0;
    }
}
