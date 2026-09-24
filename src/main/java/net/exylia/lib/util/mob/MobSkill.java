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
 * @since 1.192.0
 */
public record MobSkill(@NotNull Trigger trigger, @NotNull Type type, double chance,
                       @NotNull Duration cooldown, double threshold, double radius,
                       double amount, @NotNull Duration duration, @NotNull String text,
                       @NotNull String effect) {

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
        DEATH;

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
        /** Jumps at the target. Reads {@code amount} as strength, {@code 1} by default. */
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
        /** Deals {@code amount} damage to every player within {@code radius}. */
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
        COMMAND(false);

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
    }

    /**
     * A skill with no {@code effect}: the shape before 1.195.0.
     *
     * @since 1.192.0
     */
    public MobSkill(@NotNull Trigger trigger, @NotNull Type type, double chance, @NotNull Duration cooldown,
                    double threshold, double radius, double amount, @NotNull Duration duration,
                    @NotNull String text) {
        this(trigger, type, chance, cooldown, threshold, radius, amount, duration, text, "");
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
        };
    }

    /** The same skill, tried on another trigger. */
    public @NotNull MobSkill withTrigger(@NotNull Trigger trigger) {
        return new MobSkill(trigger, type, chance, cooldown, threshold, radius, amount, duration, text, effect);
    }

    /** The same skill, with other odds. */
    public @NotNull MobSkill withChance(double chance) {
        return new MobSkill(trigger, type, chance, cooldown, threshold, radius, amount, duration, text, effect);
    }

    /** The same skill, with another cooldown or period. */
    public @NotNull MobSkill withCooldown(@NotNull Duration cooldown) {
        return new MobSkill(trigger, type, chance, cooldown, threshold, radius, amount, duration, text, effect);
    }

    /** The same skill, with another text. */
    public @NotNull MobSkill withText(@NotNull String text) {
        return new MobSkill(trigger, type, chance, cooldown, threshold, radius, amount, duration, text, effect);
    }

    /**
     * The same skill, with other lines played as it goes off.
     *
     * @since 1.195.0
     */
    public @NotNull MobSkill withEffect(@NotNull String effect) {
        return new MobSkill(trigger, type, chance, cooldown, threshold, radius, amount, duration, text, effect);
    }

    /**
     * Whether it does nothing without somebody to aim at: its type's answer,
     * except a POTION or TELEPORT with a radius.
     *
     * @since 1.195.0
     */
    public boolean needsTarget() {
        if ((type == Type.POTION || type == Type.TELEPORT) && radius > 0) return false;
        return type.needsTarget();
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
}
