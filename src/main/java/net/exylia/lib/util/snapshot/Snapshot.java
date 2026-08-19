package net.exylia.lib.util.snapshot;

import net.exylia.lib.util.snapshot.internal.PlayerState;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A player, as they were.
 *
 * <pre>{@code
 * Snapshot before = Snapshot.of(player);   // captured here, on the main thread
 * // ... the player plays a round and loses everything ...
 * before.restoreTo(player);                // put back exactly as they were
 * }</pre>
 *
 * <h2>One type, two lifetimes</h2>
 * The same value whether it lives for the length of a menu or survives a
 * restart. A plugin that holds it in a field is holding a snapshot; a plugin
 * that hands it to {@link PluginSnapshots#save} is storing the same snapshot.
 * ExyliaCommons had a {@code SnapshotFactory}, a {@code SnapshotRegistry}, a
 * {@code SnapshotCacheManager} and a {@code SnapshotManager} static singleton
 * for that one distinction, and the singleton meant the first plugin to call
 * {@code initialize} owned it and its {@code shutdown} took it away from
 * everyone else.
 *
 * <h2>What it holds</h2>
 * The inventory, armour, off hand, ender chest, health and maximum health,
 * hunger and saturation, experience, potion effects, game mode, flight, and the
 * physical state &mdash; fire ticks, remaining air, velocity, walk speed and
 * invulnerability. The ender chest and the physical state are new: a snapshot
 * read from a row ExyliaCommons wrote does not carry them, which
 * {@link #has(SnapshotPart)} answers and a restore quietly skips.
 *
 * <h2>Threads</h2>
 * {@link #of(Player)} and {@link #restoreTo(Player)} read and write live player
 * state, so both belong on the thread that owns the player &mdash; the main
 * thread on Spigot and Paper, that player's region on Folia. The library's own
 * store already does this; a plugin doing it by hand uses
 * {@code tasks.runAtEntity(player, ...)}. Everything else here &mdash; encoding,
 * decoding, comparing &mdash; is pure and safe anywhere.
 *
 * <h2>Immutability</h2>
 * Nothing here can be changed after capture. The item arrays are copied in and
 * copied out, so a caller that empties the array it was handed has emptied its
 * own copy. Two snapshots are equal when every field is, which is what makes a
 * wire-format test meaningful.
 *
 * @since 1.34.0
 */
public final class Snapshot {

    /** The four armour slots, which is what {@code getArmorContents} returns. */
    public static final int ARMOR_SLOTS = 4;

    /** The main inventory, hotbar included. Commons stored exactly this many. */
    public static final int INVENTORY_SLOTS = 36;

    private final @Nullable GameMode gameMode;
    private final ItemStack @Nullable [] inventory;
    private final ItemStack @Nullable [] armor;
    private final @Nullable ItemStack offHand;
    private final ItemStack @Nullable [] enderChest;
    private final double health;
    private final double maxHealth;
    private final int foodLevel;
    private final float saturation;
    private final int level;
    private final float exp;
    private final List<Effect> potionEffects;
    private final boolean allowFlight;
    private final boolean flying;
    private final float flySpeed;
    private final @Nullable Physical physical;

    /**
     * One active potion effect, in the shape the column stores it.
     *
     * <p>The type is its name rather than a {@code PotionEffectType} because
     * that is what is stored, and because a type this server has never heard of
     * must survive being read: an effect from a mod or a newer version is
     * skipped when it is applied, not when it is decoded.
     *
     * @param type      the effect's name, as {@code PotionEffectType#getName}
     * @param duration  how many ticks were left
     * @param amplifier the level, zero-based
     * @param ambient   whether it came from a beacon
     * @param particles whether particles were shown
     * @param icon      whether the icon was shown
     * @since 1.34.0
     */
    public record Effect(@NotNull String type, int duration, int amplifier,
                         boolean ambient, boolean particles, boolean icon) {
    }

    /**
     * The physical state a player was in.
     *
     * <p>New in this library. ExyliaCommons restored a player who was on fire
     * and drowning into a player who was neither, which is a small gift in a
     * lobby and a real one in a minigame that put them there on purpose.
     *
     * @param fireTicks     how long they were still burning for
     * @param remainingAir  how much air they had left
     * @param velocityX     how fast they were moving, east
     * @param velocityY     how fast they were moving, up
     * @param velocityZ     how fast they were moving, south
     * @param walkSpeed     their walk speed
     * @param invulnerable  whether they could be hurt
     * @since 1.34.0
     */
    public record Physical(int fireTicks, int remainingAir,
                           double velocityX, double velocityY, double velocityZ,
                           float walkSpeed, boolean invulnerable) {

        /** Their velocity, as a fresh vector. */
        public @NotNull Vector velocity() {
            return new Vector(velocityX, velocityY, velocityZ);
        }
    }

    /**
     * Builds a snapshot from parts that are already decoded.
     *
     * <p>Public because the codec and the store are in another package; a
     * plugin capturing a player calls {@link #of(Player)} instead, and a plugin
     * reading one from a column calls {@link SnapshotCodec#decode}.
     *
     * @param gameMode      the game mode, or {@code null} when unknown
     * @param inventory     the main slots, or {@code null} when not captured
     * @param armor         the armour slots, or {@code null} when not captured
     * @param offHand       the off hand, or {@code null} when empty
     * @param enderChest    the ender chest, or {@code null} when not captured
     * @param health        current health
     * @param maxHealth     the base maximum health
     * @param foodLevel     the food level
     * @param saturation    the saturation
     * @param level         the experience level
     * @param exp           progress towards the next level
     * @param potionEffects the active effects; never {@code null}
     * @param allowFlight   whether flight was allowed
     * @param flying        whether they were flying
     * @param flySpeed      their fly speed
     * @param physical      the physical state, or {@code null} when not captured
     */
    public Snapshot(@Nullable GameMode gameMode,
                    ItemStack @Nullable [] inventory,
                    ItemStack @Nullable [] armor,
                    @Nullable ItemStack offHand,
                    ItemStack @Nullable [] enderChest,
                    double health, double maxHealth,
                    int foodLevel, float saturation,
                    int level, float exp,
                    @NotNull List<Effect> potionEffects,
                    boolean allowFlight, boolean flying, float flySpeed,
                    @Nullable Physical physical) {
        this.gameMode = gameMode;
        this.inventory = copy(inventory);
        this.armor = copy(armor);
        this.offHand = offHand == null ? null : offHand.clone();
        this.enderChest = copy(enderChest);
        this.health = health;
        this.maxHealth = maxHealth;
        this.foodLevel = foodLevel;
        this.saturation = saturation;
        this.level = level;
        this.exp = exp;
        this.potionEffects = List.copyOf(potionEffects);
        this.allowFlight = allowFlight;
        this.flying = flying;
        this.flySpeed = flySpeed;
        this.physical = physical;
    }

    /**
     * Captures a player exactly as they are now.
     *
     * <p>Must run on the thread that owns the player. Reading an inventory from
     * anywhere else is a data race on Spigot and throws on Folia.
     *
     * @param player the player
     * @return their state
     */
    public static @NotNull Snapshot of(@NotNull Player player) {
        return PlayerState.capture(player);
    }

    /**
     * Puts every part of this snapshot back on a player.
     *
     * <p>Must run on the thread that owns the player. Parts this snapshot does
     * not carry are left alone.
     *
     * @param player the player to restore
     */
    public void restoreTo(@NotNull Player player) {
        PlayerState.apply(this, player, SnapshotPart.ALL);
    }

    /**
     * Puts only the chosen parts back on a player.
     *
     * <p>Nothing outside {@code parts} is touched, so a caller restoring only
     * {@link SnapshotPart#HEALTH} and {@link SnapshotPart#HUNGER} keeps whatever
     * the player is holding now.
     *
     * @param player the player to restore
     * @param parts  which parts to put back
     */
    public void restoreTo(@NotNull Player player, @NotNull Set<SnapshotPart> parts) {
        PlayerState.apply(this, player, parts);
    }

    /**
     * Whether this snapshot carries a part at all.
     *
     * <p>The question a row written by ExyliaCommons makes worth asking: it has
     * no ender chest and no physical state, and restoring those parts from it
     * would mean deciding what "no ender chest" should do to a player who has
     * one. It does nothing.
     *
     * @param part the part
     * @return whether the snapshot has it
     */
    public boolean has(@NotNull SnapshotPart part) {
        return switch (part) {
            case INVENTORY -> inventory != null;
            case ARMOR -> armor != null;
            case OFF_HAND -> offHand != null;
            case ENDER_CHEST -> enderChest != null;
            case GAME_MODE -> gameMode != null;
            case PHYSICAL -> physical != null;
            // Numbers are always written, so they are always present. Commons
            // read a missing one as zero, and so does the codec.
            case HEALTH, HUNGER, EXPERIENCE, POTION_EFFECTS, FLIGHT -> true;
        };
    }

    // -------------------------------------------------------------- accessors

    /** The game mode they were in, or {@code null} when the row had none. */
    public @Nullable GameMode gameMode() {
        return gameMode;
    }

    /** The main inventory slots, or {@code null} when not captured. */
    public ItemStack @Nullable [] inventory() {
        return copy(inventory);
    }

    /** The armour slots, or {@code null} when not captured. */
    public ItemStack @Nullable [] armor() {
        return copy(armor);
    }

    /** What they held in the off hand, or {@code null} when it was empty. */
    public @Nullable ItemStack offHand() {
        return offHand == null ? null : offHand.clone();
    }

    /** The ender chest, or {@code null} when the row predates it. */
    public ItemStack @Nullable [] enderChest() {
        return copy(enderChest);
    }

    /** The health they had. */
    public double health() {
        return health;
    }

    /** The base maximum health they had, which a plugin may have raised. */
    public double maxHealth() {
        return maxHealth;
    }

    /** Their food level. */
    public int foodLevel() {
        return foodLevel;
    }

    /** Their saturation. */
    public float saturation() {
        return saturation;
    }

    /** Their experience level. */
    public int level() {
        return level;
    }

    /** Their progress towards the next level, from zero to one. */
    public float exp() {
        return exp;
    }

    /** The effects that were active, in the order they were read. */
    public @NotNull List<Effect> potionEffects() {
        return potionEffects;
    }

    /** Whether flight was allowed. */
    public boolean allowFlight() {
        return allowFlight;
    }

    /** Whether they were flying. */
    public boolean flying() {
        return flying;
    }

    /** How fast they flew. */
    public float flySpeed() {
        return flySpeed;
    }

    /** The physical state, or {@code null} when the row predates it. */
    public @Nullable Physical physical() {
        return physical;
    }

    private static ItemStack @Nullable [] copy(ItemStack @Nullable [] items) {
        if (items == null) {
            return null;
        }
        ItemStack[] copied = new ItemStack[items.length];
        for (int slot = 0; slot < items.length; slot++) {
            copied[slot] = items[slot] == null ? null : items[slot].clone();
        }
        return copied;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Snapshot that)) {
            return false;
        }
        return gameMode == that.gameMode
                && Arrays.equals(inventory, that.inventory)
                && Arrays.equals(armor, that.armor)
                && Objects.equals(offHand, that.offHand)
                && Arrays.equals(enderChest, that.enderChest)
                && Double.compare(health, that.health) == 0
                && Double.compare(maxHealth, that.maxHealth) == 0
                && foodLevel == that.foodLevel
                && Float.compare(saturation, that.saturation) == 0
                && level == that.level
                && Float.compare(exp, that.exp) == 0
                && potionEffects.equals(that.potionEffects)
                && allowFlight == that.allowFlight
                && flying == that.flying
                && Float.compare(flySpeed, that.flySpeed) == 0
                && Objects.equals(physical, that.physical);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(gameMode, offHand, health, maxHealth, foodLevel, saturation,
                level, exp, potionEffects, allowFlight, flying, flySpeed, physical);
        result = 31 * result + Arrays.hashCode(inventory);
        result = 31 * result + Arrays.hashCode(armor);
        return 31 * result + Arrays.hashCode(enderChest);
    }

    @Override
    public String toString() {
        return "Snapshot[" + gameMode + ", " + health + '/' + maxHealth + " hp, "
                + potionEffects.size() + " effect(s)"
                + (physical == null ? "" : ", physical") + ']';
    }
}
