package net.exylia.lib.ragdoll.internal;

import net.exylia.lib.ragdoll.RagdollPart;
import org.jetbrains.annotations.ApiStatus;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Cuts a skin into the little cubes a body is drawn with when it wears the
 * real thing, each one repainted as a head.
 *
 * <h2>Why heads</h2>
 * A client draws a player head with whatever texture Mojang hosts for it, and
 * a head is a box with six painted faces and a second, slightly larger box
 * over it for the hat. At a detail of two every piece of a body is a box too:
 * four skin pixels a side. So each piece is given a skin of its own whose head
 * is that piece &mdash; its six faces at twice the size, its jacket layer in
 * the hat &mdash; and drawn as a head at half the size. Nothing about the
 * shape is approximated, and no resource pack is involved.
 *
 * <h2>The faces a piece does not have</h2>
 * A cube in the middle of a chest has no side of its own on the skin: that
 * side is inside the body. It is painted by stretching the nearest edge of
 * its front and back inwards, so a piece that comes away from the body shows
 * the colours of the shirt it was cut from rather than a grey hole.
 *
 * <p>Pure: pictures in, pictures out, so every face can be asserted.
 */
@ApiStatus.Internal
public final class SkinCubes {

    /** The detail at which every body piece is exactly a four-pixel cube. */
    public static final int DETAIL = 2;

    /** Skin pixels per cube edge. */
    private static final int CUBE = 4;

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

    /**
     * One piece of a body, painted as a head.
     *
     * @param part  the part it was cut from
     * @param cellX its column in that part, from the wearer's right
     * @param cellY its row, from the top
     * @param image the 64&times;64 skin whose head is this piece
     * @param hash  a fingerprint of the picture, so an identical piece of
     *              another skin is never uploaded twice
     */
    public record Cube(RagdollPart part, int cellX, int cellY, BufferedImage image, String hash) {
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
     * @param skin the skin picture, 64&times;64 or legacy 64&times;32
     * @param slim whether the arms are three pixels wide
     * @return eighteen cubes, part by part, top to bottom
     */
    public static List<Cube> cut(BufferedImage skin, boolean slim) {
        boolean legacy = skin.getHeight() < 64;
        List<Cube> cubes = new ArrayList<>(18);
        for (RagdollPart part : RagdollPart.values()) {
            if (part == RagdollPart.HEAD) {
                continue;
            }
            RagdollPart source = legacy ? part.legacy() : part;
            int width = slim && (part == RagdollPart.ARM_RIGHT || part == RagdollPart.ARM_LEFT)
                    ? 3 : Math.round(part.blockWidth() / RagdollPart.PIXEL);
            Box base = base(source, width);
            Box over = legacy ? null : overlay(source, width);
            int columns = part.columns(DETAIL);
            int rows = part.rows(DETAIL);
            for (int cellY = 0; cellY < rows; cellY++) {
                for (int cellX = 0; cellX < columns; cellX++) {
                    BufferedImage image = paint(skin, base, over, cellX, cellY, columns, rows);
                    cubes.add(new Cube(part, cellX, cellY, image, hash(image)));
                }
            }
        }
        return cubes;
    }

    private static BufferedImage paint(BufferedImage skin, Box base, Box over,
                                       int cellX, int cellY, int columns, int rows) {
        BufferedImage head = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (Face face : Face.values()) {
            for (int row = 0; row < HEAD; row++) {
                for (int column = 0; column < HEAD; column++) {
                    int painted = sample(skin, base, face, column, row, cellX, cellY, columns, rows);
                    int worn = over == null ? 0
                            : sample(skin, over, face, column, row, cellX, cellY, columns, rows);
                    boolean wornSolid = (worn >>> 24) >= 128;
                    if ((painted >>> 24) < 128) {
                        painted = wornSolid ? worn : FILL;
                    }
                    head.setRGB(HEAD_BASE.x(face) + column, HEAD_BASE.y(face) + row, painted | 0xFF000000);
                    if (wornSolid) {
                        head.setRGB(HEAD_HAT.x(face) + column, HEAD_HAT.y(face) + row, worn | 0xFF000000);
                    }
                }
            }
        }
        return head;
    }

    /**
     * The skin pixel under one texel of one face of one cube.
     *
     * <p>Faces run the way the net draws them: a front's first column is the
     * wearer's right, a back's is their left, a side's columns run from the
     * back to the front on the right and from the front to the back on the
     * left, and a top's and bottom's last row is the edge they share with the
     * front.
     */
    static int sample(BufferedImage skin, Box box, Face face, int texelX, int texelY,
                      int cellX, int cellY, int columns, int rows) {
        int cube = box.w() / columns;
        // A slim arm's three columns land on eight texels unevenly, but every
        // one of them lands somewhere.
        int across = texelX * (face == Face.RIGHT || face == Face.LEFT ? box.d() : cube) / HEAD;
        int down = texelY * (face == Face.TOP || face == Face.BOTTOM ? box.d() : CUBE) / HEAD;
        int front = cellX * cube;
        int back = box.w() - (cellX + 1) * cube;
        int top = cellY * CUBE;
        int half = box.d() / 2;
        return switch (face) {
            case FRONT -> box.pixel(skin, Face.FRONT, front + across, top + down);
            case BACK -> box.pixel(skin, Face.BACK, back + across, top + down);
            case RIGHT -> cellX == 0
                    ? box.pixel(skin, Face.RIGHT, across, top + down)
                    : across >= half
                    ? box.pixel(skin, Face.FRONT, front, top + down)
                    : box.pixel(skin, Face.BACK, back + cube - 1, top + down);
            case LEFT -> cellX == columns - 1
                    ? box.pixel(skin, Face.LEFT, across, top + down)
                    : across < half
                    ? box.pixel(skin, Face.FRONT, front + cube - 1, top + down)
                    : box.pixel(skin, Face.BACK, back, top + down);
            case TOP -> cellY == 0
                    ? box.pixel(skin, Face.TOP, front + across, down)
                    : down >= half
                    ? box.pixel(skin, Face.FRONT, front + across, top)
                    : box.pixel(skin, Face.BACK, box.w() - 1 - front - across, top);
            case BOTTOM -> cellY == rows - 1
                    ? box.pixel(skin, Face.BOTTOM, front + across, down)
                    : down >= half
                    ? box.pixel(skin, Face.FRONT, front + across, top + CUBE - 1)
                    : box.pixel(skin, Face.BACK, box.w() - 1 - front - across, top + CUBE - 1);
        };
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
}
