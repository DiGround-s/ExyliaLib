package net.exylia.lib.ragdoll;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

/**
 * The colours of one player's skin, cut into the parts a body comes apart into.
 *
 * <p>Only the front faces are kept, and that is a deliberate ceiling rather
 * than an oversight: a limb tumbling through the air at a tenth of a second a
 * turn is read by its colour and its shape, never by which of its six faces is
 * pointing at you. Keeping one face makes a body six small arrays instead of
 * thirty-six, and nobody has ever seen the difference.
 *
 * <p>Immutable and cached per skin, so a hundred deaths wearing the same skin
 * decode one picture.
 *
 * @since 1.120.0
 */
public final class RagdollSkin {

    /** What a part is drawn in when its own pixels are missing or clear. */
    private static final int FALLBACK = 0xFF9E9E9E;

    private final Map<RagdollPart, int[]> faces;

    @org.jetbrains.annotations.ApiStatus.Internal
    public RagdollSkin(@NotNull Map<RagdollPart, int[]> faces) {
        this.faces = new EnumMap<>(faces);
    }

    /**
     * The colour of one cell of a part, averaged over the pixels under it.
     *
     * <p>A part is drawn as a grid of {@code detail} by {@code detail} cells,
     * so {@code detail:1} is one colour for the whole limb and {@code detail:4}
     * keeps a collar, a belt and a stripe down a sleeve.
     *
     * @param part   which piece
     * @param cellX  the column, from the wearer's left
     * @param cellY  the row, from the top
     * @param detail how many cells the part is cut into on each axis
     * @return the colour as {@code 0xRRGGBB}
     */
    public int colour(@NotNull RagdollPart part, int cellX, int cellY, int detail) {
        return colour(part, cellX, cellY, detail, detail);
    }

    /**
     * The colour of one cell of a part cut into a grid of its own shape.
     *
     * @param part    which piece
     * @param cellX   the column, from the wearer's left
     * @param cellY   the row, from the top
     * @param columns how many columns the part is cut into
     * @param rows    how many rows the part is cut into
     * @return the colour as {@code 0xRRGGBB}
     * @since 1.135.0
     */
    public int colour(@NotNull RagdollPart part, int cellX, int cellY, int columns, int rows) {
        int[] pixels = faces.get(part);
        if (pixels == null || pixels.length == 0) {
            return FALLBACK & 0xFFFFFF;
        }
        int width = part.skinWidth();
        int height = part.skinHeight();
        int fromX = cellX * width / columns;
        int toX = Math.max(fromX + 1, (cellX + 1) * width / columns);
        int fromY = cellY * height / rows;
        int toY = Math.max(fromY + 1, (cellY + 1) * height / rows);

        long red = 0;
        long green = 0;
        long blue = 0;
        int counted = 0;
        for (int y = fromY; y < toY && y < height; y++) {
            for (int x = fromX; x < toX && x < width; x++) {
                int argb = pixels[y * width + x];
                // Anything but solid is the hat layer's empty space or a skin
                // drawn with holes in it. Averaging it in drags every colour
                // towards black, which is why a naive sampler makes every
                // player look like they died wearing a wetsuit.
                if ((argb >>> 24) < 128) {
                    continue;
                }
                red += (argb >> 16) & 0xFF;
                green += (argb >> 8) & 0xFF;
                blue += argb & 0xFF;
                counted++;
            }
        }
        if (counted == 0) {
            return FALLBACK & 0xFFFFFF;
        }
        return (int) (red / counted) << 16 | (int) (green / counted) << 8 | (int) (blue / counted);
    }

    /** Whether anything at all was read for this part. */
    public boolean has(@Nullable RagdollPart part) {
        return part != null && faces.containsKey(part);
    }
}
