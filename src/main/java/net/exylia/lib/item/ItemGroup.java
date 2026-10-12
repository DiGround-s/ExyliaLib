package net.exylia.lib.item;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The shelf an item belongs on in a shop, a market or a price list: blocks,
 * tools, armour, food, redstone, enchanted books, potions, or everything else.
 *
 * <pre>{@code
 * ItemGroup group = ItemGroup.of(stack.getType());   // ItemGroup.TOOLS
 * String id = group.id();                            // "tools", for a config key
 * }</pre>
 *
 * <p>Worked out from the material alone, so it is the same for a plain diamond
 * sword and a renamed one, and safe on any thread. A plugin that wants another
 * answer for a material keeps a map of overrides in front of it; this is the
 * default every screen agrees on.
 *
 * <p>Everything but {@link #BLOCKS} is decided by the material's name, which
 * needs no server; a block is asked of the server last, after every named
 * group has had its turn.
 *
 * @since 1.281.0
 */
public enum ItemGroup {

    BLOCKS,
    TOOLS,
    ARMOUR,
    FOOD,
    REDSTONE,
    ENCHANTED_BOOKS,
    POTIONS,
    MISC;

    private static final Set<String> TOOL_NAMES = Set.of(
            "BOW", "CROSSBOW", "TRIDENT", "MACE", "SHIELD", "FISHING_ROD", "SHEARS", "FLINT_AND_STEEL",
            "BRUSH", "SPYGLASS", "COMPASS", "RECOVERY_COMPASS", "CLOCK", "LEAD", "CARROT_ON_A_STICK",
            "WARPED_FUNGUS_ON_A_STICK", "ARROW", "SPECTRAL_ARROW", "WIND_CHARGE");

    private static final Set<String> FOOD_NAMES = Set.of(
            "APPLE", "GOLDEN_APPLE", "ENCHANTED_GOLDEN_APPLE", "MELON_SLICE", "GLISTERING_MELON_SLICE",
            "SWEET_BERRIES", "GLOW_BERRIES", "CHORUS_FRUIT", "CARROT", "GOLDEN_CARROT", "POTATO",
            "BAKED_POTATO", "POISONOUS_POTATO", "BEETROOT", "DRIED_KELP", "BEEF", "COOKED_BEEF", "PORKCHOP",
            "COOKED_PORKCHOP", "MUTTON", "COOKED_MUTTON", "CHICKEN", "COOKED_CHICKEN", "RABBIT",
            "COOKED_RABBIT", "COD", "COOKED_COD", "SALMON", "COOKED_SALMON", "TROPICAL_FISH", "PUFFERFISH",
            "BREAD", "COOKIE", "CAKE", "PUMPKIN_PIE", "ROTTEN_FLESH", "SPIDER_EYE", "MUSHROOM_STEW",
            "BEETROOT_SOUP", "RABBIT_STEW", "SUSPICIOUS_STEW", "HONEY_BOTTLE", "MILK_BUCKET");

    private static final Set<String> REDSTONE_NAMES = Set.of(
            "REPEATER", "COMPARATOR", "OBSERVER", "HOPPER", "DROPPER", "DISPENSER", "LEVER", "TRIPWIRE_HOOK",
            "DAYLIGHT_DETECTOR", "TARGET", "NOTE_BLOCK", "TNT", "SCULK_SENSOR", "CALIBRATED_SCULK_SENSOR",
            "LIGHTNING_ROD", "CRAFTER", "TRAPPED_CHEST", "SLIME_BLOCK", "HONEY_BLOCK");

    private static final Set<String> POTION_NAMES = Set.of(
            "POTION", "SPLASH_POTION", "LINGERING_POTION", "TIPPED_ARROW", "OMINOUS_BOTTLE");

    /** The key a config file writes it as: {@code enchanted-books}. */
    public @NotNull String id() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** The group a config key names, in any case and with {@code -} or {@code _}. */
    public static @NotNull Optional<ItemGroup> byId(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        String name = id.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (name.equals("ARMOR")) return Optional.of(ARMOUR);
        for (ItemGroup group : values()) {
            if (group.name().equals(name)) return Optional.of(group);
        }
        return Optional.empty();
    }

    /** The group a material is shelved under. */
    public static @NotNull ItemGroup of(@NotNull Material material) {
        ItemGroup named = named(material.name());
        if (named != null) return named;
        try {
            return material.isBlock() ? BLOCKS : MISC;
        } catch (RuntimeException noServer) {
            return MISC;
        }
    }

    /** The group a material's name settles on its own, or {@code null} when only the server can say. */
    static ItemGroup named(String name) {
        if (name.equals("ENCHANTED_BOOK")) return ENCHANTED_BOOKS;
        if (POTION_NAMES.contains(name)) return POTIONS;
        if (FOOD_NAMES.contains(name)) return FOOD;
        if (name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE") || name.endsWith("_LEGGINGS")
                || name.endsWith("_BOOTS") || name.endsWith("_HORSE_ARMOR") || name.equals("WOLF_ARMOR")
                || name.equals("ELYTRA") || name.equals("TURTLE_HELMET")) {
            return ARMOUR;
        }
        if (name.endsWith("_SWORD") || name.endsWith("_PICKAXE") || name.endsWith("_AXE")
                || name.endsWith("_SHOVEL") || name.endsWith("_HOE") || TOOL_NAMES.contains(name)) {
            return TOOLS;
        }
        if (name.contains("REDSTONE") || name.contains("PISTON") || name.endsWith("_BUTTON")
                || name.endsWith("_PRESSURE_PLATE") || name.endsWith("RAIL") || REDSTONE_NAMES.contains(name)) {
            return REDSTONE;
        }
        return null;
    }
}
