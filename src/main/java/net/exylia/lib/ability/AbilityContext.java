package net.exylia.lib.ability;

import net.exylia.lib.ability.internal.ChainGuard;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Everything one firing of a trigger knows: who holds the ability, at what
 * level, the event, who else is involved, and typed values a plugin adds.
 *
 * <pre>{@code
 * abilities.fire(player, "MINE_DROP", ctx -> ctx.with(MY_DROPS, drops).block(block));
 * }</pre>
 *
 * <p>One context serves every ability the trigger reaches, in turn; the level,
 * ability and scale change between them, everything else is shared, so one
 * ability's changes to the drops are the next one's input.
 *
 * <h2>Threads and lifetime</h2>
 * Lives for one firing on the holder's thread (their region on Folia) and must
 * not be kept: an effect that runs later captures the values it needs.
 *
 * @since 1.385.0
 */
public final class AbilityContext {

    private final PluginAbilities abilities;
    private final Player holder;
    private final String trigger;
    private final Map<ContextKey<?>, Object> values = new HashMap<>(4);
    private @Nullable Event event;
    private @Nullable LivingEntity victim;
    private @Nullable LivingEntity attacker;
    private @Nullable Location location;
    private @Nullable Block block;

    private @Nullable Ability ability;
    private int level = 1;
    private double scale = 1;

    private boolean ownsDrops;
    private boolean finished;
    private final List<Consumer<List<ItemStack>>> dropTransforms = new ArrayList<>(2);

    AbilityContext(@NotNull PluginAbilities abilities, @NotNull Player holder, @NotNull String trigger) {
        this.abilities = abilities;
        this.holder = holder;
        this.trigger = trigger;
    }

    // ------------------------------------------------------------ reading

    /** The plugin's abilities. */
    public @NotNull PluginAbilities abilities() {
        return abilities;
    }

    /** Who holds the ability. */
    public @NotNull Player holder() {
        return holder;
    }

    /** The trigger, upper case. */
    public @NotNull String trigger() {
        return trigger;
    }

    /** The ability being attempted; {@code null} only inside a {@link PluginAbilities#fire} setup. */
    public @Nullable Ability ability() {
        return ability;
    }

    /** The holder's level of the ability being attempted. */
    public int level() {
        return level;
    }

    /** The gate's factor for this attempt; effects multiply their amounts by it. */
    public double scale() {
        return scale;
    }

    /** The Bukkit event behind the trigger, if any. */
    public @Nullable Event event() {
        return event;
    }

    /** Who the holder hit or killed, if anyone. */
    public @Nullable LivingEntity victim() {
        return victim;
    }

    /** Who hit or killed the holder, if anyone. */
    public @Nullable LivingEntity attacker() {
        return attacker;
    }

    /** The other party: the victim, failing that the attacker. */
    public @Nullable LivingEntity other() {
        return victim != null ? victim : attacker;
    }

    /** Where it happened: as set, else the block's centre, else the holder's position. */
    public @NotNull Location location() {
        if (location != null) return location;
        if (block != null) return block.getLocation().add(0.5, 0.5, 0.5);
        return holder.getLocation();
    }

    /** The block involved, if any. */
    public @Nullable Block block() {
        return block;
    }

    /** A plugin's typed value, or {@code null}. */
    @SuppressWarnings("unchecked")
    public <T> @Nullable T get(@NotNull ContextKey<T> key) {
        return (T) values.get(key);
    }

    /** A plugin's typed value, or a default. */
    public <T> @NotNull T getOr(@NotNull ContextKey<T> key, @NotNull T fallback) {
        T value = get(key);
        return value != null ? value : fallback;
    }

    // ------------------------------------------------------------ writing (fire setup)

    /** Attaches a typed value. */
    public <T> @NotNull AbilityContext with(@NotNull ContextKey<T> key, @Nullable T value) {
        if (value == null) values.remove(key);
        else values.put(key, value);
        return this;
    }

    /** Sets the event. */
    public @NotNull AbilityContext event(@Nullable Event event) {
        this.event = event;
        return this;
    }

    /** Sets the victim. */
    public @NotNull AbilityContext victim(@Nullable LivingEntity victim) {
        this.victim = victim;
        return this;
    }

    /** Sets the attacker. */
    public @NotNull AbilityContext attacker(@Nullable LivingEntity attacker) {
        this.attacker = attacker;
        return this;
    }

    /** Sets where it happened. */
    public @NotNull AbilityContext location(@Nullable Location location) {
        this.location = location;
        return this;
    }

    /** Sets the block. */
    public @NotNull AbilityContext block(@Nullable Block block) {
        this.block = block;
        return this;
    }

    // ------------------------------------------------------------ doing

    /**
     * Damages an entity on the holder's behalf without firing ATTACK or
     * HIT_TAKEN again, unless the ability says {@code chain: true}.
     *
     * <p>Runs on the entity's own thread: inline when this thread owns it,
     * scheduled otherwise.
     *
     * @param target who to hurt
     * @param amount how much, after the {@link #scale()} the caller applied
     */
    public void damage(@NotNull LivingEntity target, double amount) {
        if (amount <= 0) return;
        Ability current = ability;
        Player source = holder;
        at(target, () -> ChainGuard.run(current, () -> target.damage(amount, source)));
    }

    /**
     * Runs something on an entity's thread: inline when this thread owns it,
     * through the entity's scheduler otherwise.
     *
     * @param entity whose thread
     * @param task   what to run
     */
    public void at(@NotNull Entity entity, @NotNull Runnable task) {
        if (abilities.tasks().isOwnedBy(entity)) task.run();
        else abilities.tasks().runAtEntity(entity, task);
    }

    /**
     * The drops this firing carries, as a list effects may change.
     *
     * <p>{@link ContextKey#DROPS} when set. On a BLOCK_BREAK with no drops set,
     * the first call takes the block's drops over: the event stops dropping
     * them and the library spawns the list once, after every ability ran, at
     * the block &mdash; or into the holder's inventory when
     * {@link ContextKey#DROPS_TO_INVENTORY} is set.
     *
     * @return the list, or {@code null} when this firing has no drops
     */
    public @Nullable List<ItemStack> drops() {
        List<ItemStack> drops = get(ContextKey.DROPS);
        if (drops != null) return drops;
        if (event instanceof BlockBreakEvent breaking && breaking.isDropItems() && block != null) {
            ItemStack tool = holder.getInventory().getItemInMainHand();
            drops = new ArrayList<>(block.getDrops(tool, holder));
            breaking.setDropItems(false);
            ownsDrops = true;
            with(ContextKey.DROPS, drops);
            return drops;
        }
        return null;
    }

    /**
     * Changes every drop this firing delivers: the current list at the end of
     * the firing, and anything delivered later through {@link #deliver}
     * (an area break that continues over several ticks).
     *
     * <p>How smelting, multiplying and similar apply once, in one merge step,
     * whatever order the effects ran in.
     *
     * @param transform changes the list in place
     */
    public void transformDrops(@NotNull Consumer<List<ItemStack>> transform) {
        List<ItemStack> drops = drops();
        if (drops != null && !ownsDrops) {
            // A list the library does not deliver (a death's drops) is changed now.
            transform.accept(drops);
        }
        dropTransforms.add(transform);
    }

    /**
     * Delivers items through this firing's drops: into the current list while
     * the firing runs, or transformed and spawned when it already finished.
     *
     * @param items what to deliver; the list may be changed
     */
    public void deliver(@NotNull List<ItemStack> items) {
        if (items.isEmpty()) return;
        if (!finished) {
            List<ItemStack> drops = drops();
            if (drops != null) {
                drops.addAll(items);
                return;
            }
        }
        dropTransforms.forEach(transform -> transform.accept(items));
        spawn(items);
    }

    // ------------------------------------------------------------ engine

    void bind(@NotNull Held held, double scale) {
        this.ability = held.ability();
        this.level = held.level();
        this.scale = scale;
    }

    void finish() {
        finished = true;
        if (!ownsDrops) return;
        List<ItemStack> drops = get(ContextKey.DROPS);
        if (drops == null || drops.isEmpty()) return;
        dropTransforms.forEach(transform -> transform.accept(drops));
        spawn(drops);
    }

    private void spawn(List<ItemStack> items) {
        Location where = block != null ? block.getLocation().add(0.5, 0.5, 0.5) : location();
        boolean toInventory = Boolean.TRUE.equals(get(ContextKey.DROPS_TO_INVENTORY)) && holder.isOnline();
        for (ItemStack item : items) {
            if (item == null || item.getType() == Material.AIR || item.getAmount() <= 0) continue;
            if (toInventory) {
                holder.getInventory().addItem(item).values()
                        .forEach(overflow -> where.getWorld().dropItemNaturally(where, overflow));
            } else if (where.getWorld() != null) {
                where.getWorld().dropItemNaturally(where, item);
            }
        }
    }
}
