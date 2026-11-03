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
import net.exylia.lib.spatial.internal.Flights;
import net.exylia.lib.spatial.internal.Geometry;
import net.exylia.lib.spatial.internal.WheelSteps;
import net.exylia.lib.spatial.internal.UndoHistory;
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
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
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
    private static final long SKIN_WAIT_MILLIS = 400L;

    /** How long a finished performance rests before it starts again. */
    private static final long ENCORE_MILLIS = 600L;
    /** The slots a preview body is dressed in, in the order they are sent. */
    private static final List<EquipmentSlot> DRESSED = List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST,
            EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.HAND, EquipmentSlot.OFF_HAND);

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
    /** The first item shown, per source, once the wheel moved it off a page's start (since 1.463.0). */
    private final Map<String, Integer> offsets = new HashMap<>();
    private final Map<String, String> selected = new HashMap<>();
    private final Map<String, String> hovers = new HashMap<>();
    /** When the crosshair came to rest on each source's hovered entry, and whether a redraw already said so. */
    private final Map<String, Long> hoverSince = new HashMap<>();
    private final java.util.Set<String> dwellTold = new java.util.HashSet<>();
    /** How long the crosshair rests on an entry before it counts as dwelling (since 1.453.0). */
    private static final long DWELL_MILLIS = 700L;
    /** Drafted values of keys that name no source; a source's draft is its selection. */
    private final Drafts drafts = new Drafts();
    /** What UNDO steps back to: the drafts before each click that changed them (since 1.423.0). */
    private final UndoHistory<DraftState> history = new UndoHistory<>(UNDO_STEPS);
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
    /** The space it stands in, kept empty for the viewer; lowered when it closes. */
    @Nullable net.exylia.lib.spatial.internal.SceneShield shield;
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
        offsets.remove(source);
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
        refreshPreview();
    }

    @Override
    public void refreshPreview() {
        if (closed || preview == null) return;
        preview.update(definition.preview == null ? SpatialPreview.none() : definition.preview.apply(this));
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

    @Override
    public @Nullable String dwelled(@NotNull String source) {
        String id = hovers.get(source);
        Long since = hoverSince.get(source);
        return id != null && since != null && System.currentTimeMillis() - since >= DWELL_MILLIS ? id : null;
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

    /** The drafts as UNDO keeps them: every plain key's draft, and the selection of every source that is saved. */
    private record DraftState(Map<String, Object> drafts, Map<String, String> selections) {
    }

    private DraftState draftState() {
        Map<String, String> selections = new HashMap<>();
        for (String key : savedState().keySet()) {
            if (definition.sources.containsKey(key)) selections.put(key, selected.get(key));
        }
        return new DraftState(drafts.snapshot(), selections);
    }

    /** Runs a click, and remembers the drafts before it as an UNDO step when it changed them. */
    private void undoable(Runnable click) {
        DraftState before = draftState();
        click.run();
        if (!closed) history.record(before, draftState());
    }

    @Override
    public boolean canUndo() {
        return !history.isEmpty();
    }

    @Override
    public void undo() {
        if (closed) return;
        DraftState back = history.pop();
        if (back == null) {
            StageLook.play(viewer, theme.errorSound());
            return;
        }
        confirm.disarm();
        drafts.restore(back.drafts());
        back.selections().forEach(this::select);
        refresh();
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
        if (definition.onRevert != null) definition.onRevert.accept(this);
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
        // What is saved now is where UNDO starts from: stepping back past it would draft the old design again.
        history.clear();
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
        // Once everything has popped in, so the tour points at pieces that are there.
        menus.tasks().runAtEntityLater(viewer, 14L, () -> {
            if (!closed) tourOnFirstOpen();
        }, () -> { });
    }

    private void draw(boolean animated) {
        // Raised when needed so its lowest piece stands above the floor the viewer stands on: clear-view keeps
        // the ground, which would hide anything under it.
        board = StagePlane.inFrontOf(anchor, EYES, distance, layout.liftOverFloor(EYES, FLOOR_MARGIN), 1, 1, 1, 1);
        stage = new Stage(viewer, board, displays, LIFE, StageTheme.defaults().withSounds("", ""));
        if (layout.backdrop().present()) backdrop(layout.backdrop(), animated ? 0L : -1L);
        int index = 0;
        for (Map.Entry<String, SpatialLayout.Panel> panel : layout.panels().entrySet()) {
            // Hidden in the layout (visible: false, since 1.465.0): not drawn, nothing to click.
            if (!panel.getValue().visible()) continue;
            parts.add(new PanelPart(panel.getKey(), panel.getValue(), animated ? index++ * 70L : -1));
        }
        if (layout.carousel().present()) {
            carousel = new Slots(layout.carousel(), animated ? 420L : -1);
            parts.add(carousel);
            // Edit mode keeps paging as it always did; a player's wheel moves the carousel an item a notch.
            if (edit != null) pointer.onScroll(notch -> turnPage(carousel, notch));
            else pointer.onScroll(notch -> wheel(carousel, notch));
        } else {
            pointer.onScroll(null);
        }
        preview = layout.preview().visible() ? new PreviewPart(animated ? 300L : -1) : null;
        if (edit != null) edit.hotspots();
    }

    /** The fewest rings a feathered backdrop fades through: fewer show as steps against the sky. */
    static final int MIN_RINGS = Geometry.MIN_RINGS;
    /** How far behind the one before each backdrop ring stands. */
    private static final double RING_STEP = 0.012;

    /** How far above the floor the board's lowest piece stands at least. */
    static final double FLOOR_MARGIN = 0.15;

    /**
     * The soft backdrop: a dark translucent sheet behind the board and fainter, larger rings round it, each a little
     * further back, so its edge fades instead of ending. The nearest sheet hides those behind it on clients that
     * hide what a translucent sheet covers, so each ring shows its own alpha either way: a stepped fade.
     */
    private void backdrop(SpatialLayout.Backdrop look, long delay) {
        int rgb = Geometry.mix(0x000000, colour(look.tint(), 0x8A51C4), 0.12);
        double w = look.size().get(0);
        double h = look.size().get(1);
        for (Geometry.Ring ring : Geometry.vignette(look.alpha(), look.rings(), look.feather())) {
            sheet(board.point(look.at().get(0), look.at().get(1), look.at().get(2) - ring.back() * RING_STEP), facing(board),
                    w + ring.grow(), h + ring.grow(), Geometry.argb(ring.alpha(), rgb), delay < 0 ? -1 : delay);
        }
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
        // The crosshair has rested on an entry long enough: what shows only on a dwell shows now.
        for (String source : hovers.keySet()) {
            if (dwelled(source) != null && dwellTold.add(source)) wordsStale = true;
        }
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
    /** A shelf tile's width against its spacing, its icon against the tile, and how far it pops forward on hover. */
    private static final double SHELF_TILE = 0.8;
    private static final double SHELF_ICON = 0.78;
    private static final double SHELF_HOVER_AHEAD = 0.12;
    /** An arc's icon against its spacing, how far a looked-at one comes forward and grows, and its disc. */
    private static final double ARC_ICON = 0.74;
    private static final double ARC_HOVER_AHEAD = 0.28;
    private static final double ARC_HOVER_GROW = 1.3;
    private static final double ARC_DISC_DROP = 0.72;
    private static final double ARC_DISC_SIZE = 5.0;
    /** How far a flat "●" sits from its display's origin, in glyph sizes: laid flat, its centre is behind it. */
    private static final double DISC_CENTRE = 0.1;
    /** The room between a shelf's last row and its page line, and between its row ends and the arrows. */
    private static final double SHELF_PAGE_GAP = 0.26;
    private static final double SHELF_ARROW_GAP = 0.18;

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
        if (theme.rises() && slideFrom == null) return rise(rotation, x, y, z, Math.max(0, delay));
        return pop(rotation, x, y, z, Math.max(0, delay), slideFrom);
    }

    /** Waits unseen, then rises a little into place and settles: the quiet way in (theme open-motion RISE). */
    private static DisplayMotion rise(Rotation rotation, double x, double y, double z, long delay) {
        float tiny = 0.01f;
        float sx = (float) x;
        float sy = (float) y;
        float sz = (float) z;
        return DisplayMotion.of(List.of(
                new DisplayKeyframe(0L, 0f, -0.14f, 0f, rotation, tiny, tiny, tiny),
                new DisplayKeyframe(delay, 0f, -0.14f, 0f, rotation, tiny, tiny, tiny),
                new DisplayKeyframe(delay + 60L, 0f, -0.1f, 0f, rotation, sx * 0.92f, sy * 0.92f, sz * 0.92f),
                new DisplayKeyframe(delay + 300L, 0f, 0f, 0f, rotation, sx, sy, sz)), LIFE);
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
        // Over the backdrop, a colour too dark to read there takes the line's own readable colour (since 1.467.0).
        if (layout.backdrop().present()) filled = net.exylia.lib.spatial.internal.Legible.over(filled,
                layout.backdrop().alpha(), MenuSession::paletteRgb);
        boolean asWritten = body && !theme.smallCapsBody();
        boolean small = !asWritten && net.exylia.lib.text.internal.SmallText.enabled();
        double floor = Geometry.readableScale(at.distance(eyes()), theme.minGlyphDegrees(),
                small ? Geometry.SMALL_CAPITAL_PIXELS : Geometry.CAPITAL_PIXELS);
        double width = Math.max(0.18, Geometry.visibleWidth(filled));
        double scale = Math.max(floor, Math.min(max, room / width));
        String shown = width * scale > room + 1e-6 ? Geometry.truncate(filled, room / scale) : filled;
        return new Fitted(asWritten ? Text.verbatim(shown) : Text.component(shown), shown, scale);
    }

    /** Body lines of a TEXT panel are fitted into 90 % of its width at the theme's text scale (see PanelPart). */
    @Override
    public @NotNull List<String> wrap(@NotNull String panel, @Nullable String text, int lines) {
        SpatialLayout.Panel part = layout.panels().get(panel);
        if (part == null) return SpatialContext.super.wrap(panel, text, lines);
        boolean small = theme.smallCapsBody() && net.exylia.lib.text.internal.SmallText.enabled();
        double away = board == null ? distance
                : Geometry.panel(anchor.getWorld(), board, part.across(), part.up(), part.forward(), part.turn())
                        .point(0, 0, 0).distance(eyes());
        double floor = Geometry.readableScale(away, theme.minGlyphDegrees(),
                small ? Geometry.SMALL_CAPITAL_PIXELS : Geometry.CAPITAL_PIXELS);
        // Balanced: lines of even length, never a lone word on the last one.
        return Geometry.balanced(text, part.width() * 0.9 / Math.max(floor, theme.textScale()), lines);
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

    /** A palette token's colour, or {@code null} for one the palette lacks. */
    private static Integer paletteRgb(String token) {
        net.kyori.adventure.text.format.TextColor colour = net.exylia.lib.text.Colors.get(token);
        return colour == null ? null : colour.value();
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

    /** A shelf tile: dark at rest, clearly lit when chosen, lighter under the crosshair. */
    private int tileColour(boolean hovered, boolean chosen) {
        if (chosen) return Geometry.mix(idlePlate(), colour(theme.plateSelected(), 0xFFD700), hovered ? 0.55 : 0.45);
        if (hovered) return Geometry.mix(idlePlate(), colour(theme.plateHover(), 0xAA76DE), 0.45);
        return Geometry.mix(glassColour(), idlePlate(), 0.5);
    }

    private static Rotation facing(StagePlane plane) {
        return StageLook.facing(plane);
    }

    /**
     * Turns an item to point along a direction: an item is drawn tip up and to the east, so it is laid
     * flat along east, raised to the slope, then swung round to the heading.
     */
    private static Rotation pointing(Vector velocity) {
        double horizontal = Math.hypot(velocity.getX(), velocity.getZ());
        return Rotation.around(Rotation.Axis.Z, -Math.PI / 4)
                .then(Rotation.around(Rotation.Axis.Z, Math.atan2(velocity.getY(), horizontal)))
                .then(Rotation.around(Rotation.Axis.Y, Math.atan2(-velocity.getZ(), velocity.getX())));
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
        // A whole page from the page the first item shown is on, whatever the wheel did before.
        slots.wheel.clear();
        offsets.remove(slots.source);
        pages.put(slots.source, page(slots.source) + notch);
        slots.step = notch;
        refresh();
        slots.step = 0;
    }

    /**
     * A notch of the wheel over a list: it moves one item (since 1.463.0). Notches that come faster than a
     * step plays wait their turn and play a beat apart, so a flick slides item by item instead of jumping.
     */
    private void wheel(Slots slots, int notch) {
        // A list that fits in its slots has nowhere to go.
        if (slots == null || closed || slots.total <= slots.perPage) return;
        if (slots.wheel.add(notch)) playStep(slots);
    }

    private void playStep(Slots slots) {
        if (closed) return;
        int step = slots.wheel.next();
        if (step == 0) return;
        confirm.disarm();
        StageLook.play(viewer, theme.pageSound());
        offsets.put(slots.source, slots.offset + step);
        slots.step = step;
        slots.gliding = true;
        refresh();
        slots.step = 0;
        slots.gliding = false;
        menus.tasks().runAtEntityLater(viewer, slots.wheel.beat(), () -> playStep(slots), () -> { });
    }

    private void act(String action) {
        // Another button: whatever waited for a second click is no longer what they are doing.
        if (!confirm.confirming(action, System.currentTimeMillis())) confirm.disarm();
        var run = definition.actions.get(action);
        if (run == null && UNDO.equals(action)) {
            undo();
            return;
        }
        Runnable click = () -> {
            if (run != null) {
                run.accept(this);
                return;
            }
            switch (action) {
                case SAVE -> save();
                case REVERT -> revert();
                case LEAVE -> close();
                case INTRO -> startTour();
                default -> StageLook.play(viewer, theme.errorSound());
            }
        };
        // SAVE is where the history starts again, and UNDO walks it (a plugin's own undo calling ctx.undo()):
        // neither is a step of it.
        if (SAVE.equals(action) || UNDO.equals(action) || LEAVE.equals(action)) click.run();
        else undoable(click);
    }

    /** The layout actions every menu answers when the plugin gives them nothing of its own. */
    static final String SAVE = "save";
    static final String REVERT = "revert";
    /** Since 1.423.0. */
    static final String UNDO = "undo";

    /** The menu's state key holding the words of the {@code ICONS} button under the crosshair (since 1.453.0). */
    static final String ICON_LABEL = "icon_label";
    /** The menu's state key set while the guided tour shows (since 1.454.0): the hint line steps aside for it. */
    static final String TOURING = "touring";
    /** How far apart an icon row's glyphs stand, in row heights. */
    private static final double ICON_PITCH = 1.6;

    /** What an {@code ACTION} button reads when the layout gives it no label. */
    static final String PRIMARY_LABEL = "%catalog_action%";
    /** Layout actions every menu answers (since 1.452.0): close it, show the guided tour again. */
    static final String LEAVE = "leave";
    static final String INTRO = "intro";

    /** The gap between footers side by side. */
    private static final double FOOTER_GAP = 0.06;
    /** A bare header at least this tall stacks its hint under its title instead of at its right end. */
    private static final double STACKED_HEADER = 0.36;
    /** The least room between a header's title and its hint at the right end. */
    private static final double HEADER_GAP = 0.2;

    /** How many clicks UNDO can walk back. */
    private static final int UNDO_STEPS = 64;

    /** How an icon moves: resting, under the crosshair, or chosen. */
    private enum Pose { IDLE, HOVER, CHOSEN }

    /**
     * An icon's motion: a gentle sway and bob on a plinth (a full, quicker turn once chosen), a slow
     * bob in a panel, lifted and grown under the crosshair. It pops in after {@code delay}, or glides
     * from wherever it is when {@code delay} is {@link #REPOP} (handed to {@code then}).
     */
    private DisplayMotion iconMotion(boolean plinth, int slot, Rotation turn, double size, double depth, Pose pose,
                                     double[] towards, long delay) {
        return iconMotion(plinth, false, slot, turn, size, depth, pose, towards, delay);
    }

    /** {@code shelf}: a shelf's icon, which moves like a plinth's but comes forward off its tile under the crosshair. */
    private DisplayMotion iconMotion(boolean plinth, boolean shelf, int slot, Rotation turn, double size, double depth,
                                     Pose pose, double[] towards, long delay) {
        return iconMotion(plinth, shelf, SHELF_HOVER_AHEAD, theme.hoverGrow(), slot, turn, size, depth, pose, towards, delay);
    }

    /** As above, coming {@code hoverAhead} forward and growing {@code hoverGrow} under the crosshair. */
    private DisplayMotion iconMotion(boolean plinth, boolean shelf, double hoverAhead, double hoverGrow, int slot, Rotation turn,
                                     double size, double depth, Pose pose, double[] towards, long delay) {
        double grow = pose == Pose.IDLE ? 1.0 : pose == Pose.HOVER ? hoverGrow : 1.1;
        double lift = shelf ? (pose == Pose.HOVER ? 0.05 : 0)
                : !plinth ? 0 : pose == Pose.CHOSEN ? 0.07 : pose == Pose.HOVER ? 0.035 : 0;
        double ahead = shelf ? (pose == Pose.HOVER ? hoverAhead : 0.04) : plinth || pose == Pose.IDLE ? 0 : 0.02;
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
        private @Nullable Tabs tabs;
        private @Nullable Icons icons;
        private @Nullable Palette palette;

        PanelPart(String name, SpatialLayout.Panel panel, long delay) {
            this.name = name;
            this.panel = panel;
            this.plane = Geometry.panel(anchor.getWorld(), board, panel.across(), panel.up(), panel.forward(), panel.turn());
            this.rotation = facing(plane);
            double w = panel.width();
            double h = panel.height();
            long inner = delay < 0 ? -1 : delay + 180;
            if (panel.frame()) {
                int glass = glassColour();
                double reach = theme.backingReach();
                sheet(plane.point(0, 0, -0.02), rotation, w + reach * 2, h + reach * 2,
                        Geometry.argb(theme.backingAlpha(), Geometry.mix(0x000000, glass, 0.5)), delay);
                sheet(plane.point(0, 0, 0), rotation, w, h, Geometry.argb(theme.glassAlpha(), glass), delay);
                double bar = theme.accentHeight();
                sheet(plane.point(0, h / 2 - bar / 2, 0.004), rotation, w, bar,
                        Geometry.argb(255, colour(theme.accent(), 0x8A51C4)), delay < 0 ? -1 : delay + 90);
            }
            SpatialLayout.Content content = panel.content();
            boolean bare = content.type() == SpatialLayout.Content.Type.NONE;
            boolean titled = !panel.title().isBlank() && !bare;
            boolean hinted = !panel.hint().isBlank() && !bare;
            if (bare && h >= STACKED_HEADER && !panel.hint().isBlank()) {
                // A header tall enough for two lines: its title, its hint centred under it.
                double titleUp = panel.title().isBlank() ? 0 : h * 0.19;
                double hintUp = panel.title().isBlank() ? 0 : -h * 0.2;
                if (!panel.title().isBlank()) {
                    // A bare title (no glass) is drawn as written: one font on a board with no panels.
                    labels.add(new Label(panel.title(), (title, at) -> write(title, !panel.frame(), plane, rotation, 0, titleUp,
                            0.012, w * 0.86, theme.titleScale() * 0.85, true, at), inner));
                }
                labels.add(new Label(panel.hint(), (hint, at) -> write(hint, true, plane, rotation, 0, hintUp, 0.012,
                        w * 0.86, theme.smallScale(), false, at), inner));
            } else if (bare) {
                // A header: its title in the middle, its hint at the right end.
                if (!panel.title().isBlank()) {
                    labels.add(new Label(panel.title(), (title, at) -> write(title, !panel.frame(), plane, rotation, 0, 0, 0.012,
                            w * 0.6, theme.titleScale(), true, at), inner));
                }
                if (!panel.hint().isBlank()) {
                    // The hint has whatever the title leaves on its right, never less than a fifth of the bar.
                    labels.add(new Label(panel.hint(), (hint, at) -> {
                        double title = panel.title().isBlank() ? 0 : fit(fill(panel.title()), false, w * 0.6,
                                theme.titleScale(), plane.point(0, 0, 0.012)).width();
                        double room = Math.max(w * 0.2, w / 2 - 0.12 - title / 2 - HEADER_GAP);
                        return writeTo(hint, true, plane, rotation, w / 2 - 0.12, 0, 0.012, room, theme.smallScale(), at);
                    }, inner));
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
            List<SpatialLayout.Footer> bottom = panel.bottomRow();
            double[] area = Geometry.content(h, band, !bottom.isEmpty());
            switch (content.type()) {
                case TEXT -> {
                    int count = content.lines().size();
                    // Lines never closer than the readable size makes them, so a far panel's lines do not touch.
                    double floor = Stage.TEXT_HEIGHT * Geometry.readableScale(plane.point(0, 0, 0).distance(eyes()),
                            theme.minGlyphDegrees(), Geometry.CAPITAL_PIXELS) * 1.12;
                    double pitch = Math.min(area[1] / Math.max(1, count), Math.max(0.16, floor));
                    for (int i = 0; i < count; i++) {
                        double up = area[0] + ((count - 1) / 2.0 - i) * pitch;
                        double scale = i == 0 ? theme.textScale() * 1.15 : theme.textScale();
                        labels.add(new Label(content.lines().get(i), (line, at) -> write(line, true, plane, rotation, 0, up,
                                0.012, w * 0.9, scale, true, at), inner < 0 ? -1 : inner + i * 40L));
                    }
                }
                case LIST -> slots = new Slots(Kind.ROW, content.source(), this, area, inner);
                case GRID -> slots = new Slots(Kind.CELL, content.source(), this, area, inner);
                case BUTTON -> buttons.add(panel.frame()
                        ? new Button(plane, rotation, 0, area[0], w - 0.24, Math.min(area[1], 0.3), content.label(),
                                content.action(), inner)
                        // Bare: the button is the panel.
                        : new Button(plane, rotation, 0, 0, w, h, content.label(), content.action(), inner, false, true));
                case BUTTONS -> column = new Column(this, content.buttons(), area, inner);
                case ACTION -> buttons.add(new Button(plane, rotation, 0, 0, w, h,
                        content.label().isBlank() ? PRIMARY_LABEL : content.label(),
                        content.action().isBlank() ? SAVE : content.action(), inner, true, true));
                case TABS -> tabs = new Tabs(this, content.source(), inner);
                case ICONS -> icons = new Icons(this, content.buttons(), inner);
                case PALETTE -> palette = new Palette(this, content, inner);
                case NONE -> { }
            }
            // The bottom row: one footer the panel's width, several side by side.
            double[] across = Geometry.row(w - 0.2, bottom.size(), FOOTER_GAP);
            double each = Geometry.rowWidth(w - 0.2, bottom.size(), FOOTER_GAP);
            for (int i = 0; i < bottom.size(); i++) {
                SpatialLayout.Footer button = bottom.get(i);
                buttons.add(new Button(plane, rotation, across[i], -h / 2 + 0.16, each, 0.2, button.label(),
                        button.action(), inner < 0 ? -1 : inner + 120 + i * 50L));
            }
        }

        void refreshWords() {
            // A bare panel of words (the name, the description, the hint) fades while the guided tour shows.
            boolean hidden = tour != null && fades(panel);
            for (Label label : labels) label.refresh(hidden);
            buttons.forEach(Button::refresh);
            if (column != null) column.refresh();
            if (icons != null) icons.refresh();
        }

        @Override
        public void refresh() {
            refreshWords();
            if (slots != null) slots.refresh();
            if (tabs != null) tabs.refresh();
            if (palette != null) palette.refresh();
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
            refresh(false);
        }

        /** {@code hidden}: drawn as nothing for now (the guided tour fades the words it is not about). */
        void refresh(boolean hidden) {
            String now = hidden ? "" : fill(raw);
            if (now.equals(shown)) return;
            shown = now;
            redraw();
        }

        /** Draws the same words again: what they look like depends on something else that changed. */
        void redraw() {
            drop(handle);
            handle = drawer.draw(shown, REPOP);
        }
    }

    private static net.kyori.adventure.text.format.@Nullable TextColor firstColour(Component text) {
        if (text.color() != null) return text.color();
        for (Component child : text.children()) {
            net.kyori.adventure.text.format.TextColor found = firstColour(child);
            if (found != null) return found;
        }
        return null;
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
        /** The one primary action ({@code ACTION}): larger words, lit while ready, dimmed while not. */
        private final boolean primary;
        private @Nullable Hotspot hotspot;
        private boolean lit;
        private boolean hot;

        Button(StagePlane plane, Rotation rotation, double across, double up, double width, double height, String label,
               String action, long delay) {
            this(plane, rotation, across, up, width, height, label, action, delay, false, false);
        }

        /**
         * @param primary the one primary action: larger words, lit while ready, dimmed while not
         * @param bare    a button standing bare in the air (a frameless panel's): its words are drawn as written,
         *                as body lines are, since small capitals read poorly and run wide with no glass behind them
         */
        Button(StagePlane plane, Rotation rotation, double across, double up, double width, double height, String label,
               String action, long delay, boolean primary, boolean bare) {
            this.action = action;
            this.primary = primary;
            this.hot = hot();
            plate = sheet(plane.point(across, up, 0.008), rotation, width, height, argb(), delay);
            double size = primary ? theme.titleScale() : theme.textScale();
            this.label = new Label(label, (text, at) -> write(primary ? styled(text) : text, bare, plane, rotation, across,
                    up, 0.016, width * 0.86, size, true, at), delay < 0 ? -1 : delay + 60);
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
            if (primary && definition.ready != null) return definition.ready.test(MenuSession.this);
            return SAVE.equals(action) && dirty() || confirm.confirming(action, System.currentTimeMillis());
        }

        /** Takes the button down: a column closing up round a button that has no words now. */
        void remove() {
            drop(plate);
            drop(label.handle);
            pointer.remove(hotspot);
        }

        /**
         * The primary button's words in one colour, whatever colours the words carry: light and bold while it has
         * something to do, muted while not.
         */
        private String styled(String filled) {
            String words = plain(Text.verbatim(filled)).strip();
            return (hot ? "{letters}&l" : "{letters_black}&l") + words;
        }

        /**
         * The plate's colour with its alpha. The primary button is the only filled thing on a modern board: a
         * soft, translucent accent while it has something to do (a little brighter under the crosshair), and no
         * fill at all while it has not (since 1.453.0; an opaque warm plate before).
         */
        private int argb() {
            if (primary) {
                if (!hot) return 0;
                return Geometry.argb(lit ? 225 : 190, Geometry.mix(0x000000, MenuSession.this.colour(theme.accent(), 0x8A51C4),
                        lit ? 0.78 : 0.62));
            }
            return Geometry.argb(255, colour());
        }

        private int colour() {
            if (hot) return Geometry.mix(glassColour(), MenuSession.this.colour(theme.plateSelected(), 0xFFD700), lit ? 0.5 : 0.36);
            return Geometry.mix(glassColour(), MenuSession.this.colour(theme.plateHover(), 0xAA76DE), lit ? 0.5 : 0.2);
        }

        private void restyle() {
            if (plate != null) plate.restyle(sheetModel(argb()));
        }

        void refresh() {
            label.refresh();
            boolean now = hot();
            if (now == hot) return;
            hot = now;
            restyle();
            // Its words change colour with it.
            if (primary) label.redraw();
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
                        button.action(), delay < 0 ? delay : delay + i * 50L, false, !panel.panel.frame()));
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

    /**
     * A {@code TABS} content: one row of plain words, one per entry of its source, the chosen one (the entry the
     * source marks {@code SELECTED}, else the selection) with a thin accent line under it (since 1.453.0; a lit
     * plate before). Tabs are as wide as their words, never
     * cut; when they do not all fit the row shows as many as fit round the chosen one. A click chooses a tab
     * (the source's {@code onSelect} runs); the wheel over the row turns the carousel. Nothing is drawn while
     * the source is empty.
     */
    private final class Tabs {
        private static final double GAP = 0.04;
        private static final double PAD = 0.16;
        private static final double UNDERLINE = 0.016;
        private final PanelPart panel;
        private final String source;
        private final List<DisplayHandle> drawn = new ArrayList<>();
        private final List<Hotspot> spots = new ArrayList<>();
        private List<SpatialEntry> shown = List.of();
        private int active = -1;
        private long delay;

        Tabs(PanelPart panel, String source, long delay) {
            this.panel = panel;
            this.source = source;
            this.delay = delay;
        }

        private List<SpatialEntry> entries() {
            var supplier = definition.sources.get(source);
            return supplier == null ? List.of() : List.copyOf(supplier.apply(MenuSession.this));
        }

        private int activeOf(List<SpatialEntry> entries) {
            String id = selected.get(source);
            for (int i = 0; i < entries.size(); i++) if (entries.get(i).state() == SpatialEntry.State.SELECTED) return i;
            for (int i = 0; i < entries.size(); i++) if (entries.get(i).id().equals(id)) return i;
            return entries.isEmpty() ? -1 : 0;
        }

        void refresh() {
            List<SpatialEntry> now = entries();
            int at = activeOf(now);
            if (now.equals(shown) && at == active) return;
            shown = now;
            active = at;
            draw();
        }

        private void draw() {
            for (DisplayHandle handle : drawn) drop(handle);
            drawn.clear();
            // The wheel over the row turns the carousel (since 1.463.0): tabs change only by click.
            while (!spots.isEmpty()) pointer.remove(spots.removeLast());
            if (shown.isEmpty()) return;
            double w = panel.panel.width();
            double h = panel.panel.height();
            // Sized for the row's far end, so the outermost tab is as readable as the middle one.
            Location left = panel.plane.point(-w / 2, 0, 0.016);
            Location right = panel.plane.point(w / 2, 0, 0.016);
            Location middle = left.distanceSquared(eyes()) > right.distanceSquared(eyes()) ? left : right;
            List<Fitted> words = new ArrayList<>();
            double[] widths = new double[shown.size()];
            for (int i = 0; i < shown.size(); i++) {
                // Drawn as written, as a body line: a row of small capitals would run wide and read poorly.
                Fitted line = fit(fill(shown.get(i).name()), true, w, theme.textScale(), middle);
                words.add(line);
                widths[i] = line.width() + PAD;
            }
            int[] window = Geometry.tabWindow(widths, GAP, w, active);
            double[] centres = Geometry.tabCentres(widths, GAP, window[0], window[1]);
            long wait = delay < 0 ? -1 : delay;
            double lineUp = -h / 2 + UNDERLINE / 2;
            for (int i = window[0]; i < window[1]; i++) {
                int tab = i;
                double across = centres[i - window[0]];
                boolean on = i == active;
                // Plain words; the open one carries a thin accent line under it, no plate anywhere.
                DisplayHandle label = text(words.get(i), panel.plane, panel.rotation, across, 0.02, 0.016, true,
                        wait < 0 ? -1 : wait + i * 30L);
                if (label != null) drawn.add(label);
                double lineWidth = words.get(i).width();
                if (on) {
                    DisplayHandle line = sheet(panel.plane.point(across, lineUp, 0.016), panel.rotation, lineWidth, UNDERLINE,
                            Geometry.argb(255, colour(theme.accent(), 0x8A51C4)), wait < 0 ? -1 : wait + 120);
                    if (line != null) drawn.add(line);
                }
                if (edit != null) continue;
                DisplayHandle[] hint = new DisplayHandle[1];
                spots.add(pointer.add(Hotspot.rect(panel.plane, across, 0, widths[i], h).priority(2)
                        .onHover(lit -> {
                            // Under the crosshair a faint line shows where a click goes.
                            drop(hint[0]);
                            hint[0] = null;
                            if (lit && tab != active) {
                                hint[0] = sheet(panel.plane.point(across, lineUp, 0.016), panel.rotation, lineWidth, UNDERLINE,
                                        Geometry.argb(255, colour("{letters_black}", 0xA89AB5)), -1);
                                if (hint[0] != null) drawn.add(hint[0]);
                            }
                        })
                        .onPress(click -> choose(tab))));
            }
            // Only the first draw comes in; later ones (a tab chosen) appear in place.
            delay = REPOP;
        }

        private void choose(int tab) {
            if (tab < 0 || tab >= shown.size() || tab == active) return;
            SpatialEntry entry = shown.get(tab);
            confirm.disarm();
            selected.put(source, entry.id());
            var select = definition.selects.get(source);
            if (select != null) select.accept(MenuSession.this, entry);
            MenuSession.this.refresh();
        }
    }

    /**
     * An {@code ICONS} content: a row of large light glyphs, no plates, lit in the accent under the crosshair. Its
     * words then show right under it (since 1.454.0; in the hint line before), and in the menu's state as
     * {@link #ICON_LABEL} ({@code %icon_label%}). A button whose words read blank is left out and the row closes up.
     */
    private final class Icons {
        private final PanelPart panel;
        private final List<SpatialLayout.Footer> all;
        private final List<DisplayHandle> drawn = new ArrayList<>();
        private final List<Hotspot> spots = new ArrayList<>();
        private List<IconButton> shown = List.of();
        private long delay;
        /** Which draw the hotspots belong to: a hotspot taken down while hovered still says so, too late. */
        private int generation;

        /** {@code colour}: the markup an icon is drawn in (its meaning), blank for the board's light colour. */
        private record IconButton(String glyph, String words, String action, String colour) {
        }

        Icons(PanelPart panel, List<SpatialLayout.Footer> all, long delay) {
            this.panel = panel;
            this.all = all;
            this.delay = delay;
            refresh();
        }

        /** {@code "<glyph>|<words>"}, or words whose first glyph is the icon; blank words leave it out. */
        private List<IconButton> buttons() {
            List<IconButton> out = new ArrayList<>();
            for (SpatialLayout.Footer button : all) {
                String raw = button.label();
                int bar = raw.indexOf('|');
                // Before the bar: the glyph and the colour it is drawn in, or the colour alone ('{info}|%words%').
                String left = bar < 0 ? "" : fill(raw.substring(0, bar));
                String glyph = plain(Text.verbatim(left)).strip();
                String colour = glyph.isEmpty() ? left.strip() : left.substring(0, Math.max(0, left.lastIndexOf(glyph))).strip();
                String words = plain(Text.verbatim(fill(bar < 0 ? raw : raw.substring(bar + 1)))).strip();
                if (words.isEmpty()) continue;
                if (glyph.isEmpty()) {
                    int space = words.indexOf(' ');
                    glyph = space < 0 ? words : words.substring(0, space);
                    words = space < 0 ? words : words.substring(space + 1).strip();
                }
                out.add(new IconButton(glyph, words, button.action(), colour));
            }
            return out;
        }

        void refresh() {
            List<IconButton> now = buttons();
            if (now.equals(shown)) return;
            shown = now;
            draw();
        }

        private void draw() {
            drawn.forEach(MenuSession.this::drop);
            drawn.clear();
            spots.forEach(pointer::remove);
            spots.clear();
            int drawing = ++generation;
            int count = shown.size();
            double pitch = Math.min(panel.panel.height() * ICON_PITCH, panel.panel.width() / Math.max(1, count));
            for (int i = 0; i < count; i++) {
                IconButton button = shown.get(i);
                double across = (i - (count - 1) / 2.0) * pitch;
                DisplayHandle[] glyph = {glyph(button, across, false, delay < 0 ? delay : delay + i * 40L)};
                DisplayHandle[] words = {null};
                if (glyph[0] != null) drawn.add(glyph[0]);
                if (edit != null) continue;
                spots.add(pointer.add(Hotspot.rect(panel.plane, across, 0, pitch * 0.9, panel.panel.height()).priority(2)
                        .onHover(on -> {
                            if (drawing != generation) {
                                // Taken down already (its glyph and words with it): only its words in the hint go.
                                if (!on && ("{letters}" + button.words()).equals(get(ICON_LABEL))) set(ICON_LABEL, null);
                                return;
                            }
                            drawn.remove(glyph[0]);
                            drop(glyph[0]);
                            glyph[0] = glyph(button, across, on, -1);
                            if (glyph[0] != null) drawn.add(glyph[0]);
                            // Its words, right under it, only while it is looked at.
                            drawn.remove(words[0]);
                            drop(words[0]);
                            words[0] = on ? write("{letters}" + button.words(), true, panel.plane, panel.rotation, across,
                                    -panel.panel.height() * 0.78, 0.016, 2.4, theme.smallScale(), true, -1) : null;
                            if (words[0] != null) drawn.add(words[0]);
                            if (on) set(ICON_LABEL, "{letters}" + button.words());
                            else if (("{letters}" + button.words()).equals(get(ICON_LABEL))) set(ICON_LABEL, null);
                        })
                        .onPress(click -> act(button.action()))));
            }
            delay = REPOP;
        }

        private @Nullable DisplayHandle glyph(IconButton button, double across, boolean lit, long delay) {
            // In its own colour when it has one, brighter and bolder under the crosshair.
            String colour = button.colour().isBlank() ? (lit ? "{secondary_light}" : "{letters}") : button.colour();
            String text = colour + (lit ? "&l" : "") + button.glyph();
            double size = theme.titleScale() * (lit ? 1.5 : 1.3);
            return write(text, true, panel.plane, panel.rotation, across, 0, 0.016, panel.panel.height() * ICON_PITCH,
                    size, true, delay);
        }
    }

    /**
     * A {@code PALETTE} content (since 1.461.0): a compact grid of a source's entries, each its name as written (a
     * word, or a glyph in its colour), no plates. The chosen one (marked {@code SELECTED}, else the selection) has a
     * thin accent line under it; the one under the crosshair grows in place (no display is made or taken down for a
     * hover, so a click that redraws the grid under the crosshair leaves nothing behind), its subtitle (else its name)
     * and price written once under the grid. A locked entry is drawn smaller. A click chooses it, also when it is
     * chosen already, so a plugin can toggle (edit a layer, stop editing it).
     */
    private final class Palette {
        private static final double UNDERLINE = 0.014;
        private final PanelPart panel;
        private final String source;
        private final int columns;
        private final int rows;
        private final List<DisplayHandle> drawn = new ArrayList<>();
        private final List<Hotspot> spots = new ArrayList<>();
        private List<SpatialEntry> shown = List.of();
        private int active = -1;
        private long delay;
        private @Nullable DisplayHandle words;
        /** Which draw the hotspots belong to: a hotspot taken down while hovered still says so, too late. */
        private int generation;

        Palette(PanelPart panel, SpatialLayout.Content content, long delay) {
            this.panel = panel;
            this.source = content.source();
            this.columns = content.columns();
            this.rows = content.rows();
            this.delay = delay;
            refresh();
        }

        private List<SpatialEntry> entries() {
            var supplier = definition.sources.get(source);
            List<SpatialEntry> all = supplier == null ? List.of() : List.copyOf(supplier.apply(MenuSession.this));
            return all.size() > columns * rows ? all.subList(0, columns * rows) : all;
        }

        /** The chosen entry: marked {@code SELECTED}, else the selection; {@code -1} for none. */
        private int activeOf(List<SpatialEntry> entries) {
            for (int i = 0; i < entries.size(); i++) if (entries.get(i).state() == SpatialEntry.State.SELECTED) return i;
            String id = selected.get(source);
            for (int i = 0; i < entries.size(); i++) if (entries.get(i).id().equals(id)) return i;
            return -1;
        }

        void refresh() {
            List<SpatialEntry> now = entries();
            int at = activeOf(now);
            if (now.equals(shown) && at == active) return;
            shown = now;
            active = at;
            draw();
        }

        private double[] cell(int index, int count) {
            int across = Math.min(columns, count);
            int down = (count + columns - 1) / columns;
            double pitchX = panel.panel.width() / Math.max(1, across);
            double pitchY = panel.panel.height() / Math.max(1, down);
            int column = index % columns;
            int row = index / columns;
            // A last row with fewer entries stands centred under the full ones.
            int inRow = row == down - 1 ? count - row * columns : across;
            return new double[]{(column - (inRow - 1) / 2.0) * pitchX, ((down - 1) / 2.0 - row) * pitchY, pitchX, pitchY};
        }

        /** The largest an entry is drawn: a glyph (a swatch, a mark) as large as a title, words as a body line. */
        private double max(SpatialEntry entry) {
            String plain = plain(Text.verbatim(fill(entry.name()))).strip();
            double max = plain.codePointCount(0, plain.length()) <= 2 ? theme.titleScale() * 1.2 : theme.textScale();
            return entry.state() == SpatialEntry.State.LOCKED ? max * 0.75 : max;
        }

        private @Nullable DisplayHandle entry(SpatialEntry entry, double[] at, long wait) {
            return write(entry.name(), true, panel.plane, panel.rotation, at[0], at[1], 0.016, at[2] * 0.92, max(entry),
                    true, wait);
        }

        /** Grows the entry's glyph in place while it is looked at, and back: the same display, only its scale. */
        private void grow(@Nullable DisplayHandle glyph, SpatialEntry entry, double[] at, boolean lit) {
            if (glyph == null) return;
            DisplayKeyframe here = glyph.pose();
            if (here == null) return;
            double base = fit(fill(entry.name()), true, at[2] * 0.92, max(entry), panel.plane.point(at[0], at[1], 0.016)).scale();
            float size = (float) (lit ? base * 1.25 : base);
            glyph.then(DisplayMotion.of(List.of(here.at(0), new DisplayKeyframe(90, here.x(), here.y(), here.z(),
                    here.rotation(), size, size, size)), LIFE));
        }

        private void draw() {
            drawn.forEach(MenuSession.this::drop);
            drawn.clear();
            spots.forEach(pointer::remove);
            spots.clear();
            drop(words);
            words = null;
            int drawing = ++generation;
            int count = shown.size();
            long wait = delay;
            for (int i = 0; i < count; i++) {
                SpatialEntry entry = shown.get(i);
                double[] at = cell(i, count);
                DisplayHandle glyph = entry(entry, at, wait < 0 ? wait : wait + i * 25L);
                if (glyph != null) drawn.add(glyph);
                if (i == active) {
                    double width = Math.min(at[2] * 0.7, 0.3);
                    DisplayHandle line = sheet(panel.plane.point(at[0], at[1] - at[3] * 0.42, 0.016), panel.rotation, width,
                            UNDERLINE, Geometry.argb(255, colour(theme.accent(), 0x8A51C4)), wait < 0 ? -1 : wait + 120);
                    if (line != null) drawn.add(line);
                }
                if (edit != null) continue;
                spots.add(pointer.add(Hotspot.rect(panel.plane, at[0], at[1], at[2] * 0.95, at[3] * 0.95).priority(2)
                        .onHover(on -> {
                            if (drawing != generation) return;
                            grow(glyph, entry, at, on);
                            drop(words);
                            words = on ? describe(entry) : null;
                        })
                        .onPress(click -> choose(entry))));
            }
            delay = REPOP;
        }

        /** The words of the entry looked at, once, under the grid: its subtitle (else its name), then its price. */
        private @Nullable DisplayHandle describe(SpatialEntry entry) {
            String about = entry.subtitle().isBlank() ? entry.name() : entry.subtitle();
            String price = entry.state() == SpatialEntry.State.LOCKED && entry.price() != null && !entry.price().isBlank()
                    ? " {letters_black}· " + entry.price() : "";
            double under = -panel.panel.height() / 2 - SpatialLayout.Content.PALETTE_WORDS / 2;
            return write("{letters}" + about + price, true, panel.plane, panel.rotation, 0, under, 0.016,
                    Math.max(panel.panel.width(), 1.6), theme.smallScale(), true, -1);
        }

        private void choose(SpatialEntry entry) {
            confirm.disarm();
            selected.put(source, entry.id());
            var select = definition.selects.get(source);
            if (select != null) select.accept(MenuSession.this, entry);
            MenuSession.this.refresh();
        }
    }

    /** How a list draws its entries. */
    private enum Kind { ROW, CELL, PEDESTAL, SHELF, ARC }

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
        /** The first item shown: a page's start, or any item once the wheel moved it. */
        int offset;
        /** How many entries the list had at its last redraw. */
        int total;
        /** The notches of the wheel still to play, one item each. */
        final WheelSteps wheel = new WheelSteps(WHEEL_QUEUE);
        /** While a wheel step redraws: every item slides one place over instead of a page sliding away. */
        boolean gliding;
        /** Where the page line stands across the carousel's plane: an arc's under its right arrow. */
        private double pageAcross;
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

        /** The carousel: a row of plinths, or a shelf of tiles. */
        Slots(SpatialLayout.Carousel row, long delay) {
            this.kind = row.shelf() ? Kind.SHELF : row.arc() ? Kind.ARC : Kind.PEDESTAL;
            this.source = row.source();
            this.delay = delay;
            this.perPage = row.perPage();
            if (kind == Kind.ARC) {
                this.plane = Geometry.panel(anchor.getWorld(), board, row.at().get(0), row.at().get(1), row.at().get(2), 0);
                this.rotation = facing(plane);
                this.slotWidth = row.spacing() * 0.9;
                this.slotHeight = row.spacing() * 0.9;
                this.iconScale = row.spacing() * ARC_ICON;
                for (int i = 0; i < perPage; i++) {
                    double[] slot = Geometry.carousel(i, perPage, row.spacing(), row.centreGap(), row.curve());
                    double depth = distance - row.at().get(2) - slot[1];
                    StagePlane turned = Geometry.panel(anchor.getWorld(), board, row.at().get(0) + slot[0], row.at().get(1),
                            row.at().get(2) + slot[1], Geometry.facingTurn(row.at().get(0) + slot[0], depth));
                    places.add(new Place(turned, facing(turned), slot[0], 0, turned.point(0, 0, 0)));
                }
                emptyUp = 0;
                captionUp = Double.NaN;
                hotspots(0, 0);
                // The arrows just past either end of the arc, as near as its ends; the page under the right one.
                double outer = Geometry.outermost(perPage, row.spacing(), row.centreGap()) + row.spacing() * 0.9;
                shelfArrow(-outer, 0, row.curve(), -1, theme.previousArrow());
                shelfArrow(outer, 0, row.curve(), 1, theme.nextArrow());
                pageAcross = outer - row.spacing() * 0.5;
                pageUp = -slotHeight * 0.95;
                return;
            }
            if (kind == Kind.SHELF) {
                this.plane = Geometry.panel(anchor.getWorld(), board, row.at().get(0), row.at().get(1), row.at().get(2), 0);
                this.rotation = facing(plane);
                this.slotWidth = row.spacing() * SHELF_TILE;
                this.slotHeight = Math.min(slotWidth, row.rowGap() * 0.86);
                this.iconScale = slotHeight * SHELF_ICON;
                for (int i = 0; i < perPage; i++) {
                    double[] slot = Geometry.shelf(i, row.slots(), row.spacing(), row.rowGap(), row.curve());
                    double depth = distance - row.at().get(2) - slot[2];
                    StagePlane turned = Geometry.panel(anchor.getWorld(), board, row.at().get(0) + slot[0],
                            row.at().get(1) + slot[1], row.at().get(2) + slot[2],
                            Geometry.facingTurn(row.at().get(0) + slot[0], depth));
                    places.add(new Place(turned, facing(turned), slot[0], slot[1], turned.point(0, 0, 0)));
                }
                double bottom = -(row.rows() - 1) * row.rowGap();
                emptyUp = bottom / 2;
                pageUp = bottom - slotHeight / 2 - SHELF_PAGE_GAP;
                captionUp = Double.NaN;
                hotspots(0, 0);
                // The arrows at the ends of the rows, half-way down, a little nearer like the outermost tiles.
                double outer = Geometry.outermost(row.slots(), row.spacing(), 0) + row.spacing() * 0.5 + SHELF_ARROW_GAP;
                shelfArrow(-outer, bottom / 2, row.curve(), -1, theme.previousArrow());
                shelfArrow(outer, bottom / 2, row.curve(), 1, theme.nextArrow());
                return;
            }
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

        /** The plane of a point {@code across} an arc's row at its ends' nearness, turned to the eyes. */
        private StagePlane arrowPlane(double across) {
            SpatialLayout.Carousel row = layout.carousel();
            double depth = distance - row.at().get(2) - row.curve();
            return Geometry.panel(anchor.getWorld(), board, row.at().get(0) + across, row.at().get(1),
                    row.at().get(2) + row.curve(), Geometry.facingTurn(row.at().get(0) + across, Math.max(0.5, depth)));
        }

        /** A shelf's page arrow: large, beside the end of the rows, turned to the eyes, lit under the crosshair. */
        private void shelfArrow(double across, double up, double curve, int step, String label) {
            double depth = distance - layout.carousel().at().get(2) - curve;
            StagePlane own = Geometry.panel(anchor.getWorld(), board, layout.carousel().at().get(0) + across,
                    layout.carousel().at().get(1) + up, layout.carousel().at().get(2) + curve,
                    Geometry.facingTurn(layout.carousel().at().get(0) + across, Math.max(0.5, depth)));
            Rotation turn = facing(own);
            if (kind == Kind.ARC) {
                // Quiet chevrons, muted until looked at.
                DisplayHandle[] chevron = {write(fill(label), true, own, turn, 0, 0, 0.01, 0.5, theme.titleScale() * 1.3, true,
                        delay < 0 ? -1 : delay + 260)};
                if (edit != null) return;
                pointer.add(Hotspot.rect(own, 0, 0, 0.45, slotHeight * 1.3).priority(2)
                        .onHover(on -> {
                            drop(chevron[0]);
                            String words = plain(Text.verbatim(fill(label))).strip();
                            chevron[0] = write(on ? "{letters}&l" + words : fill(label), true, own, turn, 0, 0, 0.01, 0.5,
                                    theme.titleScale() * (on ? 1.5 : 1.3), true, -1);
                        })
                        .onPress(click -> turnPage(this, step)));
                return;
            }
            DisplayHandle[] shown = new DisplayHandle[1];
            shown[0] = write(fill(label), false, own, turn, 0, 0, 0.01, 0.5, theme.titleScale() * 1.3, true,
                    delay < 0 ? -1 : delay + 200);
            if (edit != null) return;
            pointer.add(Hotspot.rect(own, 0, 0, 0.5, slotHeight * 1.2).priority(2)
                    .onHover(on -> {
                        drop(shown[0]);
                        String lit = on ? theme.hoverGlow() + "&l" + plain(Text.component(fill(label))) : fill(label);
                        shown[0] = write(lit, false, own, turn, 0, 0, 0.01, 0.5, theme.titleScale() * (on ? 1.45 : 1.3), true, -1);
                    })
                    .onPress(click -> turnPage(this, step)));
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
            IntConsumer scroll = notch -> wheel(this, notch);
            if (kind == Kind.ROW || kind == Kind.CELL) {
                // The whole panel moves its list under the wheel, not only its slots.
                pointer.add(Hotspot.rect(plane, 0, 0, panelWidth, panelHeight).priority(-1).silent(true).onScroll(scroll));
            }
            for (int i = 0; i < perPage; i++) {
                int slot = i;
                Place place = places.get(i);
                // A plinth answers from its item down to its label, on its own turned plane.
                Hotspot spot = kind == Kind.PEDESTAL
                        ? Hotspot.rect(place.plane(), 0, -0.13, slotWidth, 0.62)
                        : kind == Kind.SHELF || kind == Kind.ARC ? Hotspot.rect(place.plane(), 0, 0, slotWidth, slotHeight)
                        : Hotspot.rect(plane, place.across(), place.up(), slotWidth, kind == Kind.ROW ? slotHeight * 0.96 : slotHeight);
                boolean carried = kind == Kind.PEDESTAL || kind == Kind.SHELF || kind == Kind.ARC;
                spot.priority(1)
                        .onHover(on -> hover(slot, on))
                        .onPress(click -> choose(slot))
                        .onScroll(carried ? null : scroll)
                        .enabled(false);
                spots.add(pointer.add(spot));
            }
        }

        @Override
        public void refresh() {
            var supplier = definition.sources.get(source);
            List<SpatialEntry> all = supplier == null ? List.of() : List.copyOf(supplier.apply(MenuSession.this));
            int pageCount = Geometry.pages(all.size(), perPage);
            total = all.size();
            // The wheel moves the first item shown anywhere round the ring; a page turn puts it on a page's start.
            Integer free = offsets.get(source);
            int from;
            List<SpatialEntry> now;
            if (free != null && all.size() > perPage) {
                from = Geometry.wrapItem(free, all.size(), perPage);
                offsets.put(source, from);
                now = Geometry.ring(all, from, perPage);
            } else {
                offsets.remove(source);
                from = Geometry.wrap(page(source), pageCount) * perPage;
                now = Geometry.page(all, from / perPage, perPage);
            }
            // The page counter reads the page the first item shown is on.
            int page = from / perPage;
            pages.put(source, page);
            List<Integer> changed = Geometry.changed(shown, now, perPage);
            // A page turn on the carousel: the old page slides off one side, the new comes in from the other.
            // A wheel step: every item slides one place over, all at once.
            boolean turning = (kind == Kind.PEDESTAL || kind == Kind.SHELF || kind == Kind.ARC) && !first && from != offset;
            int side = step != 0 ? Integer.signum(step) : Integer.signum(from - offset);
            boolean glide = turning && gliding && places.size() > 1;
            double[] across = offset(plane, 1, 0, 0);
            offset = from;
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
                    slideOut(new ArrayList<>(drawn.get(slot)), glide ? shift(slot, -side) : scaled(across, -side * 0.55));
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
                    long at = first ? (delay < 0 ? -1 : delay + slot * 45L) : glide ? 0L : wave++ * 35L;
                    slideFrom = !turning ? null : glide ? shift(slot, side) : scaled(across, side * 0.55);
                    boolean isChosen = id != null ? entry.id().equals(id) : entry.state() == SpatialEntry.State.SELECTED;
                    draw(slot, entry, at, isChosen ? Pose.CHOSEN : slot == hovered ? Pose.HOVER : Pose.IDLE);
                    slideFrom = null;
                }
                if (slot < spots.size()) spots.get(slot).enabled(entry != null);
            }
            first = false;
            // The crosshair stayed on a slot whose item changed under it: the words follow the new item.
            SpatialEntry under = turning && hovered >= 0 ? at(hovered) : null;
            if (under != null && !under.id().equals(hovers.get(source))) {
                hovers.put(source, under.id());
                hoverSince.put(source, System.currentTimeMillis());
                dwellTold.remove(source);
                wordsStale = false;
                refreshWords();
            }
            // Empty: one line that says so, in the middle.
            if (now.isEmpty() && empty == null) {
                empty = write(fill(theme.emptyLine()), true, plane, rotation, 0, emptyUp, 0.012,
                        kind == Kind.PEDESTAL || kind == Kind.SHELF || kind == Kind.ARC ? 2.4 : slotWidth * 0.9, theme.smallScale(), false, -1);
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

        /** From a slot to the place {@code dir} slots along (world axes); past either end, one more step the same way. */
        private double[] shift(int slot, int dir) {
            int to = slot + dir;
            boolean inside = to >= 0 && to < places.size();
            Location here = places.get(slot).at();
            Location there = places.get(inside ? to : slot - dir).at();
            double k = inside ? 1 : -1;
            return new double[]{(there.getX() - here.getX()) * k, (there.getY() - here.getY()) * k,
                    (there.getZ() - here.getZ()) * k};
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
                case ARC -> {
                    StagePlane own = place.plane();
                    Rotation turn = place.rotation();
                    // A soft disc of light under it, nothing behind it.
                    DisplayHandle disc = show(discModel(false, false), motion(flat(turn), discScale(), discScale(), discScale(),
                            at < 0 ? -1 : at), own.point(0, -iconScale * ARC_DISC_DROP, -discScale() * DISC_CENTRE));
                    keep(slot, disc);
                    plates.set(slot, disc);
                    DisplayHandle icon = show(iconModel, iconMotion(true, true, ARC_HOVER_AHEAD, ARC_HOVER_GROW, slot, turn,
                            iconScale, iconScale, pose, towards, at < 0 ? -1 : at + 40), own.point(0, 0, 0));
                    keep(slot, icon);
                    icons.set(slot, icon);
                    String marker = switch (entry.state()) {
                        case EQUIPPED -> theme.equippedMarker();
                        case LOCKED -> theme.lockedMarker();
                        default -> "";
                    };
                    if (marker != null && !marker.isBlank()) {
                        // A small badge on the icon's top corner, kept inside the slot.
                        keep(slot, write(fill(marker, entry), true, own, turn, iconScale * 0.42, iconScale * 0.48, 0.06,
                                0.4, theme.smallScale(), true, at < 0 ? -1 : at + 90));
                    }
                }
                case SHELF -> {
                    StagePlane own = place.plane();
                    Rotation turn = place.rotation();
                    // A dark tile, warmed when chosen or looked at; the icon just in front of it, dimmed when locked.
                    DisplayHandle tile = sheet(own.point(0, 0, 0), turn, slotWidth, slotHeight,
                            Geometry.argb(255, tileColour(false, false)), at);
                    keep(slot, tile);
                    plates.set(slot, tile);
                    DisplayHandle icon = show(iconModel, iconMotion(true, true, slot, turn, iconScale, iconScale, pose, towards,
                            at < 0 ? -1 : at + 60), own.point(0, 0, 0));
                    keep(slot, icon);
                    icons.set(slot, icon);
                    // Its state in the tile's top corner: worn, or not theirs yet.
                    String marker = switch (entry.state()) {
                        case EQUIPPED -> theme.equippedMarker();
                        case LOCKED -> theme.lockedMarker();
                        default -> "";
                    };
                    if (marker != null && !marker.isBlank()) {
                        keep(slot, write(fill(marker, entry), true, own, turn, slotWidth * 0.34, slotHeight * 0.3, 0.09,
                                0.4, theme.smallScale(), true, at < 0 ? -1 : at + 90));
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
                hoverSince.put(source, System.currentTimeMillis());
                dwellTold.remove(source);
                wordsStale = false;
                refreshWords();
            } else if (entry != null && entry.id().equals(hovers.get(source))) {
                hovers.remove(source);
                hoverSince.remove(source);
                dwellTold.remove(source);
                wordsStale = true;
            }
        }

        /** How large an arc's disc is drawn: a "●" about as wide as the icon. */
        private double discScale() {
            return iconScale * ARC_DISC_SIZE;
        }

        /** An arc's disc: dark at rest, lighter under the crosshair, the accent when chosen. */
        private Component discText(boolean on, boolean chosen) {
            int idle = Geometry.mix(0x000000, colour(theme.plateIdle(), 0x8A51C4), 0.5);
            int rgb = chosen ? colour(theme.accent(), 0x8A51C4)
                    : on ? Geometry.mix(idle, colour(theme.plateHover(), 0xAA76DE), 0.7) : idle;
            return Text.verbatim(String.format("<#%06x>●", rgb & 0xFFFFFF));
        }

        private DisplayModel discModel(boolean on, boolean chosen) {
            return Stage.textModel(discText(on, chosen)).background(0).textShadow(false).seeThrough(false).lineWidth(1000);
        }

        /** An entry's own colour: the first its subtitle is written in (a rarity's), else {@code fallback}. */
        private int accentOf(@Nullable SpatialEntry entry, int fallback) {
            if (entry == null || entry.subtitle().isBlank()) return fallback;
            net.kyori.adventure.text.format.TextColor found = firstColour(Text.component(entry.subtitle()));
            return found == null ? fallback : found.value();
        }

        /** Dresses a slot for its state: resting, under the crosshair, or chosen. */
        private void style(int slot) {
            if (slot < 0 || slot >= drawn.size()) return;
            boolean on = slot == hovered;
            boolean picked = slot == chosen;
            boolean shelf = kind == Kind.SHELF || kind == Kind.ARC;
            Pose pose = shelf ? (on ? Pose.HOVER : picked ? Pose.CHOSEN : Pose.IDLE)
                    : kind == Kind.PEDESTAL && picked ? Pose.CHOSEN : on ? Pose.HOVER : Pose.IDLE;
            DisplayHandle icon = icons.get(slot);
            Place place = places.get(slot);
            if (icon != null && poses.get(slot) != pose) {
                poses.set(slot, pose);
                boolean plinth = kind == Kind.PEDESTAL || shelf;
                icon.then(kind == Kind.ARC
                        ? iconMotion(true, true, ARC_HOVER_AHEAD, ARC_HOVER_GROW, slot, place.rotation(), iconScale, iconScale,
                                pose, offset(place.plane(), 0, 0, 1), REPOP)
                        : iconMotion(plinth, shelf, slot, place.rotation(), iconScale, plinth ? iconScale : iconScale * 0.3,
                                pose, offset(place.plane(), 0, 0, 1), REPOP));
            }
            if (kind == Kind.ARC) {
                // Under the crosshair it glows in its own colour (its subtitle's: a rarity), and its disc lights.
                SpatialEntry entry = at(slot);
                if (icon != null) icon.glow(on ? accentOf(entry, colour(theme.hoverGlow(), 0xFFD700)) : -1);
                DisplayHandle disc = plates.get(slot);
                if (disc != null) disc.text(discText(on, picked));
                return;
            }
            if (icon != null && kind != Kind.PEDESTAL) icon.glow(on ? colour(theme.hoverGlow(), 0xFFD700) : -1);
            if (shelf) {
                DisplayHandle tile = plates.get(slot);
                if (tile != null) tile.restyle(sheetModel(Geometry.argb(255, tileColour(on, picked))));
                return;
            }
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
            var select = definition.selects.get(source);
            undoable(() -> {
                selected.put(source, entry.id());
                if (select != null) select.accept(MenuSession.this, entry);
            });
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
            // A shelf marks the chosen entry with its tile, an arc with its disc, not a sliding bar.
            if (kind == Kind.SHELF || kind == Kind.ARC) return;
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
            boolean show = kind == Kind.PEDESTAL || kind == Kind.SHELF || kind == Kind.ARC && pageCount > 1 || pageCount > 1;
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
            double room = kind == Kind.PEDESTAL ? 0.62 : kind == Kind.SHELF || kind == Kind.ARC ? 1.6 : slotWidth * 0.9;
            pageLine = write(raw, true, kind == Kind.ARC ? arrowPlane(pageAcross) : plane,
                    kind == Kind.ARC ? facing(arrowPlane(pageAcross)) : rotation, 0, pageUp, 0.012, room, theme.smallScale(),
                    false, delay < 0 ? -1 : delay + 220);
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

    /** The piece in the middle on its pedestal: an NPC, an item, an effect on a mannequin, or a flight. */
    private final class PreviewPart {

        private final StagePlane plane;
        private final Location base;
        private final Rotation rotation;
        private SpatialPreview current = SpatialPreview.none();
        private @Nullable NpcHandle npc;
        private @Nullable DisplayHandle item;
        private @Nullable SequenceRun run;
        private long nextPlay;
        private long npcUntil;
        /** Whether a mannequin is taken down while its effect plays: it stands again once the effect is gone. */
        private boolean hidden;
        /** What the NPC was last sent, slot by slot, so a re-dress sends only what changed. */
        private final Map<EquipmentSlot, ItemStack> dressed = new EnumMap<>(EquipmentSlot.class);
        /** A performance's runs: one body, or a duet's two. */
        private final List<SequenceRun> acts = new ArrayList<>(2);
        /** Whose skin a mannequin with nobody named wears, picked each time it stands, worn by its effect too. */
        private @Nullable net.exylia.lib.npc.PreviewSkins.Body body;
        /** A duet's second body, from {@code preview.skins}. */
        private @Nullable net.exylia.lib.npc.PreviewSkins.Body partner;
        /** A performance's body in the skin it was handed, built once rather than on every encore. */
        private @Nullable RagdollModel own;
        /** When that body stood; its first play waits up to {@link #SKIN_WAIT_MILLIS} for its skin to be read. */
        private long stoodAt;
        /** A flight's target and the projectile in the air (or stuck in it), with what they set off. */
        private @Nullable DisplayHandle target;
        private @Nullable DisplayHandle projectile;
        private final List<SequenceRun> flightRuns = new ArrayList<>();
        /** When the projectile last set off, 0 before the first; the trail steps drawn since; whether it landed. */
        private long launchedAt;
        private int trailSteps;
        private boolean landed;
        /** With {@code turn-controls}: which way the body faces, turned by the viewer or on its own. */
        private final @Nullable net.exylia.lib.spatial.internal.Turntable table;
        private float sentYaw = Float.NaN;
        private final DisplayHandle[] arrows = new DisplayHandle[2];
        /** An effect played out of sight to learn how to show it, what it showed, where and since when. */
        private @Nullable SequenceRun rehearsal;
        private @Nullable List<double[]> rehearsing;
        private @Nullable Location rehearsalBase;
        private long rehearsalSince;

        PreviewPart(long delay) {
            SpatialLayout.Preview look = layout.preview();
            this.plane = Geometry.panel(anchor.getWorld(), board, look.at().get(0), look.at().get(1), look.at().get(2), 0);
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
            table = look.turnControls() ? new net.exylia.lib.spatial.internal.Turntable(look.facingDegrees(),
                    look.turnSpeed(), (long) (look.turnPause() * 1000), System.currentTimeMillis()) : null;
            if (table != null) controls(look, delay);
        }

        /** The arrows either side of the pedestal, the hint under it, and the body answering clicks and the wheel. */
        private void controls(SpatialLayout.Preview look, long delay) {
            long after = delay < 0 ? -1 : delay + 180;
            for (int side = 0; side < 2; side++) arrow(side, false, after);
            if (!look.turnHint().isBlank()) {
                write(fill(look.turnHint()), true, plane, rotation, 0, -0.27, 0.4, 1.1, theme.smallScale(), true, after);
            }
            if (edit != null) return;
            for (int side = 0; side < 2; side++) {
                int which = side;
                pointer.add(Hotspot.rect(plane, ARROW_ACROSS * (side == 0 ? -1 : 1), ARROW_UP, 0.26, 0.26).priority(2)
                        .onHover(on -> arrow(which, on, -1))
                        .onPress(click -> turn(which == 0 ? -1 : 1)));
            }
            // The body itself: a click turns it to the next side (sneaking, the other way), the wheel a little.
            double height = 1.9 * look.scale();
            pointer.add(Hotspot.rect(plane, 0, height / 2, 0.75 * look.scale(), height).priority(1)
                    .onPress(click -> turn(click.sneaking() ? -1 : 1))
                    .onScroll(notch -> {
                        if (table != null) table.turnBy(notch * WHEEL_DEGREES, System.currentTimeMillis());
                        keep();
                    }));
        }

        /** One of the two arrows, lit while the crosshair is on it. */
        private void arrow(int side, boolean lit, long delay) {
            drop(arrows[side]);
            String glyph = side == 0 ? "↺" : "↻";
            String text = (lit ? theme.hoverGlow() : "{letters}") + "&l" + glyph;
            arrows[side] = write(fill(text), false, plane, rotation, ARROW_ACROSS * (side == 0 ? -1 : 1),
                    ARROW_UP - 0.08, 0.36, 0.3, theme.textScale() * (lit ? 1.5 : 1.3), true, delay);
        }

        /** The viewer turns the body to the next side, {@code direction} the way round. */
        private void turn(int direction) {
            if (table == null) return;
            table.nextSide(direction, System.currentTimeMillis());
            keep();
        }

        /** With the controls: the body turned to where the table faces now, when that moved. */
        private void face() {
            if (table == null || npc == null) return;
            float yaw = (float) (board.yaw() + 180 + table.angle(System.currentTimeMillis()));
            if (!Float.isNaN(sentYaw) && Math.abs(yaw - sentYaw) < 0.25f) return;
            sentYaw = yaw;
            npc.look(yaw, 0);
        }

        // A click (on the input's thread) and the poll (on the viewer's) both drive the stage: one at a time, so a
        // play the poll starts as a selection lands can never outlive it.
        synchronized void update(SpatialPreview next) {
            if (next == null) next = SpatialPreview.none();
            if (current instanceof SpatialPreview.LiveNpc && next instanceof SpatialPreview.LiveNpc live
                    && next.equals(current) && npc != null && npc.isShowing()) {
                // The same body: only its supplier is new, read at once.
                current = next;
                dress(live.equipment().get());
                return;
            }
            if (next.equals(current) && (npc == null || npc.isShowing())) return;
            if (current instanceof SpatialPreview.Npc was && next instanceof SpatialPreview.Npc now
                    && was.skin().equals(now.skin()) && was.equipment() != null && now.equipment() != null
                    && npc != null && npc.isShowing()) {
                current = next;
                dress(now.equipment());
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
                    dress(body.equipment());
                }
                case SpatialPreview.LiveNpc body -> {
                    spawn(NpcModel.of(body.skin()).scale(look.scale()).nameless(), stand, look);
                    dress(body.equipment().get());
                }
                case SpatialPreview.Performance act -> {
                    body = act.skin() == null ? look.skinPolicy().pick(viewer) : null;
                    partner = act.isDuet() ? look.skinPolicy().pick(viewer) : null;
                    own = act.skin() == null ? null : RagdollModel.of(act.skin());
                    stoodAt = System.currentTimeMillis();
                    nextPlay = 0;
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
                case SpatialPreview.Flight flight -> {
                    double size = Flights.TARGET * look.scale();
                    target = show(DisplayModel.block(Material.TARGET.createBlockData()).light(15),
                            motion(rotation, size, size, size, -1), plane.point(0, size / 2, 0));
                    nextPlay = 0;
                }
                case SpatialPreview.None none -> { }
            }
        }

        /** A flight, every poll: sets off again when due, draws its trail where it is, its impact once it lands. */
        private void fly(SpatialPreview.Flight flight, long now) {
            if (now >= nextPlay) launch(flight, now);
            long flown = now - launchedAt;
            if (flight.trail() != null && flown < Flights.FLIGHT_MILLIS) {
                // At most one step a poll, where the projectile is now: a step missed between two polls is
                // not drawn late, behind it.
                int step = (int) (flown / (flight.trailEveryTicks() * 50L));
                if (step > trailSteps) {
                    trailSteps = step;
                    play(flight.trail(), (double) flown / Flights.FLIGHT_MILLIS);
                }
            }
            if (!landed && flown >= Flights.FLIGHT_MILLIS) {
                landed = true;
                if (flight.impact() != null) play(flight.impact(), 1);
            }
        }

        private void launch(SpatialPreview.Flight flight, long now) {
            drop(projectile);
            flightRuns.removeIf(SequenceRun::isFinished);
            launchedAt = now;
            trailSteps = 0;
            landed = false;
            long impact = flight.impact() == null || flight.impact().isEndless() ? 0
                    : Math.min(3000L, flight.impact().durationMillis());
            long cycle = Math.max(Flights.CYCLE_MILLIS, Flights.FLIGHT_MILLIS + impact + 700L);
            nextPlay = now + cycle;
            double scale = layout.preview().scale();
            double size = 0.5 * scale;
            double[] start = Flights.at(0, scale);
            List<DisplayKeyframe> poses = new ArrayList<>();
            for (long at = 0; at <= Flights.FLIGHT_MILLIS; at += 100) {
                double progress = (double) at / Flights.FLIGHT_MILLIS;
                double[] here = Flights.at(progress, scale);
                double[] moved = offset(plane, here[0] - start[0], here[1] - start[1], 0);
                poses.add(new DisplayKeyframe(at, (float) moved[0], (float) moved[1], (float) moved[2],
                        pointing(headingAt(progress)), (float) size, (float) size, (float) size));
            }
            // Stuck in the target until it sets off again.
            poses.add(poses.get(poses.size() - 1).at(cycle));
            projectile = show(DisplayModel.item(flight.projectile()).light(15), DisplayMotion.of(poses, cycle + 200L),
                    plane.point(start[0], start[1], 0));
            if (flight.launch() != null) play(flight.launch(), 0);
        }

        /** Plays a moment of the flight where the projectile is at {@code progress}, facing the way it flies. */
        private void play(net.exylia.lib.util.sequence.Sequence sequence, double progress) {
            double[] here = Flights.at(progress, layout.preview().scale());
            Location at = plane.point(here[0], here[1], 0);
            // An impact faces along the stage, level, as a real hit faces the way the shot came.
            at.setDirection(progress >= 1 ? headingAt(-1) : headingAt(progress));
            flightRuns.add(Sequences.of(menus.plugin()).play(sequence, SequenceTarget.at(at).by(viewer).onlyTo(viewer)
                    .scale(layout.preview().effectScale())));
        }

        /** The projectile's heading in the world; a negative progress is level, to the viewer's right. */
        private Vector headingAt(double progress) {
            double[] heading = progress < 0 ? new double[]{1, 0} : Flights.heading(progress, 1);
            double[] world = offset(plane, heading[0], heading[1], 0);
            return new Vector(world[0], world[1], world[2]);
        }

        private void mannequin(SpatialPreview.Effect effect) {
            Location stand = base.clone();
            // Turned as the effect will play, so the body that dies is the one that stood there.
            stand.setYaw((float) (board.yaw() + 180 + effectTurn(effect)));
            body = effect.skin() == null ? layout.preview().skinPolicy().pick(viewer) : null;
            stoodAt = System.currentTimeMillis();
            NpcModel model = body != null ? body.npc() : NpcModel.of(effect.skin());
            // The size the effect plays at, so the body that dies is the one that stood there.
            spawn(model.scale(effectScale(effect)).nameless(), stand, layout.preview());
        }

        /** How an effect is turned from facing the viewer: its own facing, the layout's, or what was learned. */
        private double effectTurn(SpatialPreview.Effect effect) {
            String facing = effect.facing() != null ? effect.facing() : layout.preview().effectFacing();
            double fixed = SpatialLayout.Preview.degreesOf(facing);
            if (!Double.isNaN(fixed)) return fixed;
            net.exylia.lib.spatial.internal.EffectView.Tuning learned = TUNED.get(key(effect));
            return learned == null ? 0 : learned.turn();
        }

        /** How large an effect plays: the layout's effect-scale, smaller when its zone needs it. */
        private double effectScale(SpatialPreview.Effect effect) {
            net.exylia.lib.spatial.internal.EffectView.Tuning learned = TUNED.get(key(effect));
            return layout.preview().effectScale() * (learned == null ? 1 : learned.scale());
        }

        private String key(SpatialPreview.Effect effect) {
            List<Double> zone = layout.preview().zone();
            String facing = effect.facing() != null ? effect.facing() : layout.preview().effectFacing();
            return System.identityHashCode(effect.sequence()) + "|" + facing + "|" + zone + "|" + layout.preview().effectScale();
        }

        /** Whether this effect's view is still to be learned from the play about to start. */
        private boolean learning(SpatialPreview.Effect effect) {
            if (TUNED.containsKey(key(effect))) return false;
            String facing = effect.facing() != null ? effect.facing() : layout.preview().effectFacing();
            List<Double> zone = layout.preview().zone();
            return Double.isNaN(SpatialLayout.Preview.degreesOf(facing)) || zone.get(0) > 0 && zone.get(1) > 0;
        }

        /**
         * Learns how to show an effect before anybody sees it: plays it once out of sight (a miniature far over
         * the board, too small and too far to see or hear), watches where its pieces go, and chooses its turn
         * and its size from that. Every visible play, the first included, then looks the same.
         */
        private void rehearse(SpatialPreview.Effect effect, long now) {
            if (rehearsal != null) return;
            Location far = base.clone().add(0, REHEARSAL_UP, 0);
            far.setYaw(board.yaw() + 180);
            rehearsing = new ArrayList<>();
            rehearsalBase = far;
            rehearsalSince = now;
            SequenceTarget where = SequenceTarget.at(far).by(viewer).onlyTo(viewer).scale(REHEARSAL_SCALE);
            where = body != null ? where.wearing(body.ragdoll()) : where.wearing(net.exylia.lib.npc.PreviewSkins.extra(0));
            rehearsal = Sequences.of(menus.plugin()).play(effect.sequence(), where);
        }

        /** While a rehearsal runs: where its pieces are; once it is over (or has run long enough), the choice. */
        private void watch() {
            if (rehearsal == null) return;
            java.util.Set<Object> ours = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            ours.addAll(pieces);
            rehearsing.addAll(net.exylia.lib.display.internal.DisplayRuntime.sample(viewer, rehearsalBase, 3, ours));
            if (!rehearsal.isFinished() && System.currentTimeMillis() - rehearsalSince < WATCH_MILLIS) return;
            rehearsal.cancel();
            if (current instanceof SpatialPreview.Effect effect) {
                // Back to the size it plays at, about a base at the origin.
                double grow = layout.preview().effectScale() / REHEARSAL_SCALE;
                List<double[]> samples = new ArrayList<>(rehearsing.size());
                for (double[] sample : rehearsing) {
                    samples.add(new double[]{(sample[0] - rehearsalBase.getX()) * grow, (sample[1] - rehearsalBase.getY()) * grow,
                            (sample[2] - rehearsalBase.getZ()) * grow, sample[3] * grow, sample.length > 4 ? sample[4] : 0});
                }
                String facing = effect.facing() != null ? effect.facing() : layout.preview().effectFacing();
                double fixed = SpatialLayout.Preview.degreesOf(facing);
                List<Double> zone = layout.preview().zone();
                TUNED.put(key(effect), net.exylia.lib.spatial.internal.EffectView.choose(samples, new double[]{0, 0, 0},
                        board.yaw(), 0, Double.isNaN(fixed), Double.isNaN(fixed) ? 0 : fixed,
                        new double[]{zone.get(0), zone.get(1)}));
                // The mannequin stands as the effect will play, once the play it stood for is over.
                if (npc != null && (run == null || run.isFinished())) {
                    npc.remove();
                    npc = null;
                    mannequin(effect);
                }
            }
            rehearsal = null;
            rehearsing = null;
        }

        private void spawn(NpcModel model, Location stand, SpatialLayout.Preview look) {
            NpcMotion turn;
            if (table != null) {
                // Turned by the table every poll, so a turn the viewer asks for glides from where it stands.
                turn = NpcMotion.still();
                stand.setYaw((float) (board.yaw() + 180 + table.angle(System.currentTimeMillis())));
                sentYaw = stand.getYaw();
            } else {
                turn = look.turnSpeed() == 0 ? NpcMotion.still() : NpcMotion.builder().spin(look.turnSpeed()).build();
                stand.setYaw((float) (stand.getYaw() + look.facingDegrees()));
            }
            npc = Npcs.of(menus.plugin()).show(model, turn, stand, 120_000L, List.of(viewer));
            npcUntil = System.currentTimeMillis() + 115_000L;
        }

        /** Sends the slots whose item differs from what the NPC was last sent; a fresh NPC wears nothing. */
        private void dress(@Nullable Map<EquipmentSlot, ItemStack> equipment) {
            if (npc == null || equipment == null) return;
            Map<EquipmentSlot, ItemStack> changed = new EnumMap<>(EquipmentSlot.class);
            for (EquipmentSlot slot : DRESSED) {
                ItemStack item = equipment.get(slot);
                if (item != null && item.getType().isAir()) item = null;
                if (Objects.equals(item, dressed.get(slot))) continue;
                changed.put(slot, item);
                if (item == null) dressed.remove(slot);
                else dressed.put(slot, item.clone());
            }
            // One packet for every slot that moved, not one a slot.
            if (!changed.isEmpty()) npc.equip(changed);
        }

        /**
         * A performance, every poll: plays once its bodies' skins are read (or after a short wait), and a
         * finite one again once every piece of the last play is gone. There is no mannequin to hide.
         */
        private void perform(SpatialPreview.Performance act, long now) {
            if (!acts.isEmpty()) {
                for (SequenceRun playing : acts) {
                    if (!playing.isFinished()) return;
                }
                acts.clear();
                nextPlay = now + ENCORE_MILLIS;
            }
            if (now < nextPlay) return;
            boolean ready = (body == null || body.ready()) && (partner == null || partner.ready());
            if (!ready && now - stoodAt <= SKIN_WAIT_MILLIS) return;
            Location at = base.clone();
            if (!act.isDuet()) {
                acts.add(Sequences.of(menus.plugin()).play(act.sequence(), performer(at, body, act.skin())));
                return;
            }
            // As a duet plays between two players: both on one line, the emote's gap apart (at the size it
            // plays), turned towards each other, so the choreography meets in the middle.
            double half = act.gap() * layout.preview().effectScale() / 2;
            Location first = plane.point(-half, 0, 0);
            Location second = plane.point(half, 0, 0);
            first.setDirection(second.toVector().subtract(first.toVector()));
            second.setDirection(first.toVector().subtract(second.toVector()));
            acts.add(Sequences.of(menus.plugin()).play(act.sequence(), performer(first, body, act.skin())));
            acts.add(Sequences.of(menus.plugin()).play(act.sequence(), performer(second, partner, null)));
        }

        /** Where one body performs and whose skin it wears: {@code skin}'s, or the picked body's. */
        private SequenceTarget performer(Location at, @Nullable net.exylia.lib.npc.PreviewSkins.Body worn,
                                         @Nullable Player skin) {
            SequenceTarget where = SequenceTarget.at(at).by(viewer).onlyTo(viewer)
                    .scale(layout.preview().effectScale());
            if (worn != null) return where.wearing(worn.ragdoll()).standing(worn::npc);
            return skin != null && own != null ? where.wearing(own) : where;
        }

        /** Every poll: an NPC lives two minutes at most, so a long look gets a fresh one; an effect plays again. */
        synchronized void keep() {
            face();
            watch();
            long now = System.currentTimeMillis();
            if (current instanceof SpatialPreview.Flight flight) {
                fly(flight, now);
                return;
            }
            if (current instanceof SpatialPreview.Performance act) {
                perform(act, now);
                return;
            }
            // Gone while its effect plays, and back only once every piece of it is: never two bodies.
            if (current instanceof SpatialPreview.Effect effect && npc == null && hidden
                    && (run == null || run.isFinished())) {
                hidden = false;
                mannequin(effect);
                nextPlay = now + 1000L;
            }
            if (npc != null && (now > npcUntil || !npc.isShowing())) {
                SpatialPreview again = current;
                remove();
                current = again;
                draw();
                return;
            }
            if (current instanceof SpatialPreview.LiveNpc live && npc != null) dress(live.equipment().get());
            if (current instanceof SpatialPreview.Effect effect && npc != null && now >= nextPlay
                    && (body == null || body.ready() || now - stoodAt > SKIN_WAIT_MILLIS)) {
                if (run != null) run.cancel();
                // Where the mannequin's feet were, the pedestal's top: a kill effect is written round the
                // victim's feet. It plays as a miniature of itself (effect-scale) so all of it stays
                // between the carousel and the header.
                // Learned out of sight while the first play shows at the layout's size: every selection plays at
                // once, and the plays after the first are turned and sized from what the rehearsal saw.
                if (learning(effect)) rehearse(effect, now);
                Location at = base.clone();
                // Facing the viewer (or turned to show its motion best, never its back): the effect is written
                // round the victim, who faces where it is played.
                double turn = effectTurn(effect);
                at.setYaw((float) (board.yaw() + 180 + turn));
                // The viewer sets it off; [RAGDOLL] and [NPC] {victim} lines wear the mannequin's skin, so
                // the body that falls is the one that stood there. No target, as in the previews' mannequin
                // mode: a [POTION] or a victim's title never lands on anybody.
                SequenceTarget where = SequenceTarget.at(at).by(viewer).onlyTo(viewer)
                        .scale(effectScale(effect));
                where = body != null ? where.wearing(body.ragdoll()).standing(body::npc)
                        : where.wearing(RagdollModel.of(effect.skin()));
                run = Sequences.of(menus.plugin()).play(effect.sequence(), where);
                long length = effect.sequence().isEndless() ? Long.MAX_VALUE / 4 : effect.sequence().durationMillis();
                nextPlay = now + Math.max(2500L, length + 1500L);
                if (layout.preview().vanishOnPlay()) {
                    // As the previews' vanish-on-hit: the mannequin is gone while it plays, so a kill reads
                    // as a death. It stands again once the effect's last piece is gone (never while an
                    // endless one plays), and the next play waits a second after that.
                    npc.remove();
                    npc = null;
                    hidden = true;
                    nextPlay = Long.MAX_VALUE;
                }
            }
        }

        /** Plays the preview again from the start: an effect at once, its mannequin back if it was gone. */
        synchronized void replay() {
            SpatialPreview again = current;
            remove();
            current = again;
            draw();
            keep();
        }

        synchronized void remove() {
            if (rehearsal != null) rehearsal.cancel();
            rehearsal = null;
            rehearsing = null;
            if (npc != null) npc.remove();
            npc = null;
            drop(item);
            item = null;
            if (run != null) run.cancel();
            run = null;
            drop(target);
            drop(projectile);
            target = null;
            projectile = null;
            flightRuns.forEach(SequenceRun::cancel);
            flightRuns.clear();
            acts.forEach(SequenceRun::cancel);
            acts.clear();
            dressed.clear();
            body = null;
            partner = null;
            own = null;
            launchedAt = 0;
            hidden = false;
            current = SpatialPreview.none();
        }
    }

    /**
     * What each effect's first play taught (by sequence, facing, zone and scale): the turn and the fit, so every
     * later play, in any menu, shows it best at once. Memory only.
     */
    /** The longest a rehearsal is watched before the view is chosen from what it showed. */
    private static final long WATCH_MILLIS = 6000L;
    /** How far over the board a rehearsal plays, and how small: out of sight and out of earshot. */
    private static final double REHEARSAL_UP = 48;
    private static final double REHEARSAL_SCALE = 0.05;

    private static final Map<String, net.exylia.lib.spatial.internal.EffectView.Tuning> TUNED =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** The preview's arrows: how far either side of the pedestal's middle, and how high over its top. */
    private static final double ARROW_ACROSS = 0.56;
    private static final double ARROW_UP = 0.2;
    /** How many notches of the wheel a list keeps waiting at most; more in one flick are dropped. */
    static final int WHEEL_QUEUE = 12;

    /** How far one notch of the wheel over the body turns it. */
    private static final double WHEEL_DEGREES = 30;

    // ── For edit mode ─────────────────────────────────────────────────────

    /** The plane a named piece stands on, for picking it: a panel, {@code carousel} or {@code preview}. */
    StagePlane pieceAt(String piece) {
        if (piece.equals(EditMode.CAROUSEL)) {
            List<Double> at = layout.carousel().at();
            // A shelf's middle is half-way down its rows, and its outline as near as its outermost tiles, so
            // the curve never brings a tile in front of it.
            SpatialLayout.Carousel row = layout.carousel();
            double up = row.shelf() ? -(row.rows() - 1) * row.rowGap() / 2 : -0.1;
            double forward = row.shelf() ? row.curve() + SHELF_HOVER_AHEAD : 0;
            return Geometry.panel(anchor.getWorld(), board, at.get(0), at.get(1) + up, at.get(2) + forward, 0);
        }
        if (piece.equals(EditMode.PREVIEW)) {
            List<Double> at = layout.preview().at();
            // The body standing on it, as tall as the preview's scale makes it.
            return Geometry.panel(anchor.getWorld(), board, at.get(0), at.get(1) + 0.95 * previewSize(), at.get(2), 0);
        }
        SpatialLayout.Panel panel = Objects.requireNonNull(layout.panel(piece), piece);
        return Geometry.panel(anchor.getWorld(), board, panel.across(), panel.up(), panel.forward(), panel.turn());
    }

    /** Whether a panel's words step aside while the guided tour shows: a bare panel of words, no buttons. */
    static boolean fades(SpatialLayout.Panel panel) {
        SpatialLayout.Content.Type type = panel.content().type();
        return !panel.frame() && (type == SpatialLayout.Content.Type.NONE || type == SpatialLayout.Content.Type.TEXT);
    }

    /** How tall a body on the pedestal stands, in blocks: the larger of the preview's and an effect's scale. */
    private double effectBodyHeight() {
        return 1.8 * Math.max(layout.preview().scale(), layout.preview().effectScale());
    }

    /** How large the preview's body stands, by its scale. */
    private double previewSize() {
        return Math.clamp(layout.preview().scale(), 0.42, 1.6);
    }

    /** How large a named piece is on its plane, {width, height}: what edit mode and the tour outline. */
    double[] pieceSize(String piece) {
        if (piece.equals(EditMode.CAROUSEL)) {
            SpatialLayout.Carousel row = layout.carousel();
            if (row.shelf()) {
                return new double[]{(row.slots() - 1) * row.spacing() + row.spacing() * SHELF_TILE + 0.12,
                        (row.rows() - 1) * row.rowGap() + Math.min(row.spacing() * SHELF_TILE, row.rowGap() * 0.86) + 0.12};
            }
            return new double[]{Geometry.outermost(row.slots(), row.spacing(), row.centreGap()) * 2 + row.spacing(), 0.8};
        }
        if (piece.equals(EditMode.PREVIEW)) return new double[]{0.4 + 0.6 * previewSize(), 1.9 * previewSize()};
        SpatialLayout.Panel panel = layout.panel(piece);
        return panel == null ? new double[]{0, 0} : new double[]{panel.width(), panel.height()};
    }

    // ── Guided tour ───────────────────────────────────────────────────────

    private @Nullable Tour tour;
    /** The widest a tour's line may be, and the column its words are broken to, in blocks. */
    private static final double TOUR_ROOM = 4.4;
    private static final double TOUR_COLUMN = 1.9;
    /** The room between a tour caption's box and its arrow, the arrow's own half size, a caption's own margin. */
    private static final double ARROW_GAP = 0.3;
    private static final double ARROW_RADIUS = 0.16;
    /** How far round the point an arrow aims at it stays: it points at a piece, it never sits on it. */
    private static final double TARGET_CLEAR = 0.3;
    private static final double CAPTION_MARGIN = 0.03;
    /** How far across and up from the board's middle a tour caption may stand: inside the view. */
    private static final double VIEW_HALF = 4.8;
    private static final double VIEW_TOP = 2.4;

    /** Shows the layout's guided tour now, from its first step; nothing when it has none. Marks it seen. */
    void startTour() {
        if (closed || edit != null || !layout.intro().present()) return;
        if (tour != null) tour.end();
        tour = new Tour(layout.intro().steps());
        // Now that it is the tour: the words it fades step aside.
        refreshWords();
        menus.markIntroSeen(viewer, definition.id());
    }

    /** Opens the tour on a first open: once per player and menu. */
    private void tourOnFirstOpen() {
        if (edit == null && layout.intro().present() && !menus.introSeen(viewer, definition.id())) startTour();
    }

    /** Sneaking during the tour skips it instead of leaving: {@code true} when there was one to skip. */
    boolean skipTour() {
        if (tour == null) return false;
        tour.end();
        return true;
    }

    /**
     * The guided tour: each step outlines its piece and writes its words, with the theme's continue line
     * under them. While it shows, a click anywhere moves on (the menu under it is not pressed) and the wheel
     * does nothing; after the last step the menu is the player's.
     */
    private final class Tour {
        private final List<SpatialLayout.Step> steps;
        private final List<DisplayHandle> drawn = new ArrayList<>();
        private final List<DisplayHandle> lit = new ArrayList<>();
        private final @Nullable Hotspot catcher;
        private int at = -1;
        /** Where the last caption and arrow stood, in the world: the next ones glide from there. */
        private @Nullable Location captionWas;
        private @Nullable Location arrowWas;

        Tour(List<SpatialLayout.Step> steps) {
            this.steps = steps;
            set(TOURING, Boolean.TRUE);
            refreshWords();
            // In front of everything, as wide as the view: every click is the tour's while it lasts.
            catcher = pointer.add(Hotspot.rect(board, 0, 0, 40, 30).priority(100).silent(true)
                    .onPress(click -> next()).onScroll(notch -> { }));
            next();
        }

        /**
         * One step as a coach mark: a short caption (a title and a line, shadowed, nothing behind it) beside the
         * piece, an arrow between them pointing at it and nudging towards it, the piece softly lit (its icons
         * glowing, a light on the floor, a line under it), the step dots under the caption. Each step glides
         * in from where the last one stood.
         */
        void next() {
            clear();
            at++;
            if (at >= steps.size()) {
                end();
                return;
            }
            if (at > 0) StageLook.play(viewer, theme.pageSound());
            SpatialLayout.Step step = steps.get(at);
            int accent = colour(theme.introOutline(), 0xFFD700);
            List<Double> where = step.at();
            String[] lines = fill(step.text()).split("\\\\n|\n");
            StringBuilder dots = new StringBuilder();
            for (int i = 0; i < steps.size(); i++) {
                dots.append(i == at ? String.format("<#%06x>●", accent & 0xFFFFFF) : "{letters_black}○").append(i + 1 < steps.size() ? " " : "");
            }
            String more = fill(theme.introLine().replace("%dots%", dots.toString())
                    .replace("%step%", String.valueOf(at + 1)).replace("%steps%", String.valueOf(steps.size())));
            Location probe = board.point(where.get(0), where.get(1), where.get(2));
            double floor = Geometry.readableScale(probe.distance(eyes()), theme.minGlyphDegrees(), Geometry.CAPITAL_PIXELS);
            double pitch = Stage.TEXT_HEIGHT * floor * 1.2;
            // One block: the title, its words broken to a narrow balanced column, the step dots and the continue
            // line, evenly spaced. Measured first, then set down in free room beside its piece.
            List<String> column = new ArrayList<>();
            column.add(lines[0]);
            for (int i = 1; i < lines.length; i++) {
                column.addAll(Geometry.balanced(lines[i], TOUR_COLUMN / Math.max(floor, theme.textScale()), 3));
            }
            String[] tails = more.split("\\\\n|\n");
            List<Fitted> fitted = new ArrayList<>();
            List<Double> ups = new ArrayList<>();
            double widest = 0;
            double top = 0;
            double bottom = 0;
            for (int i = 0; i < column.size() + tails.length; i++) {
                boolean tail = i >= column.size();
                String text = tail ? tails[i - column.size()] : column.get(i);
                double size = i == 0 ? theme.titleScale() * 0.75 : tail ? theme.smallScale() : theme.textScale();
                Fitted line = fit(text, true, TOUR_ROOM, size, probe);
                double up = -i * pitch - (tail ? pitch * 0.25 : 0);
                fitted.add(line);
                ups.add(up);
                widest = Math.max(widest, line.width());
                top = Math.max(top, up + Stage.TEXT_HEIGHT * line.scale() / 2);
                bottom = Math.min(bottom, up - Stage.TEXT_HEIGHT * line.scale() / 2);
            }
            double halfWidth = widest / 2 + CAPTION_MARGIN;
            double halfHeight = (top - bottom) / 2 + CAPTION_MARGIN;
            double[] target = step.target().isBlank() ? null : targetPoint(step.target(), where.get(0));
            double[] centre = target == null ? new double[]{where.get(0), where.get(1)}
                    : freeRoom(target, where, halfWidth, halfHeight, step.target());
            double titleUp = centre[1] + (top - bottom) / 2 - top;
            double[] from = captionWas == null ? null : worldOffset(captionWas, board.point(centre[0], titleUp, where.get(2)));
            for (int i = 0; i < fitted.size(); i++) {
                drawn.add(glideLine(fitted.get(i), centre[0], titleUp + ups.get(i), where.get(2), from, i * 50L));
            }
            captionWas = board.point(centre[0], titleUp, where.get(2));
            double[] box = {centre[0], centre[1], where.get(2), widest / 2, (top - bottom) / 2};
            if (target != null) point(step.target(), box, accent, target);
            drawn.removeIf(Objects::isNull);
        }

        /**
         * Where a caption {@code halfWidth} by {@code halfHeight} stands, around {@code target}: the nearest spot,
         * on the side the layout's {@code at} suggests first, whose box and arrow touch no piece of the board and
         * stay in view. The layout's {@code at} when there is none.
         */
        private double[] freeRoom(double[] target, List<Double> hint, double halfWidth, double halfHeight, String name) {
            List<double[]> taken = obstacles(null);
            // The arrow may reach into its own piece's room, never onto the spot it points at.
            List<double[]> others = obstacles(name);
            double kt = distance / Math.max(0.5, distance - hint.get(2));
            others.add(new double[]{(target[0] - TARGET_CLEAR) * kt, (target[1] - TARGET_CLEAR) * kt,
                    (target[0] + TARGET_CLEAR) * kt, (target[1] + TARGET_CLEAR) * kt});
            double depth = hint.get(2);
            double k = distance / Math.max(0.5, distance - depth);
            double prefer = Math.atan2(hint.get(1) - target[1], hint.get(0) - target[0]);
            double[] best = null;
            double bestCost = Double.MAX_VALUE;
            for (double reach = 0.5; reach <= 3.6; reach += 0.1) {
                for (int step = 0; step < 36; step++) {
                    double angle = Math.toRadians(step * 10);
                    double x = target[0] + Math.cos(angle) * (reach + halfWidth * Math.abs(Math.cos(angle)));
                    double y = target[1] + Math.sin(angle) * (reach * 0.6 + halfHeight * Math.abs(Math.sin(angle)));
                    double[] box = {(x - halfWidth) * k, (y - halfHeight) * k, (x + halfWidth) * k, (y + halfHeight) * k};
                    if (Math.abs(box[0]) > VIEW_HALF || Math.abs(box[2]) > VIEW_HALF) continue;
                    // Never lower than the board's lowest piece (the floor is under it), never off the top of the view.
                    if (y - halfHeight < layout.lowest() - 0.05 || box[3] > VIEW_TOP) continue;
                    if (hits(box, taken)) continue;
                    // The arrow, between the box and the piece, must not land on anything but the piece.
                    double[] arrow = arrowAt(new double[]{x, y, depth, halfWidth - CAPTION_MARGIN, halfHeight - CAPTION_MARGIN}, target);
                    double half = ARROW_RADIUS;
                    double[] arrowBox = {(arrow[0] - half) * k, (arrow[1] - half) * k, (arrow[0] + half) * k, (arrow[1] + half) * k};
                    if (hits(arrowBox, others)) continue;
                    double turn = Math.abs(Math.atan2(Math.sin(angle - prefer), Math.cos(angle - prefer)));
                    double cost = reach + turn * 0.35;
                    if (cost < bestCost) {
                        bestCost = cost;
                        best = new double[]{x, y};
                    }
                }
                if (best != null && reach > bestCost) break;
            }
            if (best == null) net.exylia.lib.debug.Debug.of(menus.plugin()).log("No free room for the tour's '" + name
                    + "' caption; it stands where the layout put it.");
            return best != null ? best : new double[]{hint.get(0), hint.get(1)};
        }

        private boolean hits(double[] box, List<double[]> taken) {
            for (double[] other : taken) {
                if (box[0] < other[2] && other[0] < box[2] && box[1] < other[3] && other[1] < box[3]) return true;
            }
            return false;
        }

        /** Where a step points: a panel's middle, the body on the pedestal, or an inner item of the arc on the caption's side. */
        private double[] targetPoint(String target, double side) {
            if (target.equals(EditMode.PREVIEW)) {
                List<Double> base = layout.preview().at();
                return new double[]{base.get(0), base.get(1) + 0.5 * effectBodyHeight(), base.get(2)};
            }
            if (target.equals(EditMode.CAROUSEL) && carousel != null && layout.carousel().present()) {
                SpatialLayout.Carousel row = layout.carousel();
                double[] chosen = null;
                for (int i = 0; i < carousel.perPage; i++) {
                    double[] slot = row.shelf() ? Geometry.shelf(i, row.slots(), row.spacing(), row.rowGap(), row.curve())
                            : new double[]{Geometry.carousel(i, carousel.perPage, row.spacing(), row.centreGap(), row.curve())[0], 0,
                                    Geometry.carousel(i, carousel.perPage, row.spacing(), row.centreGap(), row.curve())[1]};
                    if (Math.signum(slot[0]) != Math.signum(side == 0 ? -1 : side)) continue;
                    // The second item from the end: the outermost has the page arrow beside it.
                    double[] here = {row.at().get(0) + slot[0], row.at().get(1) + slot[1], row.at().get(2) + slot[2]};
                    if (chosen == null || Math.abs(here[0]) > Math.abs(chosen[0])) {
                        if (Math.abs(slot[0]) < Geometry.outermost(carousel.perPage, row.spacing(), row.centreGap()) - 1e-6) chosen = here;
                    }
                }
                if (chosen != null) return chosen;
            }
            SpatialLayout.Panel panel = layout.panel(target);
            if (panel == null) return new double[]{0, 0, 0};
            return new double[]{panel.across(), panel.up(), panel.forward()};
        }

        /**
         * The arrow from the caption towards the piece, and the soft light on the piece. {@code caption} is the
         * caption's box: {across, up, forward, half width, half height}; the arrow stands just outside it, on the
         * side facing the piece, so no word of the caption is ever under it, its nudge included (it nudges away).
         */
        private void point(String target, double[] caption, int accent, double[] centre) {
            // The soft light on the piece.
            if (target.equals(EditMode.CAROUSEL) && carousel != null) {
                for (DisplayHandle icon : carousel.icons) if (icon != null) icon.glow(accent);
            } else if (target.equals(EditMode.PREVIEW)) {
                List<Double> base = layout.preview().at();
                lit.add(show(Stage.textModel(Text.verbatim(String.format("<#%06x>●", accent & 0xFFFFFF))).background(0)
                                .textShadow(false).seeThrough(false).lineWidth(1000),
                        motion(flat(facing(board)), 4.2, 4.2, 4.2, 0), board.point(base.get(0), base.get(1) + 0.01, base.get(2))));
            } else {
                SpatialLayout.Panel panel = layout.panel(target);
                if (panel != null) {
                    lit.add(sheet(pieceAt(target).point(0, -panel.height() / 2 - 0.05, 0.02), facing(pieceAt(target)),
                            panel.width() * 0.7, 0.014, Geometry.argb(255, accent), 0));
                }
            }
            centre = aimAt(target, caption, centre);
            double[] spot = shortOfGlyphs(target, caption, centre);
            double fit = spot[3];
            double dx = caption[0] - centre[0];
            double dy = caption[1] - centre[1];
            double length = Math.max(1e-6, Math.hypot(dx, dy));
            double ux = dx / length;
            double uy = dy / length;
            double half = Stage.TEXT_HEIGHT * theme.titleScale() * 1.1 * 0.5 * fit;
            double scale = theme.titleScale() * 1.1 * fit;
            Location here = board.point(spot[0], spot[1], spot[2]);
            // "➜" points along its own right; turned in the board's plane to point back at the piece.
            Rotation turn = Rotation.around(Rotation.Axis.Z, Math.atan2(-uy, -ux)).then(facing(board));
            double[] towards = offset(board, -ux, -uy, 0);
            double[] glideFrom = arrowWas == null ? null : worldOffset(arrowWas, here);
            List<DisplayKeyframe> frames = new ArrayList<>();
            long lead = 0;
            float s = (float) scale;
            if (glideFrom != null) {
                frames.add(new DisplayKeyframe(0L, (float) glideFrom[0], (float) glideFrom[1], (float) glideFrom[2], turn, s, s, s));
                lead = 320L;
            } else {
                frames.add(new DisplayKeyframe(0L, 0f, 0f, 0f, turn, 0.01f, 0.01f, 0.01f));
                lead = 200L;
            }
            // A gentle nudge towards the piece, over and over.
            long cycle = 900L;
            for (int i = 0; i <= 2; i++) {
                double push = i == 1 ? 0.08 : 0;
                frames.add(new DisplayKeyframe(lead + cycle * i / 2, (float) (towards[0] * push), (float) (towards[1] * push),
                        (float) (towards[2] * push), turn, s, s, s));
            }
            DisplayModel arrow = Stage.textModel(Text.verbatim(String.format("<#%06x>➜", accent & 0xFFFFFF)))
                    .background(0).textShadow(true).lineWidth(1000);
            // Centred on its spot: a text display grows from its origin along its own up, which the turn swings
            // round with it, so the origin is half a line back along that turned up.
            double angle = Math.atan2(-uy, -ux);
            Location origin = board.point(spot[0] + Math.sin(angle) * half, spot[1] - Math.cos(angle) * half, spot[2]);
            drawn.add(show(arrow, DisplayMotion.of(frames, LIFE).looping(lead, lead + cycle, 1, 1),
                    origin));
            arrowWas = here;
        }

        /**
         * Where the arrow stands for a caption box {across, up, forward, half width, half height} pointing at
         * {@code target}: where the line from the box's middle to the target leaves the box, then a gap and half the
         * arrow beyond it, so no word of the caption is under it, its nudge (towards the target) included.
         */
        /**
         * Where the arrow aims: for a row of icons, the glyph nearest the caption rather than the row's middle, so it
         * points at the row from beside it instead of lying across its first glyph.
         */
        private double[] aimAt(String target, double[] caption, double[] centre) {
            SpatialLayout.Panel panel = layout.panel(target);
            if (panel == null || panel.content().type() != SpatialLayout.Content.Type.ICONS) return centre;
            int count = Math.max(1, panel.content().buttons().size());
            double pitch = Math.min(panel.height() * ICON_PITCH, panel.width() / count);
            double[] best = centre;
            for (int i = 0; i < count; i++) {
                double[] glyph = {panel.across() + (i - (count - 1) / 2.0) * pitch, panel.up(), panel.forward()};
                if (best == centre || Math.hypot(glyph[0] - caption[0], glyph[1] - caption[1])
                        < Math.hypot(best[0] - caption[0], best[1] - caption[1])) best = glyph;
            }
            return best;
        }

        /**
         * The arrow's spot and size, {across, up, forward, size}: outside its caption at its usual size, or, pointing
         * at a row of icons with little room before it, set in the middle of what room there is between the caption
         * and the glyph and made smaller to fit (never under 60 %), so it covers neither.
         */
        private double[] shortOfGlyphs(String target, double[] caption, double[] aim) {
            double[] spot = arrowAt(caption, aim, ARROW_GAP);
            SpatialLayout.Panel panel = layout.panel(target);
            if (panel == null || panel.content().type() != SpatialLayout.Content.Type.ICONS) {
                return new double[]{spot[0], spot[1], spot[2], 1};
            }
            double dx = aim[0] - caption[0];
            double dy = aim[1] - caption[1];
            double length = Math.max(1e-6, Math.hypot(dx, dy));
            double ux = dx / length;
            double uy = dy / length;
            double exit = Math.min(Math.abs(ux) < 1e-6 ? Double.MAX_VALUE : caption[3] / Math.abs(ux),
                    Math.abs(uy) < 1e-6 ? Double.MAX_VALUE : caption[4] / Math.abs(uy));
            // From the caption's edge to the near edge of the glyph, a little kept clear at both ends.
            double from = exit + 0.01;
            double to = length - panel.height() * 0.45 - 0.01;
            double room = to - from;
            double size = Math.clamp(room / (ARROW_RADIUS * 2), 0.6, 1);
            double mid = room >= ARROW_RADIUS * 2 + ARROW_GAP ? from + ARROW_GAP + ARROW_RADIUS : (from + to) / 2;
            return new double[]{caption[0] + ux * mid, caption[1] + uy * mid, spot[2], size};
        }

        private double[] arrowAt(double[] caption, double[] target) {
            return arrowAt(caption, target, ARROW_GAP);
        }

        private double[] arrowAt(double[] caption, double[] target, double gap) {
            double dx = target[0] - caption[0];
            double dy = target[1] - caption[1];
            double length = Math.max(1e-6, Math.hypot(dx, dy));
            double ux = dx / length;
            double uy = dy / length;
            double exit = Math.min(Math.abs(ux) < 1e-6 ? Double.MAX_VALUE : caption[3] / Math.abs(ux),
                    Math.abs(uy) < 1e-6 ? Double.MAX_VALUE : caption[4] / Math.abs(uy));
            double out = exit + gap + ARROW_RADIUS;
            return new double[]{caption[0] + ux * out, caption[1] + uy * out, caption[2] + 0.02};
        }

        /** Every piece of the board as the eyes see it, {minAcross, minUp, maxAcross, maxUp} on the board's plane. */
        private List<double[]> obstacles(@Nullable String except) {
            List<double[]> out = new ArrayList<>();
            for (Map.Entry<String, SpatialLayout.Panel> named : layout.panels().entrySet()) {
                SpatialLayout.Panel panel = named.getValue();
                // Faded while the tour shows: its room is free for a caption.
                if (fades(panel)) continue;
                if (named.getKey().equals(except)) continue;
                double under = panel.content().wordsUnder(panel.height());
                project(out, panel.across(), panel.up() - under / 2, panel.forward(), panel.width() / 2, (panel.height() + under) / 2);
            }
            SpatialLayout.Preview look = layout.preview();
            if (EditMode.PREVIEW.equals(except)) return carouselObstacles(out, null);
            double zoneWidth = Math.max(look.zone().get(0), 0.8);
            double zoneHeight = Math.max(look.zone().get(1), effectBodyHeight());
            project(out, look.at().get(0), look.at().get(1) + zoneHeight / 2 - 0.09, look.at().get(2), zoneWidth / 2, zoneHeight / 2 + 0.09);
            return carouselObstacles(out, except);
        }

        private List<double[]> carouselObstacles(List<double[]> out, @Nullable String except) {
            SpatialLayout.Carousel row = layout.carousel();
            if (EditMode.CAROUSEL.equals(except)) return out;
            if (row.present() && carousel != null) {
                for (int i = 0; i < carousel.perPage; i++) {
                    double[] slot = row.shelf() ? Geometry.shelf(i, row.slots(), row.spacing(), row.rowGap(), row.curve())
                            : new double[]{Geometry.carousel(i, carousel.perPage, row.spacing(), row.centreGap(), row.curve())[0], 0,
                                    Geometry.carousel(i, carousel.perPage, row.spacing(), row.centreGap(), row.curve())[1]};
                    double size = carousel.iconScale * ARC_HOVER_GROW;
                    project(out, row.at().get(0) + slot[0], row.at().get(1) + slot[1] - carousel.iconScale * 0.2,
                            row.at().get(2) + slot[2] + ARC_HOVER_AHEAD, size / 2 + 0.04, size / 2 + carousel.iconScale * 0.45);
                }
                if (row.arc()) {
                    double outer = Geometry.outermost(carousel.perPage, row.spacing(), row.centreGap()) + row.spacing() * 0.9;
                    project(out, row.at().get(0) - outer, row.at().get(1), row.at().get(2) + row.curve(), 0.2, 0.2);
                    project(out, row.at().get(0) + outer, row.at().get(1) - row.spacing() * 0.45, row.at().get(2) + row.curve(), 0.35, 0.45);
                }
            }
            return out;
        }

        private void project(List<double[]> out, double across, double up, double forward, double halfWidth, double halfHeight) {
            double k = distance / Math.max(0.5, distance - forward);
            out.add(new double[]{(across - halfWidth) * k, (up - halfHeight) * k, (across + halfWidth) * k, (up + halfHeight) * k});
        }

        /** A caption line, gliding in from {@code from} (world offset) when a step before stood elsewhere. */
        private @Nullable DisplayHandle glideLine(Fitted line, double across, double up, double forward,
                                                  double @Nullable [] from, long delay) {
            if (line.raw().isBlank()) return null;
            Location place = board.point(across, up - Stage.TEXT_HEIGHT * line.scale() / 2, forward);
            Rotation rotation = facing(board);
            float s = (float) line.scale();
            DisplayMotion motion = from == null ? motion(rotation, s, s, s, 120L + delay)
                    : DisplayMotion.of(List.of(
                            new DisplayKeyframe(0L, (float) from[0], (float) from[1], (float) from[2], rotation, s * 0.9f, s * 0.9f, s * 0.9f),
                            new DisplayKeyframe(320L + delay, 0f, 0f, 0f, rotation, s, s, s)), LIFE);
            return show(Stage.textModel(line.text()).background(0).textShadow(true).lineWidth(1000), motion, place);
        }

        private double[] worldOffset(Location from, Location to) {
            return new double[]{from.getX() - to.getX(), from.getY() - to.getY(), from.getZ() - to.getZ()};
        }

        private void clear() {
            drawn.forEach(MenuSession.this::drop);
            drawn.clear();
            lit.forEach(MenuSession.this::drop);
            lit.clear();
            // The row's icons glow again only for the crosshair.
            if (carousel != null) for (DisplayHandle icon : carousel.icons) if (icon != null) icon.glow(-1);
        }

        void end() {
            clear();
            pointer.remove(catcher);
            if (tour == this) tour = null;
            set(TOURING, null);
            refreshWords();
        }
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
