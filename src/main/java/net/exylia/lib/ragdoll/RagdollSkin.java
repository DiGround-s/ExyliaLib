package net.exylia.lib.ragdoll;

import net.exylia.lib.ragdoll.internal.SkinCubes;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The colours of one player's skin, cut into the parts a body comes apart into,
 * and the picture they came from.
 *
 * <h2>Two ways to be drawn</h2>
 * Every skin has its colours: every face of each part, which is what a body
 * is drawn from in blocks. A skin read from a picture also keeps the picture,
 * so it can be cut into pieces each repainted as a head, at whichever quality
 * the server asks for; a piece whose texture has been made wears its actual
 * pixels on all six faces. Which pieces have a texture is not kept here: a
 * texture belongs to a piece's pixels, and every skin whose sleeve looks the
 * same shares it.
 *
 * <p>Every face of every part is kept, unfolded the way the skin lays it out
 * and with the second layer painted over the first. The cells of a body in
 * blocks read the front, which is where a design is and what a limb tumbling
 * at a tenth of a second a turn is read by; a body at detail five reads all
 * six, so a logo on a back comes apart with the body too.
 *
 * <p>Immutable and cached per skin, so a hundred deaths wearing the same skin
 * decode one picture and cut it once per quality.
 *
 * @since 1.120.0
 */
public final class RagdollSkin {

    /** What a part is drawn in when its own pixels are missing or clear. */
    private static final int FALLBACK = 0xFF9E9E9E;

    private final Map<RagdollPart, int[]> faces;
    private final @Nullable BufferedImage image;
    private final boolean slim;
    private final Map<SkinCubes.Quality, List<SkinCubes.Cube>> cut = new ConcurrentHashMap<>();

    /**
     * A skin from its parts' unfolded boxes, with no picture to cut.
     *
     * @param faces per part, its whole box net row by row, as {@link #net}
     *              describes it
     */
    @ApiStatus.Internal
    public RagdollSkin(@NotNull Map<RagdollPart, int[]> faces) {
        this(faces, null, false);
    }

    /**
     * A skin from its parts' unfolded boxes and the picture they were read from.
     *
     * @param faces per part, its whole box net row by row
     * @param image the skin picture, never drawn on
     * @param slim  whether its arms are three pixels wide
     * @since 1.142.0
     */
    @ApiStatus.Internal
    public RagdollSkin(@NotNull Map<RagdollPart, int[]> faces, @Nullable BufferedImage image, boolean slim) {
        this.faces = new EnumMap<>(faces);
        this.image = image;
        this.slim = slim;
    }

    /**
     * The pieces this skin is cut into at a quality, each painted as a head.
     *
     * <p>Cut the first time a quality is asked for and kept, so changing the
     * quality on a reload cuts every skin again once and nothing more.
     *
     * @param quality how finely
     * @return the pieces, torso first; {@code null} for a skin with no picture
     * @since 1.142.0
     */
    @ApiStatus.Internal
    public @Nullable List<SkinCubes.Cube> cubes(@NotNull SkinCubes.Quality quality) {
        BufferedImage picture = image;
        return picture == null ? null
                : cut.computeIfAbsent(quality, level -> List.copyOf(SkinCubes.cut(picture, slim, level)));
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
