package net.exylia.lib.ability.internal;

import net.exylia.lib.ability.AbilityArgs;
import net.exylia.lib.ability.AbilityContext;
import net.exylia.lib.ability.AbilityEffect;
import net.exylia.lib.ability.PluginAbilities;
import net.exylia.lib.item.Items;
import net.exylia.lib.item.internal.CustomItems;
import net.exylia.lib.packet.GlowingBlocks;
import net.exylia.lib.packet.Packets;
import net.exylia.lib.region.CommonRegionPolicies;
import net.exylia.lib.region.Regions;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.util.Formula;
import net.exylia.lib.util.reward.OverflowPolicy;
import net.exylia.lib.util.reward.PluginRewards;
import net.exylia.lib.util.reward.Rewards;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Effects for gathering: revealing blocks, replanting and growing crops,
 * fishing waits, and handing out an item. Each reads all its keys when
 * compiled; blocks are only read and changed in regions this thread owns.
 */
final class FieldEffects {

    /** The most blocks one reveal outlines, and the widest radii, so a formula cannot scan a world. */
    static final int REVEAL_LIMIT = 512;
    static final int REVEAL_RADIUS = 16;
    static final int GROW_RADIUS = 8;

    /** What a ripe crop is replanted with. */
    static final Map<Material, Material> SEEDS = new EnumMap<>(Map.of(
            Material.WHEAT, Material.WHEAT_SEEDS,
            Material.CARROTS, Material.CARROT,
            Material.POTATOES, Material.POTATO,
            Material.BEETROOTS, Material.BEETROOT_SEEDS,
            Material.NETHER_WART, Material.NETHER_WART,
            Material.COCOA, Material.COCOA_BEANS));

    /** What {@code grow} advances: crops whose age is all there is to them (no two-block pitcher crops). */
    static final Set<Material> GROWABLE = EnumSet.of(Material.WHEAT, Material.CARROTS, Material.POTATOES,
            Material.BEETROOTS, Material.NETHER_WART, Material.COCOA, Material.MELON_STEM, Material.PUMPKIN_STEM,
            Material.SWEET_BERRY_BUSH, Material.TORCHFLOWER_CROP);

    private FieldEffects() {
        throw new AssertionError("No instances.");
    }

    static void register(PluginAbilities abilities) {
        abilities.effect("reveal", FieldEffects::reveal);
        abilities.effect("replant", FieldEffects::replant);
        abilities.effect("grow", FieldEffects::grow);
        abilities.effect("fish-hook", FieldEffects::fishHook);
        abilities.effect("item", FieldEffects::item);
    }

    // ------------------------------------------------------------------ reveal

    /**
     * {@code reveal: { radius: 6, only: [...], duration: 5s, colour: aqua }}: outlines
     * the matching blocks around the holder, through walls, for the holder only.
     */
    private static AbilityEffect reveal(AbilityArgs args) {
        Formula radius = args.formula("radius", "6");
        Set<Material> only = BlockMatcher.compile(args.raw("only"), args::problem);
        Formula seconds = args.seconds("duration", 5);
        String written = args.has("color") ? args.string("color", "aqua") : args.string("colour", "aqua");
        TextColor colour = colour(written);
        if (colour == null) {
            args.problem("unknown colour '" + written + "'; aqua is used");
            colour = NamedTextColor.AQUA;
        }
        if (only.isEmpty()) throw new IllegalArgumentException("reveal needs 'only': the blocks to outline");
        TextColor outline = colour;
        return (ctx, report) -> {
            if (!Packets.isAvailable()) return;
            Player holder = ctx.holder();
            int r = clamp((int) Math.round(radius.applyOr(6, ctx.level())), 1, REVEAL_RADIUS);
            Map<Location, BlockData> found = new HashMap<>();
            TaskScheduler tasks = ctx.abilities().tasks();
            scan(holder.getLocation(), r, tasks, block -> {
                if (!only.contains(block.getType())) return true;
                found.put(block.getLocation(), block.getBlockData());
                return found.size() < REVEAL_LIMIT;
            });
            if (found.isEmpty()) return;
            GlowingBlocks glowing = Packets.of(ctx.abilities().plugin()).glowingBlocks();
            glowing.show(holder, found, outline);
            long ticks = Math.max(1, Math.round(seconds.applyOr(5, ctx.level()) * 20));
            tasks.runAtEntityLater(holder, ticks, () -> glowing.clear(holder, found.keySet()));
        };
    }

    static @Nullable TextColor colour(String written) {
        String value = written.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (value.startsWith("#")) return TextColor.fromHexString(value);
        return NamedTextColor.NAMES.value(value);
    }

    // ------------------------------------------------------------------ crops

    /**
     * {@code replant}: after a ripe crop is broken, plants it again a tick
     * later with one seed taken from the drops, after every multiplier ran.
     * No seed in the drops, or the spot no longer free, plants nothing.
     */
    private static AbilityEffect replant(AbilityArgs args) {
        args.value();
        return (ctx, report) -> {
            Block block = ctx.block();
            if (block == null || !(ctx.event() instanceof BlockBreakEvent)) return;
            BlockData data = block.getBlockData();
            Material seed = SEEDS.get(block.getType());
            if (seed == null || !(data instanceof Ageable crop) || crop.getAge() < crop.getMaximumAge()) return;
            if (ctx.drops() == null) return;
            Ageable young = (Ageable) data.clone();
            young.setAge(0);
            Location where = block.getLocation();
            TaskScheduler tasks = ctx.abilities().tasks();
            boolean[] done = {false};
            ctx.transformDropsLast(items -> {
                // Once: the same transform also sees drops an area break delivers later.
                if (done[0]) return;
                done[0] = true;
                if (!takeOne(items, seed)) return;
                tasks.runAtLocationLater(where, 1, () -> {
                    Block now = where.getBlock();
                    if (now.getType().isAir() && young.isSupported(now)) {
                        now.setBlockData(young);
                    } else if (where.getWorld() != null) {
                        where.getWorld().dropItemNaturally(where.clone().add(0.5, 0.5, 0.5), new ItemStack(seed));
                    }
                });
            });
        };
    }

    /** Takes one plain {@code seed} out of the items; answers whether there was one. */
    static boolean takeOne(List<ItemStack> items, Material seed) {
        for (Iterator<ItemStack> it = items.iterator(); it.hasNext(); ) {
            ItemStack item = it.next();
            if (item == null || item.getType() != seed || item.hasItemMeta() || item.getAmount() <= 0) continue;
            if (item.getAmount() == 1) it.remove();
            else item.setAmount(item.getAmount() - 1);
            return true;
        }
        return false;
    }

    /**
     * {@code grow: { radius: 4, stages: 1, max: 16 }}: advances up to {@code max}
     * crops around the trigger's place by {@code stages} ages, where the
     * region policy {@code build} allows it.
     */
    private static AbilityEffect grow(AbilityArgs args) {
        Formula radius = args.formula("radius", "4");
        Formula stages = args.formula("stages", "1");
        Formula max = args.formula("max", "16");
        return (ctx, report) -> {
            int r = clamp((int) Math.round(radius.applyOr(4, ctx.level())), 1, GROW_RADIUS);
            int step = Math.max(1, (int) Math.round(stages.applyOr(1, ctx.level()) * ctx.scale()));
            int[] left = {(int) Math.round(max.applyOr(16, ctx.level()))};
            if (left[0] <= 0) return;
            scan(ctx.location(), r, ctx.abilities().tasks(), block -> {
                if (!GROWABLE.contains(block.getType()) || !(block.getBlockData() instanceof Ageable crop)) return true;
                int next = grown(crop.getAge(), crop.getMaximumAge(), step);
                if (next == crop.getAge()) return true;
                if (!Regions.resolve(block.getWorld().getUID(), block.getX() + 0.5, block.getY() + 0.5,
                        block.getZ() + 0.5, CommonRegionPolicies.BUILD).value()) return true;
                crop.setAge(next);
                block.setBlockData(crop);
                return --left[0] > 0;
            });
        };
    }

    static int grown(int age, int maximum, int step) {
        return Math.min(maximum, age + Math.max(0, step));
    }

    /**
     * Visits the blocks of a sphere around a centre, column by column, only in
     * loaded chunks this thread owns; stops when the visitor answers false.
     */
    private static void scan(Location centre, int radius, TaskScheduler tasks, Predicate<Block> visit) {
        World world = centre.getWorld();
        if (world == null) return;
        int cx = centre.getBlockX();
        int cy = centre.getBlockY();
        int cz = centre.getBlockZ();
        int bottom = Math.max(world.getMinHeight(), cy - radius);
        int top = Math.min(world.getMaxHeight() - 1, cy + radius);
        int squared = radius * radius;
        Location scratch = new Location(world, 0, 0, 0);
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                int flat = (x - cx) * (x - cx) + (z - cz) * (z - cz);
                if (flat > squared || !world.isChunkLoaded(x >> 4, z >> 4)) continue;
                scratch.set(x, cy, z);
                if (!tasks.isOwnedBy(scratch)) continue;
                for (int y = bottom; y <= top; y++) {
                    if (flat + (y - cy) * (y - cy) > squared) continue;
                    if (!visit.test(world.getBlockAt(x, y, z))) return;
                }
            }
        }
    }

    // ------------------------------------------------------------------ fishing

    /**
     * {@code fish-hook: { wait-multiplier: 0.8 }} on {@code CAST}: scales how long
     * the hook waits for a bite. Below 1 is faster. The gate's factor does not apply.
     */
    private static AbilityEffect fishHook(AbilityArgs args) {
        Formula multiplier = args.formula("wait-multiplier", "1");
        return (ctx, report) -> {
            if (!(ctx.event() instanceof PlayerFishEvent fishing)) return;
            FishHook hook = fishing.getHook();
            int[] waits = waits(hook.getMinWaitTime(), hook.getMaxWaitTime(), multiplier.applyOr(1, ctx.level()));
            // In an order that never puts the minimum above the maximum, which the server refuses.
            if (waits[0] <= hook.getMinWaitTime()) {
                hook.setMinWaitTime(waits[0]);
                hook.setMaxWaitTime(waits[1]);
            } else {
                hook.setMaxWaitTime(waits[1]);
                hook.setMinWaitTime(waits[0]);
            }
        };
    }

    /** {@code [min, max]} scaled by a factor, at least 1 tick, the maximum never below the minimum. */
    static int[] waits(int min, int max, double factor) {
        double safe = Double.isFinite(factor) ? Math.max(0, factor) : 1;
        int low = Math.max(1, (int) Math.round(min * safe));
        int high = Math.max(low, (int) Math.round(max * safe));
        return new int[]{low, high};
    }

    // ------------------------------------------------------------------ items

    /**
     * {@code item: { material, amount: 1, to: inventory | drop, match: block | victim }}:
     * gives an item, vanilla or a resource-pack plugin's id. With {@code match},
     * the material takes the wood or colour of the trigger's block or victim
     * ({@link #variant}). What does not fit goes to the plugin's reward queue
     * when it chose {@link OverflowPolicy#QUEUE}, else drops at the holder's feet.
     */
    private static AbilityEffect item(AbilityArgs args) {
        String material = args.string("material", "").trim();
        Formula amount = args.formula("amount", "1");
        boolean drop = args.string("to", "inventory").equalsIgnoreCase("drop");
        String match = args.string("match", "").trim().toLowerCase(Locale.ROOT);
        if (material.isEmpty()) throw new IllegalArgumentException("item needs a 'material'");
        if (!CustomItems.isCustom(material) && Material.matchMaterial(material) == null) {
            throw new IllegalArgumentException("unknown material '" + material + "'");
        }
        if (!match.isEmpty() && !match.equals("block") && !match.equals("victim") && !match.equals("entity")) {
            throw new IllegalArgumentException("item 'match' is block or victim, not '" + match + "'");
        }
        Material plain = Material.matchMaterial(material);
        return (ctx, report) -> {
            int count = BuiltinEffects.roll(amount.applyOr(0, ctx.level()) * ctx.scale());
            if (count <= 0) return;
            // Built on each proc: a pack plugin may load its items after this compiled.
            ItemStack base = plain != null && !match.isEmpty()
                    ? new ItemStack(variant(plain, variantOf(ctx, match)))
                    : Items.icon(material);
            int stack = Math.max(1, base.getMaxStackSize());
            Player holder = ctx.holder();
            Location where = drop ? ctx.location() : holder.getLocation();
            PluginRewards queue = drop ? null : queueOf(ctx);
            report.drops(count);
            for (int left = count; left > 0; left -= stack) {
                ItemStack one = base.clone();
                one.setAmount(Math.min(stack, left));
                if (drop) {
                    dropAt(ctx, where, one);
                } else if (queue != null) {
                    queue.giveItem(holder, one);
                } else {
                    holder.getInventory().addItem(one).values().forEach(over -> dropAt(ctx, where, over));
                }
            }
        };
    }

    /** The owning plugin's rewards, when it asked to queue what does not fit. */
    private static @Nullable PluginRewards queueOf(AbilityContext ctx) {
        return RewardQueues.of(ctx.abilities().plugin());
    }

    /** Wood types and dye colours, longest first so {@code DARK_OAK} wins over {@code OAK}. */
    static final List<String> VARIANTS;

    static {
        List<String> names = new java.util.ArrayList<>(List.of("OAK", "SPRUCE", "BIRCH", "JUNGLE", "ACACIA",
                "DARK_OAK", "PALE_OAK", "MANGROVE", "CHERRY", "CRIMSON", "WARPED", "BAMBOO"));
        for (org.bukkit.DyeColor colour : org.bukkit.DyeColor.values()) names.add(colour.name());
        names.sort(java.util.Comparator.comparingInt(String::length).reversed());
        VARIANTS = List.copyOf(names);
    }

    /** Names the swap builds that the game spells differently. */
    static final Map<String, String> VARIANT_NAMES = Map.of(
            "CRIMSON_SAPLING", "CRIMSON_FUNGUS",
            "WARPED_SAPLING", "WARPED_FUNGUS",
            "MANGROVE_SAPLING", "MANGROVE_PROPAGULE",
            "BAMBOO_SAPLING", "BAMBOO");

    /** The wood or colour of the trigger's block or victim, {@code null} when it has none. */
    private static @Nullable String variantOf(AbilityContext ctx, String match) {
        if (match.equals("block")) {
            Block block = ctx.block();
            return block == null ? null : variantOf(block.getType().name());
        }
        if (ctx.victim() instanceof org.bukkit.material.Colorable colourful && colourful.getColor() != null) {
            return colourful.getColor().name();
        }
        return ctx.victim() == null ? null : variantOf(ctx.victim().getType().name());
    }

    /** The variant a name starts with ({@code STRIPPED_DARK_OAK_LOG} is {@code DARK_OAK}), or {@code null}. */
    static @Nullable String variantOf(String name) {
        String bare = name.startsWith("STRIPPED_") ? name.substring("STRIPPED_".length()) : name;
        for (String variant : VARIANTS) {
            if (bare.equals(variant) || bare.startsWith(variant + "_")) return variant;
        }
        return null;
    }

    /**
     * {@code material} in another variant: {@code OAK_SAPLING} for {@code SPRUCE}
     * is {@code SPRUCE_SAPLING}, {@code WHITE_WOOL} for {@code RED} is
     * {@code RED_WOOL}. The material itself when either has no variant or the
     * result is not a material on this server.
     */
    static Material variant(Material material, @Nullable String variant) {
        String own = variantOf(material.name());
        if (variant == null || own == null || own.equals(variant)) return material;
        String name = variant + material.name().substring(material.name().indexOf(own) + own.length());
        Material found = Material.matchMaterial(VARIANT_NAMES.getOrDefault(name, name));
        // Every swap of an item name is an item; the few the game spells otherwise are in VARIANT_NAMES.
        return found != null ? found : material;
    }

    private static void dropAt(AbilityContext ctx, Location where, ItemStack item) {
        World world = where.getWorld();
        if (world == null) return;
        TaskScheduler tasks = ctx.abilities().tasks();
        if (tasks.isOwnedBy(where)) world.dropItemNaturally(where, item);
        else tasks.runAtLocation(where, () -> world.dropItemNaturally(where, item));
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }
}
