package net.exylia.lib.ability;

import net.exylia.lib.ability.internal.Targets;
import net.exylia.lib.util.Formula;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.Event;
import org.bukkit.event.entity.EntityDamageEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The effects that shape the hit itself: {@code bonus-damage} raises the
 * event's damage (armour still applies, knockback stays vanilla) and
 * {@code armour-pierce} takes a share off what armour or Protection would
 * remove, applied after every ability of the firing changed the hit.
 *
 * <p>Why the pierce waits: {@code EntityDamageEvent#setDamage(double)}
 * recomputes the armour and magic reductions, so a bonus added after a pierce
 * would undo it. Pierces are noted per event and applied once the library's
 * damage handler has fired ATTACK and HIT_TAKEN, or, for a plugin trigger,
 * when its {@link PluginAbilities#fire} ends.
 */
final class DamageShaping {

    // Keyed by the event itself (identity); weak, so a pierce nobody applied never leaks.
    private final Map<EntityDamageEvent, double[]> pending = Collections.synchronizedMap(new WeakHashMap<>());
    private final ThreadLocal<boolean[]> deferring = ThreadLocal.withInitial(() -> new boolean[1]);

    static void register(PluginAbilities abilities) {
        abilities.scale("missing-health", args -> ctx -> {
            double max = Targets.maxHealth(ctx.holder());
            return max <= 0 ? 0 : 1 - Math.clamp(ctx.holder().getHealth() / max, 0, 1);
        });
        abilities.scale("health-gap", args -> ctx -> {
            LivingEntity victim = ctx.victim();
            if (victim == null) return 0;
            return healthGap(Targets.maxHealth(ctx.holder()), Targets.maxHealth(victim));
        });
        abilities.effect("bonus-damage", args -> bonusDamage(abilities, args));
        abilities.effect("armour-pierce", args -> armourPierce(abilities, args));
    }

    /** 0 when the victim's max is the holder's or less, 1 at twice it or more, a straight line between. */
    static double healthGap(double holderMax, double victimMax) {
        if (holderMax <= 0 || victimMax <= holderMax) return 0;
        return Math.min(1, (victimMax - holderMax) / holderMax);
    }

    /** {@code min(cap, max(0, units) * rate)}. */
    static double bonus(double units, double rate, double cap) {
        return Math.min(cap, Math.max(0, units) * rate);
    }

    /**
     * {@code bonus-damage: { amount, mode: percent | flat, by, max }}: raises
     * the trigger's hit by {@code amount} (percent of it by default),
     * multiplied by the {@code by} scale's units and capped at {@code max}.
     */
    private static AbilityEffect bonusDamage(PluginAbilities abilities, AbilityArgs args) {
        Formula amount = args.formula("amount", "10");
        boolean flat = args.string("mode", "percent").equalsIgnoreCase("flat");
        String by = args.string("by", "none").toLowerCase(Locale.ROOT);
        Formula max = args.formula("max", "1000");
        AbilityScale scale = null;
        if (!by.equals("none")) {
            AbilityScale.Factory factory = abilities.scaleFactory(by);
            if (factory == null) args.problem("'by' cannot be " + by + "; using none");
            else scale = factory.create(args);
        }
        AbilityScale units = scale;
        return (ctx, report) -> {
            if (!(ctx.event() instanceof EntityDamageEvent hit)) return;
            double rate = amount.applyOr(0, ctx.level());
            double cap = max.applyOr(1000, ctx.level());
            double value = bonus(units == null ? 1 : units.units(ctx), rate, cap) * ctx.scale();
            if (value <= 0) return;
            double extra = flat ? value : hit.getDamage() * value / 100;
            hit.setDamage(hit.getDamage() + extra);
            report.damage(extra).target();
        };
    }

    /**
     * {@code armour-pierce: { ignore, of: armor | protection }}: this share,
     * in percent, of what armour points (or Protection enchantments) would
     * take off the hit is ignored; the largest of a firing wins.
     */
    private static AbilityEffect armourPierce(PluginAbilities abilities, AbilityArgs args) {
        Formula ignore = args.formula("ignore", "100");
        boolean protection = args.string("of", "armor").equalsIgnoreCase("protection");
        return (ctx, report) -> {
            if (!(ctx.event() instanceof EntityDamageEvent hit)) return;
            double share = Math.clamp(ignore.applyOr(0, ctx.level()) * ctx.scale() / 100, 0, 1);
            if (share <= 0) return;
            abilities.shaping().request(hit, protection, share);
            report.target();
        };
    }

    void request(EntityDamageEvent event, boolean protection, double share) {
        synchronized (pending) {
            double[] pierce = pending.computeIfAbsent(event, key -> new double[2]);
            int index = protection ? 1 : 0;
            pierce[index] = Math.max(pierce[index], share);
        }
    }

    /** Whether this thread is inside the library's damage handler, which applies pierces itself. */
    boolean deferring() {
        return deferring.get()[0];
    }

    void deferring(boolean on) {
        deferring.get()[0] = on;
    }

    /** Applies and forgets the pierce noted for this event, if any. */
    @SuppressWarnings("deprecation") // DamageModifier is the only way to reach armour's share of a hit.
    void apply(@Nullable Event event) {
        if (!(event instanceof EntityDamageEvent hit) || pending.isEmpty()) return;
        double[] pierce = pending.remove(hit);
        if (pierce == null || hit.isCancelled()) return;
        if (pierce[0] > 0 && hit.isApplicable(EntityDamageEvent.DamageModifier.ARMOR)) {
            double reduction = hit.getDamage(EntityDamageEvent.DamageModifier.ARMOR);
            hit.setDamage(EntityDamageEvent.DamageModifier.ARMOR, reduction * (1 - pierce[0]));
        }
        if (pierce[1] > 0 && hit.isApplicable(EntityDamageEvent.DamageModifier.MAGIC)) {
            double reduction = hit.getDamage(EntityDamageEvent.DamageModifier.MAGIC);
            hit.setDamage(EntityDamageEvent.DamageModifier.MAGIC, reduction * (1 - pierce[1]));
        }
    }

    /** What is pending for an event, for tests: {@code [armour, protection]} or {@code null}. */
    double @Nullable [] pending(EntityDamageEvent event) {
        double[] pierce = pending.get(event);
        return pierce == null ? null : pierce.clone();
    }

    void clear() {
        pending.clear();
    }
}
