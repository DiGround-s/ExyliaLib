package net.exylia.lib.util;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Fortune for drops the server's loot tables never see: a plugin's own loot, money, rare rewards.
 *
 * <pre>{@code
 * int level = Fortune.level(tool);
 * int amount = Fortune.apply(Fortune.Mode.VANILLA, 1, level, 0);   // 1 to 4 at Fortune III
 * double chance = Fortune.chance(0.5, level, 0.2);                  // 0.5% raised to 0.8%
 * ItemStack held = Fortune.limit(tool, 2, false);                   // at most Fortune II, no Silk Touch
 * }</pre>
 *
 * <p>Enchantments are looked up by key rather than through the {@code Enchantment} constants,
 * which were renamed across server versions and fail the first time a direct reference loads.
 *
 * @since 1.227.0
 */
public final class Fortune {

    /** How Fortune changes an amount. */
    public enum Mode {

        /** Not at all. */
        OFF,

        /**
         * The ore formula: each level adds a chance of a bonus multiple, x2.2 on average at
         * Fortune III, as a diamond ore drops.
         */
        VANILLA,

        /** {@code amount × (1 + level × perLevel)}, the fraction rolled. */
        LINEAR,

        /** Amounts stay as they are; the chance of each line rises instead, see {@link #chance}. */
        CHANCE
    }

    /** Read on first use of an enchantment, so the arithmetic needs no server. */
    private static final class Keys {
        static final Enchantment FORTUNE = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("fortune"));
        static final Enchantment SILK_TOUCH = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("silk_touch"));
    }

    private Fortune() {
        throw new AssertionError("No instances.");
    }

    /** The Fortune level of a tool; 0 for none or no tool. */
    public static int level(@Nullable ItemStack tool) {
        if (tool == null || tool.getType().isAir() || Keys.FORTUNE == null) return 0;
        return tool.getEnchantmentLevel(Keys.FORTUNE);
    }

    /**
     * An amount after Fortune.
     *
     * @param mode     how Fortune applies; {@link Mode#OFF} and {@link Mode#CHANCE} leave it alone
     * @param amount   the amount without Fortune
     * @param level    the Fortune level
     * @param perLevel what each level adds, for {@link Mode#LINEAR}
     * @return the amount, never below the one given for a positive level
     */
    public static int apply(@NotNull Mode mode, int amount, int level, double perLevel) {
        if (amount <= 0 || level <= 0) return amount;
        return switch (mode) {
            case VANILLA -> amount * multiple(level);
            case LINEAR -> Multipliers.scale(amount, 1.0 + level * Math.max(0.0, perLevel));
            case OFF, CHANCE -> amount;
        };
    }

    /**
     * What an amount that is not counted in items is multiplied by: money, for instance.
     *
     * @return 1 when Fortune leaves it alone
     */
    public static double factor(@NotNull Mode mode, int level, double perLevel) {
        if (level <= 0) return 1.0;
        return switch (mode) {
            case VANILLA -> multiple(level);
            case LINEAR -> 1.0 + level * Math.max(0.0, perLevel);
            case OFF, CHANCE -> 1.0;
        };
    }

    /**
     * A chance raised by Fortune: {@code percent × (1 + level × perLevel)}, at most 100.
     *
     * @param percent  the chance without Fortune, 0 to 100
     * @param level    the Fortune level
     * @param perLevel what each level adds; 0 leaves the chance alone
     */
    public static double chance(double percent, int level, double perLevel) {
        if (level <= 0 || perLevel <= 0 || percent <= 0) return percent;
        return Math.min(100.0, percent * (1.0 + level * perLevel));
    }

    /**
     * A tool as a place allows it: Fortune at most {@code maxLevel}, and Silk Touch only where
     * allowed. Durability still belongs to the real item, so this is for working out drops only.
     *
     * @param maxLevel the highest Fortune counted; below zero for no limit
     * @return the same tool when nothing had to change, a copy otherwise
     */
    public static @Nullable ItemStack limit(@Nullable ItemStack tool, int maxLevel, boolean silkTouch) {
        if (tool == null || tool.getType().isAir()) return tool;
        Enchantment fortune = Keys.FORTUNE, silk = Keys.SILK_TOUCH;
        boolean cutFortune = maxLevel >= 0 && fortune != null && tool.getEnchantmentLevel(fortune) > maxLevel;
        boolean cutSilk = !silkTouch && silk != null && tool.getEnchantmentLevel(silk) > 0;
        if (!cutFortune && !cutSilk) return tool;
        ItemStack limited = tool.clone();
        if (cutFortune) {
            limited.removeEnchantment(fortune);
            if (maxLevel > 0) limited.addUnsafeEnchantment(fortune, maxLevel);
        }
        if (cutSilk) limited.removeEnchantment(silk);
        return limited;
    }

    /** The ore formula's multiple: 1, or 2 to level + 1, the bonus coming up 1 - 2/(level+2) of the time. */
    private static int multiple(int level) {
        int bonus = ThreadLocalRandom.current().nextInt(level + 2) - 1;
        return Math.max(0, bonus) + 1;
    }
}
