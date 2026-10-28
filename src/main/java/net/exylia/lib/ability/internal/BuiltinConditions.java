package net.exylia.lib.ability.internal;

import net.exylia.lib.ability.AbilityArgs;
import net.exylia.lib.ability.AbilityCondition;
import net.exylia.lib.ability.AbilityContext;
import net.exylia.lib.ability.PluginAbilities;
import net.exylia.lib.region.CommonRegionPolicies;
import net.exylia.lib.region.PolicyKey;
import net.exylia.lib.region.Regions;
import net.exylia.lib.util.combat.Combat;
import net.exylia.lib.util.internal.Conditions;
import org.bukkit.entity.Animals;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * The conditions every plugin gets: target, health, in-combat, sneaking,
 * sprinting, blocking, gliding, in-water, world, region-policy, mode,
 * permission and placeholder. {@code not} and {@code any} are structural and
 * live in {@link PluginAbilities}.
 */
public final class BuiltinConditions {

    private static final Map<String, PolicyKey<Boolean>> POLICIES = Map.ofEntries(
            Map.entry("pvp", CommonRegionPolicies.PVP), Map.entry("build", CommonRegionPolicies.BUILD),
            Map.entry("break", CommonRegionPolicies.BREAK), Map.entry("interact", CommonRegionPolicies.INTERACT),
            Map.entry("fall_damage", CommonRegionPolicies.FALL_DAMAGE),
            Map.entry("item_drop", CommonRegionPolicies.ITEM_DROP),
            Map.entry("item_pickup", CommonRegionPolicies.ITEM_PICKUP),
            Map.entry("entry", CommonRegionPolicies.ENTRY), Map.entry("exit", CommonRegionPolicies.EXIT));

    private BuiltinConditions() {
        throw new AssertionError("No instances.");
    }

    /** Registers them all. */
    public static void register(PluginAbilities abilities) {
        abilities.condition("target", BuiltinConditions::target);
        abilities.condition("health", BuiltinConditions::health);
        abilities.condition("in-combat", args -> {
            boolean wanted = flag(args);
            return ctx -> Combat.isTagged(ctx.holder()) == wanted;
        });
        abilities.condition("sneaking", args -> state(args, entity -> entity instanceof Player p && p.isSneaking()));
        abilities.condition("sprinting", args -> state(args, entity -> entity instanceof Player p && p.isSprinting()));
        abilities.condition("blocking", args -> state(args, entity -> entity instanceof Player p && p.isBlocking()));
        abilities.condition("gliding", args -> state(args, LivingEntity::isGliding));
        abilities.condition("in-water", args -> state(args, LivingEntity::isInWater));
        abilities.condition("world", args -> {
            Set<String> worlds = lower(values(args));
            return ctx -> worlds.contains(ctx.location().getWorld() == null ? ""
                    : ctx.location().getWorld().getName().toLowerCase(Locale.ROOT));
        });
        abilities.condition("region-policy", BuiltinConditions::regionPolicy);
        abilities.condition("mode", args -> {
            Set<String> modes = lower(values(args));
            return ctx -> modes.contains(ctx.holder().getGameMode().name().toLowerCase(Locale.ROOT));
        });
        abilities.condition("permission", args -> {
            String node = scalar(args);
            return ctx -> ctx.holder().hasPermission(node);
        });
        abilities.condition("placeholder", BuiltinConditions::placeholder);
    }

    /** {@code target: player | mob | monster | animal | any | <entity type>}: the other party. */
    private static AbilityCondition target(AbilityArgs args) {
        Set<String> kinds = lower(values(args));
        return ctx -> {
            LivingEntity other = ctx.other();
            if (other == null) return false;
            for (String kind : kinds) {
                boolean matches = switch (kind) {
                    case "any" -> true;
                    case "player" -> other instanceof Player;
                    case "mob" -> !(other instanceof Player);
                    case "monster" -> other instanceof Monster;
                    case "animal" -> other instanceof Animals;
                    default -> other.getType().name().equalsIgnoreCase(kind);
                };
                if (matches) return true;
            }
            return false;
        };
    }

    /**
     * {@code health: { below: 30%, above: 10%, of: self | victim }}; a value
     * with {@code %} is a share of the maximum, without it absolute health.
     */
    private static AbilityCondition health(AbilityArgs args) {
        String below = args.string("below", "");
        String above = args.string("above", "");
        boolean ofVictim = args.string("of", "self").equalsIgnoreCase("victim");
        double belowValue = threshold(args, "below", below);
        double aboveValue = threshold(args, "above", above);
        boolean belowShare = below.endsWith("%");
        boolean aboveShare = above.endsWith("%");
        return ctx -> {
            LivingEntity subject = ofVictim ? ctx.other() : ctx.holder();
            if (subject == null) return false;
            double health = subject.getHealth();
            double max = Targets.maxHealth(subject);
            if (!below.isEmpty() && health > (belowShare ? max * belowValue / 100 : belowValue)) return false;
            return above.isEmpty() || health > (aboveShare ? max * aboveValue / 100 : aboveValue);
        };
    }

    private static double threshold(AbilityArgs args, String key, String written) {
        if (written.isEmpty()) return 0;
        try {
            return Double.parseDouble(written.replace("%", "").trim());
        } catch (NumberFormatException unreadable) {
            args.problem("'" + key + "' is not a number (" + written + "); ignored");
            return written.endsWith("%") ? 100 : Double.MAX_VALUE;
        }
    }

    /** {@code region-policy: pvp}, or a list: every boolean policy named must allow it here. */
    private static AbilityCondition regionPolicy(AbilityArgs args) {
        List<PolicyKey<Boolean>> keys = values(args).stream().map(name -> {
            PolicyKey<Boolean> key = POLICIES.get(name.toLowerCase(Locale.ROOT).replace('-', '_'));
            if (key == null) args.problem("unknown policy " + name + "; ignored");
            return key;
        }).filter(java.util.Objects::nonNull).toList();
        return ctx -> {
            for (PolicyKey<Boolean> key : keys) {
                if (!Boolean.TRUE.equals(Regions.resolve(ctx.location(), key).value())) return false;
            }
            return true;
        };
    }

    /**
     * {@code placeholder: '%player_level% >= 10'}: the shared toy comparison.
     * Resolves placeholders on every attempt, so it is the one condition that
     * costs a PlaceholderAPI pass; an unreadable one is reported once and holds.
     */
    private static AbilityCondition placeholder(AbilityArgs args) {
        String condition = scalar(args);
        Set<String> reported = ConcurrentHashMap.newKeySet();
        return ctx -> Conditions.holds(condition, ctx.holder(), (subject, message) -> {
            if (reported.add(subject)) {
                ctx.abilities().plugin().getLogger().warning("[abilities] " + message);
            }
        });
    }

    /**
     * {@code sneaking: true | false | self | victim}: whether the holder, or
     * the other party, is in that state.
     */
    private static AbilityCondition state(AbilityArgs args, Predicate<LivingEntity> test) {
        Object value = args.value();
        String written = value == null ? "" : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        boolean victim = written.equals("victim") || written.equals("other");
        boolean wanted = !written.equals("false") && !written.equals("no");
        return ctx -> {
            LivingEntity subject = victim ? ctx.other() : ctx.holder();
            return subject != null && test.test(subject) == wanted;
        };
    }

    private static boolean flag(AbilityArgs args) {
        Object value = args.value();
        if (value == null) return true;
        String written = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return !(written.equals("false") || written.equals("no"));
    }

    private static String scalar(AbilityArgs args) {
        Object value = args.value();
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("needs a value");
        }
        return String.valueOf(value).trim();
    }

    private static List<String> values(AbilityArgs args) {
        Object value = args.value();
        if (value instanceof List<?> list) return list.stream().map(String::valueOf).map(String::trim).toList();
        if (value == null) return args.strings("value");
        return List.of(String.valueOf(value).trim());
    }

    private static Set<String> lower(List<String> values) {
        Set<String> out = new HashSet<>();
        values.forEach(value -> out.add(value.toLowerCase(Locale.ROOT)));
        return out;
    }
}
