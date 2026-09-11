package net.exylia.lib.ragdoll.internal;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.ApiStatus;

import java.util.Collections;
import java.util.HashMap;
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
 * already has: every block in the game, and a limb is a box. No resource pack
 * is ever involved, because a server that needs a download before a kill
 * effect works is a server most players never see the effect on.
 *
 * <h2>The palette</h2>
 * Flat on purpose. Every block here was measured from its own texture, and
 * only the ones whose pixels stray from their average by a standard deviation
 * of fourteen or less made it: concrete, concrete powder, terracotta, wool,
 * stripped wood and a handful of smooth, pale and icy blocks. Planks, bricks
 * and patterned stone were the obvious way to reach more colours and the wrong
 * one &mdash; on a piece a few pixels across their grain is louder than the
 * colour, and a shirt's design disappears under somebody else's floorboards.
 *
 * <p>Colours are compared in CIELAB with the CIE94 weighting, the way the eye
 * rather than the arithmetic tells two colours apart, so a peach face is
 * matched to a pale wood and not to the one orange with the closest numbers.
 *
 * <p>The numbers are each texture's measured average, written down. They are a
 * calibration table, not a constant: a texture pack changes what these blocks
 * look like, and a server running one can want them moved.
 */
@ApiStatus.Internal
public final class BlockPalette {

    /** What a cell with no solid pixel at all is drawn in: the grey a missing skin is. */
    private static final int EMPTY = 0x9E9E9E;

    /** Material to the measured average colour of its texture, as {@code 0xRRGGBB}. */
    private static final Map<Material, Integer> COLOURS = new LinkedHashMap<>();

    static {
        // Concrete: the flattest textures in the game, and every saturated hue.
        put(Material.WHITE_CONCRETE, 0xCFD5D6);
        put(Material.LIGHT_GRAY_CONCRETE, 0x7D7D73);
        put(Material.GRAY_CONCRETE, 0x373A3E);
        put(Material.BLACK_CONCRETE, 0x080A0F);
        put(Material.BROWN_CONCRETE, 0x603C20);
        put(Material.RED_CONCRETE, 0x8E2121);
        put(Material.ORANGE_CONCRETE, 0xE06101);
        put(Material.YELLOW_CONCRETE, 0xF1AF15);
        put(Material.LIME_CONCRETE, 0x5EA918);
        put(Material.GREEN_CONCRETE, 0x495B24);
        put(Material.CYAN_CONCRETE, 0x157788);
        put(Material.LIGHT_BLUE_CONCRETE, 0x2489C7);
        put(Material.BLUE_CONCRETE, 0x2D2F8F);
        put(Material.PURPLE_CONCRETE, 0x64209C);
        put(Material.MAGENTA_CONCRETE, 0xA9309F);
        put(Material.PINK_CONCRETE, 0xD6658F);

        // Concrete powder: the same hues a step lighter, with a fine grain.
        put(Material.WHITE_CONCRETE_POWDER, 0xE2E3E4);
        put(Material.LIGHT_GRAY_CONCRETE_POWDER, 0x9B9B94);
        put(Material.GRAY_CONCRETE_POWDER, 0x4D5155);
        put(Material.BLACK_CONCRETE_POWDER, 0x191B20);
        put(Material.BROWN_CONCRETE_POWDER, 0x7E5536);
        put(Material.RED_CONCRETE_POWDER, 0xA83633);
        put(Material.ORANGE_CONCRETE_POWDER, 0xE38420);
        put(Material.YELLOW_CONCRETE_POWDER, 0xE9C737);
        put(Material.LIME_CONCRETE_POWDER, 0x7DBD2A);
        put(Material.GREEN_CONCRETE_POWDER, 0x61772D);
        put(Material.CYAN_CONCRETE_POWDER, 0x25949D);
        put(Material.LIGHT_BLUE_CONCRETE_POWDER, 0x4AB5D5);
        put(Material.BLUE_CONCRETE_POWDER, 0x4649A7);
        put(Material.PURPLE_CONCRETE_POWDER, 0x8438B2);
        put(Material.MAGENTA_CONCRETE_POWDER, 0xC154B9);
        put(Material.PINK_CONCRETE_POWDER, 0xE599B5);

        // Terracotta: muted and warm, and nearly as flat as concrete. Most skin
        // tones, hair and leather are in here.
        put(Material.TERRACOTTA, 0x985E44);
        put(Material.WHITE_TERRACOTTA, 0xD2B2A1);
        put(Material.LIGHT_GRAY_TERRACOTTA, 0x876B62);
        put(Material.GRAY_TERRACOTTA, 0x3A2A24);
        put(Material.BLACK_TERRACOTTA, 0x251710);
        put(Material.BROWN_TERRACOTTA, 0x4D3324);
        put(Material.RED_TERRACOTTA, 0x8F3D2F);
        put(Material.ORANGE_TERRACOTTA, 0xA25426);
        put(Material.YELLOW_TERRACOTTA, 0xBA8523);
        put(Material.LIME_TERRACOTTA, 0x687635);
        put(Material.GREEN_TERRACOTTA, 0x4C532A);
        put(Material.CYAN_TERRACOTTA, 0x575B5B);
        put(Material.LIGHT_BLUE_TERRACOTTA, 0x716D8A);
        put(Material.BLUE_TERRACOTTA, 0x4A3C5B);
        put(Material.PURPLE_TERRACOTTA, 0x764656);
        put(Material.MAGENTA_TERRACOTTA, 0x96586D);
        put(Material.PINK_TERRACOTTA, 0xA24E4F);

        // Wool: the soft middle of every hue. Pink wool's weave is too loud to
        // be here; pink concrete and powder cover it.
        put(Material.WHITE_WOOL, 0xEAECED);
        put(Material.LIGHT_GRAY_WOOL, 0x8E8E87);
        put(Material.GRAY_WOOL, 0x3F4448);
        put(Material.BLACK_WOOL, 0x15151A);
        put(Material.BROWN_WOOL, 0x724829);
        put(Material.RED_WOOL, 0xA12723);
        put(Material.ORANGE_WOOL, 0xF17614);
        put(Material.YELLOW_WOOL, 0xF9C628);
        put(Material.LIME_WOOL, 0x70B91A);
        put(Material.GREEN_WOOL, 0x556E1C);
        put(Material.CYAN_WOOL, 0x158A91);
        put(Material.LIGHT_BLUE_WOOL, 0x3AAFD9);
        put(Material.BLUE_WOOL, 0x35399D);
        put(Material.PURPLE_WOOL, 0x7A2AAD);
        put(Material.MAGENTA_WOOL, 0xBE45B4);

        // Stripped wood: faint grain and the widest run of skin, hair and
        // leather tones, from pale oak's near-white to dark oak's near-black.
        put(Material.STRIPPED_PALE_OAK_WOOD, 0xF6EEED);
        put(Material.STRIPPED_CHERRY_WOOD, 0xD79195);
        put(Material.STRIPPED_BIRCH_WOOD, 0xC5B076);
        put(Material.STRIPPED_OAK_WOOD, 0xB19056);
        put(Material.STRIPPED_JUNGLE_WOOD, 0xAB8555);
        put(Material.STRIPPED_ACACIA_WOOD, 0xAF5D3C);
        put(Material.STRIPPED_SPRUCE_WOOD, 0x745A34);
        put(Material.STRIPPED_MANGROVE_WOOD, 0x783630);
        put(Material.STRIPPED_DARK_OAK_WOOD, 0x493924);
        put(Material.STRIPPED_CRIMSON_HYPHAE, 0x89395A);
        put(Material.STRIPPED_WARPED_HYPHAE, 0x3A9794);

        // Pale, sandy, icy and earthy: the ends of the range the families
        // above do not reach, and faces paler than concrete's grey white.
        put(Material.SNOW_BLOCK, 0xF9FEFE);
        put(Material.SMOOTH_QUARTZ, 0xEDE6E0);
        put(Material.SMOOTH_SANDSTONE, 0xE0D6AA);
        put(Material.SAND, 0xDBCFA3);
        put(Material.RED_SAND, 0xBF6721);
        put(Material.SMOOTH_RED_SANDSTONE, 0xB5621F);
        put(Material.CLAY, 0xA1A6B3);
        put(Material.PACKED_ICE, 0x8EB4FA);
        put(Material.BLUE_ICE, 0x74A8FD);
        put(Material.LAPIS_BLOCK, 0x1F438C);
        put(Material.PACKED_MUD, 0x8E6B50);
        put(Material.MUD, 0x3C393D);
        put(Material.SOUL_SOIL, 0x4C3A2F);
        put(Material.MOSS_BLOCK, 0x596E2D);
        put(Material.NETHER_WART_BLOCK, 0x730302);
        put(Material.WARPED_WART_BLOCK, 0x177879);
    }

    private static void put(Material material, int rgb) {
        COLOURS.put(material, rgb);
    }

    /** Every palette colour in CIELAB, worked out once. */
    private static final Map<Material, double[]> LABS = new LinkedHashMap<>();

    static {
        COLOURS.forEach((material, rgb) -> LABS.put(material, lab(rgb)));
    }

    /** What each colour a skin has asked for came out as, so no colour is matched twice. */
    private static final Map<Integer, Material> MATCHED = new ConcurrentHashMap<>();

    /** One block state per material, built the first time it is drawn. */
    private static final Map<Material, BlockData> BLOCKS = new ConcurrentHashMap<>();

    private BlockPalette() {
    }

    /**
     * The block nearest in colour, as the eye rather than the arithmetic sees it.
     *
     * @param rgb the colour, as {@code 0xRRGGBB}
     * @return the block
     */
    public static BlockData nearest(int rgb) {
        return block(material(rgb));
    }

    /**
     * The block state a palette material is drawn with.
     *
     * @param material the material
     * @return its block state, shared
     */
    public static BlockData block(Material material) {
        return BLOCKS.computeIfAbsent(material, Material::createBlockData);
    }

    /**
     * The block most of a cell's pixels are, one pixel at a time.
     *
     * <p>Not the block nearest the cell's average: averaging a white stripe
     * into a red shirt makes pink, and a body covered in the colours between
     * its colours is a body with no design left on it. Counting keeps the
     * shirt red. A tie goes to whichever of the tied blocks is nearest the
     * average, so a cell split evenly is still decided by what it looks like.
     *
     * @param argb the cell's pixels; anything less than half opaque is skipped
     * @return the block, or the grey of a missing skin when none is solid
     */
    public static Material dominant(int[] argb) {
        Map<Material, Integer> counts = new HashMap<>();
        long red = 0;
        long green = 0;
        long blue = 0;
        int solid = 0;
        for (int pixel : argb) {
            if ((pixel >>> 24) < 128) {
                continue;
            }
            counts.merge(material(pixel), 1, Integer::sum);
            red += (pixel >> 16) & 0xFF;
            green += (pixel >> 8) & 0xFF;
            blue += pixel & 0xFF;
            solid++;
        }
        if (solid == 0) {
            return material(EMPTY);
        }
        double[] average = lab((int) (red / solid) << 16 | (int) (green / solid) << 8 | (int) (blue / solid));
        Material best = null;
        int bestCount = 0;
        double bestDistance = Double.MAX_VALUE;
        for (Map.Entry<Material, Integer> candidate : counts.entrySet()) {
            int count = candidate.getValue();
            if (count < bestCount) {
                continue;
            }
            double distance = difference(average, LABS.get(candidate.getKey()));
            if (count > bestCount || distance < bestDistance) {
                best = candidate.getKey();
                bestCount = count;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** The palette material a colour is drawn in, remembered. */
    static Material material(int rgb) {
        return MATCHED.computeIfAbsent(rgb & 0xFFFFFF, BlockPalette::match);
    }

    /**
     * Whichever of some palette materials looks nearest another.
     *
     * @param material a palette material
     * @param choices  the materials it may become
     * @return itself when it is one of them, else the nearest of them
     */
    static Material nearestAmong(Material material, java.util.Collection<Material> choices) {
        if (choices.contains(material)) {
            return material;
        }
        double[] target = LABS.get(material);
        Material best = material;
        double bestDistance = Double.MAX_VALUE;
        for (Material choice : choices) {
            double distance = difference(target, LABS.get(choice));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = choice;
            }
        }
        return best;
    }

    /** Every palette material and its measured colour, for tests. */
    static Map<Material, Integer> colours() {
        return Collections.unmodifiableMap(COLOURS);
    }

    static Material match(int rgb) {
        double[] target = lab(rgb);
        Material best = Material.LIGHT_GRAY_CONCRETE;
        double bestDistance = Double.MAX_VALUE;
        for (Map.Entry<Material, double[]> candidate : LABS.entrySet()) {
            double distance = difference(target, candidate.getValue());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate.getKey();
            }
        }
        return best;
    }

    /**
     * A colour in CIELAB, from sRGB under the D65 white point.
     *
     * @param rgb the colour, as {@code 0xRRGGBB}
     * @return lightness, a and b
     */
    static double[] lab(int rgb) {
        double r = linear((rgb >> 16) & 0xFF);
        double g = linear((rgb >> 8) & 0xFF);
        double b = linear(rgb & 0xFF);
        double x = f((0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047);
        double y = f(0.2126729 * r + 0.7151522 * g + 0.0721750 * b);
        double z = f((0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883);
        return new double[]{116 * y - 16, 500 * (x - y), 200 * (y - z)};
    }

    private static double linear(int channel) {
        double value = channel / 255.0;
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    private static double f(double t) {
        return t > 216.0 / 24389 ? Math.cbrt(t) : (24389.0 / 27 * t + 16) / 116;
    }

    /**
     * How different two colours look, by CIE94 with the graphic-arts weights,
     * squared.
     *
     * <p>Plain distance in RGB treats a step in blue like a step in green, and
     * the eye does not: it sent skin tones to greys and navy to purple. CIE94
     * also forgives a difference in how saturated a strong colour is more than
     * one in its hue, which is what keeps a red shirt red rather than brown.
     * Squared because it is only ever compared.
     *
     * @param reference the colour asked for
     * @param sample    the candidate
     */
    static double difference(double[] reference, double[] sample) {
        double lightness = reference[0] - sample[0];
        double chroma = Math.hypot(reference[1], reference[2]);
        double chromaDelta = chroma - Math.hypot(sample[1], sample[2]);
        double da = reference[1] - sample[1];
        double db = reference[2] - sample[2];
        double hue = Math.max(0, da * da + db * db - chromaDelta * chromaDelta);
        double sc = 1 + 0.045 * chroma;
        double sh = 1 + 0.015 * chroma;
        return lightness * lightness + (chromaDelta / sc) * (chromaDelta / sc) + hue / (sh * sh);
    }
}
