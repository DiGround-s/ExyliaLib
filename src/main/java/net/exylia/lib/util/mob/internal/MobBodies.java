package net.exylia.lib.util.mob.internal;

import net.exylia.lib.ragdoll.RagdollPart;
import net.exylia.lib.ragdoll.RagdollSkin;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a mob's body is made of when it comes apart: a flat skin and a head for
 * the humanoids, the blocks of its colours for everything else, and a short
 * vanish for an entrance.
 *
 * <p>Everything here is decided from the type, the variant it wears and its
 * back item, never read from the client or a network, so a death costs no
 * lookup. The tables are materials and colours; block states are made the
 * first time one is drawn and kept.
 */
final class MobBodies {

    /** What a type with no palette of its own breaks into: plain, so it never clashes. */
    static final List<Material> FALLBACK = List.of(Material.LIGHT_GRAY_CONCRETE, Material.GRAY_CONCRETE,
            Material.WHITE_CONCRETE);

    private static final Map<EntityType, Map<RagdollPart, Integer>> COLOURS = new EnumMap<>(EntityType.class);
    private static final Map<EntityType, Material> HEADS = new EnumMap<>(EntityType.class);
    private static final Map<String, List<Material>> PALETTES = new HashMap<>();
    private static final Map<EntityType, RagdollSkin> SKINS = new ConcurrentHashMap<>();
    private static final Map<Material, BlockData> BLOCKS = new ConcurrentHashMap<>();

    static {
        // Head, torso, arms, legs. The head colour only shows where no head item is given.
        humanoid(EntityType.ZOMBIE, 0x5C9A48, 0x2F9E9E, 0x5C9A48, 0x3B3F8F, Material.ZOMBIE_HEAD);
        humanoid(EntityType.HUSK, 0xA38F63, 0x7B6A4B, 0xA38F63, 0x5A4A33, null);
        humanoid(EntityType.DROWNED, 0x5FA79A, 0x3E8C84, 0x5FA79A, 0x2D5E6F, null);
        humanoid(EntityType.ZOMBIE_VILLAGER, 0x5C9A48, 0x6B4A2E, 0x5C9A48, 0x6B4A2E, null);
        humanoid(EntityType.SKELETON, 0xC6C6C6, 0xA8A8A8, 0xC6C6C6, 0xBDBDBD, Material.SKELETON_SKULL);
        humanoid(EntityType.STRAY, 0xA9BBBF, 0x5F7F86, 0xA9BBBF, 0x8FA3A8, null);
        humanoid(EntityType.BOGGED, 0x9AA36E, 0x6E7F4A, 0x9AA36E, 0x7E8A5A, null);
        humanoid(EntityType.WITHER_SKELETON, 0x2A2A2A, 0x1C1C1C, 0x2A2A2A, 0x242424, Material.WITHER_SKELETON_SKULL);
        humanoid(EntityType.PIGLIN, 0xE39A91, 0x6E4B2F, 0xE39A91, 0x4F3B28, Material.PIGLIN_HEAD);
        humanoid(EntityType.PIGLIN_BRUTE, 0xD88E86, 0x2E2B28, 0xD88E86, 0x3B2F25, Material.PIGLIN_HEAD);
        humanoid(EntityType.ZOMBIFIED_PIGLIN, 0xD6897F, 0x8B6B5C, 0x7EA05E, 0x6E5646, null);
        humanoid(EntityType.VINDICATOR, 0x9A9A8C, 0x3A3A3A, 0x3A3A3A, 0x2E2E2E, null);
        humanoid(EntityType.PILLAGER, 0x9A9A8C, 0x5C4A38, 0x5C4A38, 0x3A4058, null);
        humanoid(EntityType.EVOKER, 0x9A9A8C, 0x262626, 0x262626, 0x262626, null);
        humanoid(EntityType.ILLUSIONER, 0x9A9A8C, 0x2C4A8A, 0x2C4A8A, 0x2C4A8A, null);
        humanoid(EntityType.WITCH, 0xB4876A, 0x4A2B63, 0x4A2B63, 0x4A2B63, null);

        palette("ZOMBIE", Material.GREEN_TERRACOTTA, Material.CYAN_CONCRETE, Material.BLUE_TERRACOTTA);
        palette("HUSK", Material.YELLOW_TERRACOTTA, Material.BROWN_TERRACOTTA, Material.SANDSTONE);
        palette("DROWNED", Material.CYAN_TERRACOTTA, Material.PRISMARINE, Material.BLUE_TERRACOTTA);
        palette("ZOMBIE_VILLAGER", Material.GREEN_TERRACOTTA, Material.BROWN_TERRACOTTA, Material.BROWN_WOOL);
        palette("SKELETON", Material.BONE_BLOCK, Material.WHITE_TERRACOTTA, Material.LIGHT_GRAY_CONCRETE);
        palette("STRAY", Material.LIGHT_GRAY_CONCRETE, Material.CYAN_TERRACOTTA, Material.BONE_BLOCK);
        palette("BOGGED", Material.MOSS_BLOCK, Material.BONE_BLOCK, Material.GREEN_TERRACOTTA);
        palette("WITHER_SKELETON", Material.BLACK_CONCRETE, Material.BLACKSTONE, Material.GRAY_CONCRETE);
        palette("PIGLIN", Material.PINK_TERRACOTTA, Material.BROWN_TERRACOTTA, Material.GOLD_BLOCK);
        palette("PIGLIN_BRUTE", Material.PINK_TERRACOTTA, Material.BLACK_WOOL, Material.GOLD_BLOCK);
        palette("ZOMBIFIED_PIGLIN", Material.PINK_TERRACOTTA, Material.GREEN_TERRACOTTA, Material.BROWN_TERRACOTTA);
        palette("VINDICATOR", Material.GRAY_WOOL, Material.BLACK_WOOL, Material.LIGHT_GRAY_TERRACOTTA);
        palette("PILLAGER", Material.BROWN_WOOL, Material.GRAY_WOOL, Material.BLUE_TERRACOTTA);
        palette("EVOKER", Material.BLACK_WOOL, Material.GOLD_BLOCK, Material.LIGHT_GRAY_TERRACOTTA);
        palette("ILLUSIONER", Material.BLUE_WOOL, Material.LIGHT_GRAY_TERRACOTTA, Material.CYAN_WOOL);
        palette("WITCH", Material.PURPLE_WOOL, Material.BROWN_TERRACOTTA, Material.GREEN_CONCRETE);
        palette("VILLAGER", Material.BROWN_WOOL, Material.PINK_TERRACOTTA, Material.BROWN_TERRACOTTA);
        palette("WANDERING_TRADER", Material.BLUE_WOOL, Material.PINK_TERRACOTTA, Material.YELLOW_WOOL);

        palette("COW", Material.BROWN_WOOL, Material.BROWN_WOOL, Material.WHITE_WOOL, Material.BLACK_WOOL);
        palette("MOOSHROOM", Material.RED_WOOL, Material.RED_MUSHROOM_BLOCK, Material.WHITE_WOOL);
        palette("MOOSHROOM:BROWN", Material.BROWN_WOOL, Material.BROWN_MUSHROOM_BLOCK, Material.WHITE_WOOL);
        palette("PIG", Material.PINK_TERRACOTTA, Material.PINK_CONCRETE, Material.PINK_WOOL);
        palette("SHEEP", Material.WHITE_WOOL, Material.WHITE_WOOL, Material.PINK_TERRACOTTA);
        palette("CHICKEN", Material.WHITE_WOOL, Material.WHITE_CONCRETE, Material.RED_WOOL, Material.YELLOW_WOOL);
        palette("HORSE", Material.BROWN_TERRACOTTA, Material.ORANGE_TERRACOTTA, Material.BROWN_WOOL);
        palette("HORSE:WHITE", Material.WHITE_WOOL, Material.WHITE_CONCRETE, Material.LIGHT_GRAY_WOOL);
        palette("HORSE:CREAMY", Material.WHITE_TERRACOTTA, Material.SANDSTONE, Material.WHITE_WOOL);
        palette("HORSE:CHESTNUT", Material.ORANGE_TERRACOTTA, Material.BROWN_TERRACOTTA, Material.BROWN_WOOL);
        palette("HORSE:BROWN", Material.BROWN_WOOL, Material.BROWN_TERRACOTTA, Material.BLACK_WOOL);
        palette("HORSE:BLACK", Material.BLACK_WOOL, Material.GRAY_WOOL, Material.BLACK_TERRACOTTA);
        palette("HORSE:GRAY", Material.GRAY_WOOL, Material.LIGHT_GRAY_WOOL, Material.WHITE_WOOL);
        palette("HORSE:DARK_BROWN", Material.BROWN_WOOL, Material.BLACK_TERRACOTTA, Material.BLACK_WOOL);
        palette("DONKEY", Material.GRAY_TERRACOTTA, Material.BROWN_TERRACOTTA, Material.LIGHT_GRAY_WOOL);
        palette("MULE", Material.BROWN_TERRACOTTA, Material.BLACK_TERRACOTTA, Material.GRAY_TERRACOTTA);
        palette("LLAMA", Material.WHITE_TERRACOTTA, Material.WHITE_WOOL, Material.SANDSTONE);
        palette("LLAMA:CREAMY", Material.WHITE_TERRACOTTA, Material.WHITE_WOOL, Material.SANDSTONE);
        palette("LLAMA:WHITE", Material.WHITE_WOOL, Material.SNOW_BLOCK, Material.WHITE_CONCRETE);
        palette("LLAMA:BROWN", Material.BROWN_WOOL, Material.BROWN_TERRACOTTA, Material.WHITE_TERRACOTTA);
        palette("LLAMA:GRAY", Material.GRAY_WOOL, Material.LIGHT_GRAY_WOOL, Material.WHITE_WOOL);
        PALETTES.put("TRADER_LLAMA", PALETTES.get("LLAMA"));
        for (String colour : List.of("CREAMY", "WHITE", "BROWN", "GRAY")) {
            PALETTES.put("TRADER_LLAMA:" + colour, PALETTES.get("LLAMA:" + colour));
        }
        palette("WOLF", Material.WHITE_WOOL, Material.LIGHT_GRAY_WOOL, Material.BROWN_WOOL);
        palette("CAT", Material.ORANGE_TERRACOTTA, Material.YELLOW_TERRACOTTA, Material.WHITE_WOOL);
        palette("OCELOT", Material.YELLOW_TERRACOTTA, Material.ORANGE_TERRACOTTA, Material.BLACK_TERRACOTTA);
        palette("FOX", Material.ORANGE_WOOL, Material.ORANGE_TERRACOTTA, Material.WHITE_WOOL);
        palette("FOX:SNOW", Material.WHITE_WOOL, Material.SNOW_BLOCK, Material.LIGHT_GRAY_WOOL);
        palette("RABBIT", Material.BROWN_WOOL, Material.WHITE_WOOL, Material.BROWN_TERRACOTTA);
        palette("PARROT", Material.RED_WOOL, Material.YELLOW_WOOL, Material.BLUE_WOOL);
        palette("PARROT:BLUE", Material.BLUE_WOOL, Material.LIGHT_BLUE_WOOL, Material.YELLOW_WOOL);
        palette("PARROT:GREEN", Material.LIME_WOOL, Material.GREEN_WOOL, Material.YELLOW_WOOL);
        palette("PARROT:CYAN", Material.CYAN_WOOL, Material.LIGHT_BLUE_WOOL, Material.YELLOW_WOOL);
        palette("PARROT:GRAY", Material.LIGHT_GRAY_WOOL, Material.GRAY_WOOL, Material.WHITE_WOOL);
        palette("AXOLOTL", Material.PINK_CONCRETE, Material.PINK_WOOL, Material.MAGENTA_TERRACOTTA);
        palette("AXOLOTL:WILD", Material.BROWN_TERRACOTTA, Material.BROWN_WOOL, Material.YELLOW_TERRACOTTA);
        palette("AXOLOTL:GOLD", Material.YELLOW_CONCRETE, Material.GOLD_BLOCK, Material.ORANGE_TERRACOTTA);
        palette("AXOLOTL:CYAN", Material.LIGHT_BLUE_CONCRETE, Material.CYAN_CONCRETE, Material.WHITE_WOOL);
        palette("AXOLOTL:BLUE", Material.BLUE_CONCRETE, Material.BLUE_WOOL, Material.LIGHT_BLUE_WOOL);
        palette("BEE", Material.YELLOW_CONCRETE, Material.BLACK_CONCRETE, Material.HONEYCOMB_BLOCK);
        palette("BAT", Material.BLACK_WOOL, Material.BROWN_WOOL, Material.GRAY_WOOL);
        palette("GOAT", Material.WHITE_WOOL, Material.LIGHT_GRAY_WOOL, Material.BONE_BLOCK);
        palette("POLAR_BEAR", Material.WHITE_WOOL, Material.SNOW_BLOCK, Material.WHITE_CONCRETE);
        palette("PANDA", Material.WHITE_WOOL, Material.BLACK_WOOL, Material.WHITE_CONCRETE);
        palette("TURTLE", Material.GREEN_TERRACOTTA, Material.LIME_TERRACOTTA, Material.BROWN_TERRACOTTA);
        palette("FROG", Material.ORANGE_TERRACOTTA, Material.GREEN_TERRACOTTA, Material.WHITE_TERRACOTTA);
        palette("ARMADILLO", Material.PINK_TERRACOTTA, Material.BROWN_TERRACOTTA, Material.PINK_WOOL);
        palette("CAMEL", Material.SANDSTONE, Material.YELLOW_TERRACOTTA, Material.BROWN_TERRACOTTA);
        palette("SNIFFER", Material.RED_TERRACOTTA, Material.MOSS_BLOCK, Material.GREEN_TERRACOTTA);
        palette("DOLPHIN", Material.LIGHT_GRAY_CONCRETE, Material.GRAY_CONCRETE, Material.WHITE_CONCRETE);
        palette("SQUID", Material.BLUE_TERRACOTTA, Material.LIGHT_BLUE_TERRACOTTA, Material.CYAN_TERRACOTTA);
        palette("GLOW_SQUID", Material.CYAN_CONCRETE, Material.LIGHT_BLUE_CONCRETE, Material.PRISMARINE);
        palette("SPIDER", Material.BLACK_CONCRETE, Material.GRAY_CONCRETE, Material.RED_CONCRETE);
        palette("CAVE_SPIDER", Material.CYAN_TERRACOTTA, Material.BLACK_CONCRETE, Material.RED_CONCRETE);
        palette("CREEPER", Material.LIME_CONCRETE, Material.GREEN_CONCRETE, Material.LIME_TERRACOTTA);
        palette("SLIME", Material.SLIME_BLOCK, Material.LIME_STAINED_GLASS, Material.LIME_CONCRETE);
        palette("MAGMA_CUBE", Material.MAGMA_BLOCK, Material.ORANGE_CONCRETE, Material.BLACK_CONCRETE);
        palette("ENDERMAN", Material.BLACK_CONCRETE, Material.OBSIDIAN, Material.PURPLE_CONCRETE);
        palette("ENDERMITE", Material.PURPLE_TERRACOTTA, Material.BLACK_CONCRETE, Material.PURPLE_CONCRETE);
        palette("SILVERFISH", Material.LIGHT_GRAY_CONCRETE, Material.GRAY_CONCRETE, Material.STONE);
        palette("BLAZE", Material.GOLD_BLOCK, Material.ORANGE_CONCRETE, Material.YELLOW_CONCRETE);
        palette("GHAST", Material.WHITE_CONCRETE, Material.LIGHT_GRAY_CONCRETE, Material.WHITE_WOOL);
        palette("PHANTOM", Material.BLUE_TERRACOTTA, Material.GRAY_CONCRETE, Material.LIME_CONCRETE);
        palette("GUARDIAN", Material.PRISMARINE, Material.DARK_PRISMARINE, Material.ORANGE_TERRACOTTA);
        palette("ELDER_GUARDIAN", Material.PRISMARINE_BRICKS, Material.WHITE_TERRACOTTA, Material.LIGHT_GRAY_CONCRETE);
        palette("SHULKER", Material.PURPUR_BLOCK, Material.PURPLE_TERRACOTTA, Material.YELLOW_CONCRETE);
        palette("HOGLIN", Material.PINK_TERRACOTTA, Material.BROWN_TERRACOTTA, Material.WHITE_TERRACOTTA);
        palette("ZOGLIN", Material.PINK_TERRACOTTA, Material.GREEN_TERRACOTTA, Material.WHITE_TERRACOTTA);
        palette("STRIDER", Material.RED_TERRACOTTA, Material.CRIMSON_NYLIUM, Material.GRAY_CONCRETE);
        palette("RAVAGER", Material.GRAY_TERRACOTTA, Material.BLACK_TERRACOTTA, Material.BROWN_TERRACOTTA);
        palette("IRON_GOLEM", Material.IRON_BLOCK, Material.WHITE_TERRACOTTA, Material.MOSS_BLOCK);
        palette("SNOW_GOLEM", Material.SNOW_BLOCK, Material.WHITE_CONCRETE, Material.CARVED_PUMPKIN);
        palette("VEX", Material.LIGHT_GRAY_CONCRETE, Material.WHITE_STAINED_GLASS, Material.LIGHT_BLUE_CONCRETE);
        palette("ALLAY", Material.LIGHT_BLUE_STAINED_GLASS, Material.CYAN_CONCRETE, Material.WHITE_CONCRETE);
        palette("WARDEN", Material.SCULK, Material.CYAN_TERRACOTTA, Material.BLACK_CONCRETE);
        palette("BREEZE", Material.LIGHT_BLUE_CONCRETE, Material.WHITE_STAINED_GLASS, Material.CYAN_CONCRETE);
        palette("WITHER", Material.BLACK_CONCRETE, Material.SOUL_SAND, Material.GRAY_CONCRETE);
        palette("ENDER_DRAGON", Material.BLACK_CONCRETE, Material.OBSIDIAN, Material.PURPLE_CONCRETE);
    }

    private MobBodies() {
    }

    private static void humanoid(EntityType type, int head, int torso, int arms, int legs, @Nullable Material item) {
        Map<RagdollPart, Integer> colours = new EnumMap<>(RagdollPart.class);
        colours.put(RagdollPart.HEAD, head);
        colours.put(RagdollPart.TORSO, torso);
        colours.put(RagdollPart.ARM_RIGHT, arms);
        colours.put(RagdollPart.ARM_LEFT, arms);
        colours.put(RagdollPart.LEG_RIGHT, legs);
        colours.put(RagdollPart.LEG_LEFT, legs);
        COLOURS.put(type, colours);
        if (item != null) HEADS.put(type, item);
    }

    private static void palette(String key, Material... materials) {
        PALETTES.put(key, List.of(materials));
    }

    /** The types whose bodies come apart as a ragdoll. */
    static @NotNull Set<EntityType> humanoids() {
        return COLOURS.keySet();
    }

    static boolean humanoid(@NotNull EntityType type) {
        return COLOURS.containsKey(type);
    }

    /**
     * A humanoid's body in flat colours, one per part.
     *
     * @return the skin, the same instance every time, or {@code null} for a type that is no humanoid
     */
    static @Nullable RagdollSkin skin(@NotNull EntityType type) {
        Map<RagdollPart, Integer> colours = COLOURS.get(type);
        return colours == null ? null : SKINS.computeIfAbsent(type, ignored -> RagdollSkin.flat(colours));
    }

    /**
     * The head a humanoid's body wears, for the types whose vanilla head is an item.
     *
     * @return the material, or {@code null} for a plain head in the skin's colour
     */
    static @Nullable Material head(@NotNull EntityType type) {
        return HEADS.get(type);
    }

    /** Whether a worn item is a head, which a ragdoll wears as its own rather than as a hat. */
    static boolean isHead(@Nullable Material material) {
        if (material == null) return false;
        String name = material.name();
        return name.endsWith("_HEAD") || name.endsWith("_SKULL");
    }

    /**
     * The blocks a mob breaks into, most of it the first: its type's colours, the
     * variant's where that changes them, and the wool of any carpet on its back.
     *
     * @param type    the mob's type
     * @param variant the variant it shows, blank for none
     * @param body    the item it wears on its back, blank for none
     * @return at least one material; {@link #FALLBACK} for a type with no palette
     */
    static @NotNull List<Material> palette(@NotNull EntityType type, @NotNull String variant, @NotNull String body) {
        String key = type.name();
        String kind = variant.toUpperCase(Locale.ROOT);
        List<Material> base = PALETTES.get(key + ":" + kind);
        if (base == null) base = PALETTES.get(key);
        if (base == null) base = FALLBACK;
        if (type == EntityType.SHEEP && !kind.isEmpty()) {
            Material wool = Material.matchMaterial(kind + "_WOOL");
            if (wool != null) base = List.of(wool, wool, Material.PINK_TERRACOTTA);
        }
        Material worn = body.isEmpty() ? null : Material.matchMaterial(body);
        if (worn != null && worn.name().endsWith("_CARPET")) {
            Material wool = Material.matchMaterial(worn.name().replace("_CARPET", "_WOOL"));
            if (wool != null) {
                // The carpet is what a llama is remembered by: it leads.
                List<Material> dressed = new ArrayList<>(base.size() + 1);
                dressed.add(wool);
                dressed.addAll(base);
                return List.copyOf(dressed);
            }
        }
        return base;
    }

    /** A material's block state, made once. */
    static @NotNull BlockData block(@NotNull Material material) {
        return BLOCKS.computeIfAbsent(material, Material::createBlockData);
    }

    /**
     * Hides a mob for an entrance: invisible, no name, no outline.
     *
     * <p>Bukkit has no way to hide a mob from some viewers that works the same on
     * Folia, so the mob itself goes invisible for everyone, which is what an
     * entrance wants anyway.
     *
     * @return what shows it again as it was; does nothing once it is gone
     */
    static @NotNull Runnable vanish(@NotNull LivingEntity entity) {
        boolean invisible = entity.isInvisible();
        boolean named = entity.isCustomNameVisible();
        boolean glowing = entity.isGlowing();
        // ponytail: armour shows through; per-viewer hideEntity if it matters
        entity.setInvisible(true);
        entity.setCustomNameVisible(false);
        entity.setGlowing(false);
        return () -> {
            if (!entity.isValid()) return;
            entity.setInvisible(invisible);
            entity.setCustomNameVisible(named);
            entity.setGlowing(glowing);
        };
    }
}
