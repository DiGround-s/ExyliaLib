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
 * <h2>Why blocks and not the skin itself</h2>
 * A client can only be shown geometry it already has. There is no way to hand
 * it an arm-shaped model wearing four pixels of somebody's sleeve without a
 * resource pack, and a server that needs a download before a kill effect works
 * is a server most players never see the effect on. What the client does have
 * is every block in the game, each a flat colour, and a limb is a box.
 *
 * <p>So a body comes apart into boxes of the nearest block to the colour the
 * skin actually is. At the distance and the speed a body flies apart, that is
 * indistinguishable from the real thing &mdash; and the head, the one piece
 * anybody looks at, is the real thing.
 *
 * <h2>The palette</h2>
 * Concrete for anything saturated: it is the flattest, cleanest colour vanilla
 * has, and skins are drawn flat. Terracotta for everything muted, which is
 * where skin tones, leather and denim live &mdash; concrete has no colour a
 * person's face is any shade of. Plus a handful of pale blocks, because
 * concrete's white is grey and faces are not.
 *
 * <p>The numbers are each texture's average colour, measured once and written
 * down. They are a calibration table, not a constant: a texture pack changes
 * what these blocks look like, and a server running one can want them moved.
 */
@ApiStatus.Internal
public final class BlockPalette {

    /** Material to the average colour of its texture, as {@code 0xRRGGBB}. */
    private static final Map<Material, Integer> COLOURS = new LinkedHashMap<>();

    static {
        // Concrete: saturated, flat, and the closest thing vanilla has to the
        // colours a skin is actually drawn in.
        COLOURS.put(Material.WHITE_CONCRETE, 0xCFD5D6);
        COLOURS.put(Material.LIGHT_GRAY_CONCRETE, 0x7D7D73);
        COLOURS.put(Material.GRAY_CONCRETE, 0x36393D);
        COLOURS.put(Material.BLACK_CONCRETE, 0x08090D);
        COLOURS.put(Material.BROWN_CONCRETE, 0x603C20);
        COLOURS.put(Material.RED_CONCRETE, 0x8E2121);
        COLOURS.put(Material.ORANGE_CONCRETE, 0xE06101);
        COLOURS.put(Material.YELLOW_CONCRETE, 0xF1AF15);
        COLOURS.put(Material.LIME_CONCRETE, 0x5EA818);
        COLOURS.put(Material.GREEN_CONCRETE, 0x495B24);
        COLOURS.put(Material.CYAN_CONCRETE, 0x157788);
        COLOURS.put(Material.LIGHT_BLUE_CONCRETE, 0x2489C7);
        COLOURS.put(Material.BLUE_CONCRETE, 0x2C2E8F);
        COLOURS.put(Material.PURPLE_CONCRETE, 0x64209C);
        COLOURS.put(Material.MAGENTA_CONCRETE, 0xA9309F);
        COLOURS.put(Material.PINK_CONCRETE, 0xD6658F);

        // Terracotta: muted and warm. Every skin tone in the game is in here.
        COLOURS.put(Material.TERRACOTTA, 0x975D43);
        COLOURS.put(Material.WHITE_TERRACOTTA, 0xD1B1A1);
        COLOURS.put(Material.LIGHT_GRAY_TERRACOTTA, 0x876B62);
        COLOURS.put(Material.GRAY_TERRACOTTA, 0x3A2C23);
        COLOURS.put(Material.BROWN_TERRACOTTA, 0x4D3323);
        COLOURS.put(Material.RED_TERRACOTTA, 0x8E3C2E);
        COLOURS.put(Material.ORANGE_TERRACOTTA, 0xA05325);
        COLOURS.put(Material.YELLOW_TERRACOTTA, 0xBA8523);
        COLOURS.put(Material.PINK_TERRACOTTA, 0xA75157);
        COLOURS.put(Material.BLACK_TERRACOTTA, 0x251610);
        COLOURS.put(Material.BLUE_TERRACOTTA, 0x4A3C5B);
        COLOURS.put(Material.LIGHT_BLUE_TERRACOTTA, 0x716C89);
        COLOURS.put(Material.PURPLE_TERRACOTTA, 0x764656);
        COLOURS.put(Material.MAGENTA_TERRACOTTA, 0x96586D);
        COLOURS.put(Material.CYAN_TERRACOTTA, 0x575B5B);
        COLOURS.put(Material.GREEN_TERRACOTTA, 0x4C532A);
        COLOURS.put(Material.LIME_TERRACOTTA, 0x687435);

        // The pale end, which concrete does not reach. A white shirt is not
        // grey, and a light skin tone is not white terracotta either.
        COLOURS.put(Material.QUARTZ_BLOCK, 0xECE6DF);
        COLOURS.put(Material.SMOOTH_SANDSTONE, 0xE0D8A6);
        COLOURS.put(Material.BIRCH_PLANKS, 0xC6B180);
        COLOURS.put(Material.STRIPPED_OAK_WOOD, 0xB08B50);
        COLOURS.put(Material.MUD, 0x3C3A3D);
    }

    /** Colours already matched. A body asks for at most a few dozen. */
    private static final Map<Integer, BlockData> MATCHED = new ConcurrentHashMap<>();

    private BlockPalette() {
    }

    /**
     * The block whose texture is closest to a colour.
     *
     * @param rgb the colour, as {@code 0xRRGGBB}
     * @return the block state to draw it with
     */
    public static BlockData nearest(int rgb) {
        return MATCHED.computeIfAbsent(rgb & 0xFFFFFF, colour -> match(colour).createBlockData());
    }

    private static Material match(int rgb) {
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        Material best = Material.LIGHT_GRAY_CONCRETE;
        long bestDistance = Long.MAX_VALUE;
        for (Map.Entry<Material, Integer> candidate : COLOURS.entrySet()) {
            long distance = distance(red, green, blue, candidate.getValue());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate.getKey();
            }
        }
        return best;
    }

    /**
     * How far apart two colours look, rather than how far apart they are.
     *
     * <p>The redmean weighting. Plain Euclidean distance in RGB puts a dark
     * blue nearer to a dark green than to a slightly different blue, which on a
     * skin is the difference between denim and moss. This costs two extra
     * multiplications and gets it right.
     */
    private static long distance(int red, int green, int blue, int other) {
        int otherRed = (other >> 16) & 0xFF;
        int otherGreen = (other >> 8) & 0xFF;
        int otherBlue = other & 0xFF;
        long mean = (red + otherRed) / 2L;
        long deltaRed = red - otherRed;
        long deltaGreen = green - otherGreen;
        long deltaBlue = blue - otherBlue;
        return (((512 + mean) * deltaRed * deltaRed) >> 8)
                + 4 * deltaGreen * deltaGreen
                + (((767 - mean) * deltaBlue * deltaBlue) >> 8);
    }
}
