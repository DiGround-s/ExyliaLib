package net.exylia.lib.guide.internal;

import net.exylia.lib.hud.HudAnchor;
import net.exylia.lib.hud.HudHandle;
import net.exylia.lib.hud.HudNotice;
import net.exylia.lib.hud.HudWidget;
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
 * Only translation and scale interpolate on a client. An element spawns off its edge (or small, in the middle), glides
 * to its place on the next draw, glides again when its stack reflows, and slides back out before it is destroyed.
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
        for (Map.Entry<HudAnchor, List<Element>> stack : stacks(player).entrySet()) {
            double offset = before(player, stack.getKey());
            for (Element element : stack.getValue()) {
                element.place(stack.getKey(), offset);
                offset += element.tall + HudGeometry.GAP * HudGeometry.pixel(element.size);
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
            Component text = on ? widget.spec.text().apply(player) : null;
            if (text == null || PlainTextComponentSerializer.plainText().serialize(text).isBlank()) {
                if (widget.id >= 0) leave(player, widget);
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
                if (element.id >= 0 && element.anchor() == anchor) {
                    offset += element.tall + HudGeometry.GAP * HudGeometry.pixel(element.size);
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
        for (Widget widget : widgets.values()) if (widget.text != null) shown.add(widget);
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

    /** Slides an element out and destroys it once it is gone. */
    private void leave(Player player, Element element) {
        if (element.id < 0) return;
        if (!stepsAside(player) && element.spot != null) {
            double[] out = HudGeometry.aside(element.anchor(), element.spot, element.wide, element.tall);
            GuidePackets.riderTransform(player, element.id, at(out),
                    element.anchor().x() == 0.5 ? 0.01f : sized(element.size), GLIDE, null);
            ghosts.add(new int[]{element.id, (int) (now + GLIDE + 1)});
        } else {
            ghosts.add(new int[]{element.id, (int) now});
        }
        element.id = -1;
        element.sent = null;
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

    /** A spot in the view as sent, the seat taken off. */
    private float[] at(double[] spot) {
        return HudGeometry.seated(spot[0], spot[1], sentPitch, sentSeat);
    }

    private float sized(double size) {
        return (float) HudGeometry.scale(size);
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
            if (element.id >= 0) GuidePackets.destroyViewText(player, element.id);
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
            if (element.id >= 0) GuidePackets.forgetViewText(viewer, element.id);
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

    private abstract class Element {
        int id = -1;
        Component text;
        Component sent;
        double size;
        int background;
        double wide;
        double tall;
        Component measured;
        double[] spot;
        double[] sentSpot;
        float sentScale;
        boolean entering;
        int pulse;

        abstract HudAnchor anchor();

        void reset() {
            id = -1;
            sent = null;
            sentSpot = null;
            entering = false;
        }

        void place(HudAnchor anchor, double offset) {
            if (text != measured) {
                measured = text;
                double pixel = HudGeometry.pixel(size);
                int lines = 1 + (int) PlainTextComponentSerializer.plainText().serialize(text).chars()
                        .filter(c -> c == '\n').count();
                wide = (Centering.pixelWidth(text) + 3) * pixel;
                tall = lines * HudGeometry.LINE * pixel;
            }
            spot = HudGeometry.place(anchor, offset, wide, tall);
        }

        void send(Player player, boolean reseat) {
            float scale = sized(size);
            boolean middle = anchor().x() == 0.5;
            if (id < 0) {
                id = GuidePackets.newId();
                double[] from = HudGeometry.aside(anchor(), spot, wide, tall);
                sent = text;
                sentScale = middle ? 0.01f : scale;
                GuidePackets.spawnRider(player, id, text, LINE_WIDTH, background, 255,
                        SEE_THROUGH | HudGeometry.align(anchor()), sentScale,
                        at(from));
                entering = true;
                return;
            }
            float wanted = scale;
            boolean moved = sentSpot == null || sentSpot[0] != spot[0] || sentSpot[1] != spot[1];
            boolean retext = !text.equals(sent);
            int ticks;
            if (pulse == 2) {
                wanted = scale * 1.12f;
                ticks = 2;
                pulse = 1;
            } else if (pulse == 1) {
                ticks = 3;
                pulse = 0;
            } else if (entering) {
                ticks = GLIDE;
            } else if (retext) {
                // Its own new size: jumping there with the new words reads cleaner than gliding the old ones.
                ticks = 0;
            } else if (moved) {
                ticks = GLIDE;
            } else {
                ticks = 2;
            }
            if (entering || moved || retext || reseat || wanted != sentScale) {
                GuidePackets.riderTransform(player, id, at(spot), wanted, ticks, retext ? text : null);
                entering = false;
                sentSpot = spot;
                sentScale = wanted;
                sent = text;
            }
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

        /** Takes over the display of the widget it replaces, so a replacement changes in place. */
        void adopt(Widget old) {
            if (old.spec.anchor() != spec.anchor() || old.background != background) {
                if (old.id >= 0) ghosts.add(new int[]{old.id, (int) now});
                return;
            }
            id = old.id;
            sent = old.sent;
            sentSpot = old.sentSpot;
            sentScale = old.sentScale;
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
