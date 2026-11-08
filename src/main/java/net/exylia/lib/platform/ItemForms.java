package net.exylia.lib.platform;

import org.bukkit.Material;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

/**
 * The item that stands for a material, so a block that has no item of its own (a crop, a wall torch, water) can
 * still be shown as an icon: {@code CARROTS} is drawn as a carrot, {@code COCOA} as cocoa beans,
 * {@code WALL_TORCH} as a torch.
 *
 * <pre>{@code
 * Material icon = ItemForms.of(Material.POTATOES);          // POTATO
 * Material shown = ItemForms.orElse(material, Material.BOOK); // the item form, or a book when there is none
 * }</pre>
 *
 * @since 1.515.0
 */
public final class ItemForms {

    /** Blocks whose item is the crop they give rather than what places them, and blocks no item places. */
    private static final Map<Material, Material> KNOWN = new EnumMap<>(Material.class);

    static {
        KNOWN.put(Material.CARROTS, Material.CARROT);
        KNOWN.put(Material.POTATOES, Material.POTATO);
        KNOWN.put(Material.BEETROOTS, Material.BEETROOT);
        KNOWN.put(Material.COCOA, Material.COCOA_BEANS);
        KNOWN.put(Material.TORCHFLOWER_CROP, Material.TORCHFLOWER_SEEDS);
        KNOWN.put(Material.PITCHER_CROP, Material.PITCHER_POD);
        KNOWN.put(Material.MELON_STEM, Material.MELON_SEEDS);
        KNOWN.put(Material.ATTACHED_MELON_STEM, Material.MELON_SEEDS);
        KNOWN.put(Material.PUMPKIN_STEM, Material.PUMPKIN_SEEDS);
        KNOWN.put(Material.ATTACHED_PUMPKIN_STEM, Material.PUMPKIN_SEEDS);
        KNOWN.put(Material.SWEET_BERRY_BUSH, Material.SWEET_BERRIES);
        KNOWN.put(Material.CAVE_VINES, Material.GLOW_BERRIES);
        KNOWN.put(Material.CAVE_VINES_PLANT, Material.GLOW_BERRIES);
        KNOWN.put(Material.KELP_PLANT, Material.KELP);
        KNOWN.put(Material.BAMBOO_SAPLING, Material.BAMBOO);
        KNOWN.put(Material.TALL_SEAGRASS, Material.SEAGRASS);
        KNOWN.put(Material.REDSTONE_WIRE, Material.REDSTONE);
        KNOWN.put(Material.TRIPWIRE, Material.STRING);
        KNOWN.put(Material.WATER, Material.WATER_BUCKET);
        KNOWN.put(Material.BUBBLE_COLUMN, Material.WATER_BUCKET);
        KNOWN.put(Material.LAVA, Material.LAVA_BUCKET);
        KNOWN.put(Material.POWDER_SNOW, Material.POWDER_SNOW_BUCKET);
        KNOWN.put(Material.FIRE, Material.FLINT_AND_STEEL);
        KNOWN.put(Material.SOUL_FIRE, Material.FLINT_AND_STEEL);
        KNOWN.put(Material.NETHER_PORTAL, Material.OBSIDIAN);
        KNOWN.put(Material.END_PORTAL, Material.ENDER_EYE);
        KNOWN.put(Material.END_GATEWAY, Material.ENDER_EYE);
        KNOWN.put(Material.FROSTED_ICE, Material.ICE);
        KNOWN.put(Material.PISTON_HEAD, Material.PISTON);
        KNOWN.put(Material.MOVING_PISTON, Material.PISTON);
    }

    private ItemForms() {
    }

    /**
     * The item shown for a material: the material itself when it is an item, else the crop or item that places
     * it (the item a player would place it with, for wall and potted variants), else {@code null}.
     *
     * @param material any material, or {@code null}
     * @return an item material, never air; {@code null} when there is none
     */
    @Contract("null -> null")
    public static @Nullable Material of(@Nullable Material material) {
        if (material == null || material.isAir()) return null;
        if (material.isItem()) return material;
        Material known = KNOWN.get(material);
        if (known != null) return known;
        if (material.isBlock()) {
            try {
                Material placed = material.createBlockData().getPlacementMaterial();
                if (placed.isItem() && !placed.isAir()) return placed;
            } catch (RuntimeException | LinkageError noServer) {
                // Outside a server (tests) block data cannot be made: nothing to offer.
            }
        }
        return null;
    }

    /**
     * As {@link #of}, with what to show when the material has no item at all.
     *
     * @param material any material, or {@code null}
     * @param fallback an item material
     * @return the item form, or {@code fallback}
     */
    public static Material orElse(@Nullable Material material, Material fallback) {
        Material form = of(material);
        return form != null ? form : fallback;
    }
}
