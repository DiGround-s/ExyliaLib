package net.exylia.lib.kitroom.internal;

import net.exylia.lib.kitroom.KitRoomCategory;
import net.exylia.lib.text.Phrases;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.List;

/**
 * The categories an empty kit room starts with: PvP gear any server can trim.
 *
 * <p>Taken from ExyliaSandBox's kit room, without its crystal-PvP explosives and
 * without the repeats its slot grid needed. Every item is from 1.21.0, the
 * oldest server the fleet runs.
 */
public final class KitRoomDefaults {

    private KitRoomDefaults() {
    }

    public static List<KitRoomCategory> build() {
        return List.of(
                category("gear", Phrases.tr("Gear"), 0, gear()),
                category("consumables", Phrases.tr("Consumables"), 1, List.of(
                        new ItemStack(Material.GOLDEN_APPLE, 64),
                        new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 8),
                        new ItemStack(Material.ENDER_PEARL, 16),
                        new ItemStack(Material.COBWEB, 64),
                        new ItemStack(Material.EXPERIENCE_BOTTLE, 64),
                        new ItemStack(Material.WATER_BUCKET),
                        new ItemStack(Material.LAVA_BUCKET),
                        new ItemStack(Material.TOTEM_OF_UNDYING),
                        new ItemStack(Material.WIND_CHARGE, 64),
                        new ItemStack(Material.COOKED_BEEF, 64),
                        new ItemStack(Material.OAK_PLANKS, 64),
                        new ItemStack(Material.COBBLESTONE, 64))),
                category("potions", Phrases.tr("Potions"), 2, potions(Material.SPLASH_POTION, 1)),
                category("arrows", Phrases.tr("Arrows"), 3, arrows()));
    }

    private static KitRoomCategory category(String id, String name, int priority, List<ItemStack> items) {
        return new KitRoomCategory(id, name, priority, items.toArray(ItemStack[]::new));
    }

    private static List<ItemStack> gear() {
        List<ItemStack> items = new ArrayList<>();
        for (String tier : List.of("NETHERITE", "DIAMOND", "IRON")) {
            items.add(enchanted(tier + "_HELMET", Enchantment.PROTECTION, 4, Enchantment.UNBREAKING, 3));
            items.add(enchanted(tier + "_CHESTPLATE", Enchantment.PROTECTION, 4, Enchantment.UNBREAKING, 3));
            items.add(enchanted(tier + "_LEGGINGS", Enchantment.PROTECTION, 4, Enchantment.UNBREAKING, 3));
            items.add(enchanted(tier + "_BOOTS", Enchantment.PROTECTION, 4, Enchantment.FEATHER_FALLING, 4));
            items.add(enchanted(tier + "_SWORD", Enchantment.SHARPNESS, 5, Enchantment.UNBREAKING, 3));
            items.add(enchanted(tier + "_AXE", Enchantment.SHARPNESS, 5, Enchantment.EFFICIENCY, 5));
            items.add(enchanted(tier + "_PICKAXE", Enchantment.EFFICIENCY, 5, Enchantment.UNBREAKING, 3));
        }
        items.add(enchanted("BOW", Enchantment.POWER, 5, Enchantment.INFINITY, 1));
        items.add(enchanted("CROSSBOW", Enchantment.QUICK_CHARGE, 3, Enchantment.MULTISHOT, 1));
        items.add(enchanted("TRIDENT", Enchantment.IMPALING, 5, Enchantment.LOYALTY, 3));
        items.add(enchanted("MACE", Enchantment.DENSITY, 5, Enchantment.WIND_BURST, 1));
        items.add(enchanted("SHIELD", Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1));
        items.add(enchanted("FISHING_ROD", Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1));
        return items;
    }

    private static List<ItemStack> arrows() {
        List<ItemStack> items = new ArrayList<>(List.of(new ItemStack(Material.ARROW, 64),
                new ItemStack(Material.SPECTRAL_ARROW, 64)));
        items.addAll(potions(Material.TIPPED_ARROW, 16));
        return items;
    }

    private static List<ItemStack> potions(Material material, int amount) {
        List<ItemStack> items = new ArrayList<>();
        for (PotionType type : List.of(PotionType.STRONG_HEALING, PotionType.STRONG_STRENGTH,
                PotionType.STRONG_SWIFTNESS, PotionType.LONG_FIRE_RESISTANCE, PotionType.STRONG_REGENERATION,
                PotionType.LONG_INVISIBILITY, PotionType.STRONG_HARMING, PotionType.STRONG_POISON,
                PotionType.LONG_SLOWNESS, PotionType.LONG_WEAKNESS, PotionType.STRONG_TURTLE_MASTER,
                PotionType.LONG_SLOW_FALLING)) {
            ItemStack item = new ItemStack(material, amount);
            PotionMeta meta = (PotionMeta) item.getItemMeta();
            meta.setBasePotionType(type);
            item.setItemMeta(meta);
            items.add(item);
        }
        return items;
    }

    private static ItemStack enchanted(String material, Enchantment first, int firstLevel,
                                       Enchantment second, int secondLevel) {
        ItemStack item = new ItemStack(Material.valueOf(material));
        item.addUnsafeEnchantment(first, firstLevel);
        item.addUnsafeEnchantment(second, secondLevel);
        return item;
    }
}
