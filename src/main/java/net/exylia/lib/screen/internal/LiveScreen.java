package net.exylia.lib.screen.internal;

import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import net.exylia.lib.packet.Packets;
import net.exylia.lib.screen.Node;
import net.exylia.lib.screen.Screen;
import net.exylia.lib.screen.ScreenEvent;
import net.exylia.lib.screen.ScreenSession;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * One open screen: lays the tree out, sends only what changed, and turns the player's rotation, clicks, wheel
 * and Shift into a cursor and events.
 *
 * <p>Input arrives on the netty thread and handlers run on the viewer's; everything mutable is guarded by
 * {@code this}.
 *
 * <p>Internal: free to change without notice.
 */
final class LiveScreen implements ScreenSession {

    /** The layout is written as if the screen stood this far from the eyes, in blocks. */
    private static final double DISTANCE = 2.0;
    /** How far it really stands: close, so a wall the player faces never cuts through it. */
    private static final double DEPTH = 0.6;
    private static final double K = DEPTH / DISTANCE;
    /** Depth between layers, so stacked rects never fight. */
    private static final double LAYER = 0.001;
    /** The default layout area spans this many units across at {@link #DISTANCE}: inside a 16:9 view at FOV 70. */
    private static final double SPAN = 4.6;
    /** The backdrop, in units: past the edge of any view. */
    private static final double BACKDROP_WIDE = 16, BACKDROP_TALL = 10;
    /** Past this pitch the client's rotation is turned back silently; the view does not move. */
    private static final float RECENTRE_PITCH = 40f;
    /** Cursor arrow rows: how wide each row is, in arrow pixels. */
    private static final int[] ARROW = {1, 2, 3, 4, 5, 6, 3, 2};
    private static final double ARROW_PIXEL = 0.014;
    private static final int CURSOR_LAYER = 60;
    /** What one screen may draw; past it the rest is left out and reported once. */
    static final int BUDGET = 400;

    private final Plugin plugin;
    private final Player player;
    private final Screen screen;
    private final TaskScheduler tasks;
    private final Map<String, Object> state = new ConcurrentHashMap<>();
    private final Vector3d origin;
    private final double unit;
    private final int hitboxId = ScreenPackets.newId();
    private final int backdropId = ScreenPackets.newId();
    private final int[] cursorIds = new int[ARROW.length];
    private final int serverSlot;

    /** What is on the client now, by key. */
    private final Map<String, Drawn> drawn = new HashMap<>();
    private List<Layout.Placed> placed = List.of();
    private boolean open = true;
    private boolean overBudget;
    private TaskHandle ticker;
    private double cursorX, cursorY;
    private float lastYaw, lastPitch;
    private int recentreId;
    private float recentreBy;
    private int heldSlot;
    private boolean shifting;
    private String hovered;

    /** One display on the client: its entity and the last thing sent to it. */
    private record Drawn(int id, Object look) {
    }

    private record RectLook(double x, double y, double w, double h, int layer, int argb) {
    }

    private record TextLook(double x, double y, double scale, int layer, Component text) {
    }

    LiveScreen(Plugin plugin, Player player, Screen screen) {
        this.plugin = plugin;
        this.player = player;
        this.screen = screen;
        this.tasks = Tasks.of(plugin);
        this.unit = SPAN / Screen.WIDTH;
        this.serverSlot = player.getInventory().getHeldItemSlot();
        this.heldSlot = serverSlot;
        Location eyes = player.getEyeLocation();
        this.origin = new Vector3d(eyes.getX(), eyes.getY(), eyes.getZ());
        this.lastYaw = eyes.getYaw();
        this.lastPitch = eyes.getPitch();
        for (int i = 0; i < cursorIds.length; i++) cursorIds[i] = ScreenPackets.newId();
    }

    Plugin plugin() {
        return plugin;
    }

    int hitboxId() {
        return hitboxId;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** On the viewer's thread. */
    void show() {
        if (!screen.wheel()) Packets.of(plugin).fakeGameMode().cameraView(player, true);
        Packets.of(plugin).movement().freeze(player);
        List<PacketWrapper<?>> out = new ArrayList<>(ScreenPackets.spawnHitbox(hitboxId, origin));
        out.add(ScreenPackets.spawnDisplay(backdropId, origin));
        out.add(rect(backdropId, new RectLook(0, 0, BACKDROP_WIDE, BACKDROP_TALL, 0,
                ScreenMath.argb(screen.backdrop(), 0xFF0B0912))));
        for (int id : cursorIds) out.add(ScreenPackets.spawnDisplay(id, origin));
        synchronized (this) {
            cursor(out, 0);
        }
        ScreenPackets.send(player, out);
        redraw();
        ticker = tasks.runAtEntityTimer(player, 20L, 20L, this::redraw);
    }

    @Override
    public void close() {
        List<Integer> ids = new ArrayList<>();
        synchronized (this) {
            if (!open) return;
            open = false;
            for (Drawn d : drawn.values()) ids.add(d.id());
            drawn.clear();
        }
        ScreenRuntime.closed(this);
        if (ticker != null) ticker.cancel();
        ids.add(hitboxId);
        ids.add(backdropId);
        for (int id : cursorIds) ids.add(id);
        if (player.isOnline()) {
            ScreenPackets.send(player, List.of(
                    ScreenPackets.destroy(ids.stream().mapToInt(Integer::intValue).toArray()),
                    ScreenPackets.heldSlot(player.getInventory().getHeldItemSlot())));
        }
        Packets.of(plugin).movement().unfreeze(player);
        if (!screen.wheel()) Packets.of(plugin).fakeGameMode().cameraView(player, false);
        Consumer<ScreenSession> onClose = screen.onClose();
        if (onClose != null && player.isOnline() && plugin.isEnabled()) {
            tasks.runAtEntity(player, () -> guarded(() -> onClose.accept(this)));
        }
    }

    // ------------------------------------------------------------------
    // ScreenSession
    // ------------------------------------------------------------------

    @Override
    public @NotNull Player viewer() {
        return player;
    }

    @Override
    public @NotNull Screen screen() {
        return screen;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> @NotNull T state(@NotNull String key, @NotNull T fallback) {
        Object value = state.get(key);
        return value == null ? fallback : (T) value;
    }

    @Override
    public void set(@NotNull String key, @Nullable Object value) {
        if (value == null) state.remove(key);
        else state.put(key, value);
        redraw();
    }

    @Override
    public synchronized boolean open() {
        return open;
    }

    /** Runs the render function and sends the difference. Safe from any thread; renders on the viewer's. */
    @Override
    public void redraw() {
        if (!open()) return;
        tasks.runAtEntity(player, this::render);
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    /** On the viewer's thread. */
    private void render() {
        if (!open()) return;
        Node root;
        try {
            root = Objects.requireNonNull(screen.render().apply(this), "render returned null");
        } catch (RuntimeException failure) {
            plugin.getLogger().log(Level.WARNING, "A screen failed to render for " + player.getName(), failure);
            close();
            return;
        }
        List<Layout.Placed> laid = Layout.lay(root, screen.width(), screen.height());
        synchronized (this) {
            if (!open) return;
            placed = laid;
            Layout.Placed under = Layout.under(laid, px(), py(), Node::pointable);
            hovered = under == null ? null : under.key();
            sync();
        }
    }

    /** Sends what the layout wants and the client does not have. Holds the lock. */
    private void sync() {
        Map<String, Object> wanted = new HashMap<>();
        List<String> order = new ArrayList<>();
        for (Layout.Placed box : placed) {
            if (wanted.size() >= BUDGET) {
                if (!overBudget) {
                    overBudget = true;
                    plugin.getLogger().warning("A screen drew more than " + BUDGET + " displays; the rest are left out.");
                }
                break;
            }
            Node node = box.node();
            String colour = box.key().equals(hovered) && node.hover() != null ? node.hover() : node.background();
            int argb = ScreenMath.argb(colour, 0);
            double cx = (box.x() + box.w() / 2 - screen.width() / 2) * unit;
            double cy = (screen.height() / 2 - box.y() - box.h() / 2) * unit;
            int layer = 1 + box.depth() * 2;
            if (argb != 0 && box.w() > 0 && box.h() > 0) {
                String key = box.key() + "#bg";
                wanted.put(key, new RectLook(cx, cy, box.w() * unit, box.h() * unit, layer, argb));
                order.add(key);
            }
            if (node.text() != null) {
                // Text sits in its padding box: centred on what is left inside it.
                double tx = (box.x() + node.padLeft() + (box.w() - node.padLeft() - node.padRight()) / 2
                        - screen.width() / 2) * unit;
                double ty = (screen.height() / 2 - box.y() - node.padTop()
                        - (box.h() - node.padTop() - node.padBottom()) / 2) * unit;
                String key = box.key() + "#t";
                wanted.put(key, new TextLook(tx, ty, node.scale(), layer + 1, node.text()));
                order.add(key);
            }
        }
        List<PacketWrapper<?>> out = new ArrayList<>();
        Set<String> gone = new HashSet<>(drawn.keySet());
        gone.removeAll(wanted.keySet());
        if (!gone.isEmpty()) {
            int[] ids = new int[gone.size()];
            int i = 0;
            for (String key : gone) ids[i++] = drawn.remove(key).id();
            out.add(ScreenPackets.destroy(ids));
        }
        for (String key : order) {
            Object look = wanted.get(key);
            Drawn current = drawn.get(key);
            if (current != null && current.look().equals(look)) continue;
            int id = current != null ? current.id() : ScreenPackets.newId();
            if (current == null) out.add(ScreenPackets.spawnDisplay(id, origin));
            out.add(look instanceof RectLook r ? rect(id, r) : text(id, (TextLook) look));
            drawn.put(key, new Drawn(id, look));
        }
        if (!out.isEmpty()) ScreenPackets.send(player, out);
    }

    private PacketWrapper<?> rect(int id, RectLook r) {
        return ScreenPackets.rect(id, r.x() * K, r.y() * K, -DEPTH + r.layer() * LAYER, r.w() * K, r.h() * K,
                r.argb(), 0);
    }

    private PacketWrapper<?> text(int id, TextLook t) {
        double scale = t.scale() * unit * K / ScreenPackets.fontPixel();
        return ScreenPackets.text(id, t.x() * K, t.y() * K, -DEPTH + t.layer() * LAYER, scale, t.text());
    }

    /** The arrow, gliding one tick to where the cursor is: smooth, and never more than a packet behind. */
    private void cursor(List<PacketWrapper<?>> out, int glide) {
        for (int i = 0; i < ARROW.length; i++) {
            double w = ARROW[i] * ARROW_PIXEL;
            out.add(ScreenPackets.rect(cursorIds[i], (cursorX + w / 2) * K, (cursorY - (i + 0.5) * ARROW_PIXEL) * K,
                    -DEPTH + CURSOR_LAYER * LAYER, w * K, ARROW_PIXEL * K, 0xFFFFFFFF, glide));
        }
    }

    /** The cursor in layout pixels. */
    private double px() {
        return cursorX / unit + screen.width() / 2;
    }

    private double py() {
        return screen.height() / 2 - cursorY / unit;
    }

    // ------------------------------------------------------------------
    // Input (netty thread)
    // ------------------------------------------------------------------

    synchronized void turned(float yaw, float pitch) {
        if (!open) return;
        double perDegree = DISTANCE * Math.PI / 180 * ScreenRuntime.sensitivity(player.getUniqueId());
        double halfW = screen.width() / 2 * unit;
        double halfH = screen.height() / 2 * unit;
        cursorX = ScreenMath.clamp(cursorX + ScreenMath.turn(lastYaw, yaw) * perDegree, -halfW, halfW);
        cursorY = ScreenMath.clamp(cursorY - (pitch - lastPitch) * perDegree, -halfH, halfH);
        lastYaw = yaw;
        lastPitch = pitch;
        List<PacketWrapper<?>> out = new ArrayList<>();
        cursor(out, 1);
        Layout.Placed under = Layout.under(placed, px(), py(), Node::pointable);
        String now = under == null ? null : under.key();
        if (!Objects.equals(now, hovered)) {
            hovered = now;
            if (now != null && under.node().hover() != null) sound("ui.button.click", 0.15f, 2.0f);
            ScreenPackets.send(player, out);
            out.clear();
            sync();
        }
        if (recentreId == 0 && Math.abs(pitch) > RECENTRE_PITCH) {
            recentreId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
            recentreBy = pitch;
            out.add(ScreenPackets.recentre(recentreId, pitch));
        }
        if (!out.isEmpty()) ScreenPackets.send(player, out);
    }

    synchronized boolean confirmed(int teleportId) {
        if (recentreId == 0 || teleportId != recentreId) return false;
        lastPitch -= recentreBy;
        recentreId = 0;
        return true;
    }

    synchronized void click(boolean right) {
        if (!open) return;
        Layout.Placed box = Layout.under(placed, px(), py(), node -> node.onClick() != null);
        if (box == null) return;
        Consumer<ScreenEvent> handler = box.node().onClick();
        ScreenEvent event = new ScreenEvent(this, right ? ScreenEvent.Kind.RIGHT : ScreenEvent.Kind.LEFT, 0,
                box.node().id());
        sound("ui.button.click", 0.35f, 1.4f);
        handle(handler, event);
    }

    synchronized void wheel(int slot) {
        int steps = slot - heldSlot;
        if (steps > 4) steps -= 9;
        if (steps < -4) steps += 9;
        heldSlot = slot;
        if (steps == 0 || !open) return;
        Layout.Placed box = Layout.under(placed, px(), py(), node -> node.onScroll() != null);
        if (box == null) return;
        handle(box.node().onScroll(), new ScreenEvent(this, ScreenEvent.Kind.SCROLL, steps, box.node().id()));
    }

    synchronized void shift(boolean down) {
        boolean pressed = down && !shifting;
        shifting = down;
        if (pressed && open) tasks.runAtEntity(player, this::close);
    }

    /** Runs a handler on the viewer's thread, then redraws. */
    private void handle(Consumer<ScreenEvent> handler, ScreenEvent event) {
        tasks.runAtEntity(player, () -> {
            if (!open()) return;
            guarded(() -> handler.accept(event));
            render();
        });
    }

    private void guarded(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException failure) {
            plugin.getLogger().log(Level.WARNING, "A screen handler failed for " + player.getName(), failure);
        }
    }

    private void sound(String key, float volume, float pitch) {
        tasks.runAtEntity(player, () -> player.playSound(player.getLocation(), key, SoundCategory.MASTER, volume, pitch));
    }

}
