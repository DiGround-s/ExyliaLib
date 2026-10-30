package net.exylia.lib.spatial;

import net.exylia.lib.display.DisplayHandle;
import net.exylia.lib.display.DisplayKeyframe;
import net.exylia.lib.display.DisplayModel;
import net.exylia.lib.display.DisplayMotion;
import net.exylia.lib.display.Displays;
import net.exylia.lib.display.PluginDisplays;
import net.exylia.lib.display.Rotation;
import net.exylia.lib.npc.NpcHandle;
import net.exylia.lib.npc.NpcModel;
import net.exylia.lib.npc.NpcMotion;
import net.exylia.lib.npc.Npcs;
import net.exylia.lib.ragdoll.RagdollModel;
import net.exylia.lib.spatial.internal.Confirm;
import net.exylia.lib.spatial.internal.Drafts;
import net.exylia.lib.spatial.internal.Geometry;
import net.exylia.lib.stage.Stage;
import net.exylia.lib.stage.StageLook;
import net.exylia.lib.stage.StagePlane;
import net.exylia.lib.stage.StageTheme;
import net.exylia.lib.text.Text;
import net.exylia.lib.text.Values;
import net.exylia.lib.util.sequence.SequenceRun;
import net.exylia.lib.util.sequence.SequenceTarget;
import net.exylia.lib.util.sequence.Sequences;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntConsumer;

/**
 * One open spatial menu: draws its layout on a {@link Stage} in front of the viewer, keeps what each
 * list shows so a redraw only touches what changed, and answers the viewer's pointer. Used from the
 * viewer's thread.
 */
final class MenuSession implements SpatialContext {

    /** The longest a menu stays drawn; a menu nobody closes still goes by itself. */
    static final long LIFE = 30 * 60_000L;
    private static final double EYES = 1.62;
    private static final long HOVER_MILLIS = 120L;
    private static final long GLIDE_MILLIS = 180L;
    /** How long a fresh preview body waits for its skin's picture before it plays anyway. */
    private static final long SKIN_WAIT_MILLIS = 3000L;

    /** How long a mannequin is gone for an effect with no length, an endless one or a very long one. */
    private static final long VANISH_FALLBACK_MILLIS = 3000L;
    private static final long VANISH_MAX_MILLIS = 10_000L;

    final SpatialMenus menus;
    final SpatialMenu definition;
    final Player viewer;
    final Location anchor;
    final double distance;
    SpatialLayout layout;
    SpatialTheme theme;
    private final PluginDisplays displays;
    private Stage stage;
    StagePlane board;
    final SpatialPointer pointer;
    /** Every piece drawn, so a close can deflate them all. */
    private final List<DisplayHandle> pieces = new ArrayList<>();
    private final List<Part> parts = new ArrayList<>();
    private final Map<String, Object> state = new HashMap<>();
    private final Map<String, Integer> pages = new HashMap<>();
    private final Map<String, String> selected = new HashMap<>();
    private final Map<String, String> hovers = new HashMap<>();
    /** Drafted values of keys that name no source; a source's draft is its selection. */
    private final Drafts drafts = new Drafts();
    /**
     * The plugin's values read once for a whole redraw (every label of it fills from the same reading),
     * {@code null} between redraws.
     */
    private @Nullable Values pass;
    /** A click waiting for its second, confirming one. */
    private final Confirm confirm = new Confirm();
    /** The crosshair left an entry: the panels' words go back to the chosen one on the next poll. */
    private boolean wordsStale;
    private @Nullable PreviewPart preview;
    private @Nullable Slots carousel;
    /** Where pieces drawn during a carousel page turn come in from, in the world's axes; {@code null} otherwise. */
    private double @Nullable [] slideFrom;
    @Nullable EditMode edit;
    private boolean closed;

    MenuSession(SpatialMenus menus, SpatialMenu definition, SpatialLayout layout, Player viewer, Location anchor,
                double distance, SpatialPointer pointer) {
        this.menus = menus;
        this.definition = definition;
        this.layout = layout;
        this.theme = layout.theme();
        this.viewer = viewer;
        this.anchor = anchor;
        this.distance = distance;
        this.displays = Displays.of(menus.plugin());
        this.pointer = pointer;
    }

    // ── Context ───────────────────────────────────────────────────────────

    @Override
    public @NotNull Player viewer() {
        return viewer;
    }

    @Override
    public @NotNull String menu() {
        return definition.id();
    }

    @Override
    public @Nullable Object get(@NotNull String key) {
        return state.get(key);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> @NotNull T get(@NotNull String key, @NotNull T fallback) {
        Object value = state.get(key);
        return fallback.getClass().isInstance(value) ? (T) value : fallback;
    }

    @Override
    public void set(@NotNull String key, @Nullable Object value) {
        if (value == null) state.remove(key);
        else state.put(key, value);
    }

    @Override
    public int page(@NotNull String source) {
        return pages.getOrDefault(source, 0);
    }

    @Override
    public void page(@NotNull String source, int page) {
        pages.put(source, page);
        refresh();
    }

    @Override
    public @Nullable String selected(@NotNull String source) {
        return selected.get(source);
    }

    @Override
    public void select(@NotNull String source, @Nullable String entry) {
        if (entry == null) selected.remove(source);
        else selected.put(source, entry);
    }

    @Override
    public void refresh() {
        if (closed) return;
        inPass(() -> {
            for (Part part : parts) part.refresh();
        });
        if (preview != null) preview.update(definition.preview == null ? SpatialPreview.none() : definition.preview.apply(this));
    }

    /** Runs a redraw with the plugin's values read once for it. */
    private void inPass(Runnable redraw) {
        if (pass != null || definition.values == null) {
            redraw.run();
            return;
        }
        pass = definition.values.apply(this);
        try {
            redraw.run();
        } finally {
            pass = null;
        }
    }

    @Override
    public void replay() {
        if (!closed && preview != null) preview.replay();
    }

    @Override
    public void close() {
        menus.close(this, true);
    }

    // ── Drafts ────────────────────────────────────────────────────────────

    @Override
    public @Nullable String hovered(@NotNull String source) {
        return hovers.get(source);
    }

    private Map<String, ?> savedState() {
        Map<String, ?> saved = definition.saved == null ? null : definition.saved.apply(this);
        return saved == null ? Map.of() : saved;
    }

    @Override
    public @Nullable Object draft(@NotNull String key) {
        if (definition.sources.containsKey(key)) return selected(key);
        return drafts.get(key, savedState());
    }

    @Override
    public void draft(@NotNull String key, @Nullable Object value) {
        if (definition.sources.containsKey(key)) {
            select(key, value == null ? null : value.toString());
            return;
        }
        drafts.put(key, value, savedState());
    }

    @Override
    public @NotNull java.util.Set<String> changed() {
        return drafts.changed(savedState(), definition.sources::containsKey, selected::get);
    }

    @Override
    public boolean dirty() {
        return !changed().isEmpty();
    }

    @Override
    public boolean confirm(@NotNull String key) {
        return confirm.confirm(key, System.currentTimeMillis());
    }

    @Override
    public boolean confirming(@NotNull String key) {
        return confirm.confirming(key, System.currentTimeMillis());
    }

    @Override
    public void disarm() {
        confirm.disarm();
    }

    @Override
    public void revert() {
        if (closed) return;
        confirm.disarm();
        drafts.clear();
        savedState().forEach((key, value) -> {
            if (definition.sources.containsKey(key)) select(key, value == null ? null : value.toString());
        });
        refresh();
    }

    @Override
    public void save() {
        if (closed) return;
        if (definition.save == null) {
            StageLook.play(viewer, theme.errorSound());
            return;
        }
        String refusal = definition.save.apply(this);
        if (refusal != null) {
            StageLook.play(viewer, theme.errorSound());
            if (!refusal.isBlank()) viewer.sendMessage(Text.component(refusal));
        }
        drafts.prune(savedState());
        refresh();
    }

    /** The panels' words again, and nothing else: what the crosshair moving over a list changes. */
    private void refreshWords() {
        if (closed) return;
        inPass(() -> {
            for (Part part : parts) {
                if (part instanceof PanelPart panel) panel.refreshWords();
            }
        });
    }

    boolean isClosed() {
        return closed;
    }

    // ── Drawing ───────────────────────────────────────────────────────────

    /** Draws everything, popping it in panels first, then their content, then the carousel. */
    void open() {
        draw(true);
        StageLook.play(viewer, theme.openSound());
        if (definition.onOpen != null) definition.onOpen.accept(this);
        refresh();
    }

    private void draw(boolean animated) {
        board = StagePlane.inFrontOf(anchor, EYES, distance, layout.anchor().lift(), 1, 1, 1, 1);
        stage = new Stage(viewer, board, displays, LIFE, StageTheme.defaults().withSounds("", ""));
        int index = 0;
        for (Map.Entry<String, SpatialLayout.Panel> panel : layout.panels().entrySet()) {
            parts.add(new PanelPart(panel.getKey(), panel.getValue(), animated ? index++ * 70L : -1));
        }
        if (layout.carousel().present()) {
            carousel = new Slots(layout.carousel(), animated ? 420L : -1);
            parts.add(carousel);
            pointer.onScroll(notch -> turnPage(carousel, notch));
        } else {
            pointer.onScroll(null);
        }
        preview = new PreviewPart(animated ? 300L : -1);
        if (edit != null) edit.hotspots();
    }

    /** Takes everything down at once and draws {@code next} in its place, with nothing popping: edit mode. */
    void rebuild(SpatialLayout next) {
        if (closed) return;
        layout = next;
        theme = next.theme();
        takeDown(false);
        draw(false);
        refresh();
    }

    /** Takes everything down: deflating, or at once. */
    void takeDown(boolean deflate) {
        pointer.clear();
        if (preview != null) preview.remove();
        preview = null;
        carousel = null;
        parts.clear();
        List<DisplayHandle> drawn = List.copyOf(pieces);
        pieces.clear();
        Stage drawnOn = stage;
        if (!deflate) {
            drawn.forEach(DisplayHandle::remove);
            if (drawnOn != null) drawnOn.close();
            return;
        }
        for (DisplayHandle piece : drawn) {
            DisplayKeyframe here = piece.pose();
            if (here == null) continue;
            float tiny = 0.01f;
            piece.then(DisplayMotion.of(List.of(here.at(0),
                    new DisplayKeyframe(170, here.x(), here.y(), here.z(), here.rotation(), here.scaleX() * tiny,
                            here.scaleY() * tiny, here.scaleZ() * tiny)), 400));
        }
        menus.tasks().runAtEntityLater(viewer, 5L, () -> {
            drawn.forEach(DisplayHandle::remove);
            if (drawnOn != null) drawnOn.close();
        }, () -> drawn.forEach(DisplayHandle::remove));
    }

    void end() {
        // Nothing drafted was ever applied: leaving drops it, and the plugin may say so.
        boolean unsaved = definition.discard != null && dirty();
        closed = true;
        if (unsaved) definition.discard.accept(this);
        drafts.clear();
        if (definition.onClose != null) definition.onClose.accept(this);
    }

    void tick() {
        // A confirmation nobody gave: the button reads its own words again.
        if (confirm.expired(System.currentTimeMillis())) wordsStale = true;
        if (wordsStale) {
            wordsStale = false;
            refreshWords();
        }
        if (preview != null) preview.keep();
    }

    // ── Pieces ────────────────────────────────────────────────────────────

    /** A delay meaning "drawn again because its words changed": a soft re-pop instead of the entry pop. */
    private static final long REPOP = -2L;
    /** The display context an icon is drawn in: the GUI's, so every item looks like its inventory icon. */
    private static final int GUI_CONTEXT = 6;
    /** A plinth, in its slot's own terms: its centre's height, its size, and its top. */
    private static final double PLINTH_UP = -0.17;
    private static final double PLINTH_SIZE = 0.44;
    private static final double PLINTH_HEIGHT = 0.08;
    /** The icon's centre over the plinth. */
    private static final double ICON_UP = 0.07;
    private static final double CAROUSEL_ICON = 0.32;
    /**
     * A plinth's label: its top under the plinth's bottom (-0.21), and its face in front of the plinth's
     * front face (0.22), so seen from the eyes no part of the plinth is ever before it.
     */
    private static final double LABEL_TOP = -0.25;
    private static final double LABEL_FORWARD = 0.31;

    private Location eyes() {
        return anchor.clone().add(0, EYES, 0);
    }

    /**
     * A pose: popping in after {@code delay}, standing still at once when it is {@code -1}, or popping
     * softly when it is {@link #REPOP}. During a page turn it comes in from {@link #slideFrom}.
     */
    private DisplayMotion motion(Rotation rotation, double x, double y, double z, long delay) {
        if (delay == REPOP) return soft(rotation, x, y, z);
        if (delay < 0 && slideFrom == null) return StageLook.still(rotation, x, y, z, LIFE);
        return pop(rotation, x, y, z, Math.max(0, delay), slideFrom);
    }

    /** Waits unseen, then pops in to its size, gliding in from {@code from} (world axes) when given. */
    private static DisplayMotion pop(Rotation rotation, double x, double y, double z, long delay, double @Nullable [] from) {
        float tiny = 0.01f;
        float dx = from == null ? 0f : (float) from[0];
        float dy = from == null ? 0f : (float) from[1];
        float dz = from == null ? 0f : (float) from[2];
        float sx = (float) x;
        float sy = (float) y;
        float sz = (float) z;
        return DisplayMotion.of(List.of(
                new DisplayKeyframe(0L, dx, dy, dz, rotation, tiny, tiny, tiny),
                new DisplayKeyframe(delay, dx, dy, dz, rotation, tiny, tiny, tiny),
                new DisplayKeyframe(delay + 150L, dx * 0.2f, dy * 0.2f, dz * 0.2f, rotation, sx * 1.08f, sy * 1.08f, sz * 1.08f),
                new DisplayKeyframe(delay + 280L, 0f, 0f, 0f, rotation, sx, sy, sz)), LIFE);
    }

    /** A line whose words changed: back from nine tenths of its size, quickly, so it reads as a refresh. */
    private static DisplayMotion soft(Rotation rotation, double x, double y, double z) {
        float k = 0.9f;
        return DisplayMotion.of(List.of(
                new DisplayKeyframe(0L, 0f, 0f, 0f, rotation, (float) x * k, (float) y * k, (float) z * k),
                new DisplayKeyframe(170L, 0f, 0f, 0f, rotation, (float) x, (float) y, (float) z)), LIFE);
    }

    private @Nullable DisplayHandle show(DisplayModel model, DisplayMotion motion, Location at) {
        DisplayHandle handle = stage.show(model, motion, at);
        if (handle != null) pieces.add(handle);
        return handle;
    }

    /** A sheet of glass, a plate or a bar: a text display's background, centred on {@code at}. */
    private @Nullable DisplayHandle sheet(Location at, Rotation rotation, double width, double height, int argb, long delay) {
        DisplayMotion motion = delay == REPOP || (delay < 0 && slideFrom == null)
                ? DisplayMotion.of(List.of(Geometry.glass(0, rotation, width, height, 1, 0, 0, 0)), LIFE)
                : DisplayMotion.of(Geometry.glassPop(rotation, width, height, Math.max(0, delay), slideFrom), LIFE);
        // Not drawn through the stage, which bares every text display: this one is its background.
        DisplayHandle handle = displays.show(sheetModel(argb), motion, at, List.of(viewer));
        if (handle != null) pieces.add(handle);
        return handle;
    }

    private static DisplayModel sheetModel(int argb) {
        return DisplayModel.text(Component.text(Geometry.GLASS_TEXT)).billboard("FIXED").light(15)
                .background(argb).seeThrough(false).textShadow(false).lineWidth(1000);
    }

    /** A line fitted to its room: its words and its scale. */
    private record Fitted(Component text, String raw, double scale) {
        double width() {
            return Geometry.visibleWidth(raw) * scale;
        }
    }

    /**
     * Fits a line ({@link #fill filled} already) into {@code room} blocks at up to {@code max}, never
     * below the size the theme's {@code min-glyph-degrees} reads from the eyes at {@code at}; a line
     * too long at that size is cut with an ellipsis. Titles and buttons ({@code body} false) follow the
     * server's small-text setting; body lines are drawn as written unless the theme says otherwise.
     */
    private Fitted fit(String filled, boolean body, double room, double max, Location at) {
        boolean asWritten = body && !theme.smallCapsBody();
        boolean small = !asWritten && net.exylia.lib.text.internal.SmallText.enabled();
        double floor = Geometry.readableScale(at.distance(eyes()), theme.minGlyphDegrees(),
                small ? Geometry.SMALL_CAPITAL_PIXELS : Geometry.CAPITAL_PIXELS);
        double width = Math.max(0.18, Geometry.visibleWidth(filled));
        double scale = Math.max(floor, Math.min(max, room / width));
        String shown = width * scale > room + 1e-6 ? Geometry.truncate(filled, room / scale) : filled;
        return new Fitted(asWritten ? Text.verbatim(shown) : Text.component(shown), shown, scale);
    }

    /** A fitted line centred on a point of a plane. */
    private @Nullable DisplayHandle text(Fitted line, StagePlane plane, Rotation rotation, double across, double up,
                                         double forward, boolean shadow, long delay) {
        if (line.raw().isBlank()) return null;
        Location at = plane.point(across, up - Stage.TEXT_HEIGHT * line.scale() / 2, forward);
        return show(Stage.textModel(line.text()).background(0).textShadow(shadow).lineWidth(1000),
                motion(rotation, line.scale(), line.scale(), line.scale(), delay), at);
    }

    /** A line fitted into {@code room} and centred on a point of a plane. */
    private @Nullable DisplayHandle write(String filled, boolean body, StagePlane plane, Rotation rotation, double across,
                                          double up, double forward, double room, double max, boolean shadow, long delay) {
        return text(fit(filled, body, room, max, plane.point(across, up, forward)), plane, rotation, across, up, forward,
                shadow, delay);
    }

    /** A line fitted into {@code room} whose left edge is {@code left}. */
    private @Nullable DisplayHandle writeFrom(String filled, boolean body, StagePlane plane, Rotation rotation, double left,
                                              double up, double forward, double room, double max, long delay) {
        Fitted line = fit(filled, body, room, max, plane.point(left + room / 2, up, forward));
        return text(line, plane, rotation, left + line.width() / 2, up, forward, false, delay);
    }

    /** A line fitted into {@code room} whose right edge is {@code right}. */
    private @Nullable DisplayHandle writeTo(String filled, boolean body, StagePlane plane, Rotation rotation, double right,
                                            double up, double forward, double room, double max, long delay) {
        Fitted line = fit(filled, body, room, max, plane.point(right - room / 2, up, forward));
        return text(line, plane, rotation, right - line.width() / 2, up, forward, false, delay);
    }

    /** A line with the plugin's values and then the menu's state filled in. */
    String fill(String raw) {
        Values own = pass != null ? pass : definition.values == null ? null : definition.values.apply(this);
        String filled = own == null ? raw : own.apply(raw);
        Values values = Values.of();
        state.forEach(values::put);
        return values.apply(filled);
    }

    /** A theme line about one entry: its {@code %name%}, {@code %subtitle%} and {@code %price%}, then {@link #fill}. */
    private String fill(String raw, SpatialEntry entry) {
        return fill(raw.replace("%name%", entry.name()).replace("%subtitle%", entry.subtitle())
                .replace("%price%", entry.price() == null ? "" : entry.price()));
    }

    /** Takes one piece down before the rest. */
    void drop(@Nullable DisplayHandle handle) {
        if (handle == null) return;
        stage.remove(handle);
        pieces.remove(handle);
    }

    /** Sends pieces off sideways, shrinking, and takes them down once they are gone: a page turning. */
    private void slideOut(List<DisplayHandle> leaving, double[] towards) {
        List<DisplayHandle> going = new ArrayList<>();
        for (DisplayHandle piece : leaving) {
            DisplayKeyframe here = piece.pose();
            pieces.remove(piece);
            if (here == null) {
                stage.remove(piece);
                continue;
            }
            float tiny = 0.01f;
            piece.then(DisplayMotion.of(List.of(here.at(0), new DisplayKeyframe(200L, here.x() + (float) towards[0],
                    here.y() + (float) towards[1], here.z() + (float) towards[2], here.rotation(),
                    here.scaleX() * tiny, here.scaleY() * tiny, here.scaleZ() * tiny)), 400L));
            going.add(piece);
        }
        if (going.isEmpty()) return;
        Stage drawnOn = stage;
        menus.tasks().runAtEntityLater(viewer, 5L, () -> going.forEach(drawnOn::remove),
                () -> going.forEach(DisplayHandle::remove));
    }

    private int colour(String token, int fallback) {
        return StageLook.rgb(token, fallback);
    }

    private int glassColour() {
        return Geometry.mix(0x000000, colour(theme.glassTint(), 0x8A51C4), theme.glassTintStrength());
    }

    private int idlePlate() {
        return Geometry.mix(0x000000, colour(theme.plateIdle(), 0x8A51C4), theme.glassTintStrength() * 1.6);
    }

    /** A plinth's plate: dark at rest, warmed towards the hover or the chosen colour, never a flat bright sheet. */
    private int plateColour(boolean hovered, boolean chosen) {
        if (chosen) return Geometry.mix(idlePlate(), colour(theme.plateSelected(), 0xFFD700), 0.32);
        if (hovered) return Geometry.mix(idlePlate(), colour(theme.plateHover(), 0xAA76DE), 0.35);
        return idlePlate();
    }

    private static Rotation facing(StagePlane plane) {
        return StageLook.facing(plane);
    }

    private static Rotation flat(Rotation facing) {
        return Rotation.around(Rotation.Axis.X, -Math.PI / 2).then(facing);
    }

    private static double[] offset(StagePlane plane, double across, double up, double forward) {
        Location from = plane.point(0, 0, 0);
        Location to = plane.point(across, up, forward);
        return new double[]{to.getX() - from.getX(), to.getY() - from.getY(), to.getZ() - from.getZ()};
    }

    private void turnPage(Slots slots, int notch) {
        if (slots == null) return;
        confirm.disarm();
        StageLook.play(viewer, theme.pageSound());
        pages.put(slots.source, page(slots.source) + notch);
        slots.step = notch;
        refresh();
        slots.step = 0;
    }

    private void act(String action) {
        // Another button: whatever waited for a second click is no longer what they are doing.
        if (!confirm.confirming(action, System.currentTimeMillis())) confirm.disarm();
        var run = definition.actions.get(action);
        if (run != null) {
            run.accept(this);
            return;
        }
        switch (action) {
            case SAVE -> save();
            case REVERT -> revert();
            default -> StageLook.play(viewer, theme.errorSound());
        }
    }

    /** The layout actions every menu answers when the plugin gives them nothing of its own. */
    static final String SAVE = "save";
    static final String REVERT = "revert";

    /** How an icon moves: resting, under the crosshair, or chosen. */
    private enum Pose { IDLE, HOVER, CHOSEN }

    /**
     * An icon's motion: a gentle sway and bob on a plinth (a full, quicker turn once chosen), a slow
     * bob in a panel, lifted and grown under the crosshair. It pops in after {@code delay}, or glides
     * from wherever it is when {@code delay} is {@link #REPOP} (handed to {@code then}).
     */
    private DisplayMotion iconMotion(boolean plinth, int slot, Rotation turn, double size, double depth, Pose pose,
                                     double[] towards, long delay) {
        double grow = pose == Pose.IDLE ? 1.0 : pose == Pose.HOVER ? theme.hoverGrow() : 1.1;
        double lift = !plinth ? 0 : pose == Pose.CHOSEN ? 0.07 : pose == Pose.HOVER ? 0.035 : 0;
        double ahead = plinth || pose == Pose.IDLE ? 0 : 0.02;
        float sx = (float) (size * grow);
        float sz = (float) (depth * grow);
        // Every slot a little out of step, so a row does not move as one.
        double phase = slot * 0.37;
        List<DisplayKeyframe> loop = new ArrayList<>();
        long cycle;
        if (plinth && pose == Pose.CHOSEN) {
            cycle = 2400L;
            for (int i = 0; i <= 8; i++) {
                loop.add(frame(cycle * i / 8, towards, ahead, lift, Rotation.around(Rotation.Axis.Y, Math.PI * 2 * i / 8).then(turn), sx, sz));
            }
        } else if (plinth) {
            cycle = 3600L;
            for (int i = 0; i <= 6; i++) {
                double wave = Math.sin(Math.PI * 2 * (i / 6.0 + phase));
                loop.add(frame(cycle * i / 6, towards, ahead, lift + 0.012 * wave,
                        Rotation.around(Rotation.Axis.Y, Math.toRadians(16) * wave).then(turn), sx, sz));
            }
        } else {
            cycle = 4000L;
            for (int i = 0; i <= 2; i++) {
                double wave = i == 1 ? 1 : 0;
                loop.add(frame(cycle * i / 2, towards, ahead, 0.008 * wave, turn, sx, sz));
            }
        }
        List<DisplayKeyframe> frames = new ArrayList<>();
        long lead;
        if (delay == REPOP) {
            lead = 160L;
            frames.add(loop.getFirst().at(0));
        } else if (delay < 0 && slideFrom == null) {
            lead = 0L;
        } else {
            long wait = Math.max(0, delay);
            float tiny = 0.01f;
            float dx = slideFrom == null ? 0f : (float) slideFrom[0];
            float dy = slideFrom == null ? 0f : (float) slideFrom[1];
            float dz = slideFrom == null ? 0f : (float) slideFrom[2];
            DisplayKeyframe first = loop.getFirst();
            frames.add(new DisplayKeyframe(0L, first.x() + dx, first.y() + dy, first.z() + dz, first.rotation(), tiny, tiny, tiny));
            frames.add(new DisplayKeyframe(wait, first.x() + dx, first.y() + dy, first.z() + dz, first.rotation(), tiny, tiny, tiny));
            lead = wait + 280L;
        }
        for (DisplayKeyframe key : loop) frames.add(key.at(lead + key.atMillis()));
        return DisplayMotion.of(frames, LIFE).looping(lead, lead + cycle, 1, 1);
    }

    private static DisplayKeyframe frame(long at, double[] towards, double ahead, double lift, Rotation rotation, float size, float depth) {
        return new DisplayKeyframe(at, (float) (towards[0] * ahead), (float) (towards[1] * ahead + lift),
                (float) (towards[2] * ahead), rotation, size, size, depth);
    }

    // ── Parts ─────────────────────────────────────────────────────────────

    private interface Part {
        void refresh();
    }

    /** One panel: glass, accent, title, hint, what it shows and its footer. */
    private final class PanelPart implements Part {

        final String name;
        final SpatialLayout.Panel panel;
        final StagePlane plane;
        final Rotation rotation;
        private final List<Label> labels = new ArrayList<>();
        private final List<Button> buttons = new ArrayList<>();
        private @Nullable Slots slots;
        private @Nullable Column column;

        PanelPart(String name, SpatialLayout.Panel panel, long delay) {
            this.name = name;
            this.panel = panel;
            this.plane = Geometry.panel(anchor.getWorld(), board, panel.across(), panel.up(), panel.forward(), panel.turn());
            this.rotation = facing(plane);
            double w = panel.width();
            double h = panel.height();
            long inner = delay < 0 ? -1 : delay + 180;
            int glass = glassColour();
            double reach = theme.backingReach();
            sheet(plane.point(0, 0, -0.02), rotation, w + reach * 2, h + reach * 2,
                    Geometry.argb(theme.backingAlpha(), Geometry.mix(0x000000, glass, 0.5)), delay);
            sheet(plane.point(0, 0, 0), rotation, w, h, Geometry.argb(theme.glassAlpha(), glass), delay);
            double bar = theme.accentHeight();
            sheet(plane.point(0, h / 2 - bar / 2, 0.004), rotation, w, bar,
                    Geometry.argb(255, colour(theme.accent(), 0x8A51C4)), delay < 0 ? -1 : delay + 90);
            SpatialLayout.Content content = panel.content();
            boolean bare = content.type() == SpatialLayout.Content.Type.NONE;
            boolean titled = !panel.title().isBlank() && !bare;
            boolean hinted = !panel.hint().isBlank() && !bare;
            if (bare) {
                // A header: its title in the middle, its hint at the right end.
                if (!panel.title().isBlank()) {
                    labels.add(new Label(panel.title(), (title, at) -> write(title, false, plane, rotation, 0, 0, 0.012,
                            w * 0.6, theme.titleScale(), true, at), inner));
                }
                if (!panel.hint().isBlank()) {
                    labels.add(new Label(panel.hint(), (hint, at) -> writeTo(hint, true, plane, rotation, w / 2 - 0.12, 0,
                            0.012, w * 0.2, theme.smallScale(), at), inner));
                }
            } else {
                if (titled) {
                    labels.add(new Label(panel.title(), (title, at) -> write(title, false, plane, rotation, 0, h / 2 - 0.16,
                            0.012, w * 0.86, theme.titleScale() * 0.8, true, at), inner));
                }
                if (hinted) {
                    labels.add(new Label(panel.hint(), (hint, at) -> write(hint, true, plane, rotation, 0,
                            h / 2 - (titled ? 0.3 : 0.14), 0.012, w * 0.86, theme.smallScale(), false, at), inner));
                }
            }
            double band = titled ? (hinted ? 0.4 : 0.3) : hinted ? 0.24 : 0.08;
            double[] area = Geometry.content(h, band, panel.footer().present());
            switch (content.type()) {
                case TEXT -> {
                    int count = content.lines().size();
                    double pitch = Math.min(area[1] / Math.max(1, count), 0.16);
                    for (int i = 0; i < count; i++) {
                        double up = area[0] + ((count - 1) / 2.0 - i) * pitch;
                        double scale = i == 0 ? theme.textScale() * 1.15 : theme.textScale();
                        labels.add(new Label(content.lines().get(i), (line, at) -> write(line, true, plane, rotation, 0, up,
                                0.012, w * 0.9, scale, true, at), inner < 0 ? -1 : inner + i * 40L));
                    }
                }
                case LIST -> slots = new Slots(Kind.ROW, content.source(), this, area, inner);
                case GRID -> slots = new Slots(Kind.CELL, content.source(), this, area, inner);
                case BUTTON -> buttons.add(new Button(plane, rotation, 0, area[0], w - 0.24, Math.min(area[1], 0.3),
                        content.label(), content.action(), inner));
                case BUTTONS -> column = new Column(this, content.buttons(), area, inner);
                case NONE -> { }
            }
            if (panel.footer().present()) {
                buttons.add(new Button(plane, rotation, 0, -h / 2 + 0.16, w - 0.2, 0.2, panel.footer().label(),
                        panel.footer().action(), inner < 0 ? -1 : inner + 120));
            }
        }

        void refreshWords() {
            labels.forEach(Label::refresh);
            buttons.forEach(Button::refresh);
            if (column != null) column.refresh();
        }

        @Override
        public void refresh() {
            refreshWords();
            if (slots != null) slots.refresh();
        }
    }

    /** Draws a label's words ({@link #fill filled}), popping in after {@code delay} (see {@link #motion}). */
    private interface Drawer {
        @Nullable DisplayHandle draw(String filled, long delay);
    }

    /**
     * A TEXT line, a title, a hint or a button's label: drawn again, fitted to its room and softly
     * re-popped, only when its placeholders read something else.
     */
    private final class Label {
        final String raw;
        final Drawer drawer;
        @Nullable DisplayHandle handle;
        String shown;

        Label(String raw, Drawer drawer, long delay) {
            this.raw = raw;
            this.drawer = drawer;
            this.shown = fill(raw);
            this.handle = drawer.draw(shown, delay);
        }

        void refresh() {
            String now = fill(raw);
            if (now.equals(shown)) return;
            shown = now;
            drop(handle);
            handle = drawer.draw(now, REPOP);
        }
    }

    private static String plain(Component text) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(text);
    }

    /**
     * A button: an opaque plate with its label, lighting up under the crosshair. A SAVE button (the
     * action {@code save}) is warmed towards the chosen colour while there is something to save, and
     * calm otherwise.
     */
    private final class Button {
        final @Nullable DisplayHandle plate;
        final Label label;
        final String action;
        private @Nullable Hotspot hotspot;
        private boolean lit;
        private boolean hot;

        Button(StagePlane plane, Rotation rotation, double across, double up, double width, double height, String label,
               String action, long delay) {
            this.action = action;
            this.hot = hot();
            plate = sheet(plane.point(across, up, 0.008), rotation, width, height, Geometry.argb(255, colour()), delay);
            this.label = new Label(label, (text, at) -> write(text, false, plane, rotation, across, up, 0.016,
                    width * 0.86, theme.textScale(), true, at), delay < 0 ? -1 : delay + 60);
            if (edit != null) return;
            hotspot = pointer.add(Hotspot.rect(plane, across, up, width, height).priority(2)
                    .onHover(on -> {
                        lit = on;
                        restyle();
                    })
                    .onPress(click -> act(action)));
        }

        /** Warm while there is something to save, or while its action waits for a confirming click. */
        private boolean hot() {
            return SAVE.equals(action) && dirty() || confirm.confirming(action, System.currentTimeMillis());
        }

        /** Takes the button down: a column closing up round a button that has no words now. */
        void remove() {
            drop(plate);
            drop(label.handle);
            pointer.remove(hotspot);
        }

        private int colour() {
            if (hot) return Geometry.mix(glassColour(), MenuSession.this.colour(theme.plateSelected(), 0xFFD700), lit ? 0.5 : 0.36);
            return Geometry.mix(glassColour(), MenuSession.this.colour(theme.plateHover(), 0xAA76DE), lit ? 0.5 : 0.2);
        }

        private void restyle() {
            if (plate != null) plate.restyle(sheetModel(Geometry.argb(255, colour())));
        }

        void refresh() {
            label.refresh();
            boolean now = hot();
            if (now == hot) return;
            hot = now;
            restyle();
        }
    }

    /**
     * A {@code BUTTONS} content: its buttons stacked top to bottom. A button whose label reads blank is
     * left out, and when which ones read something changes the column is drawn again, closed up.
     */
    private final class Column {
        private final PanelPart panel;
        private final List<SpatialLayout.Footer> all;
        private final double[] area;
        private final List<Button> drawn = new ArrayList<>();
        private List<Integer> shown = List.of();

        Column(PanelPart panel, List<SpatialLayout.Footer> all, double[] area, long delay) {
            this.panel = panel;
            this.all = all;
            this.area = area;
            draw(visible(), delay);
        }

        private List<Integer> visible() {
            List<Integer> visible = new ArrayList<>();
            for (int i = 0; i < all.size(); i++) {
                if (!plain(Text.component(fill(all.get(i).label()))).isBlank()) visible.add(i);
            }
            return visible;
        }

        private void draw(List<Integer> visible, long delay) {
            shown = visible;
            int count = visible.size();
            if (count == 0) return;
            double width = panel.panel.width() - 0.24;
            double[] ups = Geometry.stack(area[0], area[1], count);
            double height = Geometry.stackHeight(area[1], count);
            for (int i = 0; i < count; i++) {
                SpatialLayout.Footer button = all.get(visible.get(i));
                drawn.add(new Button(panel.plane, panel.rotation, 0, ups[i], width, height, button.label(),
                        button.action(), delay < 0 ? delay : delay + i * 50L));
            }
        }

        void refresh() {
            List<Integer> visible = visible();
            if (visible.equals(shown)) {
                drawn.forEach(Button::refresh);
                return;
            }
            drawn.forEach(Button::remove);
            drawn.clear();
            draw(visible, REPOP);
        }
    }

    /** How a list draws its entries. */
    private enum Kind { ROW, CELL, PEDESTAL }

    /** Where one slot stands. */
    private record Place(StagePlane plane, Rotation rotation, double across, double up, Location at) {
    }

    /** A LIST, a GRID or the carousel: a page of entries, each in its slot, redrawn only where it changed. */
    private final class Slots implements Part {

        final Kind kind;
        final String source;
        final int perPage;
        final List<Place> places = new ArrayList<>();
        final List<SpatialEntry> shown = new ArrayList<>();
        final List<List<DisplayHandle>> drawn = new ArrayList<>();
        final List<DisplayHandle> icons = new ArrayList<>();
        final List<DisplayHandle> plates = new ArrayList<>();
        final List<DisplayHandle> plinths = new ArrayList<>();
        final List<DisplayHandle> labels = new ArrayList<>();
        final List<DisplayKeyframe> labelRest = new ArrayList<>();
        final List<Pose> poses = new ArrayList<>();
        final List<Hotspot> spots = new ArrayList<>();
        final double slotWidth;
        final double slotHeight;
        final double iconScale;
        final StagePlane plane;
        final Rotation rotation;
        private @Nullable DisplayHandle selector;
        private @Nullable DisplayHandle empty;
        private @Nullable DisplayHandle pageLine;
        private @Nullable DisplayHandle caption;
        private String captionShown = "";
        private int chosen = -1;
        private int hovered = -1;
        private final double emptyUp;
        private final double pageUp;
        private final double captionUp;
        private final long delay;
        private boolean first = true;
        private int lastPage;
        /** The notch a page turn came from, for the side the next page slides in from; 0 otherwise. */
        int step;

        /** A LIST or GRID inside a panel's content area {centre up, height}. */
        Slots(Kind kind, String source, PanelPart panel, double[] area, long delay) {
            this.kind = kind;
            this.source = source;
            this.delay = delay;
            this.plane = panel.plane;
            this.rotation = panel.rotation;
            SpatialLayout.Content content = panel.panel.content();
            double width = panel.panel.width() - 0.14;
            if (kind == Kind.ROW) {
                int rows = content.rows();
                perPage = rows;
                slotWidth = width;
                slotHeight = area[1] / rows;
                iconScale = Math.min(0.24, slotHeight * 0.8);
                for (int i = 0; i < rows; i++) {
                    double up = area[0] + area[1] / 2 - (i + 0.5) * slotHeight;
                    places.add(new Place(plane, rotation, 0, up, plane.point(0, up, 0.01)));
                }
                captionUp = Double.NaN;
            } else {
                int columns = content.columns();
                int rows = content.rows();
                perPage = columns * rows;
                double gridHeight = area[1] - 0.16;
                double gridUp = area[0] + 0.08;
                slotWidth = width / columns;
                slotHeight = gridHeight / rows;
                iconScale = Math.min(0.28, Math.min(slotWidth, slotHeight) * 0.7);
                for (int i = 0; i < perPage; i++) {
                    double[] cell = Geometry.cell(i, columns, rows, width, gridHeight);
                    places.add(new Place(plane, rotation, cell[0], gridUp + cell[1], plane.point(cell[0], gridUp + cell[1], 0.01)));
                }
                captionUp = area[0] - area[1] / 2 + 0.07;
            }
            emptyUp = area[0];
            pageUp = area[0] - area[1] / 2 - 0.02;
            hotspots(panel.panel.width(), panel.panel.height());
        }

        /** The carousel: a row of plinths. */
        Slots(SpatialLayout.Carousel row, long delay) {
            this.kind = Kind.PEDESTAL;
            this.source = row.source();
            this.delay = delay;
            this.perPage = row.slots();
            this.plane = Geometry.panel(anchor.getWorld(), board, row.at().get(0), row.at().get(1), row.at().get(2), 0);
            this.rotation = facing(plane);
            this.slotWidth = Math.min(row.spacing() * 0.94, 0.7);
            this.slotHeight = 0.7;
            this.iconScale = CAROUSEL_ICON;
            for (int i = 0; i < perPage; i++) {
                double[] slot = Geometry.carousel(i, perPage, row.spacing(), row.centreGap(), row.curve());
                double depth = distance - row.at().get(2) - slot[1];
                StagePlane turned = Geometry.panel(anchor.getWorld(), board, row.at().get(0) + slot[0], row.at().get(1),
                        row.at().get(2) + slot[1], Geometry.facingTurn(row.at().get(0) + slot[0], depth));
                places.add(new Place(turned, facing(turned), slot[0], 0, turned.point(0, 0, 0)));
            }
            emptyUp = 0;
            pageUp = -0.34;
            captionUp = Double.NaN;
            hotspots(0, 0);
            double arrows = Math.min(0.42, Math.max(0.3, row.centreGap() / 2 - 0.12));
            arrow(-arrows, -1, theme.previousArrow());
            arrow(arrows, 1, theme.nextArrow());
        }

        private void arrow(double across, int step, String label) {
            write(fill(label), false, plane, rotation, across, pageUp, 0.01, 0.3, theme.textScale() * 1.2, true,
                    delay < 0 ? -1 : delay + 200);
            if (edit != null) return;
            pointer.add(Hotspot.rect(plane, across, pageUp, 0.26, 0.22).priority(2).silent(true)
                    .onPress(click -> turnPage(this, step)));
        }

        private void hotspots(double panelWidth, double panelHeight) {
            if (edit != null) return;
            IntConsumer scroll = notch -> turnPage(this, notch);
            if (kind != Kind.PEDESTAL) {
                // The whole panel pages its list under the wheel, not only its slots.
                pointer.add(Hotspot.rect(plane, 0, 0, panelWidth, panelHeight).priority(-1).silent(true).onScroll(scroll));
            }
            for (int i = 0; i < perPage; i++) {
                int slot = i;
                Place place = places.get(i);
                // A plinth answers from its item down to its label, on its own turned plane.
                Hotspot spot = kind == Kind.PEDESTAL
                        ? Hotspot.rect(place.plane(), 0, -0.13, slotWidth, 0.62)
                        : Hotspot.rect(plane, place.across(), place.up(), slotWidth, kind == Kind.ROW ? slotHeight * 0.96 : slotHeight);
                spot.priority(1)
                        .onHover(on -> hover(slot, on))
                        .onPress(click -> choose(slot))
                        .onScroll(kind == Kind.PEDESTAL ? null : scroll)
                        .enabled(false);
                spots.add(pointer.add(spot));
            }
        }

        @Override
        public void refresh() {
            var supplier = definition.sources.get(source);
            List<SpatialEntry> all = supplier == null ? List.of() : List.copyOf(supplier.apply(MenuSession.this));
            int pageCount = Geometry.pages(all.size(), perPage);
            int page = Geometry.wrap(page(source), pageCount);
            pages.put(source, page);
            List<SpatialEntry> now = Geometry.page(all, page, perPage);
            List<Integer> changed = Geometry.changed(shown, now, perPage);
            // A page turn on the carousel: the old page slides off one side, the new comes in from the other.
            boolean turning = kind == Kind.PEDESTAL && !first && page != lastPage;
            int side = step != 0 ? Integer.signum(step) : Integer.signum(page - lastPage);
            double[] across = offset(plane, 1, 0, 0);
            lastPage = page;
            if (turning) {
                List<Integer> order = new ArrayList<>(changed);
                if (side > 0) java.util.Collections.reverse(order);
                changed = order;
            }
            String id = selected.get(source);
            int wave = 0;
            for (int slot : changed) {
                if (turning) {
                    grow(slot);
                    slideOut(new ArrayList<>(drawn.get(slot)), scaled(across, -side * 0.55));
                    drawn.get(slot).clear();
                    icons.set(slot, null);
                    plates.set(slot, null);
                    plinths.set(slot, null);
                    labels.set(slot, null);
                    poses.set(slot, null);
                } else {
                    clear(slot);
                }
                SpatialEntry entry = slot < now.size() ? now.get(slot) : null;
                set(slot, entry);
                if (entry != null) {
                    long at = first ? (delay < 0 ? -1 : delay + slot * 45L) : wave++ * 35L;
                    slideFrom = turning ? scaled(across, side * 0.55) : null;
                    boolean isChosen = id != null ? entry.id().equals(id) : entry.state() == SpatialEntry.State.SELECTED;
                    draw(slot, entry, at, isChosen ? Pose.CHOSEN : slot == hovered ? Pose.HOVER : Pose.IDLE);
                    slideFrom = null;
                }
                if (slot < spots.size()) spots.get(slot).enabled(entry != null);
            }
            first = false;
            // Empty: one line that says so, in the middle.
            if (now.isEmpty() && empty == null) {
                empty = write(fill(theme.emptyLine()), true, plane, rotation, 0, emptyUp, 0.012,
                        kind == Kind.PEDESTAL ? 2.4 : slotWidth * 0.9, theme.smallScale(), false, -1);
            } else if (!now.isEmpty() && empty != null) {
                drop(empty);
                empty = null;
            }
            pageLine(page, pageCount);
            chosen(now);
            for (int slot : changed) style(slot);
            caption(now);
        }

        private double[] scaled(double[] v, double k) {
            return new double[]{v[0] * k, v[1] * k, v[2] * k};
        }

        private void set(int slot, @Nullable SpatialEntry entry) {
            while (shown.size() <= slot) shown.add(null);
            shown.set(slot, entry);
            while (shown.size() > 0 && shown.getLast() == null) shown.removeLast();
        }

        private SpatialEntry at(int slot) {
            return slot < shown.size() ? shown.get(slot) : null;
        }

        private void grow(int slot) {
            while (drawn.size() <= slot) {
                drawn.add(new ArrayList<>());
                icons.add(null);
                plates.add(null);
                plinths.add(null);
                labels.add(null);
                labelRest.add(null);
                poses.add(null);
            }
        }

        private void clear(int slot) {
            grow(slot);
            drawn.get(slot).forEach(MenuSession.this::drop);
            drawn.get(slot).clear();
            icons.set(slot, null);
            plates.set(slot, null);
            plinths.set(slot, null);
            labels.set(slot, null);
            poses.set(slot, null);
        }

        private void keep(int slot, @Nullable DisplayHandle handle) {
            if (handle != null) drawn.get(slot).add(handle);
        }

        private DisplayModel iconModel(SpatialEntry entry) {
            if (entry.model() != null) return entry.model();
            ItemStack item = entry.icon() == null ? new ItemStack(Material.BARRIER) : entry.icon();
            // The GUI's display context: a trident, a cake or a totem each look like their inventory
            // icon, at one size, centred, instead of a held model standing beside the plinth.
            return DisplayModel.item(item).held(GUI_CONTEXT).light(entry.locked() ? 7 : 15);
        }

        private void draw(int slot, SpatialEntry entry, long at, Pose pose) {
            Place place = places.get(slot);
            DisplayModel iconModel = iconModel(entry);
            String status = status(entry);
            double[] towards = offset(place.plane(), 0, 0, 1);
            switch (kind) {
                case ROW -> {
                    double left = -slotWidth / 2;
                    Location iconAt = place.plane().point(left + 0.05 + iconScale / 2, place.up(), 0.03);
                    DisplayHandle icon = show(iconModel, iconMotion(false, slot, place.rotation(), iconScale, iconScale * 0.3,
                            pose == Pose.CHOSEN ? Pose.IDLE : pose, towards, at), iconAt);
                    keep(slot, icon);
                    icons.set(slot, icon);
                    double textLeft = left + 0.1 + iconScale;
                    double room = slotWidth - (textLeft - left) - 0.04;
                    keep(slot, writeFrom(entry.name(), true, place.plane(), place.rotation(), textLeft,
                            place.up() + slotHeight * 0.2, 0.02, room, theme.textScale() * 0.85, at < 0 ? -1 : at + 40));
                    if (!status.isBlank()) {
                        keep(slot, writeFrom(status, true, place.plane(), place.rotation(), textLeft,
                                place.up() - slotHeight * 0.22, 0.02, room, theme.smallScale(), at < 0 ? -1 : at + 70));
                    }
                }
                case CELL -> {
                    DisplayHandle icon = show(iconModel, iconMotion(false, slot, place.rotation(), iconScale, iconScale * 0.3,
                                    pose == Pose.CHOSEN ? Pose.IDLE : pose, towards, at),
                            place.plane().point(place.across(), place.up() + 0.01, 0.03));
                    keep(slot, icon);
                    icons.set(slot, icon);
                    // A cell has no line of its own: its state is a marker in the icon's corner.
                    String marker = switch (entry.state()) {
                        case EQUIPPED -> theme.equippedMarker();
                        case LOCKED -> theme.lockedMarker();
                        default -> "";
                    };
                    if (marker != null && !marker.isBlank()) {
                        // One glyph: room enough that the readable size never cuts it to an ellipsis.
                        keep(slot, write(fill(marker, entry), true, place.plane(), place.rotation(),
                                place.across() + iconScale * 0.55, place.up() + iconScale * 0.4, 0.04, 0.3,
                                theme.smallScale(), true, at < 0 ? -1 : at + 60));
                    }
                }
                case PEDESTAL -> {
                    StagePlane own = place.plane();
                    Rotation turn = place.rotation();
                    DisplayHandle plinth = show(DisplayModel.block(StageLook.block(theme.pedestalBlock(), Material.POLISHED_BLACKSTONE)).light(15),
                            motion(turn, PLINTH_SIZE, PLINTH_HEIGHT, PLINTH_SIZE, at), own.point(0, PLINTH_UP, 0));
                    keep(slot, plinth);
                    plinths.set(slot, plinth);
                    DisplayHandle plate = sheet(own.point(0, PLINTH_UP + PLINTH_HEIGHT / 2 + 0.002, 0), flat(turn), 0.36, 0.36,
                            Geometry.argb(255, plateColour(false, false)), at < 0 ? -1 : at + 40);
                    keep(slot, plate);
                    plates.set(slot, plate);
                    DisplayHandle icon = show(iconModel, iconMotion(true, slot, turn, iconScale, iconScale, pose, towards,
                            at < 0 ? -1 : at + 80), own.point(0, ICON_UP, 0));
                    keep(slot, icon);
                    icons.set(slot, icon);
                    DisplayHandle label = plinthLabel(slot, entry, status, own, turn, at < 0 ? -1 : at + 100);
                    keep(slot, label);
                    labels.set(slot, label);
                }
            }
            poses.set(slot, pose);
        }

        /**
         * A plinth's name and state in one display, bare like the library's holograms (no backing, its
         * shadow for contrast), under and in front of the plinth: both lines at one size, fitted to the
         * slot or cut, never below the readable size.
         */
        private @Nullable DisplayHandle plinthLabel(int slot, SpatialEntry entry, String status, StagePlane own, Rotation turn,
                                                    long at) {
            double room = slotWidth - 0.04;
            Location where = own.point(0, LABEL_TOP - 0.06, LABEL_FORWARD);
            Fitted name = fit(entry.name(), true, room, theme.smallScale(), where);
            Fitted state = status.isBlank() ? null : fit(status, true, room, theme.smallScale(), where);
            double scale = state == null ? name.scale() : Math.max(name.scale(), state.scale());
            if (state != null && name.scale() < scale) name = fit(entry.name(), true, room, scale, where);
            String raw = Geometry.truncate(name.raw(), room / scale)
                    + (state == null ? "" : "\n" + Geometry.truncate(state.raw(), room / scale));
            Component words = theme.smallCapsBody() ? Text.component(raw) : Text.verbatim(raw);
            int lines = state == null ? 1 : 2;
            double height = Stage.TEXT_HEIGHT * scale * lines;
            float s = (float) scale;
            labelRest.set(slot, new DisplayKeyframe(0L, 0f, 0f, 0f, turn, s, s, s));
            DisplayModel model = Stage.textModel(words).background(0).seeThrough(false).textShadow(true).lineWidth(1000);
            return show(model, motion(turn, scale, scale, scale, at), own.point(0, LABEL_TOP - height, LABEL_FORWARD));
        }

        /** The line under an entry's name: its state's, or its own subtitle. */
        private String status(SpatialEntry entry) {
            return switch (entry.state()) {
                case LOCKED -> entry.price() != null && !entry.price().isBlank()
                        ? entry.price() : fill(theme.lockedLine(), entry);
                case EQUIPPED -> fill(theme.equippedLine(), entry);
                case OWNED -> entry.subtitle().isBlank() ? fill(theme.ownedLine(), entry) : entry.subtitle();
                default -> entry.subtitle();
            };
        }

        private void hover(int slot, boolean on) {
            int before = hovered;
            hovered = on ? slot : (hovered == slot ? -1 : hovered);
            if (before >= 0 && before != hovered) style(before);
            style(slot);
            caption(shown);
            // The information panel follows the crosshair at once; when it leaves, on the next poll, so a
            // sweep from one plinth to the next does not flash the chosen entry between them.
            SpatialEntry entry = at(slot);
            if (on && entry != null) {
                hovers.put(source, entry.id());
                wordsStale = false;
                refreshWords();
            } else if (entry != null && entry.id().equals(hovers.get(source))) {
                hovers.remove(source);
                wordsStale = true;
            }
        }

        /** Dresses a slot for its state: resting, under the crosshair, or chosen. */
        private void style(int slot) {
            if (slot < 0 || slot >= drawn.size()) return;
            boolean on = slot == hovered;
            boolean picked = slot == chosen;
            Pose pose = kind == Kind.PEDESTAL && picked ? Pose.CHOSEN : on ? Pose.HOVER : Pose.IDLE;
            DisplayHandle icon = icons.get(slot);
            Place place = places.get(slot);
            if (icon != null && poses.get(slot) != pose) {
                poses.set(slot, pose);
                boolean plinth = kind == Kind.PEDESTAL;
                icon.then(iconMotion(plinth, slot, place.rotation(), iconScale, plinth ? iconScale : iconScale * 0.3, pose,
                        offset(place.plane(), 0, 0, 1), REPOP));
            }
            if (icon != null && kind != Kind.PEDESTAL) icon.glow(on ? colour(theme.hoverGlow(), 0xFFD700) : -1);
            DisplayHandle plinth = plinths.get(slot);
            if (plinth != null) {
                // The ring round a plinth: its outline, in the chosen colour or, lighter, the hover's.
                plinth.glow(picked ? colour(theme.plateSelected(), 0xFFD700) : on ? colour(theme.plateHover(), 0xAA76DE) : -1);
            }
            DisplayHandle plate = plates.get(slot);
            if (plate != null) plate.restyle(sheetModel(Geometry.argb(255, plateColour(on, picked))));
            DisplayHandle label = labels.get(slot);
            DisplayKeyframe rest = labelRest.get(slot);
            if (label != null && rest != null) {
                // Under the crosshair it grows and comes forward, its top kept under the plinth.
                double grow = on ? 1.1 : 1.0;
                double drop = on ? -Stage.TEXT_HEIGHT * rest.scaleY() * 2 * (grow - 1) : 0;
                double[] ahead = offset(place.plane(), 0, 0, on ? 0.03 : 0);
                label.moveTo(new DisplayKeyframe(0L, (float) ahead[0], (float) (ahead[1] + drop), (float) ahead[2],
                        rest.rotation(), (float) (rest.scaleX() * grow), (float) (rest.scaleY() * grow),
                        (float) (rest.scaleZ() * grow)), HOVER_MILLIS, DisplayMotion.Easing.QUAD_OUT);
            }
            if (kind == Kind.ROW) rowBar(slot, on);
        }

        private @Nullable DisplayHandle bar;

        /** A row under the crosshair is lit by an opaque bar behind it. */
        private void rowBar(int slot, boolean on) {
            if (bar != null) {
                drop(bar);
                bar = null;
            }
            if (!on || slot >= places.size()) return;
            Place place = places.get(slot);
            int lit = Geometry.mix(glassColour(), colour(theme.plateHover(), 0xAA76DE), 0.22);
            bar = sheet(place.plane().point(0, place.up(), 0.006), place.rotation(), slotWidth, slotHeight * 0.92,
                    Geometry.argb(255, lit), -1);
        }

        private void choose(int slot) {
            SpatialEntry entry = at(slot);
            if (entry == null) return;
            confirm.disarm();
            selected.put(source, entry.id());
            var select = definition.selects.get(source);
            if (select != null) select.accept(MenuSession.this, entry);
            // The whole menu: the panels read the new choice and the preview plays it at once.
            MenuSession.this.refresh();
        }

        /** Moves the selection marker to the chosen entry's slot, gliding; hides it when it is on another page. */
        private void chosen(List<SpatialEntry> now) {
            String id = selected.get(source);
            int slot = -1;
            for (int i = 0; i < now.size(); i++) {
                SpatialEntry entry = now.get(i);
                if (id != null ? entry.id().equals(id) : entry.state() == SpatialEntry.State.SELECTED) {
                    slot = i;
                    break;
                }
            }
            if (id == null && slot >= 0) selected.put(source, now.get(slot).id());
            int before = chosen;
            chosen = slot;
            if (before != slot) {
                style(before);
                style(slot);
            }
            glide(slot);
        }

        private void glide(int slot) {
            double width = kind == Kind.ROW ? 0.025 : kind == Kind.PEDESTAL ? 0.3 : slotWidth * 0.5;
            double height = kind == Kind.ROW ? slotHeight * 0.66 : kind == Kind.PEDESTAL ? 0.014 : 0.022;
            if (selector == null) {
                if (slot < 0) return;
                selector = sheet(selectorAt(0), turnAt(0), width, height,
                        Geometry.argb(255, colour(theme.plateSelected(), 0xFFD700)), delay < 0 ? -1 : delay + 200);
                if (selector == null) return;
                if (slot == 0) return;
            }
            Location origin = selectorAt(0);
            Location target = selectorAt(Math.max(0, slot));
            double grow = slot < 0 ? 0.01 : 1.0;
            selector.moveTo(Geometry.glass(0, turnAt(Math.max(0, slot)), width, height, grow, target.getX() - origin.getX(),
                    target.getY() - origin.getY(), target.getZ() - origin.getZ()), GLIDE_MILLIS, DisplayMotion.Easing.QUAD_OUT);
        }

        private Rotation turnAt(int slot) {
            return kind == Kind.PEDESTAL ? places.get(Math.min(slot, places.size() - 1)).rotation() : rotation;
        }

        private Location selectorAt(int slot) {
            Place place = places.get(Math.min(slot, places.size() - 1));
            // The carousel's marker is a thin bar between a plinth and its label, in front of the plinth.
            if (kind == Kind.PEDESTAL) return place.plane().point(0, (LABEL_TOP + PLINTH_UP - PLINTH_HEIGHT / 2) / 2, LABEL_FORWARD);
            if (kind == Kind.ROW) return plane.point(-slotWidth / 2 + 0.012, place.up(), 0.014);
            return plane.point(place.across(), place.up() - iconScale * 0.62, 0.014);
        }

        private void pageLine(int page, int pageCount) {
            boolean show = kind == Kind.PEDESTAL || pageCount > 1;
            String raw = fill(theme.pageLine().replace("%page%", String.valueOf(page + 1))
                    .replace("%pages%", String.valueOf(pageCount)));
            if (!show) {
                drop(pageLine);
                pageLine = null;
                return;
            }
            if (pageLine != null) {
                pageLine.text(theme.smallCapsBody() ? Text.component(raw) : Text.verbatim(raw));
                return;
            }
            double room = kind == Kind.PEDESTAL ? 0.62 : slotWidth * 0.9;
            pageLine = write(raw, true, plane, rotation, 0, pageUp, 0.012, room, theme.smallScale(), false,
                    delay < 0 ? -1 : delay + 220);
        }

        /** A grid names the entry under the crosshair, or the chosen one, under its cells. */
        private void caption(List<SpatialEntry> now) {
            if (kind != Kind.CELL) return;
            int slot = hovered >= 0 ? hovered : chosen;
            SpatialEntry entry = slot >= 0 && slot < now.size() ? now.get(slot) : null;
            // A locked cell's caption says why: its price, or the locked line.
            String text = entry == null ? "" : entry.locked() ? entry.name() + " {letters_black}» " + status(entry) : entry.name();
            if (text.equals(captionShown) && (caption != null || text.isBlank())) return;
            captionShown = text;
            drop(caption);
            caption = write(text, true, plane, rotation, 0, captionUp, 0.012, slotWidth * 2.8, theme.smallScale(), true,
                    caption == null && first ? -1 : REPOP);
        }
    }

    // ── Preview ───────────────────────────────────────────────────────────

    /** The piece in the middle on its pedestal: an NPC, an item, or an effect on a mannequin. */
    private final class PreviewPart {

        private final Location base;
        private final Rotation rotation;
        private SpatialPreview current = SpatialPreview.none();
        private @Nullable NpcHandle npc;
        private @Nullable DisplayHandle item;
        private @Nullable SequenceRun run;
        private long nextPlay;
        private long npcUntil;
        /** When a mannequin taken down while its effect plays comes back; 0 when it stands. */
        private long hiddenUntil;
        /** Whose skin a mannequin with nobody named wears, picked each time it stands, worn by its effect too. */
        private @Nullable net.exylia.lib.npc.PreviewSkins.Body body;
        /** When that body stood; its first play waits up to {@link #SKIN_WAIT_MILLIS} for its skin to be read. */
        private long stoodAt;

        PreviewPart(long delay) {
            SpatialLayout.Preview look = layout.preview();
            StagePlane plane = Geometry.panel(anchor.getWorld(), board, look.at().get(0), look.at().get(1), look.at().get(2), 0);
            this.rotation = facing(plane);
            this.base = plane.point(0, 0, 0);
            if (look.pedestal()) {
                show(DisplayModel.block(StageLook.block(theme.pedestalBlock(), Material.POLISHED_BLACKSTONE)).light(15),
                        motion(rotation, 0.78, 0.12, 0.78, delay), plane.point(0, -0.06, 0));
                sheet(plane.point(0, 0.002, 0), flat(rotation), 0.66, 0.66,
                        Geometry.argb(255, plateColour(false, false)), delay < 0 ? -1 : delay + 60);
                sheet(plane.point(0, -0.085, 0.395), rotation, 0.66, 0.012,
                        Geometry.argb(255, colour(theme.accent(), 0x8A51C4)), delay < 0 ? -1 : delay + 120);
            }
        }

        void update(SpatialPreview next) {
            if (next == null) next = SpatialPreview.none();
            if (next.equals(current) && (npc == null || npc.isShowing())) return;
            if (current instanceof SpatialPreview.Npc was && next instanceof SpatialPreview.Npc now
                    && was.skin().equals(now.skin()) && was.equipment() != null && now.equipment() != null
                    && npc != null && npc.isShowing()) {
                current = next;
                dress(now);
                return;
            }
            remove();
            current = next;
            draw();
        }

        private void draw() {
            SpatialLayout.Preview look = layout.preview();
            Location stand = base.clone();
            stand.setYaw(board.yaw() + 180);
            switch (current) {
                case SpatialPreview.Npc body -> {
                    // Nameless: under the viewer's own name the client would hang their scoreboard team's
                    // prefix (a rank) over it.
                    NpcModel model = NpcModel.of(body.skin()).scale(look.scale()).nameless();
                    if (body.equipment() == null) model = model.wearing(body.skin());
                    spawn(model, stand, look);
                    dress(body);
                }
                case SpatialPreview.Item shown -> {
                    double size = 0.62 * look.scale();
                    List<DisplayKeyframe> frames = new ArrayList<>();
                    long cycle = (long) (360_000 / Math.max(1, Math.abs(look.turnSpeed())));
                    for (int pose = 0; pose <= 12; pose++) {
                        double angle = Math.PI * 2 * pose / 12 * Math.signum(look.turnSpeed() == 0 ? 1 : look.turnSpeed());
                        frames.add(new DisplayKeyframe(cycle * pose / 12, 0f, 0f, 0f,
                                Rotation.around(Rotation.Axis.Y, angle).then(rotation), (float) size, (float) size, (float) size));
                    }
                    DisplayMotion spin = DisplayMotion.of(frames, cycle);
                    if (LIFE > cycle) spin = DisplayMotion.chain(spin, DisplayMotion.still(LIFE - cycle)).looping(cycle, 1, 1);
                    item = show(DisplayModel.item(shown.item()).light(15), spin, base.clone().add(0, 0.42 * look.scale(), 0));
                }
                case SpatialPreview.Effect effect -> {
                    mannequin(effect);
                    nextPlay = 0;
                }
                case SpatialPreview.None none -> { }
            }
        }

        private void mannequin(SpatialPreview.Effect effect) {
            Location stand = base.clone();
            stand.setYaw(board.yaw() + 180);
            body = effect.skin() == null ? layout.preview().skinPolicy().pick(viewer) : null;
            stoodAt = System.currentTimeMillis();
            NpcModel model = body != null ? body.npc() : NpcModel.of(effect.skin());
            // The size the effect plays at, so the body that dies is the one that stood there.
            spawn(model.scale(layout.preview().effectScale()).nameless(), stand, layout.preview());
        }

        private void spawn(NpcModel model, Location stand, SpatialLayout.Preview look) {
            NpcMotion turn = look.turnSpeed() == 0 ? NpcMotion.still() : NpcMotion.builder().spin(look.turnSpeed()).build();
            npc = Npcs.of(menus.plugin()).show(model, turn, stand, 120_000L, List.of(viewer));
            npcUntil = System.currentTimeMillis() + 115_000L;
        }

        private void dress(SpatialPreview.Npc body) {
            if (npc == null || body.equipment() == null) return;
            for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                    EquipmentSlot.FEET, EquipmentSlot.HAND, EquipmentSlot.OFF_HAND)) {
                npc.equip(slot, body.equipment().get(slot));
            }
        }

        /** Every poll: an NPC lives two minutes at most, so a long look gets a fresh one; an effect plays again. */
        void keep() {
            long now = System.currentTimeMillis();
            if (current instanceof SpatialPreview.Effect effect && npc == null && hiddenUntil > 0 && now >= hiddenUntil) {
                hiddenUntil = 0;
                mannequin(effect);
            }
            if (npc != null && (now > npcUntil || !npc.isShowing())) {
                SpatialPreview again = current;
                remove();
                current = again;
                draw();
                return;
            }
            if (current instanceof SpatialPreview.Effect effect && npc != null && now >= nextPlay
                    && (body == null || body.ready() || now - stoodAt > SKIN_WAIT_MILLIS)) {
                if (run != null) run.cancel();
                // Where the mannequin's feet were, the pedestal's top: a kill effect is written round the
                // victim's feet. It plays as a miniature of itself (effect-scale) so all of it stays
                // between the carousel and the header.
                Location at = base.clone();
                // The viewer sets it off; [RAGDOLL] and [NPC] {victim} lines wear the mannequin's skin, so
                // the body that falls is the one that stood there. No target, as in the previews' mannequin
                // mode: a [POTION] or a victim's title never lands on anybody.
                SequenceTarget where = SequenceTarget.at(at).by(viewer).onlyTo(viewer)
                        .scale(layout.preview().effectScale());
                where = body != null ? where.wearing(body.ragdoll()).standing(body::npc)
                        : where.wearing(RagdollModel.of(effect.skin()));
                run = Sequences.of(menus.plugin()).play(effect.sequence(), where);
                long length = effect.sequence().isEndless() ? Long.MAX_VALUE / 4 : effect.sequence().durationMillis();
                nextPlay = now + Math.max(2500L, length + 1500L);
                if (layout.preview().vanishOnPlay()) {
                    // As the previews' vanish-on-hit: the mannequin is gone while it plays, so a kill reads
                    // as a death, and stands again a second before the next play.
                    long gone = length <= 0 || length > VANISH_MAX_MILLIS ? VANISH_FALLBACK_MILLIS : length;
                    npc.remove();
                    npc = null;
                    hiddenUntil = now + gone;
                    nextPlay = Math.max(nextPlay, hiddenUntil + 1000L);
                }
            }
        }

        /** Plays the preview again from the start: an effect at once, its mannequin back if it was gone. */
        void replay() {
            SpatialPreview again = current;
            remove();
            current = again;
            draw();
            keep();
        }

        void remove() {
            if (npc != null) npc.remove();
            npc = null;
            drop(item);
            item = null;
            if (run != null) run.cancel();
            run = null;
            hiddenUntil = 0;
            current = SpatialPreview.none();
        }
    }

    // ── For edit mode ─────────────────────────────────────────────────────

    /** The plane a named piece stands on, for picking it: a panel, {@code carousel} or {@code preview}. */
    StagePlane pieceAt(String piece) {
        if (piece.equals(EditMode.CAROUSEL)) {
            List<Double> at = layout.carousel().at();
            return Geometry.panel(anchor.getWorld(), board, at.get(0), at.get(1) - 0.1, at.get(2), 0);
        }
        if (piece.equals(EditMode.PREVIEW)) {
            List<Double> at = layout.preview().at();
            return Geometry.panel(anchor.getWorld(), board, at.get(0), at.get(1) + 0.8, at.get(2), 0);
        }
        SpatialLayout.Panel panel = Objects.requireNonNull(layout.panel(piece), piece);
        return Geometry.panel(anchor.getWorld(), board, panel.across(), panel.up(), panel.forward(), panel.turn());
    }

    /** An outline round a rectangle of a plane: four opaque bars. */
    List<DisplayHandle> outline(StagePlane plane, double width, double height, int rgb) {
        Rotation rotation = facing(plane);
        double bar = 0.02;
        int argb = Geometry.argb(255, rgb);
        List<DisplayHandle> bars = new ArrayList<>();
        bars.add(sheet(plane.point(0, height / 2 + bar / 2, 0.03), rotation, width + bar * 2, bar, argb, -1));
        bars.add(sheet(plane.point(0, -height / 2 - bar / 2, 0.03), rotation, width + bar * 2, bar, argb, -1));
        bars.add(sheet(plane.point(-width / 2 - bar / 2, 0, 0.03), rotation, bar, height, argb, -1));
        bars.add(sheet(plane.point(width / 2 + bar / 2, 0, 0.03), rotation, bar, height, argb, -1));
        bars.removeIf(Objects::isNull);
        return bars;
    }

    int colourOf(String token, int fallback) {
        return colour(token, fallback);
    }
}
