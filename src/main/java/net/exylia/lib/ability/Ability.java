package net.exylia.lib.ability;

import net.exylia.lib.util.Formula;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One compiled ability: when it fires, the odds, the cooldown, what must hold
 * and what it does.
 *
 * <pre>{@code
 * trigger: ATTACK
 * chance: 6 + level * 4        # percent
 * cooldown: 8s
 * conditions:
 *   - target: player
 * effects:
 *   - damage: { target: victim, amount: 0.5 * level }
 * }</pre>
 *
 * <p>Built by {@link PluginAbilities#compile}, once, at load; immutable after
 * that and safe to share between threads. Recompiling on reload makes new
 * objects, which is how {@link PluginAbilities#refreshAll()} tells that every
 * holding changed.
 *
 * @since 1.385.0
 */
public final class Ability {

    private final String id;
    private final String trigger;
    private final Formula chance;
    private final Formula cooldown;
    private final List<AbilityCondition> conditions;
    private final List<AbilityEffect> effects;
    private final boolean chain;
    private final int every;
    private final Map<String, Object> source;
    private final String cooldownKey;

    Ability(String id, String trigger, Formula chance, Formula cooldown, List<AbilityCondition> conditions,
            List<AbilityEffect> effects, boolean chain, int every, Map<String, Object> source, String owner) {
        this.id = id;
        this.trigger = trigger;
        this.chance = chance;
        this.cooldown = cooldown;
        this.conditions = List.copyOf(conditions);
        this.effects = List.copyOf(effects);
        this.chain = chain;
        this.every = Math.max(1, every);
        this.source = source;
        this.cooldownKey = "ability:" + owner + ":" + id;
    }

    /** The id it was compiled under; also its cooldown's key. */
    public @NotNull String id() {
        return id;
    }

    /** Its trigger, upper case. */
    public @NotNull String trigger() {
        return trigger;
    }

    /**
     * The odds at a level, in percent, {@code 0} to {@code 100}.
     *
     * @param level the holder's level
     * @return the chance; the written default (100) when the formula has no answer
     */
    public double chance(int level) {
        return Math.max(0, Math.min(100, chance.applyOr(100, level)));
    }

    /**
     * The cooldown at a level.
     *
     * @param level the holder's level
     * @return the cooldown; zero for none
     */
    public @NotNull Duration cooldown(int level) {
        double seconds = cooldown.applyOr(0, level);
        return seconds <= 0 ? Duration.ZERO : Duration.ofMillis(Math.round(seconds * 1000));
    }

    /** The compiled conditions, in order. */
    public @NotNull List<AbilityCondition> conditions() {
        return conditions;
    }

    /** The compiled effects, in order. */
    public @NotNull List<AbilityEffect> effects() {
        return effects;
    }

    /** Whether its damage may fire ATTACK and HIT_TAKEN again ({@code chain: true}). */
    public boolean chain() {
        return chain;
    }

    /** For INTERVAL, the period in whole seconds, 1 or more ({@code every:}). */
    public int every() {
        return every;
    }

    /**
     * What the file wrote, unknown keys included, as a copy.
     *
     * <p>What the editor edits and what a plugin writes back on save: an
     * ability round-trips without losing a key the library did not know.
     */
    public @NotNull Map<String, Object> source() {
        return deepCopy(source);
    }

    /**
     * Its cooldown's key under {@code CooldownScope.player(holder)}: what a
     * tooltip reads to show the time left.
     */
    public @NotNull String cooldownKey() {
        return cooldownKey;
    }

    @Override
    public String toString() {
        return "Ability[" + id + " on " + trigger + "]";
    }

    @SuppressWarnings("unchecked")
    static <T> T deepCopy(T value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, inner) -> copy.put(String.valueOf(key), deepCopy(inner)));
            return (T) copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(inner -> copy.add(deepCopy(inner)));
            return (T) copy;
        }
        return value;
    }
}
