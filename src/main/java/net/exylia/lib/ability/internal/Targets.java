package net.exylia.lib.ability.internal;

import net.exylia.lib.ability.AbilityArgs;
import net.exylia.lib.ability.AbilityContext;
import net.exylia.lib.clan.Clans;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Who an effect lands on: {@code self}, {@code victim}, {@code attacker},
 * {@code nearby} ({@code radius}, {@code filter}, {@code max}) or {@code block}.
 *
 * <p>Read once at load from the effect's own keys; resolved on every proc.
 */
public final class Targets {

    /** Who, when the line names nobody. */
    public enum Kind { SELF, VICTIM, ATTACKER, NEARBY, BLOCK }

    /** Which entities {@code nearby} keeps. */
    public enum Filter { ENEMIES, ALLIES, PLAYERS, MOBS, ALL }

    private static final @Nullable Attribute MAX_HEALTH = maxHealthAttribute();

    private final Kind kind;
    private final double radius;
    private final Filter filter;
    private final int max;

    private Targets(Kind kind, double radius, Filter filter, int max) {
        this.kind = kind;
        this.radius = radius;
        this.filter = filter;
        this.max = max;
    }

    /**
     * Reads {@code target}, and for {@code nearby} {@code radius} (5),
     * {@code filter} (enemies) and {@code max} (16).
     */
    public static @NotNull Targets read(@NotNull AbilityArgs args, @NotNull Kind fallback) {
        Kind kind = parse(args, "target", Kind.class, fallback);
        double radius = args.number("radius", 5);
        Filter filter = parse(args, "filter", Filter.class, Filter.ENEMIES);
        int max = args.integer("max", 16);
        return new Targets(kind, Math.max(0, radius), filter, Math.max(1, max));
    }

    /** What the line names. */
    public @NotNull Kind kind() {
        return kind;
    }

    /** The entities, possibly none; {@code block} names none. */
    public @NotNull List<LivingEntity> resolve(@NotNull AbilityContext context) {
        return switch (kind) {
            case SELF -> List.of(context.holder());
            case VICTIM -> context.victim() == null ? List.of() : List.of(context.victim());
            case ATTACKER -> context.attacker() == null ? List.of() : List.of(context.attacker());
            case BLOCK -> List.of();
            case NEARBY -> nearby(context);
        };
    }

    private List<LivingEntity> nearby(AbilityContext context) {
        Player holder = context.holder();
        List<LivingEntity> out = new ArrayList<>();
        for (Entity entity : holder.getNearbyEntities(radius, radius, radius)) {
            if (!(entity instanceof LivingEntity living) || entity instanceof ArmorStand || living.isDead()) continue;
            if (!keeps(holder, living)) continue;
            out.add(living);
            if (out.size() >= max) break;
        }
        return out;
    }

    private boolean keeps(Player holder, LivingEntity other) {
        return switch (filter) {
            case ALL -> true;
            case PLAYERS -> other instanceof Player;
            case MOBS -> !(other instanceof Player);
            case ALLIES -> allied(holder, other);
            case ENEMIES -> !allied(holder, other);
        };
    }

    private static boolean allied(Player holder, LivingEntity other) {
        if (other instanceof Tameable pet && pet.isTamed() && pet.getOwner() != null) {
            return pet.getOwner().getUniqueId().equals(holder.getUniqueId());
        }
        if (!(other instanceof Player player)) return false;
        return Clans.areInSameClan(holder.getUniqueId(), player.getUniqueId())
                || Clans.areAllied(holder.getUniqueId(), player.getUniqueId());
    }

    /** An entity's maximum health, 20 when the server cannot say. */
    public static double maxHealth(@NotNull LivingEntity entity) {
        if (MAX_HEALTH == null) return 20;
        try {
            AttributeInstance instance = entity.getAttribute(MAX_HEALTH);
            return instance == null ? 20 : instance.getValue();
        } catch (RuntimeException | LinkageError unavailable) {
            return 20;
        }
    }

    /** {@code Attribute} stopped being an enum in 1.21; resolved once and guarded. */
    private static @Nullable Attribute maxHealthAttribute() {
        try {
            return Attribute.MAX_HEALTH;
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    static <E extends Enum<E>> E parse(AbilityArgs args, String key, Class<E> type, E fallback) {
        String written = args.string(key, "");
        if (written.isEmpty()) return fallback;
        try {
            return Enum.valueOf(type, written.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException unknown) {
            args.problem("'" + key + "' cannot be " + written + "; using " + fallback.name().toLowerCase(Locale.ROOT));
            return fallback;
        }
    }
}
