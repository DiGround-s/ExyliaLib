package net.exylia.lib.screen.internal;

import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import net.exylia.lib.packet.internal.IconDowngrade;
import net.exylia.lib.screen.Node;
import net.exylia.lib.screen.Screen;
import net.exylia.lib.screen.ScreenEvent;
import net.exylia.lib.screen.ScreenSession;
import net.exylia.lib.spatial.Hotspot;
import net.exylia.lib.spatial.SpatialClick;
import net.exylia.lib.spatial.SpatialInput;
import net.exylia.lib.spatial.SpatialPointer;
import net.exylia.lib.stage.StagePlane;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * A {@link Screen} standing in the world: a flat panel at a place, facing one way, drawn to each player near it
 * with their own state, hover and clicks. Nothing is frozen and nothing turns with the view; the crosshair is the
 * pointer (an {@linkplain SpatialInput#observe observing} one), a swing or a use on a button is its click.
 *
 * <p>Boxes, outlines and text are drawn as on a screen in front of the eyes; items, pictures and motion are not.
 * Each look has a display of its own, kept from frame to frame, so a redraw changes only what changed and a box
 * that goes is destroyed with it.
 */
public final class WorldBoard {

    /** Billboard fixed: the display keeps the yaw it was spawned with. */
    private static final byte FIXED = 0;
    /** Blocks between two layers, nearer the front for deeper boxes. */
    static final double LAYER = 0.004;

    private final Plugin plugin;
    private final TaskScheduler tasks;
    private final Location centre;
    private final float yaw;
    private final Screen screen;
    private final double unit;
    private final Map<UUID, View> views = new ConcurrentHashMap<>();
    private double range = 32;
    private double reach = 12;
    private TaskHandle tracker;
    private TaskHandle ticker;
    private volatile boolean removed;

    /**
     * @param centre the middle of the panel; its yaw is the way the panel faces (0 south, 90 west, 180 north)
     * @param width  how wide the panel is, in blocks; the height follows the screen's own shape
     */
    public WorldBoard(@NotNull Plugin plugin, @NotNull Location centre, @NotNull Screen screen, double width) {
        this.plugin = plugin;
        this.tasks = Tasks.of(plugin);
        this.centre = centre.clone();
        this.yaw = centre.getYaw();
        this.screen = screen;
        this.unit = width / screen.width();
    }

    public void range(double range) {
        this.range = range;
    }

    public void reach(double reach) {
        this.reach = reach;
    }

    /** Starts drawing it to whoever comes within range. */
    public void start() {
        tracker = tasks.runTimer(1, 10, this::track);
        ticker = tasks.runTimer(20, 20, this::redrawAll);
    }

    /** Draws it again for everyone who sees it (what a placeholder shows may have changed). */
    public void redrawAll() {
        for (View view : views.values()) view.redraw();
    }

    /** Takes it out of the world for everyone. */
    public void remove() {
        removed = true;
        if (tracker != null) tracker.cancel();
        if (ticker != null) ticker.cancel();
        for (View view : List.copyOf(views.values())) view.drop();
    }

    /** How many players see it now. */
    public int viewers() {
        return views.size();
    }

    private void track() {
        if (removed) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            tasks.runAtEntity(player, () -> {
                if (removed || !player.isOnline()) return;
                Location at = player.getLocation();
                boolean near = at.getWorld() == centre.getWorld() && at.distanceSquared(centre) <= range * range;
                View view = views.get(player.getUniqueId());
                if (near && view == null) {
                    view = new View(player, ScreenPackets.Wire.to(player), ScreenPackets::newId);
                    views.put(player.getUniqueId(), view);
                    view.attach();
                    view.render();
                } else if (!near && view != null) {
                    view.drop();
                }
            });
        }
        // Those who left the server are dropped by their pointer's end; this catches any it missed.
        for (Iterator<View> it = views.values().iterator(); it.hasNext(); ) {
            View view = it.next();
            if (!view.viewer.isOnline()) {
                it.remove();
                view.drop();
            }
        }
    }

    /** The plane the crosshair is tested against: its seat looks the opposite way the panel faces. */
    StagePlane plane() {
        return new StagePlane(centre.getWorld(), centre.getX(), centre.getY(), centre.getZ(), yaw + 180, 1, 1, 1, 1);
    }

    /**
     * Where a hit on the plane lands in layout pixels, {x, y} from the top left; {@code null} off the panel.
     *
     * @param unit blocks per layout pixel
     */
    static double @Nullable [] pixel(@Nullable StagePlane.Hit hit, double unit, double width, double height) {
        if (hit == null) return null;
        double px = width / 2 + hit.across() / unit;
        double py = height / 2 - hit.up() / unit;
        if (px < 0 || px > width || py < 0 || py > height) return null;
        return new double[]{px, py};
    }

    /** The index of the topmost clickable box under a layout pixel, or -1 for empty panel. */
    static int button(List<Layout.Placed> placed, double px, double py) {
        for (int i = placed.size() - 1; i >= 0; i--) {
            Layout.Placed box = placed.get(i);
            Node node = box.node();
            if ((node.onClick() != null || node.onShiftClick() != null) && box.holds(px, py)) return i;
        }
        return -1;
    }

    /** One player's board: their state, their hover, the displays they were sent. */
    final class View implements ScreenSession {

        final Player viewer;
        private final ScreenPackets.Wire wire;
        private final IntSupplier ids;
        private final Map<String, Object> state = new ConcurrentHashMap<>(screen.state());
        private final Map<String, Integer> sent = new HashMap<>();
        private final IconDowngrade.Limits limits;
        private volatile List<Layout.Placed> placed = List.of();
        private volatile String hovered;
        private volatile boolean pending;
        private SpatialPointer pointer;
        private volatile boolean dropped;

        View(Player viewer, ScreenPackets.Wire wire, IntSupplier ids) {
            this.viewer = viewer;
            this.wire = wire;
            this.ids = ids;
            this.limits = IconDowngrade.limitsOf(viewer);
        }

        List<Layout.Placed> placed() {
            return placed;
        }

        /** Gives the viewer the pointer that hovers and clicks it. On the viewer's thread. */
        void attach() {
            StagePlane plane = plane();
            pointer = SpatialInput.of(plugin).observe(viewer);
            pointer.add(Hotspot.custom(plane, hit -> pick(plane, hit))
                    .onCell(index -> hover(index))
                    .onPress(this::press));
            pointer.onEnd(end -> {
                views.remove(viewer.getUniqueId(), this);
                drop();
            });
        }

        int pick(StagePlane plane, StagePlane.Hit hit) {
            if (hit == null) return -1;
            Location eyes = viewer.getEyeLocation();
            if (plane.point(hit.across(), hit.up(), 0).distanceSquared(eyes) > reach * reach) return -1;
            double[] at = pixel(hit, unit, screen.width(), screen.height());
            return at == null ? -1 : button(placed, at[0], at[1]);
        }

        void hover(int index) {
            List<Layout.Placed> now = placed;
            String key = index < 0 || index >= now.size() ? null : now.get(index).key();
            if (java.util.Objects.equals(key, hovered)) return;
            hovered = key;
            render();
        }

        void press(SpatialClick click) {
            List<Layout.Placed> now = placed;
            if (click.cell() < 0 || click.cell() >= now.size()) return;
            Node node = now.get(click.cell()).node();
            boolean shift = click.sneaking();
            Consumer<ScreenEvent> handler = shift && node.onShiftClick() != null ? node.onShiftClick() : node.onClick();
            if (handler == null) return;
            if (screen.onInput() != null) screen.onInput().accept(this);
            handler.accept(new ScreenEvent(this, click.kind() == SpatialClick.Kind.RIGHT
                    ? ScreenEvent.Kind.RIGHT : ScreenEvent.Kind.LEFT, 0, node.id(), null, shift));
            render();
        }

        /** Lays the screen out and sends what changed. On the viewer's thread. */
        synchronized void render() {
            pending = false;
            if (dropped) return;
            List<Layout.Placed> laid = Layout.lay(screen.render().apply(this), screen.width(), screen.height());
            Map<String, Look> looks = looks(laid);
            placed = laid;
            List<PacketWrapper<?>> frame = new ArrayList<>(looks.size() + 4);
            Vector3d at = new Vector3d(centre.getX(), centre.getY(), centre.getZ());
            for (Map.Entry<String, Look> look : looks.entrySet()) {
                if (!sent.containsKey(look.getKey())) {
                    int id = ids.getAsInt();
                    sent.put(look.getKey(), id);
                    frame.add(ScreenPackets.spawnDisplay(id, at, yaw));
                }
            }
            List<Integer> gone = new ArrayList<>();
            for (Iterator<Map.Entry<String, Integer>> it = sent.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, Integer> entry = it.next();
                if (!looks.containsKey(entry.getKey())) {
                    gone.add(entry.getValue());
                    it.remove();
                }
            }
            // The looks were built against ids not known yet: give each its id now.
            for (Map.Entry<String, Look> look : looks.entrySet()) {
                frame.add(look.getValue().at(sent.get(look.getKey())));
            }
            if (!gone.isEmpty()) frame.add(ScreenPackets.destroy(gone.stream().mapToInt(Integer::intValue).toArray()));
            wire.send(frame);
        }

        /** Every rect and text of a layout, by look key. */
        private Map<String, Look> looks(List<Layout.Placed> laid) {
            Map<String, Look> out = new LinkedHashMap<>();
            int[] beneath = new int[64];
            double w = screen.width(), h = screen.height();
            for (Layout.Placed box : laid) {
                Node node = box.node();
                boolean over = box.key().equals(hovered);
                boolean rises = over && node.onClick() != null && node.hoverTransform() == null;
                int depth = Math.min(Math.max(0, box.depth()), beneath.length - 1);
                int base = depth == 0 ? 0 : beneath[depth - 1];
                String colour = over && node.hover() != null ? node.hover() : node.background();
                ScreenMath.Gradient gradient = ScreenMath.gradient(colour);
                int argb = gradient != null ? gradient.at(0.5) : ScreenMath.argb(colour, 0);
                argb = mix(argb, base);
                beneath[depth] = argb == 0 ? base : argb;
                double x = (box.x() + box.w() / 2 - w / 2) * unit;
                double y = (h / 2 - box.y() - box.h() / 2) * unit;
                int layer = 2 + depth * 3;
                int line = mix(ScreenMath.argb(over && node.hoverOutline() != null ? node.hoverOutline() : node.outline(), 0), base);
                if (line != 0 && node.outlineWidth() > 0) {
                    double grow = node.outlineWidth() * 2 + (rises ? 2 : 0);
                    double wide = (box.w() + grow) * unit, tall = (box.h() + grow) * unit;
                    if (argb == 0) {
                        double[][] edges = LiveScreen.edges(x, y, wide, tall, node.outlineWidth() * unit);
                        for (int i = 0; i < edges.length; i++) {
                            double[] e = edges[i];
                            out.put(box.key() + "#ol" + i, rect(e[0], e[1], e[2], e[3], layer - 1, line));
                        }
                    } else {
                        out.put(box.key() + "#ol", rect(x, y, wide, tall, layer - 1, line));
                    }
                }
                if (argb != 0 && box.w() > 0 && box.h() > 0) {
                    double rise = rises ? 2 : 0;
                    out.put(box.key() + "#bg", rect(x, y, (box.w() + rise) * unit, (box.h() + rise) * unit, layer, argb));
                }
                if (node.text() != null) {
                    Layout.Wrapped wrapped = box.wrapped();
                    Component words = wrapped != null ? wrapped.text() : node.text();
                    if (limits != null) words = IconDowngrade.swap(words, limits);
                    int lines = wrapped != null ? wrapped.lines() : 1;
                    double inner = box.w() - node.padLeft() - node.padRight();
                    double wideText = wrapped != null ? wrapped.width() * node.scale() : Layout.textWidth(node);
                    double fit = wideText > inner && inner > 0 ? inner / wideText : 1;
                    double spare = Math.max(0, inner - wideText * fit);
                    double shift = switch (node.textAlign()) {
                        case START, STRETCH -> -spare / 2;
                        case END -> spare / 2;
                        case CENTER -> 0;
                    };
                    double tx = (box.x() + node.padLeft() + inner / 2 + shift - w / 2) * unit;
                    double ty = (h / 2 - box.y() - node.padTop() - (box.h() - node.padTop() - node.padBottom()) / 2) * unit;
                    byte flags = (byte) ((node.shadow() ? 1 : 0) | (lines > 1 ? switch (node.textAlign()) {
                        case CENTER -> 0;
                        case END -> 0x10;
                        default -> 0x08;
                    } : 0));
                    double scale = node.scale() * fit * unit / ScreenPackets.fontPixel();
                    Component text = words;
                    int count = lines;
                    out.put(box.key() + "#t", id -> ScreenPackets.text(id, tx, ty, (layer + 1) * LAYER, scale, 1,
                            text, 0, count, flags, FIXED));
                }
            }
            return out;
        }

        private Look rect(double x, double y, double w, double h, int layer, int argb) {
            return id -> ScreenPackets.rect(id, x, y, layer * LAYER, w, h, argb, 0, FIXED);
        }

        /** Asks for a render on the viewer's thread, once however often it is asked. */
        @Override
        public void redraw() {
            if (dropped || pending) return;
            pending = true;
            tasks.runAtEntity(viewer, this::render);
        }

        /** Destroys everything sent and lets the pointer go. */
        synchronized void drop() {
            if (dropped) return;
            dropped = true;
            views.remove(viewer.getUniqueId(), this);
            if (pointer != null) pointer.detach();
            if (!sent.isEmpty()) {
                wire.send(List.of(ScreenPackets.destroy(sent.values().stream().mapToInt(Integer::intValue).toArray())));
                sent.clear();
            }
            if (screen.onClose() != null) screen.onClose().accept(this);
        }

        @Override
        public @NotNull Player viewer() {
            return viewer;
        }

        @Override
        public @NotNull Screen screen() {
            return screen;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> @NotNull T state(@NotNull String key, @NotNull T fallback) {
            Object value = state.get(key);
            return fallback.getClass().isInstance(value) ? (T) value : fallback;
        }

        @Override
        public void set(@NotNull String key, @Nullable Object value) {
            if (value == null) state.remove(key);
            else state.put(key, value);
            redraw();
        }

        @Override
        public boolean open() {
            return !dropped;
        }

        @Override
        public void close() {
            drop();
        }
    }

    /** A colour over what holds it, so only the root may be see-through. */
    private static int mix(int argb, int base) {
        return argb == 0 || base == 0 ? argb : ScreenMath.over(argb, base);
    }

    /** A look that is given its entity id when the frame is sent. */
    @FunctionalInterface
    private interface Look {
        PacketWrapper<?> at(int id);
    }
}
