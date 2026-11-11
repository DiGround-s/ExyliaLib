package net.exylia.lib.guide.internal;

import net.exylia.lib.hud.HudAnchor;
import net.exylia.lib.hud.HudHandle;
import net.exylia.lib.hud.HudNotice;
import net.exylia.lib.hud.HudWidget;
import net.exylia.lib.screen.internal.HudLayers;
import net.exylia.lib.text.Centering;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Everything one plugin shows on one player's HUD: its widgets and notices, each a text display riding the player
 * ({@link HudGeometry}).
 *
 * <h2>One thread</h2>
 * Callers on any thread only queue what they want; the queue is run at the start of each draw, on the player's own
 * thread, so the elements are never touched by two threads and a text function always reads the player safely.
 *
 * <h2>Motion</h2>
 * Only translation and scale interpolate on a client. An element spawns tiny in its own middle and grows to its size,
 * glides when its stack reflows, and shrinks back into its middle before it is destroyed: it never crosses the
 * screen's edge, where a client cuts it (1.588.0).
 */
@ApiStatus.Internal
public final class LiveHud extends Piece {

    /** The default text size: a font pixel a little under three screen pixels on a 1080p window. */
    static final double BASE = 0.15;
    /** Pitch, in degrees, before every element's seat offset is sent again. */
    private static final double PITCH_STEP = 3;
    /** How long a glide takes, in ticks. */
    private static final int GLIDE = 4;
    /** Long enough for its client to draw no element wider than this, in pixels: lines are broken by the text alone. */
    private static final int LINE_WIDTH = 1000;
    /**
     * See-through, so a wall nearer than the text never hides it. No shadow: a see-through shadow is drawn without a
     * depth test, so it lands over its own letters and the line reads doubled.
     */
    private static final int SEE_THROUGH = 0x02;
    /** Every plugin's HUD on each player, in the order they arrived: they share one stack at each anchor. */
    private static final Map<java.util.UUID, List<LiveHud>> PEERS = new java.util.concurrent.ConcurrentHashMap<>();

    private final Queue<Consumer<Player>> queued = new ConcurrentLinkedQueue<>();
    private final Map<String, Widget> widgets = new LinkedHashMap<>();
    private final List<Notice> notices = new ArrayList<>();
    private final List<int[]> ghosts = new ArrayList<>();
    private double sentPitch = Double.NaN;
    private double sentSeat;
    /** Whether the player was offered fitting the HUD to their window, or needs not be. */
    private boolean fitOffered;
    private long now;

    LiveHud(ViewerSession session) {
        super(session);
        if (session != null) {
            PEERS.computeIfAbsent(session.player().getUniqueId(),
                    key -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(this);
        }
    }

    // ------------------------------------------------------------------ handle side, any thread

    public @NotNull HudHandle show(@NotNull HudWidget spec) {
        Widget widget = new Widget(spec);
        if (removed()) {
            widget.dead = true;
            return widget;
        }
        queued.add(player -> {
            Widget old = widgets.put(spec.id(), widget);
            if (old != null) {
                widget.adopt(old);
                old.dead = true;
            }
        });
        return widget;
    }

    public void notify(@NotNull HudNotice notice) {
        if (!removed()) queued.add(player -> arrive(player, notice));
    }

    public void remove(@NotNull String id) {
        queued.add(player -> {
            Widget widget = widgets.remove(id);
            if (widget != null) {
                widget.dead = true;
                leave(player, widget);
            }
        });
    }

    public void clear() {
        queued.add(player -> {
            for (Widget widget : widgets.values()) {
                widget.dead = true;
                leave(player, widget);
            }
            widgets.clear();
            for (Notice notice : notices) leave(player, notice);
            notices.clear();
        });
    }

    // ------------------------------------------------------------------ draw side, the player's thread

    @Override
    void draw(Player player, long tick) {
        now = tick;
        for (Consumer<Player> task; (task = queued.poll()) != null; ) task.accept(player);
        if (stepsAside(player)) {
            clear(player);
            notices.removeIf(notice -> notice.until <= now);
            return;
        }
        read(player);
        for (Iterator<Notice> it = notices.iterator(); it.hasNext(); ) {
            Notice notice = it.next();
            if (notice.until <= now) {
                it.remove();
                leave(player, notice);
            }
        }
        bury(player, false);

        double pitch = player.getLocation().getPitch();
        double seat = Math.max(0, player.getHeight() - player.getEyeHeight());
        boolean reseat = Double.isNaN(sentPitch) || Math.abs(pitch - sentPitch) >= PITCH_STEP
                || Math.abs(seat - sentSeat) > 0.01;
        // No sprint FOV compensation: the server cannot know a client's FOV Effects slider, and guessing it wrong
        // pumps the whole HUD in and out on every sprint. Drawn as is, it only narrows a little with the world.
        if (reseat) {
            sentPitch = pitch;
            sentSeat = seat;
        }
        // Fitted to the player's window: what they set on their first screen or HUD, FOV 70 at 16:9 until then.
        java.util.UUID id = player.getUniqueId();
        double zoom = net.exylia.lib.screen.internal.ScreenPreferences.fovScale(id);
        double aspect = net.exylia.lib.screen.internal.ScreenPreferences.aspect(id);
        Map<HudAnchor, List<Element>> stacks = stacks(player);
        if (!fitOffered && !stacks.isEmpty()) {
            fitOffered = net.exylia.lib.screen.internal.ScreenPreferences.fitted(id) || offerFit(player);
        }
        for (Map.Entry<HudAnchor, List<Element>> stack : stacks.entrySet()) {
            double offset = before(player, stack.getKey());
            for (Element element : stack.getValue()) {
                element.place(stack.getKey(), offset, zoom, aspect);
                offset += element.tall + HudGeometry.GAP * HudGeometry.pixel(element.drawSize());
                element.send(player, reseat);
            }
        }
    }

    /** Reads every due widget's condition and text. */
    private void read(Player player) {
        for (Widget widget : widgets.values()) {
            if (!widget.again && now < widget.next) continue;
            widget.again = false;
            widget.next = now + widget.spec.every();
            boolean on = widget.spec.when() == null || widget.spec.when().test(player);
            if (widget.spec.layout() != null) {
                net.exylia.lib.screen.Node tree = on ? widget.spec.layout().apply(player) : null;
                HudLayers.Drawn drawn = tree == null ? null : HudLayers.of(tree);
                if (drawn == null || drawn.layers().isEmpty()) {
                    if (widget.spawned()) leave(player, widget);
                    widget.drawn = null;
                } else {
                    widget.drawn = drawn;
                }
                continue;
            }
            Component text = on ? widget.spec.text().apply(player) : null;
            if (text == null || PlainTextComponentSerializer.plainText().serialize(text).isBlank()) {
                if (widget.spawned()) leave(player, widget);
                widget.text = null;
            } else {
                widget.text = text;
            }
        }
    }

    /**
     * How far along an anchor's stack this HUD starts: past what the plugins that arrived before it show there, so two
     * plugins' widgets never land on each other.
     */
    private double before(Player player, HudAnchor anchor) {
        List<LiveHud> peers = PEERS.get(player.getUniqueId());
        if (peers == null) return 0;
        double offset = 0;
        for (LiveHud peer : peers) {
            if (peer == this) break;
            if (peer.removed()) {
                peers.remove(peer);
                continue;
            }
            for (Element element : peer.elements()) {
                if (element.spawned() && element.anchor() == anchor) {
                    offset += element.tall + HudGeometry.GAP * HudGeometry.pixel(element.drawSize());
                }
            }
        }
        return offset;
    }

    /** Stops sharing the player's stacks; called when its session ends. */
    void unshare() {
        if (session == null) return;
        PEERS.computeIfPresent(session.player().getUniqueId(), (key, peers) -> {
            peers.remove(this);
            return peers.isEmpty() ? null : peers;
        });
    }

    /** What stands at each anchor, edge first: widgets by order, then notices newest first. */
    private Map<HudAnchor, List<Element>> stacks(Player player) {
        Map<HudAnchor, List<Element>> stacks = new EnumMap<>(HudAnchor.class);
        List<Widget> shown = new ArrayList<>();
        for (Widget widget : widgets.values()) if (widget.has()) shown.add(widget);
        shown.sort(Comparator.comparingInt(widget -> widget.spec.order()));
        for (Widget widget : shown) stacks.computeIfAbsent(widget.spec.anchor(), key -> new ArrayList<>()).add(widget);
        for (int i = notices.size() - 1; i >= 0; i--) {
            Notice notice = notices.get(i);
            stacks.computeIfAbsent(notice.spec.anchor(), key -> new ArrayList<>()).add(notice);
        }
        return stacks;
    }

    private void arrive(Player player, HudNotice spec) {
        long until = now + Math.round(spec.seconds() * 20);
        for (Notice notice : notices) {
            if (notice.spec.anchor() != spec.anchor()) continue;
            if (spec.big() && notice.spec.big()) {
                notice.spec = spec;
                notice.count = 1;
                notice.text = spec.text();
                notice.until = until;
                notice.pulse = 2;
                return;
            }
            if (spec.group() != null && spec.group().equals(notice.spec.group())) {
                notice.count++;
                notice.text = spec.text().append(net.exylia.lib.text.Text.of("{muted} ×" + notice.count).build());
                notice.until = until;
                notice.pulse = 2;
                return;
            }
        }
        Notice notice = new Notice(spec, until);
        notices.add(notice);
        int atAnchor = 0;
        for (int i = notices.size() - 1; i >= 0; i--) {
            Notice other = notices.get(i);
            if (other.spec.anchor() != spec.anchor()) continue;
            if (++atAnchor > HudNotice.MAX_SHOWN) {
                notices.remove(i);
                leave(player, other);
            }
        }
    }

    /** Shrinks an element into its middle and destroys it once it is gone. */
    private void leave(Player player, Element element) {
        if (!element.spawned()) return;
        boolean slide = !stepsAside(player) && element.spot != null;
        double k = SHRUNK;
        double[] out = slide ? HudGeometry.popped(element.spot, element.tall, k) : null;
        for (Part part : element.parts) {
            if (slide && part.sent != null) {
                Look l = part.sent;
                GuidePackets.riderTransform(player, part.id, at(out, l, k), (float) (l.sx() * k), (float) (l.sy() * k),
                        GLIDE, null, -1);
            }
            ghosts.add(new int[]{part.id, (int) (slide ? now + GLIDE + 1 : now)});
        }
        element.parts.clear();
    }

    /** Destroys what has finished sliding out; everything when {@code all}. */
    private void bury(Player player, boolean all) {
        for (Iterator<int[]> it = ghosts.iterator(); it.hasNext(); ) {
            int[] ghost = it.next();
            if (all || ghost[1] <= now) {
                GuidePackets.destroyViewText(player, ghost[0]);
                it.remove();
            }
        }
    }

    /** Where a part stands, its element's bottom centre at {@code spot} and everything drawn {@code k} times as large. */
    private float[] at(double[] spot, Look look, double k) {
        return HudGeometry.seated(spot[0] + look.tx() * k, spot[1] + look.ty() * k, sentPitch, sentSeat);
    }

    /** Offers fitting the view when it is safe to open a screen; whether it was. */
    private boolean offerFit(Player player) {
        try {
            return net.exylia.lib.screen.internal.FitPanel.offer(GuideRuntime.library(), player);
        } catch (Throwable unavailable) {
            return true;
        }
    }

    private static boolean stepsAside(Player player) {
        return player.isDead() || player.getGameMode() == GameMode.SPECTATOR || filmed(player)
                || screenOpen(player) || GuideRuntime.hudHidden(player);
    }

    private static boolean screenOpen(Player player) {
        try {
            return net.exylia.lib.screen.internal.ScreenRuntime.isOpen(player.getUniqueId());
        } catch (Throwable unavailable) {
            return false;
        }
    }

    @Override
    void clear(Player player) {
        bury(player, true);
        for (Element element : elements()) {
            for (Part part : element.parts) GuidePackets.destroyViewText(player, part.id);
            element.reset();
        }
        sentPitch = Double.NaN;
    }

    @Override
    void forget() {
        java.util.UUID viewer = session.player().getUniqueId();
        for (int[] ghost : ghosts) GuidePackets.forgetViewText(viewer, ghost[0]);
        ghosts.clear();
        for (Element element : elements()) {
            for (Part part : element.parts) GuidePackets.forgetViewText(viewer, part.id);
            element.reset();
        }
        sentPitch = Double.NaN;
    }

    private List<Element> elements() {
        List<Element> all = new ArrayList<>(widgets.values());
        all.addAll(notices);
        return all;
    }

    // ------------------------------------------------------------------ elements

    /** How small an element is drawn as it pops in or out. */
    private static final double SHRUNK = 0.01;
    /** The most displays one element draws; a design past it is cut, its frontmost layers first. */
    private static final int MOST_PARTS = 64;
    /** The full block glyph a rect is drawn with, and its size in font pixels. */
    private static final Component BLOCK = Component.text("█");
    private static final int BLOCK_SIDE = 8;

    /**
     * What one display shows, relative to its element: translation from the element's bottom centre and scale at
     * size 1 (blocks), its text, opacity, flags and background.
     */
    private record Look(float tx, float ty, float sx, float sy, Component text, int opacity, int flags, int background,
                        int lineWidth) {
    }

    /** One display of an element. */
    private static final class Part {
        final int id;
        Look sent;

        Part(int id) {
            this.id = id;
        }
    }

    private abstract class Element {
        /** Its displays, in the order they were spawned: the order the client draws them, back to front. */
        final List<Part> parts = new ArrayList<>();
        Component text;
        HudLayers.Drawn drawn;
        double size;
        /** How much larger the player's FOV draws everything, and their window's width over height. */
        double zoom = 1;
        double aspect = HudGeometry.ASPECT;
        int background;
        double wide;
        double tall;
        Object measured;
        List<Look> looks = List.of();
        double[] spot;
        double[] sentSpot;
        double sentK = 1;
        boolean entering;
        int pulse;

        abstract HudAnchor anchor();

        boolean has() {
            return text != null || drawn != null;
        }

        boolean spawned() {
            return !parts.isEmpty();
        }

        void reset() {
            parts.clear();
            sentSpot = null;
            entering = false;
        }

        double drawSize() {
            return size * zoom;
        }

        void place(HudAnchor anchor, double offset, double zoom, double aspect) {
            if (zoom != this.zoom) measured = null;
            this.zoom = zoom;
            this.aspect = aspect;
            Object content = drawn != null ? drawn : text;
            if (content != measured) {
                measured = content;
                double pixel = HudGeometry.pixel(drawSize());
                if (drawn != null) {
                    wide = drawn.width() * pixel;
                    tall = drawn.height() * pixel;
                    looks = looks(drawn, pixel);
                } else {
                    int lines = 1 + (int) PlainTextComponentSerializer.plainText().serialize(text).chars()
                            .filter(c -> c == '\n').count();
                    wide = (Centering.pixelWidth(text) + 3) * pixel;
                    tall = lines * HudGeometry.LINE * pixel;
                    float scale = HudGeometry.scale(drawSize());
                    looks = List.of(new Look(0, 0, scale, scale, text, 255, SEE_THROUGH | HudGeometry.align(anchor),
                            background, LINE_WIDTH));
                }
            }
            spot = HudGeometry.place(anchor, offset, wide, tall, zoom, aspect);
        }

        /** A designed widget's layers as looks, {@code pixel} blocks to a layout pixel. */
        private List<Look> looks(HudLayers.Drawn drawn, double pixel) {
            List<Look> out = new ArrayList<>();
            double w = drawn.width(), h = drawn.height();
            double font = HudGeometry.FONT_PIXEL;
            for (HudLayers.Layer layer : drawn.layers()) {
                if (out.size() >= MOST_PARTS) break; // ponytail: cut past 64 displays; a budget per player if HUDs grow
                double cx = (layer.x() - w / 2) * pixel, cy = (h - layer.y()) * pixel;
                switch (layer.kind()) {
                    case RECT -> {
                        int alpha = layer.argb() >>> 24;
                        // A client draws text under 4 of alpha fully opaque: such a faint rect is left out.
                        if (alpha < 4) continue;
                        double sx = layer.w() * pixel / (BLOCK_SIDE * font), sy = layer.h() * pixel / (BLOCK_SIDE * font);
                        // The block stands one font pixel over its origin and is centred half a pixel right of it.
                        out.add(new Look((float) (cx - 0.5 * font * sx), (float) (cy - layer.h() * pixel / 2 - sy * font),
                                (float) sx, (float) sy,
                                BLOCK.color(net.kyori.adventure.text.format.TextColor.color(layer.argb() & 0xFFFFFF)),
                                alpha, SEE_THROUGH, 0, LINE_WIDTH));
                    }
                    case TEXT -> {
                        double scale = HudGeometry.scale(drawSize()) * layer.scale();
                        out.add(new Look((float) (cx - 0.5 * font * scale),
                                (float) (cy - (HudGeometry.LINE * layer.lines() + 1) * font * scale / 2),
                                (float) scale, (float) scale, layer.text(), 255, SEE_THROUGH | layer.flags(), 0,
                                LINE_WIDTH));
                    }
                    case PICTURE -> {
                        double cell = layer.w() * pixel;
                        double fx = cell / HudLayers.PICTURE_ADVANCE, fy = cell / HudLayers.PICTURE_LINE;
                        double x = cx + layer.dx() * fx, y = cy - layer.dy() * fy;
                        out.add(new Look((float) (x - 0.5 * fx), (float) (y - layer.lines() * HudLayers.PICTURE_LINE * fy / 2),
                                (float) (fx / font), (float) (fy / font), layer.text(), 255, SEE_THROUGH, 0, 4000));
                    }
                }
            }
            return out;
        }

        void send(Player player, boolean reseat) {
            if (!spawned() || !sameShape()) {
                boolean fresh = !spawned();
                if (!fresh) {
                    // Another shape: the layers are spawned again, back to front, so the client draws them in order.
                    for (Part part : parts) GuidePackets.destroyViewText(player, part.id);
                    parts.clear();
                }
                double k = fresh ? SHRUNK : 1;
                double[] from = fresh ? HudGeometry.popped(spot, tall, k) : spot;
                for (Look look : looks) {
                    Part part = new Part(GuidePackets.newId());
                    GuidePackets.spawnRider(player, part.id, look.text(), look.lineWidth(), look.background(),
                            look.opacity(), look.flags(), (float) (look.sx() * k), (float) (look.sy() * k),
                            at(from, look, k), false);
                    part.sent = look;
                    parts.add(part);
                }
                GuidePackets.remount(player);
                sentK = k;
                sentSpot = fresh ? null : spot;
                entering = fresh;
                return;
            }
            double k = 1;
            boolean moved = sentSpot == null || sentSpot[0] != spot[0] || sentSpot[1] != spot[1];
            int ticks;
            if (pulse == 2) {
                k = 1.12;
                ticks = 2;
                pulse = 1;
            } else if (pulse == 1) {
                ticks = 3;
                pulse = 0;
            } else if (entering || moved) {
                ticks = GLIDE;
            } else {
                ticks = 2;
            }
            for (int i = 0; i < parts.size(); i++) {
                Part part = parts.get(i);
                Look look = looks.get(i);
                boolean changed = !look.equals(part.sent);
                if (!(entering || moved || reseat || changed || k != sentK)) continue;
                boolean retext = !look.text().equals(part.sent.text());
                // New words jump to their own new place: cleaner than gliding the old ones there.
                int t = changed && !moved && !entering ? 0 : ticks;
                GuidePackets.riderTransform(player, part.id, at(spot, look, k), (float) (look.sx() * k),
                        (float) (look.sy() * k), t, retext ? look.text() : null,
                        look.opacity() != part.sent.opacity() ? look.opacity() : -1);
                part.sent = look;
            }
            entering = false;
            sentSpot = spot;
            sentK = k;
        }

        /** Whether the displays on the client can show {@link #looks} by a change of their data alone. */
        private boolean sameShape() {
            if (parts.size() != looks.size()) return false;
            for (int i = 0; i < parts.size(); i++) {
                Look sent = parts.get(i).sent, want = looks.get(i);
                if (sent.flags() != want.flags() || sent.background() != want.background()
                        || sent.lineWidth() != want.lineWidth()) return false;
            }
            return true;
        }
    }

    private final class Widget extends Element implements HudHandle {
        final HudWidget spec;
        volatile boolean dead;
        volatile boolean again = true;
        long next;

        Widget(HudWidget spec) {
            this.spec = spec;
            this.size = BASE * spec.scale();
            this.background = spec.background();
        }

        @Override
        HudAnchor anchor() {
            return spec.anchor();
        }

        /** Takes over the displays of the widget it replaces, so a replacement changes in place. */
        void adopt(Widget old) {
            if (old.spec.anchor() != spec.anchor()) {
                for (Part part : old.parts) ghosts.add(new int[]{part.id, (int) now});
                old.parts.clear();
                return;
            }
            parts.addAll(old.parts);
            old.parts.clear();
            sentSpot = old.sentSpot;
            sentK = old.sentK;
        }

        @Override
        public void refresh() {
            again = true;
        }

        @Override
        public void remove() {
            if (dead) return;
            dead = true;
            queued.add(player -> {
                if (widgets.get(spec.id()) == this) {
                    widgets.remove(spec.id());
                    leave(player, this);
                }
            });
        }

        @Override
        public boolean isShowing() {
            return !dead && LiveHud.this.isShowing();
        }
    }

    private final class Notice extends Element {
        HudNotice spec;
        long until;
        int count = 1;

        Notice(HudNotice spec, long until) {
            this.spec = spec;
            this.until = until;
            this.text = spec.text();
            this.size = BASE * spec.scale();
            this.background = 0;
        }

        @Override
        HudAnchor anchor() {
            return spec.anchor();
        }
    }
}
