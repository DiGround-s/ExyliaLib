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
    private @Nullable PreviewPart preview;
    private @Nullable Slots carousel;
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
        for (Part part : parts) part.refresh();
        if (preview != null) preview.update(definition.preview == null ? SpatialPreview.none() : definition.preview.apply(this));
    }

    @Override
    public void close() {
        menus.close(this, true);
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
        closed = true;
        if (definition.onClose != null) definition.onClose.accept(this);
    }

    void tick() {
        if (preview != null) preview.keep();
    }

    // ── Pieces ────────────────────────────────────────────────────────────

    /** A pose: popping in after {@code delay}, or standing still at once when the delay is negative. */
    private DisplayMotion motion(Rotation rotation, double x, double y, double z, long delay) {
        return delay < 0 ? StageLook.still(rotation, x, y, z, LIFE) : StageLook.pop(rotation, x, y, z, delay, LIFE);
    }

    private @Nullable DisplayHandle show(DisplayModel model, DisplayMotion motion, Location at) {
        DisplayHandle handle = stage.show(model, motion, at);
        if (handle != null) pieces.add(handle);
        return handle;
    }

    /** A sheet of glass, a plate or a bar: a text display's background, centred on {@code at}. */
    private @Nullable DisplayHandle sheet(Location at, Rotation rotation, double width, double height, int argb, long delay) {
        DisplayMotion motion = delay < 0
                ? DisplayMotion.of(List.of(Geometry.glass(0, rotation, width, height, 1, 0, 0, 0)), LIFE)
                : DisplayMotion.of(Geometry.glassPop(rotation, width, height, delay), LIFE);
        // Not drawn through the stage, which bares every text display: this one is its background.
        DisplayHandle handle = displays.show(sheetModel(argb), motion, at, List.of(viewer));
        if (handle != null) pieces.add(handle);
        return handle;
    }

    private static DisplayModel sheetModel(int argb) {
        return DisplayModel.text(Component.text(Geometry.GLASS_TEXT)).billboard("FIXED").light(15)
                .background(argb).seeThrough(false).textShadow(false).lineWidth(1000);
    }

    /** Text centred on a point of a plane. */
    private @Nullable DisplayHandle text(Component content, StagePlane plane, Rotation rotation, double across, double up,
                                         double forward, double scale, boolean shadow, long delay) {
        Location at = plane.point(across, up - Stage.TEXT_HEIGHT * scale / 2, forward);
        return show(Stage.textModel(content).background(0).textShadow(shadow).lineWidth(1000),
                motion(rotation, scale, scale, scale, delay), at);
    }

    /** Text whose left edge is {@code left}, from its estimated width. */
    private @Nullable DisplayHandle textFrom(Component content, StagePlane plane, Rotation rotation, double left, double up,
                                             double forward, double scale, long delay) {
        double width = StageLook.width(Text.of(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(content)).plain()) * scale;
        return text(content, plane, rotation, left + width / 2, up, forward, scale, false, delay);
    }

    private Component line(String raw) {
        return Text.component(fill(raw));
    }

    /** A line with the plugin's values and then the menu's state filled in. */
    private String fill(String raw) {
        Values own = definition.values == null ? null : definition.values.apply(this);
        String filled = own == null ? raw : own.apply(raw);
        Values values = Values.of();
        state.forEach(values::put);
        return values.apply(filled);
    }

    /** Takes one piece down before the rest. */
    void drop(@Nullable DisplayHandle handle) {
        if (handle == null) return;
        stage.remove(handle);
        pieces.remove(handle);
    }

    private int colour(String token, int fallback) {
        return StageLook.rgb(token, fallback);
    }

    private int glassColour() {
        return Geometry.mix(0x000000, colour(theme.glassTint(), 0x8A51C4), theme.glassTintStrength());
    }

    private int plateColour(boolean hovered, boolean chosen) {
        if (chosen) return colour(theme.plateSelected(), 0xFFD700);
        if (hovered) return colour(theme.plateHover(), 0xAA76DE);
        return Geometry.mix(0x000000, colour(theme.plateIdle(), 0x8A51C4), theme.glassTintStrength() * 1.6);
    }

    private static Rotation facing(StagePlane plane) {
        return StageLook.facing(plane);
    }

    private static Rotation flat(Rotation facing) {
        return Rotation.around(Rotation.Axis.X, -Math.PI / 2).then(facing);
    }

    private void turnPage(Slots slots, int notch) {
        if (slots == null) return;
        StageLook.play(viewer, theme.pageSound());
        pages.put(slots.source, page(slots.source) + notch);
        refresh();
    }

    private void act(String action) {
        var run = definition.actions.get(action);
        if (run == null) {
            StageLook.play(viewer, theme.errorSound());
            return;
        }
        run.accept(this);
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
        private final List<TextLine> lines = new ArrayList<>();
        private @Nullable Slots slots;

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
            if (bare) {
                // A header: its title in the middle, its hint at the right end.
                if (!panel.title().isBlank()) {
                    Component title = line(panel.title());
                    text(title, plane, rotation, 0, 0, 0.012, StageLook.fit(title, w * 0.6, theme.titleScale()), true, inner);
                }
                if (!panel.hint().isBlank()) {
                    Component hint = line(panel.hint());
                    double scale = StageLook.fit(hint, w * 0.25, theme.smallScale());
                    double width = StageLook.width(Text.of(panel.hint()).plain()) * scale;
                    text(hint, plane, rotation, w / 2 - 0.12 - width / 2, 0, 0.012, scale, false, inner);
                }
            } else {
                if (!panel.title().isBlank()) {
                    Component title = line(panel.title());
                    text(title, plane, rotation, 0, h / 2 - 0.16, 0.012,
                            StageLook.fit(title, w * 0.86, theme.titleScale() * 0.8), true, inner);
                }
                if (!panel.hint().isBlank()) {
                    Component hint = line(panel.hint());
                    text(hint, plane, rotation, 0, h / 2 - 0.27, 0.012,
                            StageLook.fit(hint, w * 0.86, theme.smallScale() * 0.85), false, inner);
                }
            }
            double[] area = Geometry.content(h, !panel.title().isBlank() && !bare, panel.footer().present());
            switch (content.type()) {
                case TEXT -> {
                    int count = content.lines().size();
                    double pitch = Math.min(area[1] / Math.max(1, count), 0.16);
                    for (int i = 0; i < count; i++) {
                        double up = area[0] + ((count - 1) / 2.0 - i) * pitch;
                        lines.add(new TextLine(content.lines().get(i), plane, rotation, up, w * 0.9,
                                i == 0 ? theme.textScale() * 1.15 : theme.textScale(), inner < 0 ? -1 : inner + i * 40L));
                    }
                }
                case LIST -> slots = new Slots(Kind.ROW, content.source(), this, area, inner);
                case GRID -> slots = new Slots(Kind.CELL, content.source(), this, area, inner);
                case BUTTON -> new Button(plane, rotation, 0, area[0], w - 0.24, Math.min(area[1], 0.3),
                        content.label(), content.action(), inner);
                case NONE -> { }
            }
            if (panel.footer().present()) {
                new Button(plane, rotation, 0, -h / 2 + 0.16, w - 0.2, 0.2, panel.footer().label(),
                        panel.footer().action(), inner < 0 ? -1 : inner + 120);
            }
        }

        @Override
        public void refresh() {
            lines.forEach(TextLine::refresh);
            if (slots != null) slots.refresh();
        }
    }

    /** One line of a TEXT panel, rewritten when its placeholders read something else. */
    private final class TextLine {
        final String raw;
        final @Nullable DisplayHandle handle;
        String shown;

        TextLine(String raw, StagePlane plane, Rotation rotation, double up, double width, double scale, long delay) {
            this.raw = raw;
            this.shown = fill(raw);
            Component component = Text.component(shown);
            this.handle = text(component, plane, rotation, 0, up, 0.012, StageLook.fit(component, width, scale), true, delay);
        }

        void refresh() {
            String now = fill(raw);
            if (now.equals(shown) || handle == null) return;
            shown = now;
            handle.text(Text.component(now));
        }
    }

    /** A button: an opaque plate with its label, lighting up under the crosshair. */
    private final class Button {
        final @Nullable DisplayHandle plate;
        final double width;
        final double height;

        Button(StagePlane plane, Rotation rotation, double across, double up, double width, double height, String label,
               String action, long delay) {
            this.width = width;
            this.height = height;
            int rest = Geometry.mix(glassColour(), colour(theme.plateHover(), 0xAA76DE), 0.2);
            int lit = Geometry.mix(glassColour(), colour(theme.plateHover(), 0xAA76DE), 0.5);
            plate = sheet(plane.point(across, up, 0.008), rotation, width, height, Geometry.argb(255, rest), delay);
            Component text = line(label);
            text(text, plane, rotation, across, up, 0.016, StageLook.fit(text, width * 0.86, theme.textScale()), true,
                    delay < 0 ? -1 : delay + 60);
            if (edit != null) return;
            pointer.add(Hotspot.rect(plane, across, up, width, height).priority(2)
                    .onHover(on -> {
                        if (plate != null) plate.restyle(sheetModel(Geometry.argb(255, on ? lit : rest)));
                    })
                    .onPress(click -> act(action)));
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
        private int chosen = -1;
        private int hovered = -1;
        private final double emptyUp;
        private final double pageUp;
        private final double captionUp;
        private final long delay;
        private boolean first = true;

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
                iconScale = Math.min(0.28, Math.min(slotWidth, slotHeight) * 0.66);
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
            this.slotWidth = Math.min(row.spacing() * 0.92, 0.6);
            this.slotHeight = 0.7;
            this.iconScale = 0.36;
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
            Component text = Text.component(label);
            text(text, plane, rotation, across, pageUp, 0.01, theme.textScale() * 1.2, true, delay < 0 ? -1 : delay + 200);
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
                // A plinth answers from its item down to its labels.
                double up = kind == Kind.PEDESTAL ? -0.12 : place.up();
                Hotspot spot = Hotspot.rect(plane, place.across(), up, slotWidth, kind == Kind.ROW ? slotHeight * 0.96 : slotHeight)
                        .priority(1)
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
            int wave = 0;
            for (int slot : changed) {
                clear(slot);
                SpatialEntry entry = slot < now.size() ? now.get(slot) : null;
                set(slot, entry);
                if (entry != null) {
                    long at = first ? (delay < 0 ? -1 : delay + slot * 45L) : wave++ * 35L;
                    draw(slot, entry, at);
                }
                if (slot < spots.size()) spots.get(slot).enabled(entry != null);
            }
            first = false;
            // Empty: one line that says so, in the middle.
            if (now.isEmpty() && empty == null) {
                Component text = Text.component(theme.emptyLine());
                empty = text(text, plane, rotation, 0, emptyUp, 0.012, theme.smallScale(), false, -1);
            } else if (!now.isEmpty() && empty != null) {
                drop(empty);
                empty = null;
            }
            pageLine(page, pageCount);
            chosen(now);
            caption(now);
        }

        private void set(int slot, @Nullable SpatialEntry entry) {
            while (shown.size() <= slot) shown.add(null);
            shown.set(slot, entry);
            while (shown.size() > 0 && shown.getLast() == null) shown.removeLast();
        }

        private SpatialEntry at(int slot) {
            return slot < shown.size() ? shown.get(slot) : null;
        }

        private void clear(int slot) {
            while (drawn.size() <= slot) {
                drawn.add(new ArrayList<>());
                icons.add(null);
                plates.add(null);
            }
            drawn.get(slot).forEach(MenuSession.this::drop);
            drawn.get(slot).clear();
            icons.set(slot, null);
            plates.set(slot, null);
        }

        private void keep(int slot, @Nullable DisplayHandle handle) {
            if (handle != null) drawn.get(slot).add(handle);
        }

        private void draw(int slot, SpatialEntry entry, long at) {
            Place place = places.get(slot);
            DisplayModel iconModel = entry.model() != null ? entry.model()
                    : DisplayModel.item(entry.icon() == null ? new ItemStack(Material.BARRIER) : entry.icon())
                    .light(entry.locked() ? 6 : 15);
            String status = status(entry);
            switch (kind) {
                case ROW -> {
                    double left = -slotWidth / 2;
                    Location iconAt = place.plane().point(left + 0.05 + iconScale / 2, place.up(), 0.03);
                    DisplayHandle icon = show(iconModel, motion(place.rotation(), iconScale, iconScale, iconScale * 0.3, at), iconAt);
                    keep(slot, icon);
                    icons.set(slot, icon);
                    double textLeft = left + 0.1 + iconScale;
                    double room = slotWidth - (textLeft - left) - 0.04;
                    Component name = Text.component(entry.name());
                    keep(slot, textFrom(name, place.plane(), place.rotation(), textLeft, place.up() + slotHeight * 0.16, 0.02,
                            StageLook.fit(name, room, theme.textScale() * 0.85), at < 0 ? -1 : at + 40));
                    if (!status.isBlank()) {
                        Component sub = Text.component(status);
                        keep(slot, textFrom(sub, place.plane(), place.rotation(), textLeft, place.up() - slotHeight * 0.2, 0.02,
                                StageLook.fit(sub, room, theme.smallScale() * 0.85), at < 0 ? -1 : at + 70));
                    }
                }
                case CELL -> {
                    DisplayHandle icon = show(iconModel, motion(place.rotation(), iconScale, iconScale, iconScale * 0.3, at),
                            place.plane().point(place.across(), place.up() + 0.01, 0.03));
                    keep(slot, icon);
                    icons.set(slot, icon);
                    if (entry.state() == SpatialEntry.State.EQUIPPED) {
                        Component mark = Text.component(theme.equippedMarker());
                        keep(slot, text(mark, place.plane(), place.rotation(), place.across() + iconScale * 0.5,
                                place.up() + iconScale * 0.45, 0.04, theme.smallScale() * 0.8, true, at < 0 ? -1 : at + 60));
                    }
                }
                case PEDESTAL -> {
                    StagePlane own = place.plane();
                    Rotation turn = place.rotation();
                    keep(slot, show(DisplayModel.block(StageLook.block(theme.pedestalBlock(), Material.POLISHED_BLACKSTONE)).light(15),
                            motion(turn, 0.44, 0.08, 0.44, at), own.point(0, -0.17, 0)));
                    DisplayHandle plate = sheet(own.point(0, -0.128, 0), flat(turn), 0.36, 0.36,
                            Geometry.argb(255, plateColour(false, false)), at < 0 ? -1 : at + 40);
                    keep(slot, plate);
                    plates.set(slot, plate);
                    DisplayHandle icon = show(iconModel, motion(turn, iconScale, iconScale, iconScale, at < 0 ? -1 : at + 80),
                            own.point(0, 0.06, 0));
                    keep(slot, icon);
                    icons.set(slot, icon);
                    Component name = Text.component(entry.name());
                    keep(slot, text(name, own, turn, 0, -0.29, 0.02, StageLook.fit(name, slotWidth, theme.smallScale() * 1.05),
                            true, at < 0 ? -1 : at + 100));
                    if (!status.isBlank()) {
                        Component sub = Text.component(status);
                        keep(slot, text(sub, own, turn, 0, -0.38, 0.02, StageLook.fit(sub, slotWidth, theme.smallScale() * 0.85),
                                false, at < 0 ? -1 : at + 120));
                    }
                }
            }
            if (slot == hovered) styleHover(slot, true);
        }

        /** The line under an entry's name: its state's, or its own subtitle. */
        private String status(SpatialEntry entry) {
            return switch (entry.state()) {
                case LOCKED -> entry.price() != null && !entry.price().isBlank()
                        ? entry.price() : theme.lockedLine().replace("%price%", "");
                case EQUIPPED -> theme.equippedLine();
                case OWNED -> entry.subtitle().isBlank() ? theme.ownedLine() : entry.subtitle();
                default -> entry.subtitle();
            };
        }

        private void hover(int slot, boolean on) {
            hovered = on ? slot : (hovered == slot ? -1 : hovered);
            styleHover(slot, on);
            caption(shown);
        }

        private void styleHover(int slot, boolean on) {
            DisplayHandle icon = slot < icons.size() ? icons.get(slot) : null;
            if (icon != null) {
                icon.glow(on ? colour(theme.hoverGlow(), 0xFFD700) : -1);
                DisplayKeyframe here = icon.pose();
                if (here != null) {
                    double size = iconScale * (on ? theme.hoverGrow() : 1.0);
                    double depth = kind == Kind.PEDESTAL ? size : size * 0.3;
                    icon.moveTo(new DisplayKeyframe(0L, here.x(), here.y(), here.z(), here.rotation(),
                            (float) size, (float) size, (float) depth), HOVER_MILLIS, DisplayMotion.Easing.QUAD_OUT);
                }
            }
            DisplayHandle plate = slot < plates.size() ? plates.get(slot) : null;
            if (plate != null) plate.restyle(sheetModel(Geometry.argb(255, plateColour(on, slot == chosen))));
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
            selected.put(source, entry.id());
            var select = definition.selects.get(source);
            if (select != null) select.accept(MenuSession.this, entry);
            refresh();
            if (preview != null) preview.update(definition.preview == null ? SpatialPreview.none() : definition.preview.apply(MenuSession.this));
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
            if (kind == Kind.PEDESTAL) {
                for (int i = 0; i < plates.size(); i++) {
                    DisplayHandle plate = plates.get(i);
                    if (plate != null && (i == before || i == slot)) {
                        plate.restyle(sheetModel(Geometry.argb(255, plateColour(i == hovered, i == slot))));
                    }
                }
                return;
            }
            glide(slot);
        }

        private void glide(int slot) {
            double width = kind == Kind.ROW ? 0.025 : slotWidth * 0.5;
            double height = kind == Kind.ROW ? slotHeight * 0.66 : 0.022;
            if (selector == null) {
                if (slot < 0) return;
                selector = sheet(selectorAt(0), rotation, width, height,
                        Geometry.argb(255, colour(theme.plateSelected(), 0xFFD700)), delay < 0 ? -1 : delay + 200);
                if (selector == null) return;
                if (slot == 0) return;
            }
            Location origin = selectorAt(0);
            Location target = selectorAt(Math.max(0, slot));
            double grow = slot < 0 ? 0.01 : 1.0;
            selector.moveTo(Geometry.glass(0, rotation, width, height, grow, target.getX() - origin.getX(),
                    target.getY() - origin.getY(), target.getZ() - origin.getZ()), GLIDE_MILLIS, DisplayMotion.Easing.QUAD_OUT);
        }

        private Location selectorAt(int slot) {
            Place place = places.get(Math.min(slot, places.size() - 1));
            if (kind == Kind.ROW) return plane.point(-slotWidth / 2 + 0.012, place.up(), 0.014);
            return plane.point(place.across(), place.up() - iconScale * 0.62, 0.014);
        }

        private void pageLine(int page, int pageCount) {
            boolean show = kind == Kind.PEDESTAL || pageCount > 1;
            String raw = theme.pageLine().replace("%page%", String.valueOf(page + 1))
                    .replace("%pages%", String.valueOf(pageCount));
            if (!show) {
                drop(pageLine);
                pageLine = null;
                return;
            }
            Component text = Text.component(raw);
            if (pageLine != null) {
                pageLine.text(text);
                return;
            }
            double scale = theme.smallScale() * (kind == Kind.PEDESTAL ? 1.0 : 0.8);
            pageLine = text(text, plane, rotation, 0, pageUp, 0.012, scale, false,
                    delay < 0 ? -1 : delay + 220);
        }

        /** A grid names the entry under the crosshair, or the chosen one, under its cells. */
        private void caption(List<SpatialEntry> now) {
            if (kind != Kind.CELL) return;
            int slot = hovered >= 0 ? hovered : chosen;
            SpatialEntry entry = slot >= 0 && slot < now.size() ? now.get(slot) : null;
            Component text = Text.component(entry == null ? "" : entry.name());
            if (caption == null) {
                caption = text(text, plane, rotation, 0, captionUp, 0.012, theme.smallScale(), true, -1);
            } else {
                caption.text(text);
            }
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
                    NpcModel model = NpcModel.of(body.skin()).scale(look.scale());
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
                    NpcModel model = effect.skin() == null ? NpcModel.of("Mannequin") : NpcModel.of(effect.skin());
                    spawn(model.scale(look.scale()), stand, look);
                    nextPlay = 0;
                }
                case SpatialPreview.None none -> { }
            }
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
            if (npc != null && (now > npcUntil || !npc.isShowing())) {
                SpatialPreview again = current;
                remove();
                current = again;
                draw();
                return;
            }
            if (current instanceof SpatialPreview.Effect effect && npc != null && now >= nextPlay) {
                if (run != null) run.cancel();
                Location at = base.clone().add(0, 1.0 * layout.preview().scale(), 0);
                run = Sequences.of(menus.plugin()).play(effect.sequence(), SequenceTarget.at(at).onlyTo(viewer));
                long length = effect.sequence().isEndless() ? Long.MAX_VALUE / 4 : effect.sequence().durationMillis();
                nextPlay = now + Math.max(2500L, length + 1500L);
            }
        }

        void remove() {
            if (npc != null) npc.remove();
            npc = null;
            drop(item);
            item = null;
            if (run != null) run.cancel();
            run = null;
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
