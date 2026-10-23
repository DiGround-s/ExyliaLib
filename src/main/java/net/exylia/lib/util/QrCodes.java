package net.exylia.lib.util;

import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.ByteMatrix;
import com.google.zxing.qrcode.encoder.Encoder;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Map;

/**
 * QR codes, as a grid of modules or as the pixels of a map a player holds.
 *
 * <pre>{@code
 * byte[] pixels = QrCodes.mapPixels(Totp.uri("Exylia", name, secret));
 * HeldMaps.show(player, pixels);   // a phone scans it off the screen
 * }</pre>
 *
 * <p>Encoding is ZXing's, bundled and relocated inside the library. Error
 * correction is level M, which leaves room for a slightly blurry phone photo
 * of a monitor. Pure Java, safe from any thread.
 */
public final class QrCodes {

    /** Pixels a map is wide and tall. */
    public static final int MAP_SIZE = 128;
    /** Map colour id of the brightest white (snow, lightest shade). */
    public static final byte MAP_WHITE = 34;
    /** Map colour id of black (colour 29, lightest shade); the darkest black loses contrast on some screens. */
    public static final byte MAP_BLACK = 118;

    /** Modules of white kept around the code, which scanners need to find its edge. */
    private static final int QUIET_ZONE = 2;

    private QrCodes() {
    }

    /**
     * The code as {@code [row][column]}, {@code true} for a dark module, with
     * no quiet zone around it.
     *
     * @throws IllegalArgumentException when the text does not fit in a QR code
     */
    public static boolean @NotNull [] @NotNull [] matrix(@NotNull String text) {
        ByteMatrix matrix;
        try {
            matrix = Encoder.encode(text, ErrorCorrectionLevel.M, Map.of(EncodeHintType.CHARACTER_SET, "UTF-8"))
                    .getMatrix();
        } catch (WriterException tooLong) {
            throw new IllegalArgumentException("Text does not fit in a QR code", tooLong);
        }
        boolean[][] out = new boolean[matrix.getHeight()][matrix.getWidth()];
        for (int y = 0; y < out.length; y++) {
            for (int x = 0; x < out[y].length; x++) {
                out[y][x] = matrix.get(x, y) == 1;
            }
        }
        return out;
    }

    /**
     * The code drawn on a 128×128 map, row by row, ready for a map packet:
     * centred, each module scaled to the largest whole number of pixels that
     * fits, white around it.
     *
     * @throws IllegalArgumentException when the code is too dense for a map
     *                                  (about 200 characters is the limit)
     */
    public static byte @NotNull [] mapPixels(@NotNull String text) {
        boolean[][] modules = matrix(text);
        int size = modules.length;
        int scale = MAP_SIZE / (size + QUIET_ZONE * 2);
        if (scale < 1) {
            throw new IllegalArgumentException("QR code too dense for a map: " + size + " modules");
        }
        int offset = (MAP_SIZE - size * scale) / 2;
        byte[] pixels = new byte[MAP_SIZE * MAP_SIZE];
        Arrays.fill(pixels, MAP_WHITE);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (!modules[y][x]) {
                    continue;
                }
                for (int dy = 0; dy < scale; dy++) {
                    int row = (offset + y * scale + dy) * MAP_SIZE + offset + x * scale;
                    Arrays.fill(pixels, row, row + scale, MAP_BLACK);
                }
            }
        }
        return pixels;
    }
}
