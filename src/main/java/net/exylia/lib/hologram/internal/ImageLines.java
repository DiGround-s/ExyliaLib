package net.exylia.lib.hologram.internal;

import net.exylia.lib.screen.internal.Pictures;
import net.kyori.adventure.text.Component;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The picture an {@code <image:...>} line draws: a PNG from the owning
 * plugin's {@code images} folder, scaled to its width and written as rows of
 * coloured full-block glyphs, the screen module's pixel pictures.
 *
 * <p>The file is read through {@link Pictures#file}, which keeps it for half a
 * minute (an owner's edit shows on the next rebuild), and the glyphs are
 * cached by their pixels, so every hologram showing one picture shares it.
 */
final class ImageLines {

    /** A picture drawn as text, and how many rows of glyphs it takes. */
    record Drawn(Component text, int rows) {
    }

    private ImageLines() {
    }

    /**
     * Reads and draws a picture.
     *
     * @param folder the plugin's {@code images} folder
     * @param file   the file, relative to it; never reaches outside it
     * @param width  the pixels wide to draw it, 0 for its own width
     * @throws IOException when the file is missing, outside the folder or not a PNG
     */
    static Drawn draw(Path folder, String file, int width) throws IOException {
        Pictures.Picture picture = Pictures.file(folder, file);
        int w = width > 0 ? Math.min(width, picture.width()) : picture.width();
        int h = Math.max(1, (int) Math.round(picture.height() * (double) w / picture.width()));
        int[] argb = w == picture.width() ? picture.argb() : shrink(picture, w, h);
        return new Drawn(Pictures.glyphs(argb, w, h, 0xFF000000), h);
    }

    /**
     * A picture made smaller, each pixel the average of the ones it covers,
     * weighted by their alpha so a see-through edge does not darken it.
     */
    static int[] shrink(Pictures.Picture picture, int w, int h) {
        int sw = picture.width();
        int sh = picture.height();
        int[] source = picture.argb();
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            int y0 = y * sh / h;
            int y1 = Math.max(y0 + 1, (y + 1) * sh / h);
            for (int x = 0; x < w; x++) {
                int x0 = x * sw / w;
                int x1 = Math.max(x0 + 1, (x + 1) * sw / w);
                long a = 0;
                long r = 0;
                long g = 0;
                long b = 0;
                for (int sy = y0; sy < y1; sy++) {
                    for (int sx = x0; sx < x1; sx++) {
                        int pixel = source[sy * sw + sx];
                        int alpha = pixel >>> 24;
                        a += alpha;
                        r += (long) (pixel >> 16 & 0xFF) * alpha;
                        g += (long) (pixel >> 8 & 0xFF) * alpha;
                        b += (long) (pixel & 0xFF) * alpha;
                    }
                }
                int count = (y1 - y0) * (x1 - x0);
                out[y * w + x] = a == 0 ? 0
                        : (int) (a / count) << 24 | (int) (r / a) << 16 | (int) (g / a) << 8 | (int) (b / a);
            }
        }
        return out;
    }
}
