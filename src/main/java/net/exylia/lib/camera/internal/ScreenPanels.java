package net.exylia.lib.camera.internal;

import net.exylia.lib.camera.CameraScreen;
import net.exylia.lib.camera.WorldShot;
import net.exylia.lib.text.Typewriter;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A {@link CameraScreen} for one viewer, riding whatever camera body it is
 * attached to.
 *
 * <p>The panels are the ones a world shot's overlay uses (see
 * {@link CameraOverlay} for the geometry), spawned for the viewer alone and
 * seated on the camera body, so they follow the view without a packet of their
 * own. The owner of the camera calls {@link #attach} whenever it spawns the
 * body, {@link #frame()} on every frame it moves it, and {@link #detach()}
 * when it takes it away. Callers change what is wanted from any thread; the
 * frame sends what changed.
 */
public final class ScreenPanels implements CameraScreen {

    private static final CameraPackets SINK = CameraPackets.INSTANCE;
    private static final Component BLANK = Component.text(" ");

    private static final float BAR_EDGE = (float) (CameraOverlay.HALF_VIEW * (1 - 2 * WorldShot.DEFAULT_BAR_SIZE));
    private static final float MARGIN = 0.07f;
    /** From the back: the bars at one block, the fill, the lines, the title. */
    private static final float FILL_DISTANCE = 0.8f;
    private static final float LINE_DISTANCE = 0.75f;
    private static final float TITLE_DISTANCE = 0.7f;
    private static final float LINE_SCALE = 0.2f;
    private static final float CAPTION_SCALE = 0.15f;
    private static final float TITLE_SCALE = 0.5f;
    private static final int LINE_WIDTH = 300;

    private static final int FILL = 0;
    private static final int TITLE = 1;
    private static final int LINE = 2;
    private static final int CAPTION = 3;
    private static final int TOP = 4;
    private static final int BOTTOM = 5;

    private final List<Player> to;
    private final int[] ids = new int[6];
    private int vehicle = -1;

    private int fillFrom;
    private int fillTo;
    private long fillStart;
    private long fillMillis;
    private int fillShown;

    private Component title;
    private double titleFrom = 1;
    private long titleMillis;
    private boolean titleDirty;
    private boolean landing;

    private Component line;
    private Typewriter writer;
    private int perSecond;
    private long lineSince;
    private Component lineShown = Component.empty();

    private Component caption;
    private boolean captionDirty;

    private boolean bars;
    private boolean barsDirty;

    public ScreenPanels(Player viewer) {
        this.to = List.of(viewer);
        for (int index = 0; index < ids.length; index++) {
            ids[index] = SINK.newEntityId();
        }
    }

    // ------------------------------------------------------------- the API

    @Override
    public synchronized void fill(int argb, long millis) {
        long now = System.currentTimeMillis();
        fillFrom = fillAt(now);
        fillTo = argb;
        fillStart = now;
        fillMillis = Math.max(0, millis);
    }

    @Override
    public synchronized void title(@Nullable Component text, double from, long millis) {
        title = text;
        titleFrom = Double.isFinite(from) && from > 0 ? from : 1;
        titleMillis = Math.max(0, millis);
        titleDirty = true;
    }

    @Override
    public synchronized void line(@Nullable Component text, int perSecond) {
        line = text;
        this.perSecond = Math.max(0, perSecond);
        writer = text == null || perSecond <= 0 ? null : Typewriter.of(text, LINE_WIDTH);
        lineSince = System.currentTimeMillis();
    }

    @Override
    public synchronized void caption(@Nullable Component text) {
        caption = text;
        captionDirty = true;
    }

    @Override
    public synchronized void letterbox(boolean shown) {
        if (bars == shown) return;
        bars = shown;
        barsDirty = true;
    }

    // ------------------------------------------------------- the camera side

    /** The body the panels ride now, or -1. */
    public synchronized int vehicle() {
        return vehicle;
    }

    /** Spawns every panel at the camera body, as things stand, and seats them on it. */
    public synchronized void attach(int vehicleId, double x, double y, double z) {
        detach();
        vehicle = vehicleId;
        CameraPath.Frame at = new CameraPath.Frame(x, y, z, 0f, 0f);
        long now = System.currentTimeMillis();
        fillShown = fillAt(now);
        lineShown = lineAt(now);
        landing = false;
        titleDirty = barsDirty = captionDirty = false;
        SINK.spawnPanel(to, ids[TOP], at, new CameraOverlay.Panel(BLANK, bars ? CameraOverlay.BLACK : 0,
                0f, BAR_EDGE, 1f, 80f, 10f, 200, WorldShot.Align.CENTER, false));
        SINK.spawnPanel(to, ids[BOTTOM], at, new CameraOverlay.Panel(CameraOverlay.BAR_TEXT, bars ? CameraOverlay.BLACK : 0,
                0f, -BAR_EDGE - CameraOverlay.BAR_HEIGHT, 1f, 80f, CameraOverlay.BAR_LINE_SCALE, 200,
                WorldShot.Align.CENTER, false));
        // Centred by being far larger than any view, as the overlay's fade is.
        SINK.spawnPanel(to, ids[FILL], at, new CameraOverlay.Panel(BLANK, fillShown, 0f, -4f, FILL_DISTANCE,
                80f, 30f, 200, WorldShot.Align.CENTER, false));
        SINK.spawnPanel(to, ids[LINE], at, linePanel(lineShown));
        SINK.spawnPanel(to, ids[CAPTION], at, captionPanel());
        SINK.spawnPanel(to, ids[TITLE], at, titlePanel(1));
        SINK.mount(to, vehicle, ids.clone());
    }

    /** Takes every panel off the viewer's screen; attached again with the next body. */
    public synchronized void detach() {
        if (vehicle < 0) return;
        vehicle = -1;
        for (int id : ids) {
            SINK.destroy(to, id);
        }
    }

    /** Sends what changed since the last frame. */
    public synchronized void frame() {
        if (vehicle < 0) return;
        long now = System.currentTimeMillis();
        int colour = fillAt(now);
        if (colour != fillShown) {
            fillShown = colour;
            SINK.background(to, ids[FILL], colour);
        }
        if (landing) {
            landing = false;
            SINK.glide(to, ids[TITLE], titlePanel(1), (int) Math.max(1, titleMillis / 50));
        }
        if (titleDirty) {
            titleDirty = false;
            boolean moves = title != null && titleFrom != 1 && titleMillis > 0;
            SINK.restyle(to, ids[TITLE], titlePanel(moves ? titleFrom : 1));
            landing = moves;
        }
        if (barsDirty) {
            barsDirty = false;
            int black = bars ? CameraOverlay.BLACK : 0;
            SINK.background(to, ids[TOP], black);
            SINK.background(to, ids[BOTTOM], black);
            SINK.restyle(to, ids[CAPTION], captionPanel());
            SINK.restyle(to, ids[LINE], linePanel(lineAt(now)));
        }
        if (captionDirty) {
            captionDirty = false;
            SINK.restyle(to, ids[CAPTION], captionPanel());
        }
        Component written = lineAt(now);
        if (!written.equals(lineShown)) {
            lineShown = written;
            SINK.text(to, ids[LINE], written);
        }
    }

    // ------------------------------------------------------------- drawing

    /** The fill's colour at a moment, each channel eased on its own. */
    private int fillAt(long now) {
        if (fillMillis <= 0 || now - fillStart >= fillMillis) return fillTo;
        double t = Math.max(0, now - fillStart) / (double) fillMillis;
        double eased = t * t * (3 - 2 * t);
        int colour = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int from = fillFrom >>> shift & 0xFF;
            int target = fillTo >>> shift & 0xFF;
            colour |= (int) Math.round(from + (target - from) * eased) << shift;
        }
        return colour;
    }

    private Component lineAt(long now) {
        if (line == null) return Component.empty();
        if (writer == null) return line;
        int shown = (int) Math.min(Integer.MAX_VALUE, Math.max(0, now - lineSince) * perSecond / 1000);
        return shown >= writer.length() ? writer.whole() : writer.frame(shown);
    }

    /** How far in from the top and the bottom of the view the lines sit. */
    private float inner() {
        return bars ? BAR_EDGE : CameraOverlay.HALF_VIEW * 0.88f;
    }

    private CameraOverlay.Panel linePanel(Component text) {
        return new CameraOverlay.Panel(text, 0, 0f, -inner() + MARGIN, LINE_DISTANCE, LINE_SCALE, LINE_SCALE,
                LINE_WIDTH, WorldShot.Align.CENTER, true);
    }

    private CameraOverlay.Panel captionPanel() {
        Component text = caption == null ? Component.empty() : caption;
        float height = CameraOverlay.LINE_PIXELS * CameraOverlay.PIXEL * CAPTION_SCALE;
        return new CameraOverlay.Panel(text, 0, 0f, inner() - MARGIN - height, LINE_DISTANCE, CAPTION_SCALE,
                CAPTION_SCALE, LINE_WIDTH, WorldShot.Align.CENTER, true);
    }

    /** The title card at {@code size} times its own size, centred whatever the size. */
    private CameraOverlay.Panel titlePanel(double size) {
        Component text = title == null ? Component.empty() : title;
        float scale = (float) (TITLE_SCALE * size);
        float height = CameraOverlay.rows(text, LINE_WIDTH) * CameraOverlay.LINE_PIXELS * CameraOverlay.PIXEL * scale;
        return new CameraOverlay.Panel(text, 0, 0f, -height / 2, TITLE_DISTANCE, scale, scale, LINE_WIDTH,
                WorldShot.Align.CENTER, true);
    }
}
