package net.exylia.lib.ability;

import net.exylia.lib.ability.internal.AreaPlanner;
import net.exylia.lib.ability.internal.PackedBlocks;
import net.exylia.lib.region.CommonRegionPolicies;
import net.exylia.lib.region.Regions;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.sequence.Sequence;
import net.exylia.lib.util.sequence.SequenceTarget;
import net.exylia.lib.util.sequence.Sequences;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * One area break: tuned with the builder methods, then {@link #start()}ed once.
 *
 * <p>See {@link AreaBreaks} for what makes it cheap. Every method here runs on
 * the breaker's thread.
 *
 * <p>With {@link #replace(Replace)} it changes blocks instead of breaking
 * them (till, path, strip) with the same planning, budget and checks.
 *
 * @since 1.385.0
 */
public final class AreaBreak {

    /** Blocks per tick when nobody says otherwise. */
    public static final int DEFAULT_BUDGET = 64;

    private static final Set<Material> DEFAULT_EXCLUDED = defaultExcluded();

    private final Plugin plugin;
    private final TaskScheduler tasks;
    private final Player breaker;
    private final UUID breakerId;
    private final Block origin;
    private final AreaShape shape;

    private int budget = DEFAULT_BUDGET;
    private int compatEvents;
    private final Set<Material> excluded = EnumSet.noneOf(Material.class);
    private @Nullable Set<Material> only;
    private @Nullable Check check;
    private @Nullable Consumer<List<ItemStack>> deliver;
    private boolean toInventory;
    private @Nullable Sequence sequence;
    private @Nullable Consumer<Summary> onFinish;
    private @Nullable Replace replace;

    // Running state.
    private boolean started;
    private boolean done;
    private @Nullable PackedBlocks plan;
    private @Nullable TaskHandle continuation;
    private final List<ItemStack> drops = new ArrayList<>();
    private int experience;
    private int broken;
    private ItemStack tool;
    private final Map<Material, Boolean> effective = new EnumMap<>(Material.class);
    private Location scratch;

    AreaBreak(Plugin plugin, Player breaker, Block origin, AreaShape shape) {
        this.plugin = plugin;
        this.tasks = Tasks.of(plugin);
        this.breaker = breaker;
        this.breakerId = breaker.getUniqueId();
        this.origin = origin;
        this.shape = shape;
        this.excluded.addAll(DEFAULT_EXCLUDED);
    }

    /** Asked per block before it breaks, after the region check; how WorldGuard and claims get a say. */
    @FunctionalInterface
    public interface Check {
        /**
         * @param breaker who breaks
         * @param block   the block, still intact
         * @return whether it may break
         */
        boolean allows(@NotNull Player breaker, @NotNull Block block);
    }

    /**
     * What a block becomes in {@linkplain #replace(Replace) replace mode}.
     *
     * <pre>{@code
     * AreaBreaks.of(plugin).around(player, block, AreaShape.cube(1))
     *         .replace(AreaBreak.Replace.till())
     *         .start();
     * }</pre>
     *
     * @since 1.387.0
     */
    @FunctionalInterface
    public interface Replace {
        /**
         * Asked per block after every check, on the breaker's thread.
         *
         * @param block the block, as it is
         * @return what it becomes, or {@code null} to leave it
         */
        @Nullable BlockData into(@NotNull Block block);

        /** A hoe: grass, dirt and paths with air above become farmland. */
        static @NotNull Replace till() {
            return Replacements.TILL;
        }

        /** A shovel: grass and the dirts with air above become a dirt path. */
        static @NotNull Replace path() {
            return Replacements.PATH;
        }

        /** An axe: logs, wood, stems and hyphae become their stripped variant, axis kept. */
        static @NotNull Replace strip() {
            return Replacements.STRIP;
        }
    }

    /**
     * What one operation did. In replace mode {@code broken} counts the
     * blocks changed, and {@code items} and {@code experience} are 0.
     *
     * @param planned    blocks planned
     * @param broken     blocks broken
     * @param items      items delivered
     * @param experience experience in the orb
     */
    public record Summary(int planned, int broken, int items, int experience) {
    }

    /** Blocks per tick, 1 or more; {@value #DEFAULT_BUDGET} by default. */
    public @NotNull AreaBreak budget(int blocksPerTick) {
        this.budget = Math.max(1, blocksPerTick);
        return this;
    }

    /**
     * Fires a real {@code BlockBreakEvent} for up to this many blocks, so jobs,
     * skills and protection plugins see them; a cancelled one is not broken.
     * {@code 0}, the default, fires none.
     */
    public @NotNull AreaBreak compatEvents(int max) {
        this.compatEvents = Math.max(0, max);
        return this;
    }

    /** Materials never broken, on top of the defaults (spawners and containers). */
    public @NotNull AreaBreak exclude(@NotNull Collection<Material> materials) {
        excluded.addAll(materials);
        return this;
    }

    /**
     * Only these materials break; the rest of the area stays. Applied after
     * {@link #exclude}. An empty collection lifts the restriction.
     *
     * @since 1.386.0
     */
    public @NotNull AreaBreak only(@NotNull Collection<Material> materials) {
        this.only = materials.isEmpty() ? null : EnumSet.copyOf(materials);
        return this;
    }

    /** A per-block protection check; see {@link Check}. */
    public @NotNull AreaBreak check(@Nullable Check check) {
        this.check = check;
        return this;
    }

    /**
     * Takes the merged drops instead of the default delivery, once, at the end.
     * The list may be changed or kept.
     */
    public @NotNull AreaBreak deliver(@Nullable Consumer<List<ItemStack>> deliver) {
        this.deliver = deliver;
        return this;
    }

    /** Default delivery into the breaker's inventory, overflow dropped once at the origin. */
    public @NotNull AreaBreak toInventory(boolean toInventory) {
        this.toInventory = toInventory;
        return this;
    }

    /** One sequence played at the origin when it ends: the operation's particle burst and sound. */
    public @NotNull AreaBreak sequence(@Nullable Sequence sequence) {
        this.sequence = sequence;
        return this;
    }

    /**
     * Changes blocks instead of breaking them: each planned block that passes
     * the region check and {@link #check} becomes what {@code replace}
     * answers, without physics. Nothing drops, no experience, no compat
     * events and no durability; the tool's preferred blocks do not filter the
     * plan (the replacer decides). {@code null} goes back to breaking.
     *
     * @since 1.387.0
     */
    public @NotNull AreaBreak replace(@Nullable Replace replace) {
        this.replace = replace;
        return this;
    }

    /** Told what it did, once, when it ends. */
    public @NotNull AreaBreak onFinish(@Nullable Consumer<Summary> onFinish) {
        this.onFinish = onFinish;
        return this;
    }

    /**
     * Plans the area and breaks it: within this tick up to the budget, the
     * rest on the following ticks.
     *
     * @return how many blocks were planned
     * @throws IllegalStateException when started twice
     */
    public int start() {
        if (started) throw new IllegalStateException("An area break starts once");
        started = true;
        World world = origin.getWorld();
        tool = breaker.getInventory().getItemInMainHand().clone();
        scratch = new Location(world, 0, 0, 0);

        int[] look = shape.vertical() ? verticalAxis(breaker.getLocation()) : lookAxis(breaker.getLocation());
        PackedBlocks planned = new PackedBlocks(shape.max() < 64 ? shape.max() : 64);
        Predicate<Material> breakable = this::breakable;
        AreaPlanner.plan(shape.kind(), shape.radius(), shape.depth(), shape.max(),
                origin.getX(), origin.getY(), origin.getZ(), look[0], look[1], look[2],
                world::getType, breakable, planned);
        plan = planned;
        if (planned.size() == 0) {
            done = true;
            return 0;
        }
        AreaBreaks.track(plugin.getName(), this);
        if (step()) {
            finish();
        } else {
            continuation = tasks.runAtEntityTimer(breaker, 1, 1, handle -> {
                if (done || step()) {
                    handle.cancel();
                    if (!done) finish();
                }
            });
        }
        return planned.size();
    }

    UUID breakerId() {
        return breakerId;
    }

    /** Stops breaking and delivers what was collected; from a quit or a disable. */
    void abort() {
        if (done) return;
        if (continuation != null) continuation.cancel();
        if (plan != null) plan.clearRemaining();
        Location where = origin.getLocation().add(0.5, 0.5, 0.5);
        if (tasks.isOwnedBy(where)) finish();
        else tasks.runAtLocation(where, this::finish);
    }

    /** @return whether nothing is left */
    private boolean step() {
        PackedBlocks planned = plan;
        if (planned == null) return true;
        World world = origin.getWorld();
        planned.drain(budget, next -> breakOne(world, PackedBlocks.x(next), PackedBlocks.y(next), PackedBlocks.z(next)));
        return !planned.hasNext();
    }

    private void breakOne(World world, int x, int y, int z) {
        scratch.setX(x + 0.5);
        scratch.setY(y + 0.5);
        scratch.setZ(z + 0.5);
        if (!tasks.isOwnedBy(scratch)) return;
        Material type = world.getType(x, y, z);
        if (!breakable(type)) return;
        if (!Regions.resolve(world.getUID(), x + 0.5, y + 0.5, z + 0.5, CommonRegionPolicies.BREAK).value()) return;
        Block block = world.getBlockAt(x, y, z);
        if (check != null && !check.allows(breaker, block)) return;
        if (replace != null) {
            BlockData next = replace.into(block);
            if (next == null) return;
            block.setBlockData(next, false);
            broken++;
            return;
        }

        boolean dropItems = true;
        if (compatEvents > 0) {
            compatEvents--;
            BlockBreakEvent event = new BlockBreakEvent(block, breaker);
            AreaBreaks.enterBreaking();
            try {
                Bukkit.getPluginManager().callEvent(event);
            } finally {
                AreaBreaks.exitBreaking();
            }
            if (event.isCancelled()) return;
            dropItems = event.isDropItems();
        }
        if (dropItems) merge(block.getDrops(tool, breaker));
        experience += experienceOf(type);
        block.setType(Material.AIR, needsPhysics(world, x, y, z));
        broken++;
    }

    private void finish() {
        if (done) return;
        done = true;
        AreaBreaks.untrack(plugin.getName(), this);
        if (replace == null) wearTool();
        Location where = origin.getLocation().add(0.5, 0.5, 0.5);
        World world = origin.getWorld();
        if (experience > 0) {
            int orb = experience;
            world.spawn(where, ExperienceOrb.class, spawned -> spawned.setExperience(orb));
        }
        int items = 0;
        for (ItemStack drop : drops) items += drop.getAmount();
        if (deliver != null) {
            deliver.accept(drops);
        } else {
            for (ItemStack drop : drops) {
                if (toInventory && breaker.isOnline()) {
                    breaker.getInventory().addItem(drop).values().forEach(overflow -> dropSplit(world, where, overflow));
                } else {
                    dropSplit(world, where, drop);
                }
            }
        }
        if (sequence != null) Sequences.of(plugin).play(sequence, SequenceTarget.at(where));
        if (onFinish != null) {
            onFinish.accept(new Summary(plan == null ? 0 : plan.size(), broken, items, experience));
        }
    }

    // ------------------------------------------------------------------ pieces

    private boolean breakable(Material type) {
        if (type.isAir() || type == Material.WATER || type == Material.LAVA || excluded.contains(type)) return false;
        if (only != null && !only.contains(type)) return false;
        if (!type.isBlock() || type.getHardness() < 0) return false;
        if (replace != null) return true;
        return effective.computeIfAbsent(type, material -> tool.getType().isAir()
                || material.createBlockData().isPreferredTool(tool));
    }

    private void merge(Collection<ItemStack> items) {
        outer:
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) continue;
            for (ItemStack kept : drops) {
                if (kept.isSimilar(item)) {
                    kept.setAmount(kept.getAmount() + item.getAmount());
                    continue outer;
                }
            }
            drops.add(item.clone());
        }
    }

    private static void dropSplit(World world, Location where, ItemStack stack) {
        int left = stack.getAmount();
        int max = Math.max(1, stack.getMaxStackSize());
        while (left > 0) {
            ItemStack part = stack.clone();
            part.setAmount(Math.min(max, left));
            left -= part.getAmount();
            world.dropItemNaturally(where, part);
        }
    }

    /** The durability the whole operation costs, in one change; Unbreaking rolled once per block, in one pass. */
    private void wearTool() {
        if (broken == 0 || !breaker.isOnline()) return;
        ItemStack held = breaker.getInventory().getItemInMainHand();
        if (held.getType() != tool.getType() || held.getType().getMaxDurability() <= 0) return;
        ItemMeta meta = held.getItemMeta();
        if (!(meta instanceof Damageable damageable) || meta.isUnbreakable()) return;
        int unbreaking = held.getEnchantmentLevel(Enchantment.UNBREAKING);
        int wear = 0;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < broken; i++) {
            if (unbreaking == 0 || random.nextInt(unbreaking + 1) == 0) wear++;
        }
        if (wear == 0) return;
        int damage = damageable.getDamage() + wear;
        if (damage >= held.getType().getMaxDurability()) {
            breaker.getInventory().setItemInMainHand(null);
            breaker.getWorld().playSound(breaker.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1f);
            return;
        }
        damageable.setDamage(damage);
        held.setItemMeta(meta);
    }

    /** Whether a neighbour must notice: a liquid that should flow in, a block that should fall. */
    private static boolean needsPhysics(World world, int x, int y, int z) {
        return reacts(world.getType(x + 1, y, z)) || reacts(world.getType(x - 1, y, z))
                || reacts(world.getType(x, y + 1, z)) || reacts(world.getType(x, y - 1, z))
                || reacts(world.getType(x, y, z + 1)) || reacts(world.getType(x, y, z - 1));
    }

    private static boolean reacts(Material type) {
        return type == Material.WATER || type == Material.LAVA || type.hasGravity();
    }

    /** Vanilla's ore experience, the middle of each range; none with Silk Touch. */
    private int experienceOf(Material type) {
        if (tool.getEnchantmentLevel(Enchantment.SILK_TOUCH) > 0) return 0;
        String name = type.name();
        if (name.endsWith("COAL_ORE")) return 1;
        if (name.endsWith("DIAMOND_ORE") || name.endsWith("EMERALD_ORE")) return 5;
        if (name.endsWith("LAPIS_ORE") || name.equals("NETHER_QUARTZ_ORE")) return 3;
        if (name.endsWith("REDSTONE_ORE")) return 3;
        if (name.equals("NETHER_GOLD_ORE")) return 1;
        return 0;
    }

    /** The axis the breaker looks along: down/up past 45 degrees of pitch, else the horizontal facing. */
    static int[] lookAxis(Location eye) {
        float pitch = eye.getPitch();
        if (pitch > 45) return new int[]{0, -1, 0};
        if (pitch < -45) return new int[]{0, 1, 0};
        float yaw = ((eye.getYaw() % 360) + 360) % 360;
        if (yaw >= 45 && yaw < 135) return new int[]{-1, 0, 0};
        if (yaw >= 135 && yaw < 225) return new int[]{0, 0, -1};
        if (yaw >= 225 && yaw < 315) return new int[]{1, 0, 0};
        return new int[]{0, 0, 1};
    }

    /** Straight down, or straight up when the breaker looks above the horizon. */
    static int[] verticalAxis(Location eye) {
        return eye.getPitch() < 0 ? new int[]{0, 1, 0} : new int[]{0, -1, 0};
    }

    /** The built-in replacers; the tables are built once, when first used. */
    static final class Replacements {
        static final Set<Material> TILLABLE = Collections.unmodifiableSet(EnumSet.of(
                Material.GRASS_BLOCK, Material.DIRT, Material.DIRT_PATH));
        static final Set<Material> PATHABLE = Collections.unmodifiableSet(EnumSet.of(
                Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT, Material.PODZOL, Material.MYCELIUM,
                Material.ROOTED_DIRT));
        static final Map<Material, Material> STRIPPED = stripped();

        static final Replace TILL = block -> intoUnderAir(block, TILLABLE, Material.FARMLAND);
        static final Replace PATH = block -> intoUnderAir(block, PATHABLE, Material.DIRT_PATH);
        static final Replace STRIP = block -> {
            Material bare = STRIPPED.get(block.getType());
            if (bare == null) return null;
            BlockData data = bare.createBlockData();
            if (block.getBlockData() instanceof Orientable from && data instanceof Orientable to) {
                to.setAxis(from.getAxis());
            }
            return data;
        };

        private Replacements() {
        }

        private static @Nullable BlockData intoUnderAir(Block block, Set<Material> from, Material into) {
            if (!from.contains(block.getType()) || !block.getRelative(BlockFace.UP).getType().isAir()) return null;
            return into.createBlockData();
        }

        /** A log, wood, stem or hyphae and its {@code STRIPPED_} variant. */
        static Map<Material, Material> stripped() {
            Map<Material, Material> map = new EnumMap<>(Material.class);
            for (Material material : Material.values()) {
                String name = material.name();
                if (name.startsWith("LEGACY_") || name.startsWith("STRIPPED_")) continue;
                if (!(name.endsWith("_LOG") || name.endsWith("_WOOD") || name.endsWith("_STEM")
                        || name.endsWith("_HYPHAE"))) continue;
                Material bare = Material.getMaterial("STRIPPED_" + name);
                if (bare != null) map.put(material, bare);
            }
            return Collections.unmodifiableMap(map);
        }
    }

    private static Set<Material> defaultExcluded() {
        Set<Material> out = EnumSet.noneOf(Material.class);
        for (Material material : Material.values()) {
            String name = material.name();
            if (name.endsWith("SHULKER_BOX") || name.equals("SPAWNER") || name.equals("TRIAL_SPAWNER")
                    || name.equals("VAULT") || name.equals("CHEST") || name.equals("TRAPPED_CHEST")
                    || name.equals("BARREL") || name.equals("ENDER_CHEST") || name.equals("BEACON")
                    || name.equals("END_PORTAL_FRAME") || name.equals("REINFORCED_DEEPSLATE")) {
                out.add(material);
            }
        }
        return out;
    }
}
