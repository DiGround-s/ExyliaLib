package net.exylia.lib.util.mob;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * How a mob fights as a whole, rather than skill by skill: a breath between
 * attacks, skills that take turns, and phases.
 *
 * <pre>{@code
 * MobFight fight = new MobFight(Duration.ofMillis(1500),
 *         Map.of("melee", Duration.ofSeconds(6)),
 *         List.of(new MobPhase(0.5, "", "&c⚡", 1.3, 1.25, 1)));
 * }</pre>
 *
 * <h2>Rotation groups</h2>
 * {@link MobSkill.Trigger#INTERVAL} skills sharing a {@link MobSkill.Cast#group()}
 * do not roll on their own: once per the group's period exactly one of them is
 * cast, picked among those whose conditions hold with their {@code chance} as a
 * weight. A period in which none of them may be cast is not spent, so the group
 * goes off as soon as one can. A member's own {@code cooldown} is the least time
 * between two of its casts; zero lets it be picked every period.
 *
 * <h2>Phases</h2>
 * The fight starts in phase 1 and moves to phase {@code n + 1} as its health
 * drops below the {@code n}-th highest {@link MobPhase#below()}. It never goes
 * back: healing does not calm an enraged mob. Entering a phase casts the
 * {@link MobSkill.Trigger#PHASE} skills.
 *
 * @param globalCooldown after any major skill ({@link MobSkill#major()}), how long before
 *                       another major one may start; zero for none
 * @param groups         each rotation group's period, by name; a group not here uses
 *                       {@link #DEFAULT_PERIOD}. Sorted by name
 * @param phases         sorted by {@link MobPhase#below()}, highest first
 * @since 1.198.0
 */
public record MobFight(@NotNull Duration globalCooldown, @NotNull Map<String, Duration> groups,
                       @NotNull List<MobPhase> phases) {

    /** No global cooldown, no group periods, no phases: every skill on its own. */
    public static final MobFight NONE = new MobFight(Duration.ZERO, Map.of(), List.of());

    /** The period of a rotation group nobody set one for. */
    public static final Duration DEFAULT_PERIOD = Duration.ofSeconds(10);

    public MobFight {
        globalCooldown = globalCooldown == null || globalCooldown.isNegative() ? Duration.ZERO : globalCooldown;
        TreeMap<String, Duration> sorted = new TreeMap<>();
        if (groups != null) {
            groups.forEach((name, period) -> {
                if (name != null && !name.isBlank() && period != null && !period.isNegative()) {
                    sorted.put(name.trim(), period);
                }
            });
        }
        groups = Collections.unmodifiableMap(sorted);
        List<MobPhase> ordered = new ArrayList<>(phases == null ? List.of() : phases);
        ordered.removeIf(phase -> phase == null || phase.below() <= 0 || phase.below() >= 1);
        ordered.sort(Comparator.comparingDouble(MobPhase::below).reversed());
        phases = List.copyOf(ordered);
    }

    /**
     * How often a rotation group casts: its own period, else {@link #DEFAULT_PERIOD};
     * never faster than {@link MobSkill#MIN_INTERVAL}.
     *
     * @param group the group's name
     * @return the period
     */
    public @NotNull Duration period(@NotNull String group) {
        Duration period = groups.getOrDefault(group, DEFAULT_PERIOD);
        return period.compareTo(MobSkill.MIN_INTERVAL) < 0 ? MobSkill.MIN_INTERVAL : period;
    }

    /**
     * The phase a share of health falls in: one plus every threshold it is below.
     *
     * @param share health over maximum health (hits left over hits in hits mode)
     * @return the phase, {@code 1} at the start
     */
    public int phaseAt(double share) {
        int phase = 1;
        for (MobPhase each : phases) {
            if (share < each.below()) phase++;
        }
        return phase;
    }

    /**
     * What a phase changes.
     *
     * @param phase a phase, {@code 1} at the start
     * @return its settings, or {@code null} for phase 1 and anything past the last
     */
    public @Nullable MobPhase phase(int phase) {
        return phase >= 2 && phase - 2 < phases.size() ? phases.get(phase - 2) : null;
    }

    public @NotNull MobFight withGlobalCooldown(@NotNull Duration globalCooldown) {
        return new MobFight(globalCooldown, groups, phases);
    }

    public @NotNull MobFight withGroups(@NotNull Map<String, Duration> groups) {
        return new MobFight(globalCooldown, groups, phases);
    }

    public @NotNull MobFight withPhases(@NotNull List<MobPhase> phases) {
        return new MobFight(globalCooldown, groups, phases);
    }
}
