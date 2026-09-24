package net.exylia.lib.util.mob.internal;

import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.util.mob.MobSkill;
import net.exylia.lib.util.mob.MobTemplate;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One custom mob while it is alive: its skills' clocks, who hurt it, its hits,
 * its home and what it currently looks like.
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
    private final long spawnedAt;
    private final int maxHits;
    /** When each player's last counted hit landed; bounded by who hit this one mob. */
    private final Map<UUID, Long> lastHit = new HashMap<>();
    private int hitsLeft;
    private double total;
    private boolean casting;
    private volatile @Nullable TaskHandle timer;
    /** When each rotation group may go next, by name. */
    private final Map<String, Long> groupReadyAt = new HashMap<>();
    private long globalReadyAt;
    private @Nullable MobCaster.Active active;
    private int fightPhase = 1;
    /** When a hurt and a heal reaction last showed; far in the past until the first. */
    private long hurtShownAt = Long.MIN_VALUE / 2;
    private long healShownAt = Long.MIN_VALUE / 2;

    // Read and written on the mob's own thread only: its timer and its events.
    private @Nullable Location home;
    private double baseSpeed = Double.NaN;
    private int speedBoost;
    private int phase;
    private int seconds;
    private @Nullable String[] aura;
    private volatile @Nullable String glowShown;
    private @NotNull String variant = "";
    private @NotNull String body = "";

    /** What a leash asks of a mob, from how far it strayed. */
    public enum Leash { STAY, WALK_BACK, TELEPORT_BACK }

    /** Beyond the roam, how far a mob may be before it is teleported rather than walked back. */
    public static final double LEASH_SLACK = 8;

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
        this.spawnedAt = now;
        this.maxHits = template.behaviour().hits();
        this.hitsLeft = maxHits;
        List<MobSkill> skills = template.skills();
        this.readyAt = new long[skills.size()];
        this.spent = new boolean[skills.size()];
        for (int index = 0; index < skills.size(); index++) {
            // An interval skill waits its first period: a mob that fires the
            // moment it appears reads as a spawn skill nobody wrote.
            MobSkill skill = skills.get(index);
            if (skill.grouped()) {
                // The group waits its first period instead; the member's own cooldown starts on its first cast.
                groupReadyAt.putIfAbsent(skill.cast().group(),
                        now + template.fight().period(skill.cast().group()).toMillis());
            } else if (skill.trigger() == MobSkill.Trigger.INTERVAL) {
                readyAt[index] = now + skill.period().toMillis();
            }
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

    // --------------------------------------------------------------- behaviour

    /** Whether hits break it rather than health. */
    public boolean usesHits() {
        return maxHits > 0;
    }

    public int maxHits() {
        return maxHits;
    }

    public synchronized int hitsLeft() {
        return hitsLeft;
    }

    /**
     * Counts a player's hit in hits mode.
     *
     * <p>One per player per {@link net.exylia.lib.util.mob.MobBehaviour#hitCooldown()},
     * and each counted hit is one point in the damage ledger, so the top damager
     * is whoever hit most.
     *
     * @param player who hit it
     * @param now    the time, in milliseconds
     * @return the hits left after this one, or {@code -1} when it was not counted:
     *         the player's cooldown, a mob already broken, or one not in hits mode
     */
    public synchronized int hit(@NotNull UUID player, long now) {
        if (hitsLeft <= 0) return -1;
        Long last = lastHit.get(player);
        if (last != null && now - last < template.behaviour().hitCooldown().toMillis()) return -1;
        lastHit.put(player, now);
        hurt(player, 1);
        return --hitsLeft;
    }

    /** Whether its lifetime has run out. */
    public boolean expired(long now) {
        long lifetime = template.behaviour().lifetime().toMillis();
        return lifetime > 0 && now - spawnedAt >= lifetime;
    }

    /**
     * What the leash asks of a mob.
     *
     * @param sameWorld       whether it is in the world it spawned in
     * @param distanceSquared how far it is from where it spawned, squared
     * @param roam            how far it may go; {@code 0} for no leash
     * @return what to do
     */
    public static @NotNull Leash leash(boolean sameWorld, double distanceSquared, double roam) {
        if (roam <= 0) return Leash.STAY;
        double far = roam + LEASH_SLACK;
        if (!sameWorld || distanceSquared > far * far) return Leash.TELEPORT_BACK;
        return distanceSquared > roam * roam ? Leash.WALK_BACK : Leash.STAY;
    }

    public @Nullable Location home() {
        return home;
    }

    public void home(@NotNull Location home) {
        this.home = home;
    }

    /** Its movement speed as it spawned; {@code NaN} when its type has none. */
    public double baseSpeed() {
        return baseSpeed;
    }

    public void baseSpeed(double baseSpeed) {
        this.baseSpeed = baseSpeed;
    }

    /**
     * Starts a speed boost.
     *
     * @return the boost's token, which {@link #endBoost} needs to put the speed back
     */
    public int boostSpeed() {
        return ++speedBoost;
    }

    /**
     * Whether a boost ending may put the speed back: only the latest one, so an
     * earlier boost running out does not cut a later one short.
     *
     * @param token what {@link #boostSpeed} answered
     * @return whether to restore {@link #baseSpeed}
     */
    public boolean endBoost(int token) {
        return token == speedBoost;
    }

    // -------------------------------------------------------------------- look

    /** One step of the fast timer; the new phase. */
    public int step() {
        return ++phase;
    }

    public int phase() {
        return phase;
    }

    /** One step of the one-second work; the seconds it has lived. */
    public int second() {
        return ++seconds;
    }

    /** How many one-second passes it has had: what a CYCLE look counts by. */
    public int secondsLived() {
        return seconds;
    }

    /** The aura it wears, one text per frame, or {@code null} for none. */
    public @Nullable String[] aura() {
        return aura;
    }

    public void aura(@Nullable String[] aura) {
        this.aura = aura;
    }

    /** The outline colour it is in a team for, or {@code null}. */
    public @Nullable String glowShown() {
        return glowShown;
    }

    public void glowShown(@Nullable String glowShown) {
        this.glowShown = glowShown;
    }

    public @NotNull String variantShown() {
        return variant;
    }

    public void variantShown(@NotNull String variant) {
        this.variant = variant;
    }

    public @NotNull String bodyShown() {
        return body;
    }

    public void bodyShown(@NotNull String body) {
        this.body = body;
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

    /**
     * The rotation groups this mob's skills take turns in, by name.
     *
     * @return the names, in no particular order
     */
    public synchronized @NotNull java.util.Set<String> groups() {
        return java.util.Set.copyOf(groupReadyAt.keySet());
    }

    /** Whether a rotation group's period has come round. */
    public synchronized boolean groupDue(@NotNull String group, long now) {
        Long ready = groupReadyAt.get(group);
        return ready != null && now >= ready;
    }

    /**
     * Picks the one skill a rotation group casts this period.
     *
     * <p>Among the members that may be cast now — the caller's conditions, and
     * each member's own cooldown here — one is picked with its {@code chance}
     * as its weight. The period is spent only when there was somebody to pick:
     * a group whose members are all kept out goes off as soon as one may.
     *
     * @param group    the group's name
     * @param period   its period, in milliseconds
     * @param eligible the positions of the members whose conditions hold
     * @param now      the time, in milliseconds
     * @param roll     a uniform draw in {@code [0, 1)}
     * @return the position of the skill to cast, or {@code -1} for none
     */
    public synchronized int rotate(@NotNull String group, long period, @NotNull List<Integer> eligible,
                                   long now, double roll) {
        if (!groupDue(group, now)) return -1;
        List<MobSkill> skills = template.skills();
        double total = 0;
        for (int index : eligible) {
            if (now >= readyAt[index]) total += skills.get(index).chance();
        }
        if (total <= 0) return -1;
        double left = roll * total;
        int picked = -1;
        for (int index : eligible) {
            if (now < readyAt[index] || skills.get(index).chance() <= 0) continue;
            picked = index;
            left -= skills.get(index).chance();
            if (left < 0) break;
        }
        groupReadyAt.put(group, now + period);
        readyAt[picked] = now + skills.get(picked).cooldown().toMillis();
        return picked;
    }

    /** Whether the fight's global cooldown still holds major skills back. */
    public synchronized boolean globalCooling(long now) {
        return now < globalReadyAt;
    }

    /** Holds major skills back until then. */
    public synchronized void globalCooldown(long until) {
        globalReadyAt = Math.max(globalReadyAt, until);
    }

    /** The staged cast under way, or {@code null}; the mob's own thread only. */
    public @Nullable MobCaster.Active active() {
        return active;
    }

    public void active(@Nullable MobCaster.Active active) {
        this.active = active;
    }

    /** The fight phase it is in, {@code 1} at the start. */
    public synchronized int fightPhase() {
        return fightPhase;
    }

    /**
     * Moves the fight on to a phase; it never goes back.
     *
     * @param phase the phase its health falls in now
     * @return whether that is a phase it had not reached
     */
    public synchronized boolean enterPhase(int phase) {
        if (phase <= fightPhase) return false;
        fightPhase = phase;
        return true;
    }

    // --------------------------------------------------------------- reactions

    /**
     * Whether a hurt reaction may show now, taking the turn when it may.
     *
     * <p>A sword hits five times a second and a sweep hits a crowd; one burst
     * of sparks per {@code gapMillis} per mob is what keeps a fight readable
     * and the packets flat. The numbers are not held back by this: they merge
     * on their own.
     *
     * @param now       the time, in milliseconds
     * @param gapMillis the least time between two
     * @return whether to draw it
     */
    public synchronized boolean hurtShown(long now, long gapMillis) {
        if (now - hurtShownAt < gapMillis) return false;
        hurtShownAt = now;
        return true;
    }

    /** The same for healing, which regeneration raises every few ticks. */
    public synchronized boolean healShown(long now, long gapMillis) {
        if (now - healShownAt < gapMillis) return false;
        healShownAt = now;
        return true;
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
