package net.exylia.lib.camera.internal;

import net.exylia.lib.camera.WorldShot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What rides a world shot's camera: the subtitles, the title card, the
 * letterbox bars and the fade panel.
 *
 * <h2>Why they ride rather than follow</h2>
 * Each one is a text display seated on the camera entity with a
 * camera-facing billboard and an offset in the camera's own axes. The client
 * then positions them from the camera every frame it draws, so they move with
 * the view exactly, however fast it pans, and moving the camera costs no
 * packet for any of them. Sending each its own position would be four times
 * the packets and a panel visibly trailing the view on every turn.
 *
 * <h2>The geometry</h2>
 * A text display with a {@code CENTER} billboard is turned to the camera's own
 * orientation, and its transform is applied after that turn. A translation of
 * {@code (0, y, -d)} is therefore always {@code d} blocks straight ahead of the
 * view and {@code y} above its centre, whatever the camera is doing. Panels are
 * described here at one block away and scaled with their distance, so the same
 * numbers read the same at any depth.
 *
 * <h2>The bars</h2>
 * A text display grows upwards from its origin, so the top bar's edge is
 * simply where it is put. The bottom bar has to end at its edge instead, which
 * needs its height: it is many blank lines at a small vertical scale, because
 * a line's height (ten pixels) is exact while the panel's padding is not, and
 * forty lines shrink a pixel's doubt to a few thousandths of the view. Before
 * 1.397.0 the bottom bar was turned half a turn about the view axis to grow
 * downwards, and clients drew no bottom bar at all. Both edges sit the same
 * distance from the centre, so the bars are symmetric at any aspect ratio;
 * how much of the screen they cover follows the player's field of view (the
 * server is never told it), and the sizes are given for the default 70&deg;.
 *
 * <h2>Subtitles</h2>
 * One panel per line on screen, up to {@link WorldShot#MAX_LINES}. A line keeps
 * its panel for as long as it shows, so a reply arriving does not make the
 * first line jump panels; lines sharing a position and a side stack by their
 * estimated height.
 */
final class CameraOverlay {

    /**
     * One panel, in view units: one block in front of the camera, where the
     * default 70&deg; field of view spans about 0.7 above and below centre.
     *
     * @param text       what it says; a space for a plain panel
     * @param background ARGB behind the text
     * @param x          where its centre sits, right of the centre of the view
     * @param y          where its bottom edge sits, above the centre of the view
     * @param distance   how far ahead of the camera, in blocks
     * @param scaleX     horizontal stretch
     * @param scaleY     vertical stretch
     * @param lineWidth  where text wraps, in pixels
     * @param align      how the text is aligned inside it
     * @param shadow     whether the text has a drop shadow
     */
    record Panel(Component text, int background, float x, float y, float distance, float scaleX, float scaleY,
                 int lineWidth, WorldShot.Align align, boolean shadow, boolean seeThrough) {

        Panel(Component text, int background, float x, float y, float distance, float scaleX, float scaleY,
              int lineWidth, WorldShot.Align align, boolean shadow) {
            this(text, background, x, y, distance, scaleX, scaleY, lineWidth, align, shadow, false);
        }

        Panel with(Component newText, int newBackground) {
            return new Panel(newText, newBackground, x, y, distance, scaleX, scaleY, lineWidth, align, shadow, seeThrough);
        }

        /** Whether two panels sit in the same place at the same size, whatever they say. */
        boolean sameShape(Panel other) {
            return other != null && x == other.x && y == other.y && distance == other.distance
                    && scaleX == other.scaleX && scaleY == other.scaleY && lineWidth == other.lineWidth
                    && align == other.align && shadow == other.shadow && seeThrough == other.seeThrough;
        }
    }

    private static final int BLACK = 0xFF000000;

    /** Half the view's height one block ahead, at the default 70&deg; field of view. */
    static final float HALF_VIEW = (float) Math.tan(Math.toRadians(35));

    /** Pixels per block of a text display at scale one, and the height of one line in pixels. */
    private static final float PIXEL = 0.025f;
    private static final int LINE_PIXELS = 10;

    /**
     * How a subtitle is drawn at scale one: about a twentieth of the screen
     * per line at the default field of view, readable at a glance and small
     * enough to leave the shot alone.
     */
    private static final float SUBTITLE_SCALE = 0.2f;
    private static final float SUBTITLE_DISTANCE = 0.95f;
    /** Where a side line's centre sits: inside a 4:3 screen, clear of a centred line's middle. */
    private static final float SIDE_X = 0.42f;
    /** Between a subtitle stack and the bar, or the screen's edge. */
    private static final float MARGIN = 0.07f;
    /** An average glyph's advance, in pixels, for guessing where a line wraps. */
    private static final float GLYPH = 6f;

    /** The bottom bar: forty blank lines at this vertical scale; see the class comment. */
    private static final int BAR_LINES = 40;
    private static final float BAR_LINE_SCALE = 0.25f;
    private static final float BAR_HEIGHT = BAR_LINES * LINE_PIXELS * PIXEL * BAR_LINE_SCALE;
    private static final Component BAR_TEXT = Component.text(String.join("\n", Collections.nCopies(BAR_LINES, " ")));

    /** A title card: in front of the fade, so it reads over black. */
    private static final float TITLE_SCALE = 0.45f;
    private static final float TITLE_DISTANCE = 0.7f;

    private final WorldShot shot;
    private final int[] subtitleIds;
    private final int topBarId;
    private final int bottomBarId;
    private final int fadeId;
    private final int titleId;
    private final float barEdge;

    /** What each subtitle panel shows and how it was last drawn. */
    private final WorldShot.Subtitle[] showing;
    private final Panel[] drawn;
    private WorldShot.Title card;
    private int alpha = -1;

    CameraOverlay(WorldShot shot, CameraSink sink) {
        this.shot = shot;
        int lines = shot.subtitles().isEmpty() ? 0 : Math.max(1, shot.maxConcurrentSubtitles());
        this.subtitleIds = new int[lines];
        for (int index = 0; index < lines; index++) {
            subtitleIds[index] = sink.newEntityId();
        }
        this.topBarId = shot.letterbox() ? sink.newEntityId() : -1;
        this.bottomBarId = shot.letterbox() ? sink.newEntityId() : -1;
        this.fadeId = shot.fades() ? sink.newEntityId() : -1;
        this.titleId = shot.titles().isEmpty() ? -1 : sink.newEntityId();
        this.barEdge = (float) (HALF_VIEW * (1 - 2 * shot.barSize()));
        this.showing = new WorldShot.Subtitle[lines];
        this.drawn = new Panel[lines];
    }

    /** Every panel this overlay owns, for the mount and the destroy. */
    int[] ids() {
        List<Integer> ids = new ArrayList<>(8);
        for (int id : subtitleIds) {
            ids.add(id);
        }
        for (int id : new int[]{topBarId, bottomBarId, fadeId, titleId}) {
            if (id >= 0) {
                ids.add(id);
            }
        }
        return ids.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Spawns every panel at the camera and seats them on it. */
    void start(CameraSink sink, List<Player> viewers, int cameraId, CameraPath.Frame at) {
        Map<WorldShot.Subtitle, Panel> layout = layout(shot.subtitlesAt(0));
        assign(layout.keySet());
        for (int index = 0; index < subtitleIds.length; index++) {
            Panel panel = showing[index] != null ? layout.get(showing[index])
                    : place(new WorldShot.Subtitle(0, 0, null, Component.empty()), 0, 0);
            drawn[index] = panel;
            sink.spawnPanel(viewers, subtitleIds[index], at, panel);
        }
        if (topBarId >= 0) {
            sink.spawnPanel(viewers, topBarId, at, new Panel(Component.text(" "), BLACK, 0f, barEdge, 1f, 80f, 10f,
                    200, WorldShot.Align.CENTER, false));
            sink.spawnPanel(viewers, bottomBarId, at, new Panel(BAR_TEXT, BLACK, 0f, -barEdge - BAR_HEIGHT, 1f, 80f,
                    BAR_LINE_SCALE, 200, WorldShot.Align.CENTER, false));
        }
        if (fadeId >= 0) {
            alpha = shot.fadeAlpha(0);
            // Centred by being far larger than any view rather than by
            // knowing how tall the client draws a line.
            sink.spawnPanel(viewers, fadeId, at, new Panel(Component.text(" "), alpha << 24, 0f, -4f, 0.8f, 80f, 30f,
                    200, WorldShot.Align.CENTER, false));
        }
        if (titleId >= 0) {
            card = shot.titleAt(0);
            sink.spawnPanel(viewers, titleId, at, title(card));
        }
        int[] passengers = ids();
        if (passengers.length > 0) {
            sink.mount(viewers, cameraId, passengers);
        }
    }

    /**
     * Brings the subtitles, the title card and the fade to a moment of the
     * shot.
     *
     * <p>Only what changed is sent: a subtitle once per line (and its place
     * only when it moved), a fade in steps of a few percent. Called on every
     * camera sample.
     */
    void update(CameraSink sink, List<Player> viewers, long millis) {
        if (subtitleIds.length > 0) {
            Map<WorldShot.Subtitle, Panel> layout = layout(shot.subtitlesAt(millis));
            assign(layout.keySet());
            for (int index = 0; index < subtitleIds.length; index++) {
                WorldShot.Subtitle line = showing[index];
                Panel wanted = line == null ? drawn[index].with(Component.empty(), 0) : layout.get(line);
                Panel before = drawn[index];
                if (!wanted.sameShape(before)) {
                    sink.restyle(viewers, subtitleIds[index], wanted);
                } else {
                    if (!wanted.text().equals(before.text())) {
                        sink.text(viewers, subtitleIds[index], wanted.text());
                    }
                    if (wanted.background() != before.background()) {
                        sink.background(viewers, subtitleIds[index], wanted.background());
                    }
                }
                drawn[index] = wanted;
            }
        }
        if (titleId >= 0) {
            WorldShot.Title now = shot.titleAt(millis);
            if (now != card) {
                card = now;
                sink.restyle(viewers, titleId, title(now));
            }
        }
        if (fadeId >= 0) {
            int next = shot.fadeAlpha(millis);
            if (Math.abs(next - alpha) >= 8 || (next != alpha && (next == 0 || next == 255))) {
                alpha = next;
                sink.background(viewers, fadeId, next << 24);
            }
        }
    }

    /** Keeps each line on the panel it already had, and gives new lines the free ones. */
    private void assign(Collection<WorldShot.Subtitle> lines) {
        for (int index = 0; index < showing.length; index++) {
            if (showing[index] != null && !lines.contains(showing[index])) {
                showing[index] = null;
            }
        }
        for (WorldShot.Subtitle line : lines) {
            boolean placed = false;
            for (WorldShot.Subtitle on : showing) {
                if (on == line) {
                    placed = true;
                    break;
                }
            }
            for (int index = 0; !placed && index < showing.length; index++) {
                if (showing[index] == null) {
                    showing[index] = line;
                    placed = true;
                }
            }
        }
    }

    /**
     * Where every line on screen goes: lines sharing a position and a side
     * stack away from their base in slot order (an explicit {@code line}
     * first, then by start), each as tall as its guessed wrap.
     */
    Map<WorldShot.Subtitle, Panel> layout(List<WorldShot.Subtitle> lines) {
        Map<WorldShot.Subtitle, Panel> placed = new LinkedHashMap<>();
        Map<String, List<WorldShot.Subtitle>> stacks = new LinkedHashMap<>();
        for (WorldShot.Subtitle line : lines) {
            stacks.computeIfAbsent(line.position() + "/" + line.align(), key -> new ArrayList<>()).add(line);
        }
        for (List<WorldShot.Subtitle> stack : stacks.values()) {
            List<WorldShot.Subtitle> ordered = new ArrayList<>(stack);
            ordered.sort(Comparator.comparingInt((WorldShot.Subtitle line) -> line.line() < 0 ? Integer.MAX_VALUE : line.line())
                    .thenComparingLong(WorldShot.Subtitle::startMillis));
            float below = 0;
            for (WorldShot.Subtitle line : ordered) {
                float height = height(line);
                placed.put(line, place(line, below, height));
                below += height + (float) (style(line).gap() * 2 * HALF_VIEW);
            }
        }
        return placed;
    }

    /** One line's panel, {@code before} view units of earlier lines away from its base. */
    private Panel place(WorldShot.Subtitle line, float before, float height) {
        float inner = shot.letterbox() ? barEdge : HALF_VIEW * 0.88f;
        float offset = (float) (line.offset() * 2 * HALF_VIEW);
        float y = switch (line.position()) {
            case BOTTOM -> -inner + MARGIN + before;
            case TOP -> inner - MARGIN - before - height;
            case CENTER -> -height / 2 - before;
        };
        float x = switch (line.align()) {
            case LEFT -> -SIDE_X;
            case RIGHT -> SIDE_X;
            case CENTER -> 0f;
        };
        WorldShot.TextStyle style = style(line);
        float scale = (float) (SUBTITLE_SCALE * style.scale());
        boolean empty = line.text().equals(Component.empty()) && line.speaker() == null;
        return new Panel(empty ? Component.empty() : line.rendered(), empty ? 0 : style.background(), x, y + offset,
                SUBTITLE_DISTANCE, scale, scale, width(line), line.align(), style.shadow(), style.seeThrough());
    }

    private WorldShot.TextStyle style(WorldShot.Subtitle line) {
        return line.style() == null ? shot.subtitleStyle() : line.style();
    }

    /** A side line wraps at half the width, so two sides never meet in the middle. */
    private int width(WorldShot.Subtitle line) {
        int width = style(line).lineWidth();
        return line.align() == WorldShot.Align.CENTER ? width : Math.max(40, width / 2);
    }

    /** How tall a line is drawn, in view units: its wrapped rows plus the speaker's. */
    private float height(WorldShot.Subtitle line) {
        int width = width(line);
        int rows = rows(line.text(), width) + (line.speaker() == null ? 0 : rows(line.speaker(), width));
        return rows * LINE_PIXELS * PIXEL * (float) (SUBTITLE_SCALE * style(line).scale());
    }

    /** A guess at how many rows a component wraps to: the client does not say. */
    static int rows(Component text, int width) {
        String plain = PlainTextComponentSerializer.plainText().serialize(text);
        int rows = 0;
        for (String row : plain.split("\n", -1)) {
            rows += Math.max(1, (int) Math.ceil(row.length() * GLYPH / width));
        }
        return Math.max(1, rows);
    }

    private Panel title(WorldShot.Title showing) {
        WorldShot.TextStyle style = shot.titleStyle();
        Component text = showing == null ? Component.empty() : showing.text();
        float scale = (float) (TITLE_SCALE * style.scale());
        float height = rows(text, style.lineWidth()) * LINE_PIXELS * PIXEL * scale;
        return new Panel(text, showing == null ? 0 : style.background(), 0f, -height / 2, TITLE_DISTANCE, scale, scale,
                style.lineWidth(), WorldShot.Align.CENTER, style.shadow(), style.seeThrough());
    }
}
