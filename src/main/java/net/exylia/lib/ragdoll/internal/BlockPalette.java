package net.exylia.lib.ragdoll.internal;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.ApiStatus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The blocks a skin colour is drawn with.
 *
 * <h2>When a body is drawn in blocks</h2>
 * A body wears its real skin once MineSkin has turned every piece of it into a
 * head texture, which takes a key and a minute or so per new skin. Until then,
 * and on any server without a key, the client can only be shown what it
 * already has: every block in the game, each a flat colour, and a limb is a
 * box. No resource pack is ever involved, because a server that needs a
 * download before a kill effect works is a server most players never see the
 * effect on.
 *
 * <h2>The palette</h2>
 * Wide on purpose. Every extra block is a colour a skin no longer has to be
 * rounded to, and the rounding is what makes a body read as blocks: a peach
 * face drawn in orange terracotta, a navy hoodie drawn in the one blue there
 * was. Concrete and concrete powder for the saturated colours, wool for the
 * soft ones, terracotta and planks for skin, hair, leather and wood tones,
 * stone for greys, and a handful of pale blocks because concrete's white is
 * grey and faces are not. Nothing that glows, nothing see-through and nothing
 * with a pattern loud enough to be read as a pattern.
 *
 * <p>The numbers are each texture's average colour, written down. They are a
 * calibration table, not a constant: a texture pack changes what these blocks
 * look like, and a server running one can want them moved.
 */
@ApiStatus.Internal
public final class BlockPalette {

    /** Material to the average colour of its texture, as {@code 0xRRGGBB}. */
    private static final Map<Material, Integer> COLOURS = new LinkedHashMap<>();

    static {
        put(Material.WHITE_CONCRETE, 0xCFD5D6);
        put(Material.LIGHT_GRAY_CONCRETE, 0x7D7D73);
        put(Material.GRAY_CONCRETE, 0x36393D);
        put(Material.BLACK_CONCRETE, 0x08090D);
        put(Material.BROWN_CONCRETE, 0x603C20);
        put(Material.RED_CONCRETE, 0x8E2121);
        put(Material.ORANGE_CONCRETE, 0xE06101);
        put(Material.YELLOW_CONCRETE, 0xF1AF15);
        put(Material.LIME_CONCRETE, 0x5EA818);
        put(Material.GREEN_CONCRETE, 0x495B24);
        put(Material.CYAN_CONCRETE, 0x157788);
        put(Material.LIGHT_BLUE_CONCRETE, 0x2489C7);
        put(Material.BLUE_CONCRETE, 0x2C2E8F);
        put(Material.PURPLE_CONCRETE, 0x64209C);
        put(Material.MAGENTA_CONCRETE, 0xA9309F);
        put(Material.PINK_CONCRETE, 0xD6658F);

        put(Material.WHITE_CONCRETE_POWDER, 0xE1E3E3);
        put(Material.LIGHT_GRAY_CONCRETE_POWDER, 0x9A9A94);
        put(Material.GRAY_CONCRETE_POWDER, 0x4C5154);
        put(Material.BLACK_CONCRETE_POWDER, 0x191A1F);
        put(Material.BROWN_CONCRETE_POWDER, 0x7D5435);
        put(Material.RED_CONCRETE_POWDER, 0xA83632);
        put(Material.ORANGE_CONCRETE_POWDER, 0xE3831F);
        put(Material.YELLOW_CONCRETE_POWDER, 0xE8C736);
        put(Material.LIME_CONCRETE_POWDER, 0x7DBD29);
        put(Material.GREEN_CONCRETE_POWDER, 0x61772C);
        put(Material.CYAN_CONCRETE_POWDER, 0x24939D);
        put(Material.LIGHT_BLUE_CONCRETE_POWDER, 0x4AB4D5);
        put(Material.BLUE_CONCRETE_POWDER, 0x464888);
        put(Material.PURPLE_CONCRETE_POWDER, 0x8337B1);
        put(Material.MAGENTA_CONCRETE_POWDER, 0xC053B8);
        put(Material.PINK_CONCRETE_POWDER, 0xE499B5);

        put(Material.WHITE_WOOL, 0xE9ECEC);
        put(Material.LIGHT_GRAY_WOOL, 0x8E8E86);
        put(Material.GRAY_WOOL, 0x3E4447);
        put(Material.BLACK_WOOL, 0x141519);
        put(Material.BROWN_WOOL, 0x724728);
        put(Material.RED_WOOL, 0xA02722);
        put(Material.ORANGE_WOOL, 0xF07613);
        put(Material.YELLOW_WOOL, 0xF8C527);
        put(Material.LIME_WOOL, 0x70B919);
        put(Material.GREEN_WOOL, 0x546D1B);
        put(Material.CYAN_WOOL, 0x158991);
        put(Material.LIGHT_BLUE_WOOL, 0x3AAFD9);
        put(Material.BLUE_WOOL, 0x35399D);
        put(Material.PURPLE_WOOL, 0x792AAC);
        put(Material.MAGENTA_WOOL, 0xBD44B3);
        put(Material.PINK_WOOL, 0xED8DAC);

        // Terracotta: muted and warm. Most skin tones in the game are in here.
        put(Material.TERRACOTTA, 0x975D43);
        put(Material.WHITE_TERRACOTTA, 0xD1B1A1);
        put(Material.LIGHT_GRAY_TERRACOTTA, 0x876B62);
        put(Material.GRAY_TERRACOTTA, 0x3A2C23);
        put(Material.BROWN_TERRACOTTA, 0x4D3323);
        put(Material.RED_TERRACOTTA, 0x8E3C2E);
        put(Material.ORANGE_TERRACOTTA, 0xA05325);
        put(Material.YELLOW_TERRACOTTA, 0xBA8523);
        put(Material.PINK_TERRACOTTA, 0xA75157);
        put(Material.BLACK_TERRACOTTA, 0x251610);
        put(Material.BLUE_TERRACOTTA, 0x4A3C5B);
        put(Material.LIGHT_BLUE_TERRACOTTA, 0x716C89);
        put(Material.PURPLE_TERRACOTTA, 0x764656);
        put(Material.MAGENTA_TERRACOTTA, 0x96586D);
        put(Material.CYAN_TERRACOTTA, 0x575B5B);
        put(Material.GREEN_TERRACOTTA, 0x4C532A);
        put(Material.LIME_TERRACOTTA, 0x687435);

        // Wood: hair, leather, beards and the warmer skin tones.
        put(Material.OAK_PLANKS, 0xA2834F);
        put(Material.SPRUCE_PLANKS, 0x735531);
        put(Material.DARK_OAK_PLANKS, 0x422B14);
        put(Material.JUNGLE_PLANKS, 0xA07351);
        put(Material.ACACIA_PLANKS, 0xA85A32);
        put(Material.MANGROVE_PLANKS, 0x773631);
        put(Material.CHERRY_PLANKS, 0xE3B3AD);
        put(Material.CRIMSON_PLANKS, 0x653147);
        put(Material.WARPED_PLANKS, 0x2B6963);
        put(Material.BIRCH_PLANKS, 0xC6B180);
        put(Material.STRIPPED_OAK_WOOD, 0xB08B50);
        put(Material.PACKED_MUD, 0x8E6B50);

        // Greys and the pale end, which concrete does not reach.
        put(Material.QUARTZ_BLOCK, 0xECE6DF);
        put(Material.SMOOTH_SANDSTONE, 0xE0D8A6);
        put(Material.CALCITE, 0xDFE0DC);
        put(Material.SNOW_BLOCK, 0xF9FEFE);
        put(Material.DIORITE, 0xBCBCBC);
        put(Material.SMOOTH_STONE, 0x9E9E9E);
        put(Material.STONE, 0x7E7E7E);
        put(Material.TUFF, 0x6C6D66);
        put(Material.DEEPSLATE, 0x505053);
        put(Material.BLACKSTONE, 0x2A2429);
        put(Material.MUD, 0x3C3A3D);
        put(Material.CLAY, 0xA0A6B3);
        put(Material.MUSHROOM_STEM, 0xCBC4B9);
        put(Material.SMOOTH_RED_SANDSTONE, 0xB5621F);
        put(Material.POLISHED_GRANITE, 0x9A6A59);

        // A few saturated colours nothing above reaches.
        put(Material.NETHER_WART_BLOCK, 0x720B0F);
        put(Material.RED_NETHER_BRICKS, 0x450709);
        put(Material.WARPED_WART_BLOCK, 0x167677);
        put(Material.DARK_PRISMARINE, 0x335B4B);
        put(Material.PRISMARINE_BRICKS, 0x63AC9E);
        put(Material.PURPUR_BLOCK, 0xA97DA9);
        put(Material.MOSS_BLOCK, 0x596E2D);
        put(Material.LAPIS_BLOCK, 0x1F4387);
        put(Material.HONEYCOMB_BLOCK, 0xE5941E);
    }

    private static void put(Material material, int rgb) {
        COLOURS.put(material, rgb);
    }

    /** What each colour a skin has asked for came out as, so no colour is matched twice. */
    private static final Map<Integer, BlockData> MATCHED = new ConcurrentHashMap<>();

    private BlockPalette() {
    }

    /**
     * The block nearest in colour, as the eye rather than the arithmetic sees it.
     *
     * @param rgb the colour, as {@code 0xRRGGBB}
     * @return the block
     */
    public static BlockData nearest(int rgb) {
        return MATCHED.computeIfAbsent(rgb & 0xFFFFFF, colour -> match(colour).createBlockData());
    }

    static Material match(int rgb) {
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        Material best = Material.LIGHT_GRAY_CONCRETE;
        double bestDistance = Double.MAX_VALUE;
        for (Map.Entry<Material, Integer> candidate : COLOURS.entrySet()) {
            double distance = distance(red, green, blue, candidate.getValue());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate.getKey();
            }
        }
        return best;
    }

    /**
     * How different two colours look, by the red-mean weighting.
     *
     * <p>Plain distance in RGB treats a step in blue like a step in green, and
     * the eye does not: it sent skin tones to greys and navy to purple. The
     * red-mean weighting is a few multiplications and close enough to a proper
     * perceptual space for a palette this size.
     */
    private static double distance(int red, int green, int blue, int other) {
        int otherRed = (other >> 16) & 0xFF;
        int otherGreen = (other >> 8) & 0xFF;
        int otherBlue = other & 0xFF;
        double mean = (red + otherRed) / 2.0;
        int dr = red - otherRed;
        int dg = green - otherGreen;
        int db = blue - otherBlue;
        return (2 + mean / 256) * dr * dr + 4 * dg * dg + (2 + (255 - mean) / 256) * db * db;
    }
}
