package net.exylia.lib.ragdoll;

import net.exylia.lib.ragdoll.internal.SkinCubes;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

/**
 * The colours of one player's skin, cut into the parts a body comes apart into,
 * and &mdash; once they exist &mdash; the textures that draw it for real.
 *
 * <h2>Two ways to be drawn</h2>
 * Every skin has its colours: every face of each part, which is what a body
 * is drawn from in blocks. A skin can also carry cubes: every piece of the
 * body repainted as a head texture Mojang hosts, so each piece wears its actual
 * pixels on all six faces. Cubes arrive in the background, once per skin, and
 * only on a server with a MineSkin key; until all eighteen of them are there
 * the body is drawn in blocks.
 *
 * <p>Every face of every part is kept, unfolded the way the skin lays it out
 * and with the second layer painted over the first. The cells of a body in
 * blocks read the front, which is where a design is and what a limb tumbling
 * at a tenth of a second a turn is read by; a body at detail five reads all
 * six, so a logo on a back comes apart with the body too.
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
    private final Map<RagdollPart, String[]> cubes;
    private final boolean skinned;

    /**
     * A skin from its parts' unfolded boxes.
     *
     * @param faces per part, its whole box net row by row, as {@link #net}
     *              describes it
     */
    @ApiStatus.Internal
    public RagdollSkin(@NotNull Map<RagdollPart, int[]> faces) {
        this(faces, Map.of());
    }

    private RagdollSkin(Map<RagdollPart, int[]> faces, Map<RagdollPart, String[]> cubes) {
        this.faces = new EnumMap<>(faces);
        Map<RagdollPart, String[]> copied = new EnumMap<>(RagdollPart.class);
        boolean complete = true;
        for (RagdollPart part : RagdollPart.values()) {
            if (part == RagdollPart.HEAD) {
                continue;
            }
            String[] textures = cubes.get(part);
            int cells = part.columns(SkinCubes.DETAIL) * part.rows(SkinCubes.DETAIL);
            if (textures == null || textures.length != cells) {
                complete = false;
                continue;
            }
            copied.put(part, textures.clone());
            for (String texture : textures) {
                complete &= texture != null;
            }
        }
        this.cubes = copied;
        this.skinned = complete;
    }

    /**
     * The same colours, carrying the textures of every piece.
     *
     * @param cubes per part, one texture property per cell, row by row
     * @return a new skin
     * @since 1.141.0
     */
    @ApiStatus.Internal
    public @NotNull RagdollSkin withCubes(@NotNull Map<RagdollPart, String[]> cubes) {
        return new RagdollSkin(faces, cubes);
    }

    /**
     * The head texture one piece of the body is drawn with.
     *
     * <p>Pieces are counted at a detail of two, where every one of them is a
     * four-pixel cube: the chest two across and three down, each limb one
     * across and three down.
     *
     * @param part  which part, never the head
     * @param cellX the column, from the wearer's right
     * @param cellY the row, from the top
     * @return the texture property, or {@code null} while it has none
     * @since 1.141.0
     */
    public @Nullable String cube(@NotNull RagdollPart part, int cellX, int cellY) {
        String[] textures = cubes.get(part);
        int columns = part.columns(SkinCubes.DETAIL);
        if (textures == null || cellX < 0 || cellY < 0 || cellX >= columns
                || cellY >= part.rows(SkinCubes.DETAIL)) {
            return null;
        }
        return textures[cellY * columns + cellX];
    }

    /**
     * Whether every piece of the body has its texture, so the body can be drawn
     * wearing the real skin rather than blocks.
     *
     * @return whether all eighteen cubes are there
     * @since 1.141.0
     */
    public boolean skinned() {
        return skinned;
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
        long red = 0;
        long green = 0;
        long blue = 0;
        int counted = 0;
        for (int argb : cell(part, cellX, cellY, columns, rows)) {
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
        if (counted == 0) {
            return FALLBACK & 0xFFFFFF;
        }
        return (int) (red / counted) << 16 | (int) (green / counted) << 8 | (int) (blue / counted);
    }

    /**
     * The pixels under one cell of a part's front, row by row.
     *
     * @param part    which piece
     * @param cellX   the column, from the wearer's right
     * @param cellY   the row, from the top
     * @param columns how many columns the part is cut into
     * @param rows    how many rows the part is cut into
     * @return the pixels as {@code 0xAARRGGBB}; empty when nothing was read for the part
     * @since 1.141.0
     */
    @ApiStatus.Internal
    public int[] cell(@NotNull RagdollPart part, int cellX, int cellY, int columns, int rows) {
        int[] net = faces.get(part);
        if (net == null || net.length == 0) {
            return new int[0];
        }
        int width = part.skinWidth();
        int height = part.skinHeight();
        int depth = depth(part);
        int netWidth = 2 * (depth + width);
        int fromX = Math.min(width - 1, cellX * width / columns);
        int toX = Math.min(width, Math.max(fromX + 1, (cellX + 1) * width / columns));
        int fromY = Math.min(height - 1, cellY * height / rows);
        int toY = Math.min(height, Math.max(fromY + 1, (cellY + 1) * height / rows));
        int[] pixels = new int[(toX - fromX) * (toY - fromY)];
        int next = 0;
        for (int y = fromY; y < toY; y++) {
            for (int x = fromX; x < toX; x++) {
                int index = (depth + y) * netWidth + depth + x;
                pixels[next++] = index < net.length ? net[index] : 0;
            }
        }
        return pixels;
    }

    /**
     * A part's whole box, unfolded the way a skin lays it out.
     *
     * <p>{@code 2d + 2w} pixels across and {@code d + h} down, for a part
     * {@code w} wide, {@code h} tall and {@code d} deep: the top and then the
     * bottom along the first {@code d} rows starting {@code d} in, and below
     * them the right side, the front, the left side and the back. Anything
     * outside those six faces is clear. A slim arm is stretched to four
     * pixels, so every arm unfolds the same.
     *
     * @param part which piece
     * @return a copy of the pixels as {@code 0xAARRGGBB}, or {@code null} when
     *         nothing was read for the part
     * @since 1.141.0
     */
    @ApiStatus.Internal
    public int[] net(@NotNull RagdollPart part) {
        int[] net = faces.get(part);
        return net == null ? null : net.clone();
    }

    /** How deep a part is, in skin pixels. */
    private static int depth(RagdollPart part) {
        return Math.round(part.blockDepth() / RagdollPart.PIXEL);
    }

    /** Whether anything at all was read for this part. */
    public boolean has(@Nullable RagdollPart part) {
        return part != null && faces.containsKey(part);
    }
}
