package net.exylia.lib.ability.internal;

import net.exylia.lib.ability.Ability;
import net.exylia.lib.ability.AbilityArgs;
import net.exylia.lib.ability.AbilityContext;
import net.exylia.lib.ability.AbilityEffect;
import net.exylia.lib.ability.AbilityReport;
import net.exylia.lib.ability.AreaBreak;
import net.exylia.lib.ability.AreaBreaks;
import net.exylia.lib.ability.AreaShape;
import net.exylia.lib.ability.ContextKey;
import net.exylia.lib.ability.Held;
import net.exylia.lib.ability.PluginAbilities;
import net.exylia.lib.action.ActionCall;
import net.exylia.lib.action.ActionContext;
import net.exylia.lib.action.Actions;
import net.exylia.lib.display.Indicators;
import net.exylia.lib.display.Vfx;
import net.exylia.lib.modifier.Modifiers;
import net.exylia.lib.util.CooldownScope;
import net.exylia.lib.util.Cooldowns;
import net.exylia.lib.util.Formula;
import net.exylia.lib.util.sequence.Sequence;
import net.exylia.lib.util.sequence.SequenceTarget;
import net.exylia.lib.util.sequence.Sequences;
import net.exylia.lib.util.teleport.Teleports;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The effects every plugin gets. Each reads all its keys when compiled and
 * never parses anything on a proc.
 */
public final class BuiltinEffects {

    /** Potion effects {@code cleanse: negative} removes. */
    private static final Set<String> HARMFUL = Set.of("slowness", "mining_fatigue", "instant_damage", "nausea",
            "blindness", "hunger", "weakness", "poison", "wither", "levitation", "unluck", "darkness", "bad_omen",
            "infested", "oozing", "weaving", "wind_charged");

    private static volatile @Nullable Map<Material, ItemStack> smelting;

    private BuiltinEffects() {
        throw new AssertionError("No instances.");
    }

    /** Registers them all. */
    public static void register(PluginAbilities abilities) {
        abilities.effect("damage", BuiltinEffects::damage);
        abilities.effect("heal", BuiltinEffects::heal);
        abilities.effect("absorption", BuiltinEffects::absorption);
        abilities.effect("lifesteal", BuiltinEffects::lifesteal);
        abilities.effect("knockback", args -> push(args, false));
        abilities.effect("pull", args -> push(args, true));
        abilities.effect("launch", BuiltinEffects::launch);
        abilities.effect("ignite", BuiltinEffects::ignite);
        abilities.effect("freeze", BuiltinEffects::freeze);
        abilities.effect("potion", BuiltinEffects::potion);
        abilities.effect("cleanse", BuiltinEffects::cleanse);
        abilities.effect("cancel", args -> {
            args.value();
            return (ctx, report) -> {
                if (ctx.event() instanceof Cancellable cancellable) cancellable.setCancelled(true);
            };
        });
        abilities.effect("drops", BuiltinEffects::drops);
        abilities.effect("xp", BuiltinEffects::xp);
        abilities.effect("money", BuiltinEffects::money);
        abilities.effect("smelt", BuiltinEffects::smelt);
        abilities.effect("break-area", BuiltinEffects::breakArea);
        abilities.effect("teleport", BuiltinEffects::teleport);
        abilities.effect("sequence", BuiltinEffects::sequence);
        abilities.effect("vfx", BuiltinEffects::vfx);
        abilities.effect("action", BuiltinEffects::action);
        abilities.effect("cooldown-reset", BuiltinEffects::cooldownReset);
    }

    // ------------------------------------------------------------------ combat

    /**
     * {@code damage: { target, amount, mode: flat | percent | true, every, times }}.
     * {@code percent} is a share of the target's maximum health; {@code true}
     * ignores armour; {@code every} and {@code times} spread it over time on the
     * target's own scheduler, with the numbers of the proc that started it.
     */
    private static AbilityEffect damage(AbilityArgs args) {
        Targets targets = Targets.read(args, Targets.Kind.VICTIM);
        Formula amount = args.formula("amount", "1");
        String mode = args.string("mode", "flat").toLowerCase(Locale.ROOT);
        double every = args.seconds("every", 0).applyOr(0, 1);
        int times = Math.max(1, args.integer("times", 1));
        return (ctx, report) -> {
            double base = amount.applyOr(0, ctx.level()) * ctx.scale();
            if (base <= 0) return;
            Ability ability = ctx.ability();
            Player holder = ctx.holder();
            for (LivingEntity target : targets.resolve(ctx)) {
                double hit = mode.equals("percent") ? Targets.maxHealth(target) * base / 100 : base;
                boolean trueDamage = mode.equals("true");
                report.damage(hit * times).target();
                if (every <= 0 || times <= 1) {
                    ctx.at(target, () -> hurt(target, hit, holder, trueDamage, ability));
                    continue;
                }
                long period = Math.max(1, Math.round(every * 20));
                int[] left = {times};
                ctx.abilities().tasks().runAtEntityTimer(target, period, period, handle -> {
                    if (target.isDead() || left[0]-- <= 0) {
                        handle.cancel();
                        return;
                    }
                    hurt(target, hit, holder, trueDamage, ability);
                });
            }
        };
    }

    private static void hurt(LivingEntity target, double amount, Player holder, boolean trueDamage,
                             @Nullable Ability ability) {
        ChainGuard.run(ability, () -> {
            if (trueDamage) {
                target.setHealth(Math.max(0, target.getHealth() - amount));
            } else if (holder.isOnline()) {
                target.damage(amount, holder);
            } else {
                target.damage(amount);
            }
        });
    }

    /** {@code heal: { target: self, amount, mode: flat | percent }}. */
    private static AbilityEffect heal(AbilityArgs args) {
        Targets targets = Targets.read(args, Targets.Kind.SELF);
        Formula amount = args.formula("amount", "1");
        boolean percent = args.string("mode", "flat").equalsIgnoreCase("percent");
        return (ctx, report) -> {
            double base = amount.applyOr(0, ctx.level()) * ctx.scale();
            for (LivingEntity target : targets.resolve(ctx)) {
                double max = Targets.maxHealth(target);
                double healed = percent ? max * base / 100 : base;
                ctx.at(target, () -> target.setHealth(Math.min(max, target.getHealth() + healed)));
                report.healing(healed).target();
            }
        };
    }

    /** {@code absorption: { target: self, amount, max: 20 }}: adds absorption, up to {@code max}. */
    private static AbilityEffect absorption(AbilityArgs args) {
        Targets targets = Targets.read(args, Targets.Kind.SELF);
        Formula amount = args.formula("amount", "2");
        double cap = args.number("max", 20);
        return (ctx, report) -> {
            double added = amount.applyOr(0, ctx.level()) * ctx.scale();
            for (LivingEntity target : targets.resolve(ctx)) {
                ctx.at(target, () -> {
                    double wanted = Math.min(cap, target.getAbsorptionAmount() + added);
                    try {
                        target.setAbsorptionAmount(wanted);
                    } catch (IllegalArgumentException aboveAttribute) {
                        // The max_absorption attribute is lower than asked; fill what it allows.
                    }
                });
                report.healing(added).target();
            }
        };
    }

    /** {@code lifesteal: { amount }}: heals the holder this percent of the hit's final damage. */
    private static AbilityEffect lifesteal(AbilityArgs args) {
        Formula percent = args.formula("amount", "10");
        return (ctx, report) -> {
            if (!(ctx.event() instanceof EntityDamageEvent hit)) return;
            double healed = hit.getFinalDamage() * percent.applyOr(0, ctx.level()) / 100 * ctx.scale();
            if (healed <= 0) return;
            Player holder = ctx.holder();
            holder.setHealth(Math.min(Targets.maxHealth(holder), holder.getHealth() + healed));
            report.healing(healed);
        };
    }

    /** {@code knockback: { target, strength, vertical }} and {@code pull}: away from or towards the holder. */
    private static AbilityEffect push(AbilityArgs args, boolean towards) {
        Targets targets = Targets.read(args, Targets.Kind.VICTIM);
        Formula strength = args.formula("strength", "1");
        double vertical = args.number("vertical", 0.35);
        return (ctx, report) -> {
            double power = strength.applyOr(0, ctx.level()) * ctx.scale();
            Location from = ctx.holder().getLocation();
            for (LivingEntity target : targets.resolve(ctx)) {
                if (target.equals(ctx.holder())) continue;
                Vector away = target.getLocation().toVector().subtract(from.toVector()).setY(0);
                if (away.lengthSquared() < 1.0E-4) away = from.getDirection().setY(0);
                Vector push = away.normalize().multiply(towards ? -power : power).setY(vertical);
                ctx.at(target, () -> target.setVelocity(push));
                report.target();
            }
        };
    }

    /** {@code launch: { target, strength }}: straight up. */
    private static AbilityEffect launch(AbilityArgs args) {
        Targets targets = Targets.read(args, Targets.Kind.VICTIM);
        Formula strength = args.formula("strength", "1");
        return (ctx, report) -> {
            double power = strength.applyOr(0, ctx.level()) * ctx.scale();
            for (LivingEntity target : targets.resolve(ctx)) {
                ctx.at(target, () -> target.setVelocity(target.getVelocity().setY(power)));
                report.target();
            }
        };
    }

    /** {@code ignite: { target, duration }}: sets on fire, never shortening a longer fire. */
    private static AbilityEffect ignite(AbilityArgs args) {
        Targets targets = Targets.read(args, Targets.Kind.VICTIM);
        Formula seconds = args.seconds("duration", 3);
        return (ctx, report) -> {
            int ticks = (int) Math.round(seconds.applyOr(0, ctx.level()) * 20);
            for (LivingEntity target : targets.resolve(ctx)) {
                ctx.at(target, () -> target.setFireTicks(Math.max(target.getFireTicks(), ticks)));
                report.target();
            }
        };
    }

    /** {@code freeze: { target, duration }}: powder-snow frost, never shortening a longer one. */
    private static AbilityEffect freeze(AbilityArgs args) {
        Targets targets = Targets.read(args, Targets.Kind.VICTIM);
        Formula seconds = args.seconds("duration", 3);
        return (ctx, report) -> {
            int ticks = (int) Math.round(seconds.applyOr(0, ctx.level()) * 20);
            for (LivingEntity target : targets.resolve(ctx)) {
                ctx.at(target, () -> target.setFreezeTicks(Math.max(target.getFreezeTicks(), ticks)));
                report.target();
            }
        };
    }

    /**
     * {@code potion: { target, type, level, duration }}; level 1 is amplifier 0.
     * On a PASSIVE ability, with no duration, it lasts until unequipped and
     * {@link AbilityEffect#revert} removes it.
     */
    private static AbilityEffect potion(AbilityArgs args) {
        Targets targets = Targets.read(args, Targets.Kind.VICTIM);
        String name = args.string("type", "");
        Formula level = args.formula("level", "1");
        boolean timed = args.has("duration");
        Formula seconds = args.seconds("duration", 5);
        LazyPotion lazy = new LazyPotion(name);
        if (!lazy.checkable()) {
            // No server to ask (a unit test): resolved on the first proc instead.
        } else if (lazy.get() == null) {
            throw new IllegalArgumentException("unknown potion type '" + name + "'");
        }
        return new AbilityEffect() {
            @Override
            public void apply(AbilityContext ctx, AbilityReport report) {
                PotionEffectType type = lazy.get();
                if (type == null) return;
                int amplifier = Math.max(0, (int) Math.round(level.applyOr(1, ctx.level())) - 1);
                int ticks = !timed && ctx.trigger().equals("PASSIVE") ? PotionEffect.INFINITE_DURATION
                        : (int) Math.max(1, Math.round(seconds.applyOr(5, ctx.level()) * 20));
                PotionEffect effect = new PotionEffect(type, ticks, amplifier, true, true, true);
                for (LivingEntity target : targets.resolve(ctx)) {
                    ctx.at(target, () -> target.addPotionEffect(effect));
                    report.target();
                }
            }

            @Override
            public void revert(AbilityContext ctx) {
                PotionEffectType type = lazy.get();
                if (type == null) return;
                int amplifier = Math.max(0, (int) Math.round(level.applyOr(1, ctx.level())) - 1);
                for (LivingEntity target : targets.resolve(ctx)) {
                    PotionEffect active = target.getPotionEffect(type);
                    if (active != null && active.getAmplifier() == amplifier && active.isInfinite()) {
                        target.removePotionEffect(type);
                    }
                }
            }
        };
    }

    /** {@code cleanse: { target: self, types: negative | all | [names] }}. */
    private static AbilityEffect cleanse(AbilityArgs args) {
        Targets targets = Targets.read(args, Targets.Kind.SELF);
        List<String> types = args.strings("types");
        Set<String> names = types.isEmpty() ? Set.of("negative") : Set.copyOf(types.stream()
                .map(type -> type.toLowerCase(Locale.ROOT)).toList());
        return (ctx, report) -> {
            for (LivingEntity target : targets.resolve(ctx)) {
                ctx.at(target, () -> {
                    for (PotionEffect active : List.copyOf(target.getActivePotionEffects())) {
                        String key = active.getType().getKey().getKey();
                        if (names.contains("all") || names.contains(key)
                                || (names.contains("negative") && HARMFUL.contains(key))) {
                            target.removePotionEffect(active.getType());
                        }
                    }
                });
                report.target();
            }
        };
    }

    // ------------------------------------------------------------------ economy

    /**
     * {@code drops: { multiplier, to: inventory }}: multiplies the drops once
     * in the merge step, through the {@code drops} modifiers; {@code to:
     * inventory} sends what the library delivers to the holder.
     */
    private static AbilityEffect drops(AbilityArgs args) {
        Formula multiplier = args.formula("multiplier", "1");
        boolean toInventory = args.string("to", "ground").equalsIgnoreCase("inventory");
        boolean multiplies = args.has("multiplier");
        return (ctx, report) -> {
            if (toInventory) ctx.with(ContextKey.DROPS_TO_INVENTORY, true);
            double factor = multiplier.applyOr(1, ctx.level()) * ctx.scale();
            if (!multiplies) {
                if (ctx.drops() != null) report.drops(count(ctx.drops()));
                return;
            }
            Player holder = ctx.holder();
            String source = ctx.abilities().plugin().getName().toLowerCase(Locale.ROOT);
            ctx.transformDrops(items -> {
                for (ItemStack item : items) {
                    if (item == null) continue;
                    double boosted = Modifiers.apply(holder.getUniqueId(), Modifiers.DROPS, source,
                            item.getAmount() * factor);
                    item.setAmount(roll(boosted));
                }
                items.removeIf(item -> item == null || item.getAmount() <= 0);
            });
            List<ItemStack> now = ctx.drops();
            if (now != null) report.drops(count(now));
        };
    }

    /**
     * {@code xp: { multiplier }} multiplies the event's experience (a kill, a
     * block); {@code xp: { amount }} gives the holder experience. Both through
     * the {@code xp} modifiers.
     */
    private static AbilityEffect xp(AbilityArgs args) {
        boolean given = args.has("amount");
        Formula amount = args.formula("amount", "0");
        Formula multiplier = args.formula("multiplier", "1");
        return (ctx, report) -> {
            Player holder = ctx.holder();
            String source = ctx.abilities().plugin().getName().toLowerCase(Locale.ROOT);
            if (given) {
                int points = (int) Math.round(amount.applyOr(0, ctx.level()) * ctx.scale());
                if (points > 0) holder.giveExp(Modifiers.xp(holder.getUniqueId(), source, points));
                return;
            }
            double factor = multiplier.applyOr(1, ctx.level()) * ctx.scale();
            if (ctx.event() instanceof EntityDeathEvent death) {
                death.setDroppedExp(Modifiers.xp(holder.getUniqueId(), source,
                        (int) Math.round(death.getDroppedExp() * factor)));
            } else if (ctx.event() instanceof BlockBreakEvent breaking) {
                breaking.setExpToDrop(Modifiers.xp(holder.getUniqueId(), source,
                        (int) Math.round(breaking.getExpToDrop() * factor)));
            }
        };
    }

    /** {@code money: { amount }}: pays the holder through the {@code money} modifiers and the economy. */
    private static AbilityEffect money(AbilityArgs args) {
        Formula amount = args.formula("amount", "0");
        return (ctx, report) -> {
            double value = amount.applyOr(0, ctx.level()) * ctx.scale();
            if (value <= 0) return;
            Modifiers.pay(ctx.holder(), ctx.abilities().plugin().getName().toLowerCase(Locale.ROOT),
                    BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP));
        };
    }

    /** {@code smelt}: turns the drops into what a furnace makes of them, once, in the merge step. */
    private static AbilityEffect smelt(AbilityArgs args) {
        args.value();
        return (ctx, report) -> ctx.transformDrops(items -> {
            Map<Material, ItemStack> table = smeltingTable();
            for (int i = 0; i < items.size(); i++) {
                ItemStack item = items.get(i);
                if (item == null) continue;
                ItemStack result = table.get(item.getType());
                if (result == null) continue;
                ItemStack smelted = result.clone();
                smelted.setAmount(item.getAmount() * Math.max(1, result.getAmount()));
                items.set(i, smelted);
            }
        });
    }

    /** Every furnace recipe whose input is a set of materials, read once from the server. */
    private static Map<Material, ItemStack> smeltingTable() {
        Map<Material, ItemStack> table = smelting;
        if (table != null) return table;
        Map<Material, ItemStack> built = new EnumMap<>(Material.class);
        Iterator<Recipe> recipes = Bukkit.recipeIterator();
        while (recipes.hasNext()) {
            if (recipes.next() instanceof FurnaceRecipe furnace) {
                CookingRecipe<?> cooking = furnace;
                if (cooking.getInputChoice() instanceof RecipeChoice.MaterialChoice choice) {
                    for (Material input : choice.getChoices()) built.putIfAbsent(input, cooking.getResult());
                }
            }
        }
        smelting = built;
        return built;
    }

    // ------------------------------------------------------------------ world

    /**
     * {@code break-area: { shape: plane | cube | vein | column, radius, depth, max,
     * length, vertical, budget, compat-events, exclude, only, to: inventory }} on the block the
     * trigger broke; {@code only} takes what the {@code block} condition takes.
     *  Its drops join the firing's drops, so smelting and multipliers
     * apply once to the whole area.
     */
    private static AbilityEffect breakArea(AbilityArgs args) {
        String shape = args.string("shape", "plane").toLowerCase(Locale.ROOT);
        Formula radius = args.formula("radius", "1");
        Formula depth = args.formula("depth", "0");
        Formula max = args.formula("max", "64");
        Formula length = args.formula("length", "8");
        boolean vertical = args.flag("vertical", false);
        int budget = args.integer("budget", AreaBreak.DEFAULT_BUDGET);
        int compat = args.integer("compat-events", 0);
        List<Material> exclude = new ArrayList<>();
        for (String name : args.strings("exclude")) {
            Material material = Material.matchMaterial(name);
            if (material == null) args.problem("unknown material " + name + " in exclude; ignored");
            else exclude.add(material);
        }
        Set<Material> only = BlockMatcher.compile(args.raw("only"), args::problem);
        if (args.has("only") && only.isEmpty()) args.problem("'only' names no block; it is ignored");
        boolean toInventory = args.string("to", "ground").equalsIgnoreCase("inventory");
        if (!Set.of("plane", "cube", "vein", "column").contains(shape)) {
            throw new IllegalArgumentException("unknown shape '" + shape + "'");
        }
        return (ctx, report) -> {
            Block block = ctx.block();
            if (block == null) return;
            int level = ctx.level();
            AreaShape area = switch (shape) {
                case "cube" -> AreaShape.cube((int) Math.round(radius.applyOr(1, level)));
                case "vein" -> AreaShape.vein((int) Math.round(max.applyOr(64, level)));
                case "column" -> AreaShape.column((int) Math.round(length.applyOr(8, level)), vertical);
                default -> AreaShape.plane((int) Math.round(radius.applyOr(1, level)),
                        (int) Math.round(depth.applyOr(0, level)));
            };
            if (toInventory) ctx.with(ContextKey.DROPS_TO_INVENTORY, true);
            int planned = AreaBreaks.of(ctx.abilities().plugin()).around(ctx.holder(), block, area)
                    .budget(budget).compatEvents(compat).exclude(exclude).only(only)
                    .deliver(ctx::deliver)
                    .start();
            if (planned > 0) report.target();
        };
    }

    /**
     * {@code teleport: { target: self, to: victim | attacker | block | forward, distance }}.
     * Players move through ExyliaLib's teleports with no warm-up.
     */
    private static AbilityEffect teleport(AbilityArgs args) {
        Targets targets = Targets.read(args, Targets.Kind.SELF);
        String to = args.string("to", "forward").toLowerCase(Locale.ROOT);
        Formula distance = args.formula("distance", "5");
        return (ctx, report) -> {
            Location destination = switch (to) {
                case "victim" -> ctx.victim() == null ? null : ctx.victim().getLocation();
                case "attacker" -> ctx.attacker() == null ? null : ctx.attacker().getLocation();
                case "block" -> ctx.block() == null ? null : ctx.block().getLocation().add(0.5, 1, 0.5);
                default -> {
                    Location eye = ctx.holder().getLocation();
                    Vector ahead = eye.getDirection().setY(0);
                    if (ahead.lengthSquared() < 1.0E-4) yield eye;
                    yield eye.clone().add(ahead.normalize().multiply(distance.applyOr(5, ctx.level())));
                }
            };
            if (destination == null) return;
            for (LivingEntity target : targets.resolve(ctx)) {
                Location landing = destination.clone();
                landing.setYaw(target.getLocation().getYaw());
                landing.setPitch(target.getLocation().getPitch());
                if (target instanceof Player player) {
                    Teleports.of(ctx.abilities().plugin()).to(player, landing).warmup(0).safe().start();
                } else {
                    // A mob, not a player: ExyliaLib's teleports move players only.
                    ctx.at(target, () -> target.teleport(landing));
                }
                report.target();
            }
        };
    }

    /**
     * {@code sequence: <name>} plays the plugin's named sequence (see
     * {@link PluginAbilities#sequences}); {@code sequence: { lines: [...], target }}
     * plays lines compiled at load.
     */
    private static AbilityEffect sequence(AbilityArgs args) {
        Object value = args.value();
        String name = value instanceof String text ? text.trim() : args.string("name", "");
        List<String> lines = args.strings("lines");
        Targets targets = Targets.read(args, Targets.Kind.SELF);
        // Compiled on first play, once: the lines are known now, the plugin's sequences may not be yet.
        Sequence[] compiled = {null};
        if (name.isEmpty() && lines.isEmpty()) throw new IllegalArgumentException("needs a name or lines");
        return (ctx, report) -> {
            Sequence sequence;
            if (!name.isEmpty()) {
                sequence = ctx.abilities().sequence(name);
                if (sequence == null) return;
            } else {
                if (compiled[0] == null) compiled[0] = Sequences.of(ctx.abilities().plugin()).compile(lines);
                sequence = compiled[0];
            }
            List<LivingEntity> resolved = targets.resolve(ctx);
            SequenceTarget where = targets.kind() == Targets.Kind.BLOCK || resolved.isEmpty()
                    ? SequenceTarget.at(ctx.location())
                    : resolved.get(0) instanceof Player player ? SequenceTarget.of(player)
                    : SequenceTarget.at(resolved.get(0).getLocation());
            Sequences.of(ctx.abilities().plugin()).play(sequence, where);
        };
    }

    /**
     * {@code vfx: { target, indicator: true, shake: <radius> }}: floats the
     * damage or healing this proc reported so far over the target, and tilts
     * the cameras of players that close.
     */
    private static AbilityEffect vfx(AbilityArgs args) {
        Targets targets = Targets.read(args, Targets.Kind.VICTIM);
        boolean indicator = args.flag("indicator", true);
        double shake = args.number("shake", 0);
        return (ctx, report) -> {
            for (LivingEntity target : targets.resolve(ctx)) {
                if (indicator) {
                    Indicators indicators = Indicators.of(ctx.abilities().plugin());
                    if (report.damage() > 0) indicators.damage(target, report.damage(), false);
                    else if (report.healing() > 0) indicators.heal(target, report.healing());
                }
                if (shake > 0) {
                    Vfx.at(target.getLocation()).nearby(shake).shake(0, shake).play(ctx.abilities().plugin());
                }
            }
        };
    }

    /** {@code action: 'namespace:id args'}: an ExyliaLib action, compiled at load, run as the holder. */
    private static AbilityEffect action(AbilityArgs args) {
        Object value = args.value();
        String raw = value != null ? String.valueOf(value).trim() : args.string("action", "");
        if (raw.isEmpty()) throw new IllegalArgumentException("needs an action");
        ActionCall call = Actions.compile(raw);
        return (ctx, report) -> call.execute(ActionContext.forPlayer(ctx.holder()).origin("ability").build());
    }

    /** {@code cooldown-reset: <ability id> | all}: clears the holder's cooldowns of abilities they hold. */
    private static AbilityEffect cooldownReset(AbilityArgs args) {
        Object value = args.value();
        String which = value != null ? String.valueOf(value).trim() : args.string("ability", "all");
        return (ctx, report) -> {
            CooldownScope scope = CooldownScope.player(ctx.holder().getUniqueId());
            for (Held held : ctx.abilities().held(ctx.holder())) {
                Ability ability = held.ability();
                if (ability == ctx.ability()) continue;
                if (which.equalsIgnoreCase("all") || which.equalsIgnoreCase(ability.id())) {
                    Cooldowns.clear(scope, ability.cooldownKey());
                }
            }
        };
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A potion type looked up by name when first asked and then kept, so an
     * effect compiles where there is no registry (a unit test) and resolves
     * on its first proc on a server.
     */
    static final class LazyPotion {
        private final String name;
        private volatile @Nullable PotionEffectType type;
        private volatile boolean resolved;

        LazyPotion(String name) {
            this.name = name;
        }

        /** Whether the registry can be asked now; false without a server. */
        boolean checkable() {
            if (resolved) return true;
            try {
                get();
                return resolved;
            } catch (RuntimeException | LinkageError noServer) {
                return false;
            }
        }

        /** The type, or {@code null} when it is unknown or there is no registry yet. */
        @Nullable PotionEffectType get() {
            if (resolved) return type;
            if (name.isBlank()) {
                resolved = true;
                return null;
            }
            PotionEffectType found;
            try {
                found = potionType(name);
            } catch (RuntimeException | LinkageError noServer) {
                return null;
            }
            type = found;
            resolved = true;
            return found;
        }
    }

    /** By key, then by legacy name; throws when neither lookup can run (no server). */
    private static @Nullable PotionEffectType potionType(String name) {
        String key = name.trim().toLowerCase(Locale.ROOT);
        RuntimeException registryFailure = null;
        try {
            NamespacedKey namespaced = key.contains(":") ? NamespacedKey.fromString(key) : NamespacedKey.minecraft(key);
            PotionEffectType type = namespaced == null ? null : Registry.EFFECT.get(namespaced);
            if (type != null) return type;
        } catch (RuntimeException noRegistry) {
            registryFailure = noRegistry;
        }
        try {
            @SuppressWarnings("deprecation")
            PotionEffectType legacy = PotionEffectType.getByName(name.trim().toUpperCase(Locale.ROOT));
            return legacy;
        } catch (RuntimeException noServer) {
            if (registryFailure != null) noServer.addSuppressed(registryFailure);
            throw noServer;
        }
    }

    private static int roll(double amount) {
        int whole = (int) Math.floor(amount);
        return ThreadLocalRandom.current().nextDouble() < amount - whole ? whole + 1 : whole;
    }

    private static int count(@Nullable List<ItemStack> items) {
        if (items == null) return 0;
        int total = 0;
        for (ItemStack item : items) total += item == null ? 0 : item.getAmount();
        return total;
    }
}
