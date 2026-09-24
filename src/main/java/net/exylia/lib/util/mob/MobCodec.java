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
 * look        {"variant":"CREAMY","body":"CYCLE","glow":"RANDOM","aura":"confetti","death":"none","numbers":false}
 * fight       {"gcd":1.5,"groups":{"melee":6.0},"phases":[{"below":0.5,"suffix":" &c⚡","speed":1.3}]}
 * }</pre>
 *
 * <p>A skill cast some way other than at once carries a {@code "cast"} object
 * (since 1.198.0): {@code {"name":"slam","aim":"SELF","windup":0.9,"group":"melee",
 * "when":{"nearby":16.0,"maxRange":6.0}}}.
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
    private static final String SPAWN = "spawn";
    private static final String HURT = "hurt";
    private static final String DEATH = "death";
    private static final String LOW = "low";
    private static final String NUMBERS = "numbers";
    private static final String CAST = "cast";
    private static final String NAME = "name";
    private static final String AIM = "aim";
    private static final String WINDUP = "windup";
    private static final String STYLE = "style";
    private static final String TINT = "tint";
    private static final String SPREAD = "spread";
    private static final String WHEN = "when";
    private static final String GROUP = "group";
    private static final String THEN = "then";
    private static final String WINDUP_LINES = "windupLines";
    private static final String MIN_HEALTH = "minHealth";
    private static final String MAX_HEALTH = "maxHealth";
    private static final String MIN_RANGE = "minRange";
    private static final String MAX_RANGE = "maxRange";
    private static final String NEARBY = "nearby";
    private static final String PHASE = "phase";
    private static final String GCD = "gcd";
    private static final String GROUPS = "groups";
    private static final String PHASES = "phases";
    private static final String BELOW = "below";
    private static final String SUFFIX = "suffix";
    private static final String SPEED = "speed";
    private static final String DAMAGE = "damage";
    private static final String RESIST = "resist";

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
            if (!skill.cast().equals(MobSkill.Cast.NONE)) json.add(CAST, cast(skill.cast()));
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
                    string(json, EFFECT),
                    cast(json.get(CAST), where + ".cast", problems)));
        }
        return List.copyOf(skills);
    }

    private static JsonObject cast(MobSkill.Cast cast) {
        JsonObject json = new JsonObject();
        if (!cast.name().isEmpty()) json.addProperty(NAME, cast.name());
        if (cast.aim() != MobSkill.Aim.AUTO) json.addProperty(AIM, cast.aim().name());
        if (!cast.windup().isZero()) json.addProperty(WINDUP, seconds(cast.windup()));
        if (!cast.style().isEmpty()) json.addProperty(STYLE, cast.style());
        if (!cast.tint().isEmpty()) json.addProperty(TINT, cast.tint());
        if (cast.spread() != 0) json.addProperty(SPREAD, cast.spread());
        MobSkill.Gate when = cast.when();
        if (!when.equals(MobSkill.Gate.ANY)) {
            JsonObject gate = new JsonObject();
            if (when.minHealth() != 0) gate.addProperty(MIN_HEALTH, when.minHealth());
            if (when.maxHealth() != 1) gate.addProperty(MAX_HEALTH, when.maxHealth());
            if (when.minRange() != 0) gate.addProperty(MIN_RANGE, when.minRange());
            if (when.maxRange() != 0) gate.addProperty(MAX_RANGE, when.maxRange());
            if (when.nearby() != 0) gate.addProperty(NEARBY, when.nearby());
            if (when.phase() != 0) gate.addProperty(PHASE, when.phase());
            json.add(WHEN, gate);
        }
        if (!cast.group().isEmpty()) json.addProperty(GROUP, cast.group());
        if (!cast.then().isEmpty()) json.addProperty(THEN, cast.then());
        if (!cast.windupLines().isEmpty()) json.addProperty(WINDUP_LINES, cast.windupLines());
        return json;
    }

    /** A missing cast is {@link MobSkill.Cast#NONE}; an unknown aim reads as AUTO and is reported. */
    private static MobSkill.Cast cast(@Nullable JsonElement element, String where,
                                      BiConsumer<String, String> problems) {
        if (element == null || element.isJsonNull()) return MobSkill.Cast.NONE;
        if (!element.isJsonObject()) {
            problems.accept(where, "not a cast");
            return MobSkill.Cast.NONE;
        }
        JsonObject json = element.getAsJsonObject();
        String aimName = string(json, AIM);
        MobSkill.Aim aim = aimName.isEmpty() ? MobSkill.Aim.AUTO : constant(MobSkill.Aim.class, aimName);
        if (aim == null) {
            problems.accept(where, "unknown aim " + aimName + "; read as AUTO");
            aim = MobSkill.Aim.AUTO;
        }
        MobSkill.Gate when = MobSkill.Gate.ANY;
        JsonElement gate = json.get(WHEN);
        if (gate != null && gate.isJsonObject()) {
            JsonObject values = gate.getAsJsonObject();
            when = new MobSkill.Gate(number(values, MIN_HEALTH, 0), number(values, MAX_HEALTH, 1),
                    number(values, MIN_RANGE, 0), number(values, MAX_RANGE, 0), number(values, NEARBY, 0),
                    (int) Math.max(0, Math.min(Integer.MAX_VALUE, number(values, PHASE, 0))));
        } else if (gate != null && !gate.isJsonNull()) {
            problems.accept(where + ".when", "not a condition object");
        }
        return new MobSkill.Cast(string(json, NAME), aim, duration(number(json, WINDUP, 0)),
                string(json, STYLE), string(json, TINT), number(json, SPREAD, 0), when,
                string(json, GROUP), string(json, THEN), string(json, WINDUP_LINES));
    }

    // ------------------------------------------------------------------- fight

    /**
     * Writes a fight.
     *
     * @param fight the fight
     * @return the JSON object, or {@code null} for {@link MobFight#NONE}
     * @since 1.198.0
     */
    public static @Nullable String encodeFight(@NotNull MobFight fight) {
        if (fight.equals(MobFight.NONE)) return null;
        JsonObject json = new JsonObject();
        if (!fight.globalCooldown().isZero()) json.addProperty(GCD, seconds(fight.globalCooldown()));
        if (!fight.groups().isEmpty()) {
            JsonObject groups = new JsonObject();
            fight.groups().forEach((name, period) -> groups.addProperty(name, seconds(period)));
            json.add(GROUPS, groups);
        }
        if (!fight.phases().isEmpty()) {
            JsonArray phases = new JsonArray();
            for (MobPhase phase : fight.phases()) {
                JsonObject each = new JsonObject();
                each.addProperty(BELOW, phase.below());
                if (!phase.style().isEmpty()) each.addProperty(STYLE, phase.style());
                if (!phase.suffix().isEmpty()) each.addProperty(SUFFIX, phase.suffix());
                if (phase.speed() != 1) each.addProperty(SPEED, phase.speed());
                if (phase.damage() != 1) each.addProperty(DAMAGE, phase.damage());
                if (phase.resist() != 1) each.addProperty(RESIST, phase.resist());
                phases.add(each);
            }
            json.add(PHASES, phases);
        }
        return json.toString();
    }

    /** Reads a stored fight, ignoring what it cannot understand. @since 1.198.0 */
    public static @NotNull MobFight decodeFight(@Nullable String stored) {
        return decodeFight(stored, SILENT);
    }

    /**
     * Reads a stored fight, reporting what it had to skip. A group period or a
     * phase that is not readable costs itself; a missing field keeps its default.
     *
     * @param stored   the column value, possibly {@code null}
     * @param problems told where the trouble was and what it was
     * @return the fight, {@link MobFight#NONE} for nothing readable
     * @since 1.198.0
     */
    public static @NotNull MobFight decodeFight(@Nullable String stored, @NotNull BiConsumer<String, String> problems) {
        JsonObject json = object(stored, "fight", problems);
        if (json == null) return MobFight.NONE;
        Map<String, Duration> groups = new LinkedHashMap<>();
        JsonElement groupsJson = json.get(GROUPS);
        if (groupsJson != null && groupsJson.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : groupsJson.getAsJsonObject().entrySet()) {
                JsonElement value = entry.getValue();
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                    problems.accept("fight.groups." + entry.getKey(), "not a number of seconds");
                    continue;
                }
                groups.put(entry.getKey(), duration(value.getAsDouble()));
            }
        }
        List<MobPhase> phases = new ArrayList<>();
        JsonElement phasesJson = json.get(PHASES);
        if (phasesJson != null && phasesJson.isJsonArray()) {
            JsonArray array = phasesJson.getAsJsonArray();
            for (int index = 0; index < array.size(); index++) {
                JsonElement element = array.get(index);
                if (!element.isJsonObject() || number(element.getAsJsonObject(), BELOW, -1) <= 0) {
                    problems.accept("fight.phases[" + index + "]", "not a phase with a below share");
                    continue;
                }
                JsonObject phase = element.getAsJsonObject();
                phases.add(new MobPhase(number(phase, BELOW, 0), string(phase, STYLE), string(phase, SUFFIX),
                        number(phase, SPEED, 1), number(phase, DAMAGE, 1), number(phase, RESIST, 1)));
            }
        }
        return new MobFight(duration(number(json, GCD, 0)), groups, phases);
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
        if (!look.spawn().isEmpty()) json.addProperty(SPAWN, look.spawn());
        if (!look.hurt().isEmpty()) json.addProperty(HURT, look.hurt());
        if (!look.death().isEmpty()) json.addProperty(DEATH, look.death());
        if (!look.low().isEmpty()) json.addProperty(LOW, look.low());
        if (!look.numbers()) json.addProperty(NUMBERS, false);
        return json.toString();
    }

    /** Reads a stored look, ignoring what it cannot understand. @since 1.195.0 */
    public static @NotNull MobLook decodeLook(@Nullable String stored) {
        return decodeLook(stored, SILENT);
    }

    /**
     * Reads a stored look, reporting what it had to skip. The names are not
     * checked here: a variant or aura the server does not know is the engine's
     * to report as the mob spawns, and a reaction it does not know plays AUTO.
     * A look stored before reactions existed reads with every reaction AUTO
     * and numbers on.
     *
     * @param stored   the column value, possibly {@code null}
     * @param problems told where the trouble was and what it was
     * @return the look, {@link MobLook#NONE} for nothing readable
     * @since 1.195.0
     */
    public static @NotNull MobLook decodeLook(@Nullable String stored, @NotNull BiConsumer<String, String> problems) {
        JsonObject json = object(stored, "look", problems);
        if (json == null) return MobLook.NONE;
        JsonElement numbers = json.get(NUMBERS);
        boolean shown = numbers == null || !numbers.isJsonPrimitive() || !numbers.getAsJsonPrimitive().isBoolean()
                || numbers.getAsBoolean();
        return new MobLook(string(json, VARIANT), string(json, BODY), string(json, GLOW), string(json, AURA),
                string(json, SPAWN), string(json, HURT), string(json, DEATH), string(json, LOW), shown);
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
