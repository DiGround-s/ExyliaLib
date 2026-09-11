package net.exylia.lib.ragdoll.internal;

import net.exylia.lib.ragdoll.RagdollPart;
import org.bukkit.Material;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Cuts a skin into the pieces a body is drawn with when it wears the real
 * thing, each one repainted as a head.
 *
 * <h2>Why heads</h2>
 * A client draws a player head with whatever texture Mojang hosts for it, and
 * a head is a box with six painted faces and a second, slightly larger box
 * over it for the hat. A display may stretch that box on each axis on its own,
 * so any box of a body can be a head: a four-pixel cube, the upper two thirds
 * of an arm, a whole leg. Each piece is given a skin of its own whose head is
 * that piece &mdash; its six faces spread over the head's eight texels a side,
 * its jacket layer in the hat &mdash; and drawn as a head stretched back to
 * the piece's shape. No resource pack is involved.
 *
 * <h2>How finely</h2>
 * Every piece is one upload to MineSkin, and the free plan allows a hundred an
 * hour, so how many pieces a skin is cut into is a real cost. {@link Quality}
 * decides it. A piece whose every side is a whole number of texels per pixel
 * &mdash; one or two &mdash; is exact; anything else loses rows.
 *
 * <h2>The faces a piece does not have</h2>
 * A piece in the middle of a chest has no side of its own on the skin: that
 * side is inside the body. It is painted by stretching the nearest edge of
 * its front and back inwards, so a piece that comes away from the body shows
 * the colours of the shirt it was cut from rather than a grey hole.
 *
 * <p>Pure: pictures in, pictures out, so every face can be asserted.
 */
@ApiStatus.Internal
public final class SkinCubes {

    /** Texels per head face. */
    private static final int HEAD = 8;

    /**
     * What a base texel is painted when the skin has nothing there.
     *
     * <p>A skull draws a clear base texel as a hole straight through the head,
     * and the empty column of a slim arm would otherwise be exactly that.
     */
    private static final int FILL = 0xFF9E9E9E;

    private static final Box HEAD_BASE = new Box(0, 0, HEAD, HEAD, HEAD);
    private static final Box HEAD_HAT = new Box(32, 0, HEAD, HEAD, HEAD);

    private SkinCubes() {
    }

    /** How many pieces a skin is cut into, and so how many uploads it costs. */
    public enum Quality {

        /** Four-pixel cubes: eighteen uploads, every pixel exact, nineteen pieces when a body breaks. */
        HIGH,

        /** Each part an upper and a lower box: ten uploads, every pixel exact, eleven larger pieces. */
        NORMAL,

        /** Each part one head: five uploads, and twelve rows squeezed into eight texels, so a third are lost. */
        LOW;

        /**
         * A quality by its name in a config.
         *
         * @param name {@code high}, {@code normal} or {@code low}, in any case
         * @return the quality, or {@code null} when the name is none of them
         */
        public static @Nullable Quality of(@Nullable String name) {
            if (name == null) {
                return null;
            }
            try {
                return valueOf(name.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                return null;
            }
        }

        /** The regions one part is cut into, top to bottom and from the wearer's right. */
        List<Region> regions(RagdollPart part) {
            int width = pixels(part.blockWidth());
            int height = pixels(part.blockHeight());
            List<Region> regions = new ArrayList<>();
            switch (this) {
                case HIGH -> {
                    int columns = part.columns(2);
                    int rows = part.rows(2);
                    for (int row = 0; row < rows; row++) {
                        for (int column = 0; column < columns; column++) {
                            regions.add(new Region(part, column * width / columns, row * height / rows,
                                    width / columns, height / rows));
                        }
                    }
                }
                // Eight rows and then four: both halves reach the head's eight
                // texels by a whole number, so nothing is squeezed.
                case NORMAL -> {
                    regions.add(new Region(part, 0, 0, width, HEAD));
                    regions.add(new Region(part, 0, HEAD, width, height - HEAD));
                }
                case LOW -> regions.add(new Region(part, 0, 0, width, height));
            }
            return regions;
        }
    }

    /**
     * A box of a part: how far across and down its front it starts, and how big
     * it is, in skin pixels. Its depth is always the whole part's.
     *
     * @param part   the part it is cut from
     * @param x      its first column, from the wearer's right
     * @param y      its first row, from the top
     * @param width  its columns
     * @param height its rows
     */
    public record Region(RagdollPart part, int x, int y, int width, int height) {

        /** Where its middle sits inside its part before the part is turned, in skin pixels. */
        public float[] centre() {
            return new float[]{
                    -pixels(part.blockWidth()) / 2f + x + width / 2f,
                    pixels(part.blockHeight()) / 2f - y - height / 2f,
                    0f};
        }

        /** How big it is on each axis, in skin pixels. */
        public float[] size() {
            return new float[]{width, height, pixels(part.blockDepth())};
        }
    }

    /**
     * One piece of a body, painted as a head.
     *
     * @param region   where on its part it was cut from
     * @param image    the 64&times;64 skin whose head is this piece
     * @param hash     a fingerprint of the picture, so an identical piece of
     *                 another skin is never uploaded twice
     * @param plain    the one block every texel of the piece matches, or
     *                 {@code null} when it has a design; a plain piece is drawn
     *                 as that block and never uploaded
     * @param dominant the block most of its front and back are, for drawing it
     *                 while its texture is on its way
     */
    public record Cube(Region region, BufferedImage image, String hash, @Nullable Material plain,
                       Material dominant) {

        /** The part it was cut from. */
        public RagdollPart part() {
            return region.part();
        }
    }

    /** The six faces of a box, as the skin lays them out. */
    enum Face {
        TOP, BOTTOM, RIGHT, FRONT, LEFT, BACK
    }

    /**
     * A box on a skin: where its net starts and how big it is, in pixels.
     *
     * <p>Every box in the vanilla model is unfolded the same way &mdash; top and
     * bottom above, then right side, front, left side and back in a row &mdash;
     * which is what lets a sleeve be copied onto a head face for face.
     */
    record Box(int u, int v, int w, int h, int d) {

        int width(Face face) {
            return face == Face.RIGHT || face == Face.LEFT ? d : w;
        }

        int height(Face face) {
            return face == Face.TOP || face == Face.BOTTOM ? d : h;
        }

        int x(Face face) {
            return switch (face) {
                case RIGHT -> u;
                case TOP, FRONT -> u + d;
                case BOTTOM, LEFT -> u + d + w;
                case BACK -> u + 2 * d + w;
            };
        }

        int y(Face face) {
            return face == Face.TOP || face == Face.BOTTOM ? v : v + d;
        }

        /** A pixel of one face, or clear when the picture is too small to have it. */
        int pixel(BufferedImage image, Face face, int column, int row) {
            int x = x(face) + column;
            int y = y(face) + row;
            return x < image.getWidth() && y < image.getHeight() ? image.getRGB(x, y) : 0;
        }
    }

    /**
     * Every piece of a body but the head, painted as heads.
     *
     * @param skin    the skin picture, 64&times;64 or legacy 64&times;32
     * @param slim    whether the arms are three pixels wide
     * @param quality how finely to cut it
     * @return the pieces, part by part &mdash; the torso, then the arms, then
     *         the legs &mdash; and top to bottom within each
     */
    public static List<Cube> cut(BufferedImage skin, boolean slim, Quality quality) {
        boolean legacy = skin.getHeight() < 64;
        List<Cube> cubes = new ArrayList<>(18);
        for (RagdollPart part : RagdollPart.values()) {
            if (part == RagdollPart.HEAD) {
                continue;
            }
            RagdollPart source = legacy ? part.legacy() : part;
            int drawn = slim && (part == RagdollPart.ARM_RIGHT || part == RagdollPart.ARM_LEFT)
                    ? 3 : pixels(part.blockWidth());
            Box base = base(source, drawn);
            Box over = legacy ? null : overlay(source, drawn);
            for (Region region : quality.regions(part)) {
                cubes.add(paint(skin, base, over, region));
            }
        }
        return cubes;
    }

    private static Cube paint(BufferedImage skin, Box base, Box over, Region region) {
        BufferedImage head = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        int[] frontAndBack = new int[2 * HEAD * HEAD];
        int seen = 0;
        Material plain = null;
        boolean mixed = false;
        for (Face face : Face.values()) {
            for (int row = 0; row < HEAD; row++) {
                for (int column = 0; column < HEAD; column++) {
                    int painted = sample(skin, base, face, column, row, region);
                    int worn = over == null ? 0 : sample(skin, over, face, column, row, region);
                    boolean wornSolid = (worn >>> 24) >= 128;
                    if ((painted >>> 24) < 128) {
                        painted = wornSolid ? worn : FILL;
                    }
                    painted |= 0xFF000000;
                    head.setRGB(HEAD_BASE.x(face) + column, HEAD_BASE.y(face) + row, painted);
                    if (wornSolid) {
                        head.setRGB(HEAD_HAT.x(face) + column, HEAD_HAT.y(face) + row, worn | 0xFF000000);
                    }
                    if (!mixed) {
                        Material block = BlockPalette.material(painted);
                        plain = plain == null ? block : plain;
                        mixed = block != plain
                                || wornSolid && BlockPalette.material(worn) != plain;
                    }
                    if (face == Face.FRONT || face == Face.BACK) {
                        frontAndBack[seen++] = wornSolid ? worn : painted;
                    }
                }
            }
        }
        return new Cube(region, head, hash(head), mixed ? null : plain, BlockPalette.dominant(frontAndBack));
    }

    /**
     * The skin pixel under one texel of one face of one region.
     *
     * <p>Faces run the way the net draws them: a front's first column is the
     * wearer's right, a back's is their left, a side's columns run from the
     * back to the front on the right and from the front to the back on the
     * left, and a top's and bottom's last row is the edge they share with the
     * front. Each axis spreads its pixels over the eight texels by itself, so
     * a four-pixel side lands on two texels a pixel and a twelve-pixel one is
     * squeezed.
     */
    static int sample(BufferedImage skin, Box box, Face face, int texelX, int texelY, Region region) {
        int partWidth = pixels(region.part().blockWidth());
        // A slim arm's three columns stand in for the four the body is cut in,
        // and every one of them lands somewhere.
        int left = region.x() * box.w() / partWidth;
        int wide = Math.max(1, region.width() * box.w() / partWidth);
        int top = region.y();
        int tall = region.height();
        int across = texelX * (face == Face.RIGHT || face == Face.LEFT ? box.d() : wide) / HEAD;
        int down = texelY * (face == Face.TOP || face == Face.BOTTOM ? box.d() : tall) / HEAD;
        int back = box.w() - left - wide;
        int half = box.d() / 2;
        return switch (face) {
            case FRONT -> box.pixel(skin, Face.FRONT, left + across, top + down);
            case BACK -> box.pixel(skin, Face.BACK, back + across, top + down);
            case RIGHT -> region.x() == 0
                    ? box.pixel(skin, Face.RIGHT, across, top + down)
                    : across >= half
                    ? box.pixel(skin, Face.FRONT, left, top + down)
                    : box.pixel(skin, Face.BACK, back + wide - 1, top + down);
            case LEFT -> region.x() + region.width() == partWidth
                    ? box.pixel(skin, Face.LEFT, across, top + down)
                    : across < half
                    ? box.pixel(skin, Face.FRONT, left + wide - 1, top + down)
                    : box.pixel(skin, Face.BACK, back, top + down);
            case TOP -> top == 0
                    ? box.pixel(skin, Face.TOP, left + across, down)
                    : down >= half
                    ? box.pixel(skin, Face.FRONT, left + across, top)
                    : box.pixel(skin, Face.BACK, box.w() - 1 - left - across, top);
            case BOTTOM -> top + tall == box.h()
                    ? box.pixel(skin, Face.BOTTOM, left + across, down)
                    : down >= half
                    ? box.pixel(skin, Face.FRONT, left + across, top + tall - 1)
                    : box.pixel(skin, Face.BACK, box.w() - 1 - left - across, top + tall - 1);
        };
    }

    /**
     * A part's whole box as one picture: every face of its first layer with
     * the second painted over it.
     *
     * <p>What a body in blocks is drawn from. A slim arm's three columns are
     * stretched to four, so every arm unfolds the same; a legacy skin reads the
     * right-hand limbs for both sides and has no second layer.
     *
     * @param skin the skin picture, 64&times;64 or legacy 64&times;32
     * @param part which part
     * @param slim whether the arms are three pixels wide
     * @return the net row by row, {@code 2d + 2w} by {@code d + h}, clear
     *         outside the six faces
     */
    public static int[] net(BufferedImage skin, RagdollPart part, boolean slim) {
        boolean legacy = skin.getHeight() < 64;
        RagdollPart source = legacy ? part.legacy() : part;
        int width = pixels(part.blockWidth());
        int drawn = slim && (part == RagdollPart.ARM_RIGHT || part == RagdollPart.ARM_LEFT) ? 3 : width;
        Box base = base(source, drawn);
        Box over = legacy ? null : overlay(source, drawn);
        Box shape = new Box(0, 0, width, base.h(), base.d());
        int netWidth = 2 * (shape.d() + shape.w());
        int[] pixels = new int[netWidth * (shape.d() + shape.h())];
        for (Face face : Face.values()) {
            boolean stretched = face != Face.RIGHT && face != Face.LEFT && drawn != width;
            for (int row = 0; row < shape.height(face); row++) {
                for (int column = 0; column < shape.width(face); column++) {
                    int across = stretched ? column * drawn / width : column;
                    int painted = base.pixel(skin, face, across, row);
                    if (over != null) {
                        // The second layer is where most clothes are drawn: a
                        // jacket, a hood, rolled sleeves. Reading only the first
                        // leaves a body in whatever the artist painted
                        // underneath, which is usually skin.
                        int worn = over.pixel(skin, face, across, row);
                        if ((worn >>> 24) >= 128) {
                            painted = worn;
                        }
                    }
                    pixels[(shape.y(face) + row) * netWidth + shape.x(face) + column] = painted;
                }
            }
        }
        return pixels;
    }

    /** Where a part's first layer is unfolded on a skin. */
    static Box base(RagdollPart part, int width) {
        return switch (part) {
            case TORSO -> new Box(16, 16, 8, 12, 4);
            case ARM_RIGHT -> new Box(40, 16, width, 12, 4);
            case ARM_LEFT -> new Box(32, 48, width, 12, 4);
            case LEG_RIGHT -> new Box(0, 16, 4, 12, 4);
            case LEG_LEFT -> new Box(16, 48, 4, 12, 4);
            case HEAD -> HEAD_BASE;
        };
    }

    /** Where a part's second layer is unfolded on a skin. */
    static Box overlay(RagdollPart part, int width) {
        return switch (part) {
            case TORSO -> new Box(16, 32, 8, 12, 4);
            case ARM_RIGHT -> new Box(40, 32, width, 12, 4);
            case ARM_LEFT -> new Box(48, 48, width, 12, 4);
            case LEG_RIGHT -> new Box(0, 32, 4, 12, 4);
            case LEG_LEFT -> new Box(0, 48, 4, 12, 4);
            case HEAD -> HEAD_HAT;
        };
    }

    /** A fingerprint of a picture's pixels, as hex. */
    static String hash(BufferedImage image) {
        int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        ByteBuffer bytes = ByteBuffer.allocate(pixels.length * 4);
        bytes.asIntBuffer().put(pixels);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.array()));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Every JVM ships SHA-256", impossible);
        }
    }

    /** Blocks as skin pixels. */
    static int pixels(float blocks) {
        return Math.round(blocks / RagdollPart.PIXEL);
    }
}
