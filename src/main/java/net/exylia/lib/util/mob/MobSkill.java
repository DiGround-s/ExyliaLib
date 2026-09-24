package net.exylia.lib.util.mob;

import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * Something a custom mob does on its own: when, how often, and what.
 *
 * <pre>{@code
 * MobSkill leap = MobSkill.of(MobSkill.Type.LEAP, MobSkill.Trigger.INTERVAL)
 *         .withCooldown(Duration.ofSeconds(8));
 * }</pre>
 *
 * <h2>One flat set of fields</h2>
 * Every type reads a few of the same fields rather than carrying its own
 * class, so a skill stores as one small object and edits as one form. Which
 * fields a type reads is on each {@link Type}; the others are kept but ignored.
 *
 * <h2>Who it is aimed at</h2>
 * The mob's current target; failing that, whoever the event is about (the
 * entity it hit, the one that hit it, its killer); failing that, the nearest
 * player within 16 blocks. A type that needs a target and finds none does
 * nothing, and its cooldown does not start.
 *
 * @param trigger   when it is tried
 * @param type      what it does
 * @param chance    the odds each time it is tried, {@code 0} to {@code 1}
 * @param cooldown  the shortest gap between two casts; for {@link Trigger#INTERVAL}
 *                  the period it is tried at, one second at least
 * @param threshold for {@link Trigger#LOW_HEALTH}, the share of its maximum
 *                  health, {@code 0} to {@code 1}, that it fires at
 * @param radius    blocks, for the types that reach everybody nearby
 * @param amount    strength, damage, speed, count or percent, depending on the type
 * @param duration  how long, for {@link Type#IGNITE}, {@link Type#SPEED} and {@link Type#BABY}
 * @param text      an effect line, a template id, a projectile, sequence lines, a
 *                  scale range or a command, depending on the type; never {@code null}
 * @param effect    sequence lines, one per line, played at the mob each time the
 *                  skill goes off, whatever its type; a {@link Type#TELEPORT} plays
 *                  them where it leaves and where it lands. Blank for none (since 1.195.0)
 * @param cast      how it is cast: wind-up, aim, conditions, rotation group and chain;
 *                  {@link Cast#NONE} casts it at once, as before 1.198.0 (since 1.198.0)
 * @since 1.192.0
 */
public record MobSkill(@NotNull Trigger trigger, @NotNull Type type, double chance,
                       @NotNull Duration cooldown, double threshold, double radius,
                       double amount, @NotNull Duration duration, @NotNull String text,
                       @NotNull String effect, @NotNull Cast cast) {

    /** When a skill is tried. */
    public enum Trigger {
        /** Once, as the mob appears. */
        SPAWN,
        /** Every {@link MobSkill#cooldown()}, while somebody is there to aim at. */
        INTERVAL,
        /** When the mob hurts an entity, arrows and fireballs included. */
        ATTACK,
        /** When the mob is hurt. */
        DAMAGED,
        /** Once, the first time its health drops to {@link MobSkill#threshold()}. */
        LOW_HEALTH,
        /** As it dies. */
        DEATH,
        /**
         * As its fight enters a new phase ({@link MobFight#phases()}); with
         * {@link Gate#phase()} set, only as it enters that one.
         *
         * @since 1.198.0
         */
        PHASE;

        /** The trigger as a person reads it. */
        public @NotNull String readable() {
            return name().toLowerCase(Locale.ROOT).replace('_', ' ');
        }
    }

    /**
     * What a skill does, and which fields it reads.
     *
     * <p>{@link #needsTarget()} says whether it does nothing without somebody
     * to aim at.
     */
    public enum Type {
        /**
         * Jumps at the target. Reads {@code amount} as strength, {@code 1} by
         * default. Since 1.200.0, with a {@code radius} above zero it lands
         * hard: everyone within that radius of where it comes down takes its
         * attack damage and is knocked back.
         */
        LEAP(true),
        /** Yanks the target to the mob. Reads {@code amount} as strength. */
        PULL(true),
        /** Throws every player within {@code radius} away. Reads {@code amount} as strength. */
        PUSH(false),
        /**
         * Applies the potion effect line in {@code text} ({@code SLOWNESS|2|5}) to
         * the target, or to every player within {@code radius} when it is above zero.
         */
        POTION(true),
        /**
         * Spawns minions of the template named in {@code text}, from the same
         * plugin, within {@code radius} blocks. {@code amount} is how many of this
         * mob's minions may be alive at once, ten at most. A summoned minion never
         * summons in turn.
         */
        SUMMON(false),
        /** Strikes the target with lightning that sets nothing alight; {@code amount} is extra damage. */
        LIGHTNING(true),
        /**
         * Fires {@code text} ({@code FIREBALL}, {@code SMALL_FIREBALL},
         * {@code WITHER_SKULL}, {@code ARROW} or {@code SNOWBALL}) at the target,
         * {@code amount} as speed.
         */
        PROJECTILE(true),
        /** Restores {@code amount} percent of the mob's maximum health. */
        HEAL(false),
        /**
         * Appears a step and a half behind the target; with {@code radius} above
         * zero it needs no target and blinks instead to a random spot on the
         * ground up to that far, never outside its {@link MobBehaviour#roam()}.
         */
        TELEPORT(true),
        /**
         * Deals {@code amount} damage to every player within {@code radius};
         * since 1.200.0 a {@code duration} above zero also sets them on fire
         * that long.
         */
        AREA_DAMAGE(false),
        /** Sets the target on fire for {@code duration}. */
        IGNITE(true),
        /**
         * Jumps straight up, {@code amount} as the upward speed.
         *
         * @since 1.195.0
         */
        JUMP(false),
        /**
         * Changes its {@code scale} to a random value in the range in {@code text}:
         * {@code 0.7|1.8}, or one number for a fixed size. {@code 0.1} at least.
         *
         * @since 1.195.0
         */
        SIZE(false),
        /**
         * Runs {@code amount} times as fast as it spawned for {@code duration},
         * then goes back to that speed.
         *
         * @since 1.195.0
         */
        SPEED(false),
        /**
         * Turns into a baby for {@code duration}, then grows back; only an adult
         * of a type that ages.
         *
         * @since 1.195.0
         */
        BABY(false),
        /** Plays the sequence lines in {@code text}, one per line, at the mob. */
        EFFECT(false),
        /**
         * Runs {@code text} from the console. {@code %player%} is the target's
         * name, and a command naming it is skipped when the target is not a
         * player; {@code %mob%} is the template id.
         */
        COMMAND(false),
        /**
         * Charges in a straight line towards where its target stood as the
         * wind-up began, {@code radius} blocks (12 when 0, 24 at most), stopping
         * at a wall. Whoever it runs through takes {@code amount} damage and is
         * thrown aside, once each.
         *
         * @since 1.200.0
         */
        DASH(true),
        /**
         * Hits its target for {@code amount} damage, then jumps to the nearest
         * player it has not hit within {@code radius} blocks (6 when 0) of the
         * last one, {@code text} jumps in all (4 when blank, 8 at most), one
         * every tenth of a second.
         *
         * @since 1.200.0
         */
        CHAIN(true),
        /**
         * Takes {@code amount} percent less damage for {@code duration}; at 100
         * nothing hurts it. In hits mode no hit is counted while it lasts.
         *
         * @since 1.200.0
         */
        SHIELD(false),
        /**
         * Leaves an area {@code radius} blocks wide (3 when 0) for
         * {@code duration} (30 seconds at most) that deals {@code amount} damage
         * a second to the players inside, and puts the potion effect line in
         * {@code text} on them, if any. Where its target stood; aimed at
         * {@link Aim#SELF} it goes with the mob. Two at most per mob.
         *
         * @since 1.200.0
         */
        ZONE(false),
        /**
         * Rains {@code amount} strikes (5 when 0, 16 at most) on spots within
         * {@code radius} blocks (6 when 0) of its target, or of the mob with
         * nobody to aim at, each shown on the ground a second before it lands
         * and a fifth of a second after the one before. Whoever stands within
         * a block and a half of one takes the damage in {@code text} (4 when
         * blank).
         *
         * @since 1.200.0
         */
        BARRAGE(false);

        private final boolean needsTarget;

        Type(boolean needsTarget) {
            this.needsTarget = needsTarget;
        }

        /** Whether the skill does nothing without somebody to aim at. */
        public boolean needsTarget() {
            return needsTarget;
        }

        /** The type as a person reads it. */
        public @NotNull String readable() {
            return name().toLowerCase(Locale.ROOT).replace('_', ' ');
        }
    }

    /** The shortest period an interval skill runs at: the mob's own timer. */
    public static final Duration MIN_INTERVAL = Duration.ofSeconds(1);

    public MobSkill {
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(type, "type");
        chance = clamp(chance);
        threshold = clamp(threshold);
        cooldown = cooldown == null || cooldown.isNegative() ? Duration.ZERO : cooldown;
        duration = duration == null || duration.isNegative() ? Duration.ZERO : duration;
        radius = Double.isFinite(radius) ? Math.max(0, radius) : 0;
        amount = Double.isFinite(amount) ? Math.max(0, amount) : 0;
        text = text == null ? "" : text;
        effect = effect == null ? "" : effect;
        cast = cast == null ? Cast.NONE : cast;
    }

    /**
     * A skill cast at once, as before 1.198.0.
     *
     * @since 1.195.0
     */
    public MobSkill(@NotNull Trigger trigger, @NotNull Type type, double chance, @NotNull Duration cooldown,
                    double threshold, double radius, double amount, @NotNull Duration duration,
                    @NotNull String text, @NotNull String effect) {
        this(trigger, type, chance, cooldown, threshold, radius, amount, duration, text, effect, Cast.NONE);
    }

    /**
     * A skill with no {@code effect}: the shape before 1.195.0.
     *
     * @since 1.192.0
     */
    public MobSkill(@NotNull Trigger trigger, @NotNull Type type, double chance, @NotNull Duration cooldown,
                    double threshold, double radius, double amount, @NotNull Duration duration,
                    @NotNull String text) {
        this(trigger, type, chance, cooldown, threshold, radius, amount, duration, text, "", Cast.NONE);
    }

    /**
     * A skill with the defaults its type makes sense with.
     *
     * @param type    what it does
     * @param trigger when it is tried
     * @return the skill
     */
    public static @NotNull MobSkill of(@NotNull Type type, @NotNull Trigger trigger) {
        Duration cooldown = Duration.ofSeconds(trigger == Trigger.INTERVAL ? 10 : 5);
        return switch (type) {
            case LEAP, PULL -> new MobSkill(trigger, type, 1, cooldown, 0.3, 0, 1, Duration.ZERO, "");
            case PUSH -> new MobSkill(trigger, type, 1, cooldown, 0.3, 4, 1.2, Duration.ZERO, "");
            case POTION -> new MobSkill(trigger, type, 1, cooldown, 0.3, 0, 0, Duration.ZERO, "SLOWNESS|1|3");
            case SUMMON -> new MobSkill(trigger, type, 1, cooldown, 0.3, 3, 2, Duration.ZERO, "");
            case LIGHTNING -> new MobSkill(trigger, type, 1, cooldown, 0.3, 0, 4, Duration.ZERO, "");
            case PROJECTILE -> new MobSkill(trigger, type, 1, cooldown, 0.3, 0, 1.5, Duration.ZERO, "FIREBALL");
            case HEAL -> new MobSkill(trigger, type, 1, cooldown, 0.3, 0, 20, Duration.ZERO, "");
            case AREA_DAMAGE -> new MobSkill(trigger, type, 1, cooldown, 0.3, 4, 4, Duration.ZERO, "");
            case IGNITE -> new MobSkill(trigger, type, 1, cooldown, 0.3, 0, 0, Duration.ofSeconds(3), "");
            case JUMP -> new MobSkill(trigger, type, 1, cooldown, 0.3, 0, 0.8, Duration.ZERO, "");
            case SIZE -> new MobSkill(trigger, type, 1, cooldown, 0.3, 0, 0, Duration.ZERO, "0.7|1.8");
            case SPEED -> new MobSkill(trigger, type, 1, cooldown, 0.3, 0, 1.5, Duration.ofSeconds(5), "");
            case BABY -> new MobSkill(trigger, type, 1, cooldown, 0.3, 0, 0, Duration.ofSeconds(5), "");
            case TELEPORT, EFFECT, COMMAND -> new MobSkill(trigger, type, 1, cooldown, 0.3, 0, 0, Duration.ZERO, "");
            case DASH -> new MobSkill(trigger, type, 1, cooldown, 0.3, 12, 6, Duration.ZERO, "");
            case CHAIN -> new MobSkill(trigger, type, 1, cooldown, 0.3, 6, 4, Duration.ZERO, "4");
            case SHIELD -> new MobSkill(trigger, type, 1, cooldown, 0.3, 0, 60, Duration.ofSeconds(5), "");
            case ZONE -> new MobSkill(trigger, type, 1, cooldown, 0.3, 3, 2, Duration.ofSeconds(5), "");
            case BARRAGE -> new MobSkill(trigger, type, 1, cooldown, 0.3, 6, 5, Duration.ZERO, "4");
        };
    }

    /** The same skill, tried on another trigger. */
    public @NotNull MobSkill withTrigger(@NotNull Trigger trigger) {
        return new MobSkill(trigger, type, chance, cooldown, threshold, radius, amount, duration, text, effect, cast);
    }

    /** The same skill, with other odds. */
    public @NotNull MobSkill withChance(double chance) {
        return new MobSkill(trigger, type, chance, cooldown, threshold, radius, amount, duration, text, effect, cast);
    }

    /** The same skill, with another cooldown or period. */
    public @NotNull MobSkill withCooldown(@NotNull Duration cooldown) {
        return new MobSkill(trigger, type, chance, cooldown, threshold, radius, amount, duration, text, effect, cast);
    }

    /** The same skill, with another text. */
    public @NotNull MobSkill withText(@NotNull String text) {
        return new MobSkill(trigger, type, chance, cooldown, threshold, radius, amount, duration, text, effect, cast);
    }

    /**
     * The same skill, with other lines played as it goes off.
     *
     * @since 1.195.0
     */
    public @NotNull MobSkill withEffect(@NotNull String effect) {
        return new MobSkill(trigger, type, chance, cooldown, threshold, radius, amount, duration, text, effect, cast);
    }

    /**
     * The same skill, cast another way.
     *
     * @since 1.198.0
     */
    public @NotNull MobSkill withCast(@NotNull Cast cast) {
        return new MobSkill(trigger, type, chance, cooldown, threshold, radius, amount, duration, text, effect, cast);
    }

    /**
     * Whether it does nothing without somebody to aim at: its type's answer,
     * except a POTION or TELEPORT with a radius. Since 1.198.0 the aim decides
     * first: {@link Aim#TARGET} and {@link Aim#GROUND} need the target, the
     * aims that find their own players and {@link Aim#SELF} do not.
     *
     * @since 1.195.0
     */
    public boolean needsTarget() {
        switch (cast.aim()) {
            case TARGET, GROUND -> {
                return true;
            }
            case NEAREST, FARTHEST, RANDOM, ALL, CONE, LINE, SELF -> {
                return false;
            }
            case AUTO -> { }
        }
        if ((type == Type.POTION || type == Type.TELEPORT) && radius > 0) return false;
        return type.needsTarget();
    }

    /**
     * Whether it counts as a real attack: anything but {@link Type#EFFECT} and
     * {@link Type#COMMAND}. Only these wait out the fight's global cooldown and
     * each other's casts.
     *
     * @since 1.198.0
     */
    public boolean major() {
        return type != Type.EFFECT && type != Type.COMMAND;
    }

    /**
     * Whether it takes turns in a rotation group rather than rolling on its own:
     * an {@link Trigger#INTERVAL} skill with a {@link Cast#group()}.
     *
     * @since 1.198.0
     */
    public boolean grouped() {
        return trigger == Trigger.INTERVAL && !cast.group().isEmpty();
    }

    /**
     * How long after a cast the skill may be tried again.
     *
     * <p>An interval skill runs no faster than the mob's own timer, whatever
     * its cooldown says.
     *
     * @return the wait
     */
    public @NotNull Duration period() {
        return trigger == Trigger.INTERVAL && cooldown.compareTo(MIN_INTERVAL) < 0 ? MIN_INTERVAL : cooldown;
    }

    private static double clamp(double value) {
        return Double.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0;
    }

    private static double positive(double value) {
        return Double.isFinite(value) ? Math.max(0, value) : 0;
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    // ------------------------------------------------------------------- cast

    /**
     * Where a skill lands, worked out as it lands.
     *
     * <p>{@link #AUTO} is each type's own targeting, exactly as before 1.198.0.
     * The others find their players at impact, so somebody who walks out of a
     * cone or a line during the wind-up is not hit.
     *
     * @since 1.198.0
     */
    public enum Aim {
        /** Each type's own targeting: the target, or everybody within {@code radius}. */
        AUTO,
        /** The skill's target, wherever it has moved to. */
        TARGET,
        /** The nearest survival or adventure player within {@code radius}, 16 when it is 0. */
        NEAREST,
        /** The farthest such player within {@code radius}, 16 when it is 0. */
        FARTHEST,
        /** Any one such player within {@code radius}, 16 when it is 0. */
        RANDOM,
        /** Every such player within {@code radius} of the mob, 16 when it is 0. */
        ALL,
        /**
         * Every such player within {@code radius} (16 when 0) and within half of
         * {@link Cast#spread()} degrees (60 when 0) of where the mob faced as the
         * wind-up started.
         */
        CONE,
        /**
         * Every such player within half of {@link Cast#spread()} blocks (1.6 when 0)
         * of a line {@code radius} long (16 when 0), from the mob towards where its
         * target stood as the wind-up started.
         */
        LINE,
        /** The mob itself; an area type reaches everybody within {@code radius} of it. */
        SELF,
        /**
         * Where the target's feet were as the wind-up started, so the attack can be
         * dodged; an area type reaches everybody within {@code radius} of that spot,
         * anything else whoever stands within a block and a half of it.
         */
        GROUND;

        /** The aim as a person reads it. */
        public @NotNull String readable() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * When a skill may be cast at all, checked before its dice so a skill kept
     * out keeps its cooldown for later.
     *
     * <pre>{@code
     * // Only while somebody is within 16 blocks and the mob is below half health.
     * Gate gate = Gate.ANY.withNearby(16).withHealth(0, 0.5);
     * }</pre>
     *
     * @param minHealth the least share of its health (hits left in hits mode) it casts at, {@code 0-1}
     * @param maxHealth the most share of its health it casts at, {@code 0-1}
     * @param minRange  the closest its target may be, in blocks; {@code 0} for no limit
     * @param maxRange  the farthest its target may be, in blocks; {@code 0} for no limit.
     *                  A skill with either range and no target is not cast
     * @param nearby    above {@code 0}, it needs a survival or adventure player within
     *                  that many blocks of the mob; {@code 0} needs nobody
     * @param phase     the fight phase it is cast in ({@code 1} is the start); {@code 0} for any
     * @since 1.198.0
     */
    public record Gate(double minHealth, double maxHealth, double minRange, double maxRange,
                       double nearby, int phase) {

        /** No condition at all. */
        public static final Gate ANY = new Gate(0, 1, 0, 0, 0, 0);

        public Gate {
            minHealth = clamp(minHealth);
            maxHealth = Double.isFinite(maxHealth) ? clamp(maxHealth) : 1;
            minRange = positive(minRange);
            maxRange = positive(maxRange);
            nearby = positive(nearby);
            phase = Math.max(0, phase);
        }

        /**
         * Whether a cast may go through.
         *
         * @param health          the mob's share of health (hits left in hits mode), {@code 0-1}
         * @param targetDistance  blocks to its target, {@code NaN} for no target
         * @param nearestDistance blocks to the nearest survival or adventure player,
         *                        {@code NaN} for nobody; only read when {@link #nearby()} is set
         * @param currentPhase    the fight phase it is in
         * @return whether every condition holds
         */
        public boolean admits(double health, double targetDistance, double nearestDistance, int currentPhase) {
            if (health < minHealth || health > maxHealth) return false;
            if (minRange > 0 || maxRange > 0) {
                if (Double.isNaN(targetDistance) || targetDistance < minRange) return false;
                if (maxRange > 0 && targetDistance > maxRange) return false;
            }
            if (nearby > 0 && (Double.isNaN(nearestDistance) || nearestDistance > nearby)) return false;
            return phase == 0 || phase == currentPhase;
        }

        public @NotNull Gate withHealth(double min, double max) {
            return new Gate(min, max, minRange, maxRange, nearby, phase);
        }

        public @NotNull Gate withRange(double min, double max) {
            return new Gate(minHealth, maxHealth, min, max, nearby, phase);
        }

        public @NotNull Gate withNearby(double blocks) {
            return new Gate(minHealth, maxHealth, minRange, maxRange, blocks, phase);
        }

        public @NotNull Gate withPhase(int phase) {
            return new Gate(minHealth, maxHealth, minRange, maxRange, nearby, phase);
        }
    }

    /**
     * How a skill is cast: a wind-up the players can see coming, where it lands,
     * when it may go, whom it takes turns with and what follows it.
     *
     * <pre>{@code
     * MobSkill slam = MobSkill.of(MobSkill.Type.AREA_DAMAGE, MobSkill.Trigger.INTERVAL)
     *         .withCast(MobSkill.Cast.NONE.withName("slam").withAim(MobSkill.Aim.SELF)
     *                 .withWindup(Duration.ofMillis(900))
     *                 .withWhen(MobSkill.Gate.ANY.withNearby(16)));
     * }</pre>
     *
     * <h2>The stages</h2>
     * With a wind-up the mob stops where it is, turns to its aim and plays
     * {@link #windupLines()}; when the wind-up ends the skill lands where its
     * {@link #aim()} says and plays the skill's {@code effect} lines; a short
     * recovery later it moves again. With no wind-up it lands at once, exactly
     * as before 1.198.0.
     *
     * @param name        what {@link #then()} calls it by; blank for no name
     * @param aim         where it lands
     * @param windup      how long the mob telegraphs before it lands; zero for at once
     * @param style       the look it is cast with, one of {@link MobSkills#STYLES}; blank
     *                    for its type's own ({@link MobSkills#autoStyle}), which only plays
     *                    while the skill has no {@code effect} lines; {@link MobSkills#NO_STYLE}
     *                    for none. An id this version does not know plays as blank
     * @param tint        the colour that look is drawn in, a {@code {token}} or {@code #rrggbb};
     *                    blank for the style's own theme colour ({@link MobTheme})
     * @param spread      a {@link Aim#CONE}'s angle in degrees or a {@link Aim#LINE}'s
     *                    width in blocks; {@code 0} for 60 degrees or 1.6 blocks
     * @param when        when it may be cast
     * @param group       for an {@link Trigger#INTERVAL} skill, the rotation group it takes
     *                    turns in (see {@link MobFight#groups()}); blank rolls on its own
     * @param then        the {@link #name()} of a skill of the same mob cast right after
     *                    this one lands; blank for none
     * @param windupLines sequence lines played at the mob as the wind-up starts, one per line
     * @since 1.198.0
     */
    public record Cast(@NotNull String name, @NotNull Aim aim, @NotNull Duration windup,
                       @NotNull String style, @NotNull String tint, double spread, @NotNull Gate when,
                       @NotNull String group, @NotNull String then, @NotNull String windupLines) {

        /** The longest wind-up: a telegraph, not a timer. Declared first: {@link #NONE} is built with it. */
        public static final Duration MAX_WINDUP = Duration.ofSeconds(10);

        /** Cast at once, at its type's own target, whenever its trigger and dice say. */
        public static final Cast NONE = new Cast("", Aim.AUTO, Duration.ZERO, "", "", 0, Gate.ANY, "", "", "");

        public Cast {
            name = trimmed(name);
            aim = aim == null ? Aim.AUTO : aim;
            windup = windup == null || windup.isNegative() ? Duration.ZERO
                    : windup.compareTo(MAX_WINDUP) > 0 ? MAX_WINDUP : windup;
            style = trimmed(style);
            tint = trimmed(tint);
            spread = positive(spread);
            when = when == null ? Gate.ANY : when;
            group = trimmed(group);
            then = trimmed(then);
            windupLines = windupLines == null ? "" : windupLines.strip();
        }

        public @NotNull Cast withName(@NotNull String name) {
            return new Cast(name, aim, windup, style, tint, spread, when, group, then, windupLines);
        }

        public @NotNull Cast withAim(@NotNull Aim aim) {
            return new Cast(name, aim, windup, style, tint, spread, when, group, then, windupLines);
        }

        public @NotNull Cast withWindup(@NotNull Duration windup) {
            return new Cast(name, aim, windup, style, tint, spread, when, group, then, windupLines);
        }

        public @NotNull Cast withSpread(double spread) {
            return new Cast(name, aim, windup, style, tint, spread, when, group, then, windupLines);
        }

        public @NotNull Cast withWhen(@NotNull Gate when) {
            return new Cast(name, aim, windup, style, tint, spread, when, group, then, windupLines);
        }

        public @NotNull Cast withGroup(@NotNull String group) {
            return new Cast(name, aim, windup, style, tint, spread, when, group, then, windupLines);
        }

        public @NotNull Cast withThen(@NotNull String then) {
            return new Cast(name, aim, windup, style, tint, spread, when, group, then, windupLines);
        }

        /** @since 1.200.0 */
        public @NotNull Cast withStyle(@NotNull String style) {
            return new Cast(name, aim, windup, style, tint, spread, when, group, then, windupLines);
        }

        /** @since 1.200.0 */
        public @NotNull Cast withTint(@NotNull String tint) {
            return new Cast(name, aim, windup, style, tint, spread, when, group, then, windupLines);
        }

        public @NotNull Cast withWindupLines(@NotNull String windupLines) {
            return new Cast(name, aim, windup, style, tint, spread, when, group, then, windupLines);
        }
    }
}
