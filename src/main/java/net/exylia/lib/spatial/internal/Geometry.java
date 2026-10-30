package net.exylia.lib.spatial.internal;

import net.exylia.lib.display.DisplayKeyframe;
import net.exylia.lib.display.Rotation;
import net.exylia.lib.stage.StagePlane;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The maths behind a spatial menu, kept server-free so it is tested without a world: where a turned
 * panel stands, where each carousel slot sits, how a list is cut into pages, which slots changed
 * between two redraws, and how a text display is sized into a sheet of glass.
 *
 * <p>Internal: free to change without notice.
 */
public final class Geometry {

    /**
     * A sheet of glass is a text display holding twenty spaces: 80 pixels of text and its 1-pixel
     * background margin make it 81 pixels, 2.025 blocks wide and 0.25 tall at scale 1, its foot on the
     * display's origin. Wide on purpose: the background sits half a pixel to one side of the text, which
     * on a single space would push a panel a tenth of its width off centre; on twenty it is 0.6 %.
     */
    public static final String GLASS_TEXT = "                    ";
    public static final double GLASS_WIDTH = 81 * 0.025;
    public static final double GLASS_HEIGHT = 0.25;

    private Geometry() {
        throw new AssertionError("No instances.");
    }

    /**
     * The plane of a panel standing on a board: centred {@code across}, {@code up} and {@code forward}
     * from the board's middle and turned {@code turn} degrees towards the viewer around its own
     * vertical axis (a panel on the left turned by a positive angle, one on the right by a negative).
     */
    public static StagePlane panel(World world, StagePlane board, double across, double up, double forward, double turn) {
        var centre = board.point(across, up, forward);
        return new StagePlane(world, centre.getX(), centre.getY(), centre.getZ(), (float) (board.yaw() - turn),
                1, 1, 1, 1);
    }

    /** The turn, in degrees, that faces a point {@code across} the board and {@code depth} ahead of the eyes back at them. */
    public static double facingTurn(double across, double depth) {
        return -Math.toDegrees(Math.atan2(across, Math.max(0.1, depth)));
    }

    /**
     * Where carousel slot {@code index} of {@code slots} sits, as {across, forward} from the carousel's
     * centre: half on each side of a gap of {@code centreGap} (or evenly spread with no gap), the
     * outermost brought {@code curve} blocks nearer, the rest on a parabola between, so the row bends
     * round the viewer.
     */
    public static double[] carousel(int index, int slots, double spacing, double centreGap, double curve) {
        double across;
        if (centreGap > 0 && slots > 1) {
            int left = slots / 2;
            if (index < left) across = -(centreGap / 2 + spacing * (left - 1 - index + 0.5));
            else across = centreGap / 2 + spacing * (index - left + 0.5);
        } else {
            across = (index - (slots - 1) / 2.0) * spacing;
        }
        double outer = outermost(slots, spacing, centreGap);
        double forward = outer <= 0 ? 0 : curve * (across / outer) * (across / outer);
        return new double[]{across, forward};
    }

    /** How far from the centre the outermost carousel slot sits. */
    public static double outermost(int slots, double spacing, double centreGap) {
        if (slots <= 1) return 0;
        if (centreGap > 0) {
            int right = slots - slots / 2;
            return Math.max(centreGap / 2 + spacing * (slots / 2 - 0.5), centreGap / 2 + spacing * (right - 0.5));
        }
        return (slots - 1) / 2.0 * spacing;
    }

    /** How many pages {@code total} entries fill at {@code perPage} a page; never fewer than one. */
    public static int pages(int total, int perPage) {
        if (perPage <= 0) return 1;
        return Math.max(1, (total + perPage - 1) / perPage);
    }

    /** A page number brought back inside the pages there are, wrapping round either end. */
    public static int wrap(int page, int pages) {
        if (pages <= 1) return 0;
        return Math.floorMod(page, pages);
    }

    /** The entries on page {@code page}, at {@code perPage} a page. */
    public static <T> List<T> page(List<T> all, int page, int perPage) {
        if (perPage <= 0) return List.of();
        int from = Math.min(all.size(), wrap(page, pages(all.size(), perPage)) * perPage);
        return List.copyOf(all.subList(from, Math.min(all.size(), from + perPage)));
    }

    /**
     * The slots that differ between two redraws of the same {@code slots} places: a slot whose entry
     * is equal before and after keeps what it drew.
     */
    public static <T> List<Integer> changed(List<T> before, List<T> after, int slots) {
        List<Integer> changed = new ArrayList<>();
        for (int i = 0; i < slots; i++) {
            T was = i < before.size() ? before.get(i) : null;
            T now = i < after.size() ? after.get(i) : null;
            if (!Objects.equals(was, now)) changed.add(i);
        }
        return changed;
    }

    /** A pixel of vanilla text at scale 1: a line is ten of them, {@link #GLASS_HEIGHT} tall. */
    public static final double PIXEL = 0.025;
    /** How tall a capital of the vanilla font is, in pixels, and a small capital (the unicode glyphs, drawn at half size). */
    public static final int CAPITAL_PIXELS = 7;
    public static final int SMALL_CAPITAL_PIXELS = 5;

    /**
     * The smallest text scale whose glyphs, {@code pixels} tall, look at least {@code degrees} tall from
     * {@code distance} blocks away: the readability floor every line of a spatial menu is drawn above.
     */
    public static double readableScale(double distance, double degrees, int pixels) {
        if (degrees <= 0 || pixels <= 0) return 0;
        double height = 2 * Math.max(0.1, distance) * Math.tan(Math.toRadians(degrees) / 2);
        return height / (pixels * PIXEL);
    }

    /** How tall a glyph {@code pixels} tall at {@code scale} looks from {@code distance} blocks away, in degrees. */
    public static double glyphDegrees(double scale, int pixels, double distance) {
        return Math.toDegrees(2 * Math.atan(pixels * PIXEL * scale / 2 / Math.max(0.1, distance)));
    }

    /**
     * A line cut so its visible letters fit {@code width} blocks at scale 1, ending in an ellipsis; the
     * line itself when it fits. Markup takes no room and is kept: palette tokens ({@code {primary}}),
     * tags ({@code <#8a51c4>}) and {@code &}/{@code §} codes.
     */
    public static String truncate(String raw, double width) {
        if (raw == null || raw.isEmpty() || visibleWidth(raw) <= width) return raw;
        double room = width - net.exylia.lib.stage.StageLook.width("…");
        StringBuilder out = new StringBuilder();
        double used = 0;
        for (int i = 0; i < raw.length(); i++) {
            int markup = markup(raw, i);
            if (markup > 0) {
                out.append(raw, i, i + markup);
                i += markup - 1;
                continue;
            }
            double glyph = net.exylia.lib.stage.StageLook.width(String.valueOf(raw.charAt(i)));
            if (used + glyph > room) break;
            used += glyph;
            out.append(raw.charAt(i));
        }
        while (out.length() > 0 && out.charAt(out.length() - 1) == ' ') out.setLength(out.length() - 1);
        return out.append('…').toString();
    }

    /** How wide a line's visible letters are at scale 1, its markup left out. */
    public static double visibleWidth(String raw) {
        StringBuilder plain = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            int markup = markup(raw, i);
            if (markup > 0) {
                i += markup - 1;
                continue;
            }
            plain.append(raw.charAt(i));
        }
        return net.exylia.lib.stage.StageLook.width(plain.toString());
    }

    /** How long the markup starting at {@code i} is, or 0 when a visible letter stands there. */
    private static int markup(String raw, int i) {
        char c = raw.charAt(i);
        if ((c == '&' || c == '§') && i + 1 < raw.length()
                && "0123456789abcdefklmnorxABCDEFKLMNORX".indexOf(raw.charAt(i + 1)) >= 0) {
            return 2;
        }
        if (c == '{' || c == '<') {
            int end = raw.indexOf(c == '{' ? '}' : '>', i);
            if (end > i && end - i <= 40 && raw.substring(i + 1, end).matches("[#/!a-zA-Z0-9_:.\\-]+")) return end - i + 1;
        }
        return 0;
    }

    /**
     * The centre and height of a panel's content, below its title band and above its footer band.
     *
     * @return {centre up, height}, in the panel's own terms
     */
    public static double[] content(double height, boolean title, boolean footer) {
        return content(height, title ? 0.3 : 0.08, footer);
    }

    /** The same, below a title band {@code band} blocks tall: a title with a hint under it needs more. */
    public static double[] content(double height, double band, boolean footer) {
        double top = height / 2 - band;
        double bottom = -height / 2 + (footer ? 0.3 : 0.08);
        return new double[]{(top + bottom) / 2, Math.max(0.05, top - bottom)};
    }

    /** The centre of cell {@code index} of a grid {@code columns} wide filling {@code width} by {@code height}. */
    public static double[] cell(int index, int columns, int rows, double width, double height) {
        int column = index % columns;
        int row = index / columns;
        double pitchX = width / Math.max(1, columns);
        double pitchY = height / Math.max(1, rows);
        return new double[]{(column - (columns - 1) / 2.0) * pitchX, ((rows - 1) / 2.0 - row) * pitchY};
    }

    /**
     * A sheet of glass {@code width} by {@code height} centred on its display's origin, turned by
     * {@code rotation}, {@code grow} times its size, moved {@code dx, dy, dz} in the world's axes.
     */
    public static DisplayKeyframe glass(long at, Rotation rotation, double width, double height, double grow,
                                        double dx, double dy, double dz) {
        float[] foot = rotation.apply(new float[]{0f, (float) (-height * grow / 2), 0f});
        return new DisplayKeyframe(at, (float) (foot[0] + dx), (float) (foot[1] + dy), (float) (foot[2] + dz), rotation,
                (float) (width * grow / GLASS_WIDTH), (float) (height * grow / GLASS_HEIGHT), 1f);
    }

    /** Glass that waits {@code delay} unseen, then grows past its size and settles: a staggered pop-in. */
    public static List<DisplayKeyframe> glassPop(Rotation rotation, double width, double height, long delay) {
        return glassPop(rotation, width, height, delay, null);
    }

    /** The same pop gliding in from {@code from} (world axes), or in place when it is {@code null}. */
    public static List<DisplayKeyframe> glassPop(Rotation rotation, double width, double height, long delay, double[] from) {
        double dx = from == null ? 0 : from[0];
        double dy = from == null ? 0 : from[1];
        double dz = from == null ? 0 : from[2];
        return List.of(glass(0, rotation, width, height, 0.01, dx, dy, dz),
                glass(delay, rotation, width, height, 0.01, dx, dy, dz),
                glass(delay + 160, rotation, width, height, 1.04, dx * 0.2, dy * 0.2, dz * 0.2),
                glass(delay + 300, rotation, width, height, 1.0, 0, 0, 0));
    }

    /** {@code a} mixed towards {@code b} by {@code t}, both 0xRRGGBB. */
    public static int mix(int a, int b, double t) {
        double k = Math.clamp(t, 0, 1);
        int r = (int) Math.round(((a >> 16) & 0xFF) * (1 - k) + ((b >> 16) & 0xFF) * k);
        int g = (int) Math.round(((a >> 8) & 0xFF) * (1 - k) + ((b >> 8) & 0xFF) * k);
        int bl = (int) Math.round((a & 0xFF) * (1 - k) + (b & 0xFF) * k);
        return (r << 16) | (g << 8) | bl;
    }

    /** An 0xRRGGBB colour with an alpha, as a text display background wants it. */
    public static int argb(int alpha, int rgb) {
        return (Math.clamp(alpha, 0, 255) << 24) | (rgb & 0xFFFFFF);
    }
}
