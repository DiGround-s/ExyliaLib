package net.exylia.lib.util.mob.internal;

import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.util.mob.MobSkill;
import net.exylia.lib.util.mob.MobTemplate;
import org.bukkit.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One custom mob while it is alive: its skills' clocks and who hurt it.
 *
 * <p>Touched from the mob's own thread — its events and its timer — so the
 * locking is for Folia moving it between regions, never for contention.
 * The timer handle is what the rest of the runtime reads from other threads:
 * a cancelled timer is a mob that is gone.
 */
public final class LiveMob {

    private final MobTemplate template;
    private final @Nullable LivingEntity entity;
    private final boolean summoned;
    private final long[] readyAt;
    private final boolean[] spent;
    private final Map<UUID, Double> byPlayer = new HashMap<>();
    private final List<UUID> minions = new ArrayList<>();
    private double total;
    private boolean casting;
    private volatile @Nullable TaskHandle timer;

    /**
     * @param template what it was spawned from
     * @param entity   the entity, {@code null} only in tests
     * @param summoned whether a SUMMON skill made it, which keeps it from summoning
     * @param now      the spawn time, in milliseconds
     */
    public LiveMob(@NotNull MobTemplate template, @Nullable LivingEntity entity, boolean summoned, long now) {
        this.template = template;
        this.entity = entity;
        this.summoned = summoned;
        List<MobSkill> skills = template.skills();
        this.readyAt = new long[skills.size()];
        this.spent = new boolean[skills.size()];
        for (int index = 0; index < skills.size(); index++) {
            // An interval skill waits its first period: a mob that fires the
            // moment it appears reads as a spawn skill nobody wrote.
            MobSkill skill = skills.get(index);
            if (skill.trigger() == MobSkill.Trigger.INTERVAL) readyAt[index] = now + skill.period().toMillis();
        }
    }

    public @NotNull MobTemplate template() {
        return template;
    }

    public @Nullable LivingEntity entity() {
        return entity;
    }

    public boolean summoned() {
        return summoned;
    }

    public void timer(@NotNull TaskHandle timer) {
        this.timer = timer;
    }

    /** Whether the mob is still in the world, as far as its timer knows. */
    public boolean alive() {
        TaskHandle handle = timer;
        return handle != null && !handle.isCancelled();
    }

    /** Stops its timer, which is what marks it gone. */
    public void end() {
        TaskHandle handle = timer;
        if (handle != null) handle.cancel();
    }

    // ------------------------------------------------------------------ skills

    /** Whether one of its skills is being cast right now, on this thread. */
    public boolean casting() {
        return casting;
    }

    public void casting(boolean casting) {
        this.casting = casting;
    }

    /**
     * Whether a skill fires now, starting its cooldown if it does.
     *
     * <p>An interval skill spends its period whether or not the dice land: it
     * is tried once a period, and a one-in-ten skill checked every second would
     * otherwise fire about every ten seconds whatever its period said. Any other
     * skill only starts its cooldown when it actually fires.
     *
     * @param index the skill's position in the template
     * @param skill the skill
     * @param now   the time, in milliseconds
     * @param roll  a uniform draw in {@code [0, 1)}
     * @return whether to cast it
     */
    public synchronized boolean attempt(int index, @NotNull MobSkill skill, long now, double roll) {
        if (now < readyAt[index]) return false;
        if (skill.trigger() == MobSkill.Trigger.INTERVAL) {
            readyAt[index] = now + skill.period().toMillis();
            return roll < skill.chance();
        }
        if (roll >= skill.chance()) return false;
        readyAt[index] = now + skill.period().toMillis();
        return true;
    }

    /**
     * Whether a low-health skill fires at this health, which it does once.
     *
     * <p>Spent the first time health reaches the threshold, dice or not:
     * "when it drops below a third" is a moment, and a mob hovering at the
     * threshold must not cast it on every hit.
     *
     * @param index    the skill's position
     * @param skill    the skill
     * @param fraction health over maximum health, after the hit
     * @param roll     a uniform draw in {@code [0, 1)}
     * @return whether to cast it
     */
    public synchronized boolean lowHealth(int index, @NotNull MobSkill skill, double fraction, double roll) {
        if (spent[index] || fraction > skill.threshold()) return false;
        spent[index] = true;
        return roll < skill.chance();
    }

    // ------------------------------------------------------------------ damage

    /**
     * Records damage the mob took.
     *
     * @param player who dealt it, or {@code null} for anything that is not a player
     * @param amount how much, already capped at the health it had left
     */
    public synchronized void hurt(@Nullable UUID player, double amount) {
        if (!(amount > 0) || !Double.isFinite(amount)) return;
        total += amount;
        if (player != null) byPlayer.merge(player, amount, Double::sum);
    }

    /** Damage by player, copied. */
    public synchronized @NotNull Map<UUID, Double> damage() {
        return Map.copyOf(byPlayer);
    }

    /** The player who dealt the most, or {@code null} when none dealt any. */
    public synchronized @Nullable UUID topDamager() {
        UUID top = null;
        double most = 0;
        for (Map.Entry<UUID, Double> entry : byPlayer.entrySet()) {
            if (entry.getValue() > most) {
                most = entry.getValue();
                top = entry.getKey();
            }
        }
        return top;
    }

    /** The share of all damage taken that players dealt, {@code 0} to {@code 1}. */
    public synchronized double playerShare() {
        if (total <= 0) return 0;
        double players = 0;
        for (double amount : byPlayer.values()) players += amount;
        return Math.min(1, players / total);
    }

    // ----------------------------------------------------------------- minions

    /** The minions it summoned that are still counted, pruned by {@code alive}. */
    public synchronized int minions(@NotNull java.util.function.Predicate<UUID> alive) {
        minions.removeIf(id -> !alive.test(id));
        return minions.size();
    }

    public synchronized void minion(@NotNull UUID id) {
        minions.add(id);
    }
}
