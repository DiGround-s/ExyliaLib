package net.exylia.lib.util.mob;

import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Locale;

/**
 * One blow a model mob with a {@link MobBody} strikes: its own, timed by its
 * animation, never the vanilla mob's melee.
 *
 * <pre>{@code
 * MobAttack slam = MobAttack.melee("slam").withReach(2.5).withArc(160).withDamage(9)
 *         .withCooldown(Duration.ofSeconds(3)).withKnockback(1.2);
 * MobAttack bolt = MobAttack.ranged("bolt", "SMALL_FIREBALL").withReach(14).withCooldown(Duration.ofSeconds(2));
 * }</pre>
 *
 * <p>When a target is in reach, in front of it and in sight, and the attack is
 * ready, the mob starts the attack's animation; the blow lands on the
 * animation's {@code release} key, else its {@code impact} key, else its end
 * (or after {@code windup}, the animation fitted to it). It lands only if the
 * target is still in reach, in the arc and in sight at that moment, so a
 * player who steps back or behind a wall in time is not hit.
 *
 * @param name       what it is called, and the animation it plays when {@code animation} is blank:
 *                   {@code attack_<name>}, else {@code attack}
 * @param reach      how far past its own body it strikes, blocks; a projectile's range
 * @param arc        how wide in front of it the blow reaches, degrees; a projectile ignores it
 * @param damage     how much it deals; {@code 0} is the template's {@code attack_damage}
 * @param cooldown   how long before it can strike with it again
 * @param knockback  how hard it throws what it hits, blocks a second on top of the vanilla push
 * @param projectile what it throws ({@code FIREBALL}, {@code SMALL_FIREBALL}, {@code WITHER_SKULL},
 *                   {@code ARROW}, {@code SNOWBALL}); blank for a blow
 * @param animation  the state or animation it plays; blank for the one its name gives
 * @param windup     how long from the start of the animation to the blow; zero is the animation's own
 * @since 1.326.0
 */
public record MobAttack(@NotNull String name, double reach, double arc, double damage, @NotNull Duration cooldown,
                        double knockback, @NotNull String projectile, @NotNull String animation,
                        @NotNull Duration windup) {

    /** A plain blow: a little past its body, in front, every second and a bit. */
    public static final MobAttack DEFAULT = melee("attack");

    public MobAttack {
        name = name == null || name.isBlank() ? "attack" : name.trim().toLowerCase(Locale.ROOT);
        reach = Double.isFinite(reach) ? Math.clamp(reach, 0.2, 64) : 1.2;
        arc = Double.isFinite(arc) ? Math.clamp(arc, 10, 360) : 120;
        damage = Double.isFinite(damage) ? Math.max(0, damage) : 0;
        cooldown = cooldown == null || cooldown.isNegative() ? Duration.ofMillis(1200) : cooldown;
        knockback = Double.isFinite(knockback) ? Math.clamp(knockback, 0, 10) : 0;
        projectile = projectile == null ? "" : projectile.trim().toUpperCase(Locale.ROOT);
        animation = animation == null ? "" : animation.trim();
        windup = windup == null || windup.isNegative() ? Duration.ZERO : windup;
    }

    /** A blow called {@code name}. */
    public static @NotNull MobAttack melee(@NotNull String name) {
        return new MobAttack(name, 1.2, 120, 0, Duration.ofMillis(1200), 0, "", "", Duration.ZERO);
    }

    /** A throw called {@code name}. */
    public static @NotNull MobAttack ranged(@NotNull String name, @NotNull String projectile) {
        return new MobAttack(name, 12, 120, 0, Duration.ofMillis(2000), 0, projectile, "", Duration.ZERO);
    }

    /** Whether it throws something rather than striking. */
    public boolean ranged() {
        return !projectile.isEmpty();
    }

    /** The state it plays, as its model is asked for it. */
    public @NotNull String state() {
        return animation.isEmpty() ? (name.equals("attack") ? "attack" : "attack_" + name) : animation;
    }

    public @NotNull MobAttack withName(@NotNull String name) {
        return new MobAttack(name, reach, arc, damage, cooldown, knockback, projectile, animation, windup);
    }

    public @NotNull MobAttack withReach(double reach) {
        return new MobAttack(name, reach, arc, damage, cooldown, knockback, projectile, animation, windup);
    }

    public @NotNull MobAttack withArc(double arc) {
        return new MobAttack(name, reach, arc, damage, cooldown, knockback, projectile, animation, windup);
    }

    public @NotNull MobAttack withDamage(double damage) {
        return new MobAttack(name, reach, arc, damage, cooldown, knockback, projectile, animation, windup);
    }

    public @NotNull MobAttack withCooldown(@NotNull Duration cooldown) {
        return new MobAttack(name, reach, arc, damage, cooldown, knockback, projectile, animation, windup);
    }

    public @NotNull MobAttack withKnockback(double knockback) {
        return new MobAttack(name, reach, arc, damage, cooldown, knockback, projectile, animation, windup);
    }

    public @NotNull MobAttack withProjectile(@NotNull String projectile) {
        return new MobAttack(name, reach, arc, damage, cooldown, knockback, projectile, animation, windup);
    }

    public @NotNull MobAttack withAnimation(@NotNull String animation) {
        return new MobAttack(name, reach, arc, damage, cooldown, knockback, projectile, animation, windup);
    }

    public @NotNull MobAttack withWindup(@NotNull Duration windup) {
        return new MobAttack(name, reach, arc, damage, cooldown, knockback, projectile, animation, windup);
    }
}
