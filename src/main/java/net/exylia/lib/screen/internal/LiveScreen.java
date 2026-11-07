package net.exylia.lib.screen.internal;

import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import net.exylia.lib.packet.Packets;
import net.exylia.lib.screen.Motion;
import net.exylia.lib.screen.Node;
import net.exylia.lib.screen.Screen;
import net.exylia.lib.screen.ScreenEvent;
import net.exylia.lib.screen.ScreenSession;
import net.exylia.lib.screen.ScreenSettings;
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
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
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
 * <p>Input arrives on the netty thread, which only moves the cursor (guarded by {@link #input}) and hit-tests the
 * last published {@link View}; rendering, hover and handlers run on the viewer's thread under {@code this}. Lock
 * order: {@code this}, then {@code input}, then the wire.
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
    /** The default layout area spans this many units across at {@link #DISTANCE}: about three quarters of a 16:9 view at FOV 70. */
    private static final double SPAN = 5.6;
    /** How much wider a camera view's held field of view shows the world than the one the layout was tuned at. */
    private static final double CAMERA_VIEW_SCALE = 1.255;
    /** The backdrop, in units: past the edge of any view. */
    private static final double BACKDROP_WIDE = 16, BACKDROP_TALL = 10;
    /** Past this pitch the client's rotation is turned back silently; the view does not move. */
    private static final float RECENTRE_PITCH = 40f;
    /** The pointer, a pixel per character: {@code #} outline, {@code o} fill, {@code .} clear. */
    private static final String[] ARROW = {
            "#..........",
            "##.........",
            "#o#........",
            "#oo#.......",
            "#ooo#......",
            "#oooo#.....",
            "#ooooo#....",
            "#oooooo#...",
            "#ooooooo#..",
            "#oooooooo#.",
            "#ooooo#####",
            "#oo#oo#....",
            "#o#.#oo#...",
            "##..#oo#...",
            "#....#oo#..",
            ".....#oo#..",
            "......##...",
    };
    /** One pointer pixel, in layout units at the designed size. */
    private static final double ARROW_PIXEL = 0.0072;
    private static final int[] ARROW_ARGB = arrowPixels();
    private static final int CURSOR_LAYER = 250;
    /** How long a toast stays, in milliseconds. */
    private static final long TOAST_MILLIS = 3000;
    /** What one screen may draw; past it the rest is left out and reported once. */
    static final int BUDGET = 400;

    private final Plugin plugin;
    private final Player player;
    private final Screen screen;
    private final TaskScheduler tasks;
    private final Map<String, Object> state = new ConcurrentHashMap<>();
    private final Vector3d origin;
    private final double unit;
    // Given out again on every show, after the world is sealed: only ids newer than the seal are the screen's.
    private volatile int hitboxId;
    private volatile int[] backdropIds = new int[0];
    /** The scene's four copies, and how it is drawn (guarded by {@link #input}); {@code sceneText} null for none. */
    private final int[] sceneIds = new int[4];
    private Component sceneText;
    private int sceneRows;
    private double sceneCell;
    private final int[] cursorIds = new int[4];
    private final int serverSlot;
    private final ScreenPackets.Wire wire;
    private final java.util.function.IntSupplier ids;
    /** Guards the cursor and the keys: what the netty thread writes. Never held while taking {@code this}. */
    private final Object input = new Object();

    /** What the pointer can reach, as last laid out: read by the netty thread without a lock. */
    private record View(List<Layout.Placed> placed, int overlayFrom) {
        static final View EMPTY = new View(List.of(), -1);

        List<Layout.Placed> targets() {
            return overlayFrom < 0 ? placed : placed.subList(overlayFrom, placed.size());
        }
    }

    private volatile View view = View.EMPTY;
    /** A render is queued: set() and redraw() within one tick render once. */
    private final java.util.concurrent.atomic.AtomicBoolean dirty = new java.util.concurrent.atomic.AtomicBoolean();
    /** A tooltip's panel colour, opaque, and its depth: over everything, a dialog included. */
    private static final String TIP_BACKGROUND = "#120e1a";
    /** A toast's panel: the primary colour over the black it is mixed on (toasts stand over everything). */
    private static final String TOAST_BACKGROUND = "{primary}30";
    private static final int TIP_DEPTH = Layout.OVERLAY + 8;

    /** A hover change is queued for the viewer's thread. */
    private final java.util.concurrent.atomic.AtomicBoolean hoverQueued = new java.util.concurrent.atomic.AtomicBoolean();
    /** The last render's tree, laid out without the toast, and what it drew: an unchanged one skips layout and diff. */
    private List<Layout.Placed> lastLaid;
    private long lastPrint;
    /** Whether the last frame drew a turning body, which must move every second. */
    private boolean turning;

    /** What is on the client now, by key. */
    private final Map<String, Drawn> drawn = new HashMap<>();
    private List<Layout.Placed> placed = List.of();
    private volatile boolean open = true;
    /** Stepped aside for an input: nothing drawn, nothing taken, until it ends. */
    private volatile boolean away;
    private boolean overBudget;
    /** Whether the first frame went out: the screen opens with a zoom, later arrivals slide in. */
    private boolean shown;
    /** Ticks a hover or a moved box takes to glide, and a new box to settle in. */
    private static final int GLIDE = 2;
    private static final int SETTLE = 4;
    /** Blocks around the eyes where another player could stand in front of the screen. */
    private static final double HIDE_RADIUS = 4;
    /** How far around the viewer the world is emptied while a screen is up: past any view distance's entities. */
    private static final double SEAL_REACH = 192;
    private TaskHandle ticker;
    /** A hovered box keeps its hover this many pixels past its edges. */
    private static final double HYSTERESIS = 3;
    /** How long a clicked box shows pressed, and how long Shift is held to close from anywhere. */
    private static final int ACTIVE_TICKS = 3, HOLD_TICKS = 12;
    private static final int MAX_TOASTS = 3;
    /** Toasts on screen, oldest first; guarded by {@code this}. */
    private final List<Toast> toasts = new ArrayList<>();
    private int toastSeq;
    private int toastVersion;
    private int lastToastVersion = -1;
    private record Toast(Component text, String tone, long until, int seq) {
    }
    /** The in-screen confirm waiting for an answer, or {@code null}. */
    private volatile Confirm confirm;
    private record Confirm(Component question, boolean dangerous, String yes, String no, Consumer<ScreenSession> then) {
    }
    /** The box just clicked, drawn {@code :active} for a moment; guarded by {@code this}. */
    private String activeKey;
    private int activeGen;
    /** A render must draw even when the tree and the hover are the same (a press began). */
    private boolean resync;
    /** Per display, bumped when it gets a new target: queued steps of an older glide are dropped. */
    private final Map<Integer, Integer> generation = new HashMap<>();
    /** Displays gliding away before they are removed. */
    private final Set<Integer> leaving = new HashSet<>();
    /** The last frame's boxes, by key: where departures are measured from. */
    private Map<String, Box> lastBoxes = Map.of();
    /** When each animating box started, on the animation clock. */
    private final Map<String, Long> animStart = new HashMap<>();
    private long animClock;
    private int animStep = 2;
    private TaskHandle animator;
    private long motionSecond;
    private int motionPackets;
    private int refreshPeriod = 20;
    /** Whether items are drawn as one sprite glyph for this viewer. */
    private final boolean spriteGlyphs;
    // The cursor, the recentre and the keys below are guarded by input.
    private double cursorX, cursorY;
    private float lastYaw, lastPitch;
    private int recentreId;
    private float recentreBy;
    private int heldSlot;
    private boolean shifting;
    private long shiftStamp;
    private boolean sprinting;
    private String hovered;
    /** What the netty thread last saw under the pointer. Guarded by {@link #input}. */
    private String pointed;
    private Layout.Placed pointedBox;
    /** Where the open overlay's boxes start in {@link #placed}, or -1. */
    private int overlayFrom = -1;
    private Layout.Placed hoveredBox;
    /** Players this screen hid from the viewer, shown again when it closes. */
    private final Set<java.util.UUID> hidden = ConcurrentHashMap.newKeySet();

    /** One display on the client: its entity and the last thing sent to it. */
    private record Drawn(int id, Object look, String box) {
    }

    private record RectLook(double x, double y, double w, double h, int layer, int argb) {
    }

    /** One of a picture's four copies: x, y its centre, cell one picture pixel, dx and dy the copy's nudge. */
    private record ImageLook(double x, double y, double cell, int layer, Component text, int rows, int dx, int dy) {
    }

    /**
     * One side of a turning body: the body's centre ({@code x}, {@code y}), a skin pixel's size, where the side
     * stands from the centre in skin pixels, the way it faces, the body's turn and the side's own scale.
     */
    private record TurnLook(double x, double y, double cell, int layer, Component text, int rows, double lx,
                            double ly, double lz, double yaw, double turn, int dx, int dy, double size) {
    }

    /** How fast a body turns, degrees a second, and how long each glide to the next angle lasts. */
    private static final double TURN_SPEED = 36;
    private static final int TURN_TICKS = 20;

    /** Words: {@code lines} of them joined by newlines, {@code flags} the display's text flags (shadow, alignment). */
    private record TextLook(double x, double y, double scale, int layer, Component text, int lines, int flags) {
    }

    private record ItemLook(double x, double y, double size, int layer, org.bukkit.inventory.ItemStack item) {
    }

    LiveScreen(Plugin plugin, Player player, Screen screen) {
        this(plugin, player, screen, ScreenPackets.Wire.to(player), ScreenPackets::newId);
    }

    /** With the wire and entity ids given, so a test can count what is sent without a server. */
    LiveScreen(Plugin plugin, Player player, Screen screen, ScreenPackets.Wire wire, java.util.function.IntSupplier ids) {
        this.plugin = plugin;
        this.player = player;
        this.screen = screen;
        this.tasks = Tasks.of(plugin);
        this.wire = wire;
        this.ids = ids;
        // A camera view holds the field of view at its widest (see FakeGameMode#cameraView): drawn larger to
        // look the size it was designed at.
        this.unit = (screen.wheel() ? SPAN : SPAN * CAMERA_VIEW_SCALE) / Screen.WIDTH;
        this.serverSlot = player.getInventory().getHeldItemSlot();
        this.heldSlot = serverSlot;
        Location eyes = player.getEyeLocation();
        this.origin = new Vector3d(eyes.getX(), eyes.getY(), eyes.getZ());
        this.lastYaw = eyes.getYaw();
        this.lastPitch = eyes.getPitch();
        state.putAll(screen.state());
        this.spriteGlyphs = spritesFor(player);
    }

    /** Sprite glyphs need a 1.21.11+ server that draws them, a 1.21.11+ client, and the server's leave. */
    private static boolean spritesFor(Player player) {
        try {
            return ScreenSettings.get().spriteObjects() && net.exylia.lib.text.internal.Icons.sprites()
                    && net.exylia.lib.text.internal.Icons.itemsAtlas()
                    && net.exylia.lib.packet.ClientVersions.atLeast(player,
                    com.github.retrooper.packetevents.protocol.player.ClientVersion.V_1_21_11);
        } catch (RuntimeException | LinkageError noClient) {
            return false;
        }
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
        // A player standing at the viewer's eyes would be drawn in front of the screen and take its clicks;
        // in a lobby everyone stands at spawn. Hidden for this plugin only, so other plugins' rules stay.
        for (Player other : player.getWorld().getNearbyPlayers(player.getEyeLocation(), HIDE_RADIUS)) hide(other);
        // Everything else in the world, for this viewer only, while the screen is up: nametags draw through
        // anything, and mobs, holograms and players far behind the screen would show through or at its edges.
        // The screen's own pieces are spawned after this, so they stay.
        Packets.of(plugin).visibility().seal(player, new net.exylia.lib.packet.Volume(origin.x, origin.z,
                0f, SEAL_REACH, origin.y - SEAL_REACH, origin.y + SEAL_REACH));
        start();
    }

    /** On the viewer's thread, once the world is sealed: the screen's own entities, then its first render. */
    void start() {
        hitboxId = ids.getAsInt();
        for (int i = 0; i < cursorIds.length; i++) cursorIds[i] = ids.getAsInt();
        List<PacketWrapper<?>> out = new ArrayList<>(ScreenPackets.spawnHitbox(hitboxId, origin));
        List<RectLook> back = backdropLooks();
        int[] backIds = new int[back.size()];
        for (int i = 0; i < backIds.length; i++) {
            backIds[i] = ids.getAsInt();
            out.add(ScreenPackets.spawnDisplay(backIds[i], origin));
            out.add(rect(backIds[i], back.get(i), 0));
        }
        backdropIds = backIds;
        Node scene = screen.scene();
        if (scene != null) {
            for (int i = 0; i < sceneIds.length; i++) {
                sceneIds[i] = ids.getAsInt();
                out.add(ScreenPackets.spawnDisplay(sceneIds[i], origin));
            }
        }
        for (int id : cursorIds) out.add(ScreenPackets.spawnDisplay(id, origin));
        synchronized (this) {
            // Drawn afresh: nothing on the client is the last render's any more.
            lastLaid = null;
            lastBoxes = Map.of();
            refreshPeriod = 20;
            synchronized (input) {
                if (scene != null) sceneSetup(scene);
                cursor(out, 0);
                if (scene != null && screen.parallax() <= 0) scene(out, 0);
            }
        }
        wire.send(out);
        redraw();
        ticker = tasks.runAtEntityTimer(player, 20L, 20L, this::renderNow);
    }

    @Override
    public void close() {
        List<Integer> ids = new ArrayList<>();
        synchronized (this) {
            if (!open) return;
            open = false;
            for (Drawn d : drawn.values()) ids.add(d.id());
            drawn.clear();
            ids.addAll(leaving);
            leaving.clear();
            generation.clear();
            if (animator != null) animator.cancel();
            animator = null;
        }
        ScreenRuntime.closed(this);
        if (ticker != null) ticker.cancel();
        ids.add(hitboxId);
        for (int id : backdropIds) ids.add(id);
        if (screen.scene() != null) for (int id : sceneIds) ids.add(id);
        for (int id : cursorIds) ids.add(id);
        if (player.isOnline()) {
            wire.send(List.of(
                    ScreenPackets.destroy(ids.stream().mapToInt(Integer::intValue).toArray()),
                    ScreenPackets.heldSlot(player.getInventory().getHeldItemSlot())));
        }
        Packets.of(plugin).movement().unfreeze(player);
        Packets.of(plugin).visibility().seal(player, null);
        if (!hidden.isEmpty() && player.isOnline()) {
            // Now when we can, so a screen opened right after this one finds everyone visible to hide again.
            if (org.bukkit.Bukkit.isOwnedByCurrentRegion(player)) reveal();
            else if (plugin.isEnabled()) tasks.runAtEntity(player, this::reveal);
        }
        if (!screen.wheel()) Packets.of(plugin).fakeGameMode().cameraView(player, false);
        Consumer<ScreenSession> onClose = screen.onClose();
        if (onClose != null && player.isOnline() && plugin.isEnabled()) {
            tasks.runAtEntity(player, () -> guarded(() -> onClose.accept(this)));
        }
    }

    @Override
    public <T> @NotNull java.util.concurrent.CompletionStage<T> aside(
            @NotNull java.util.function.Supplier<? extends java.util.concurrent.CompletionStage<T>> input) {
        List<Integer> ids = new ArrayList<>();
        synchronized (this) {
            if (!open || away) return input.get();
            away = true;
            for (Drawn d : drawn.values()) ids.add(d.id());
            drawn.clear();
            ids.addAll(leaving);
            leaving.clear();
            generation.clear();
            lastBoxes = Map.of();
            if (animator != null) animator.cancel();
            animator = null;
        }
        if (ticker != null) ticker.cancel();
        ids.add(hitboxId);
        for (int id : backdropIds) ids.add(id);
        if (screen.scene() != null) for (int id : sceneIds) ids.add(id);
        for (int id : cursorIds) ids.add(id);
        wire.send(List.of(ScreenPackets.destroy(ids.stream().mapToInt(Integer::intValue).toArray())));
        Packets.of(plugin).visibility().seal(player, null);
        java.util.concurrent.CompletionStage<T> future;
        try {
            future = input.get();
        } catch (RuntimeException failure) {
            returned();
            throw failure;
        }
        future.whenComplete((done, failure) -> {
            if (plugin.isEnabled() && player.isOnline()) tasks.runAtEntity(player, this::returned);
        });
        return future;
    }

    /** On the viewer's thread: the screen returns from {@link #aside}, drawn afresh. */
    private void returned() {
        synchronized (this) {
            if (!open || !away) return;
            away = false;
        }
        // What the input led to may be a window (a chest menu opened from its answer): that covers the screen.
        if (player.getOpenInventory().getTopInventory().getType() != org.bukkit.event.inventory.InventoryType.CRAFTING) {
            close();
            return;
        }
        show();
    }

    /**
     * On the viewer's thread. Another player's position is theirs to read: on Folia one in another region is read
     * on their own thread and handed back here.
     */
    private void hide(Player other) {
        if (other.equals(player) || !other.isOnline()) return;
        if (org.bukkit.Bukkit.isOwnedByCurrentRegion(other)) {
            hideIfNear(other, other.getLocation());
            return;
        }
        tasks.runAtEntity(other, () -> {
            if (!other.isOnline()) return;
            Location at = other.getLocation();
            tasks.runAtEntity(player, () -> {
                if (open()) hideIfNear(other, at);
            });
        });
    }

    /** On the viewer's thread, with where {@code other} stood. */
    private void hideIfNear(Player other, Location at) {
        if (!player.canSee(other) || !java.util.Objects.equals(at.getWorld(), player.getWorld())
                || at.distanceSquared(player.getEyeLocation()) > HIDE_RADIUS * HIDE_RADIUS) return;
        player.hidePlayer(plugin, other);
        hidden.add(other.getUniqueId());
    }

    /** Hides a player who arrived while the screen is open. */
    void hideLater(Player other) {
        tasks.runAtEntity(player, () -> {
            if (open()) hide(other);
        });
    }

    /** On the viewer's thread: shows again only those this screen hid. */
    private void reveal() {
        for (java.util.UUID id : hidden) {
            Player other = org.bukkit.Bukkit.getPlayer(id);
            // The server remembers a hidden player by id even after they quit: shown again when they return.
            if (other != null) player.showPlayer(plugin, other);
            else ScreenRuntime.revealOnJoin(player, id, plugin);
        }
        hidden.clear();
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
    public boolean open() {
        return open;
    }

    /**
     * Runs the render function and sends the difference. Safe from any thread; renders on the viewer's, once
     * however many calls arrive before it runs.
     */
    @Override
    public void redraw() {
        if (!open()) return;
        if (dirty.compareAndSet(false, true)) tasks.runAtEntity(player, this::renderIfDirty);
    }

    private void renderIfDirty() {
        if (dirty.compareAndSet(true, false)) render();
    }

    /** On the viewer's thread: renders now, taking any queued redraw with it. */
    private void renderNow() {
        dirty.set(false);
        render();
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    /** On the viewer's thread. */
    private void render() {
        synchronized (this) {
            if (!open || away) return;
        }
        Node root;
        try {
            root = Objects.requireNonNull(screen.render().apply(this), "render returned null");
        } catch (RuntimeException | StackOverflowError failure) {
            plugin.getLogger().log(Level.WARNING, "A screen failed to render for " + player.getName(), failure);
            close();
            return;
        }
        Confirm ask = confirm;
        if (ask != null) root = withConfirm(root, ask);
        // The tree's fingerprint covers everything layout and drawing read from it, and a sprite arriving changes
        // what an unchanged tree draws: same print, same toasts, nothing turning, and the last frame still holds.
        long print = Layout.fingerprint(root) * 31 + VanillaSprites.generation();
        synchronized (this) {
            if (!open || away) return;
            pruneToasts();
            List<Layout.Placed> core = lastLaid != null && print == lastPrint && toastVersion == lastToastVersion
                    && !turning ? Layout.relink(lastLaid, root) : null;
            boolean same = core != null;
            // The handlers are this render's either way: a click acts on what it shows now.
            if (core == null) core = Layout.lay(root, screen.width(), screen.height());
            lastLaid = core;
            lastPrint = print;
            lastToastVersion = toastVersion;
            List<Layout.Placed> laid = new ArrayList<>(core);
            addToasts(laid);
            placed = laid;
            overlayFrom = Layout.overlayStart(laid);
            view = new View(laid, overlayFrom);
            retime(core);
            Layout.Placed under = underPointer();
            String now = under == null ? null : under.key();
            if (same && Objects.equals(now, hovered) && !resync) return;
            resync = false;
            entered(under, hovered);
            hovered = now;
            hoveredBox = under;
            sync(false);
        }
    }

    /**
     * The hovered box's {@link Node#tooltip}, laid out beside it: to its right, or its left when that is out of
     * the layout area, kept inside it top to bottom. Above every page and dialog. Holds the lock.
     */
    private List<Layout.Placed> tooltip() {
        Layout.Placed box = hoveredBox;
        if (box == null || box.node().tooltip() == null) return List.of();
        Node panel = Node.column().padding(4, 6).gap(1).background(TIP_BACKGROUND).outline("{primary}", 1)
                .enter(Motion.Tween.of(Motion.Transform.scale(0.96), 2, Motion.Ease.OUT));
        for (net.kyori.adventure.text.Component line : box.node().tooltip()) panel.add(Node.text(line));
        double[] size = Layout.measure(panel);
        double x = box.x() + box.w() + 6;
        if (x + size[0] > screen.width()) x = box.x() - 6 - size[0];
        x = Math.max(2, Math.min(x, screen.width() - size[0] - 2));
        double y = Math.max(2, Math.min(box.y(), screen.height() - size[1] - 2));
        List<Layout.Placed> out = new ArrayList<>();
        for (Layout.Placed part : Layout.lay(panel, size[0], size[1])) {
            out.add(part.moved("~tip" + part.key(), x, y, TIP_DEPTH, 0));
        }
        return out;
    }

    /** Whether the screen stepped aside for an input. */
    synchronized boolean away() {
        return away;
    }

    /** The pointable box under the cursor now, keeping the hovered one a little past its edges. Holds the lock. */
    private Layout.Placed underPointer() {
        double x, y;
        synchronized (input) {
            x = px();
            y = py();
        }
        return pick(targets(), x, y, hoveredBox);
    }

    /**
     * The pointable box under a point. The one already hovered keeps the hover {@value #HYSTERESIS} pixels past
     * its edges unless the point is inside a box nested in it, so the hover never flickers on a border.
     */
    private static Layout.Placed pick(List<Layout.Placed> targets, double x, double y, Layout.Placed current) {
        Layout.Placed under = Layout.under(targets, x, y, Node::pointable);
        if (current == null || under != null && under.key().equals(current.key())) return under;
        if (under != null && under.depth() > current.depth()) return under;
        for (int i = targets.size() - 1; i >= 0; i--) {
            Layout.Placed box = targets.get(i);
            if (box.key().equals(current.key())) {
                return box.node().pointable() && box.holds(x, y, HYSTERESIS) ? box : under;
            }
        }
        return under;
    }

    /** On the viewer's thread: the box under the pointer changed since the last frame. */
    private void hoverMoved() {
        hoverQueued.set(false);
        synchronized (this) {
            if (!open || away) return;
            Layout.Placed under = underPointer();
            String now = under == null ? null : under.key();
            if (Objects.equals(now, hovered)) return;
            entered(under, hovered);
            hovered = now;
            hoveredBox = under;
            sync(false);
        }
    }

    /** Runs the {@link Node#onHover} of a box the cursor has just come onto, on the viewer's thread. */
    private void entered(Layout.Placed under, String before) {
        if (under == null || under.key().equals(before) || under.node().onHover() == null) return;
        handle(under.node().onHover(), new ScreenEvent(this, ScreenEvent.Kind.LEFT, 0, under.node().id()));
    }

    // ------------------------------------------------------------------
    // Toasts, confirms, refresh
    // ------------------------------------------------------------------

    /** Drops the toasts whose time is up. Holds the lock. */
    private void pruneToasts() {
        long now = System.currentTimeMillis();
        if (toasts.removeIf(t -> t.until() <= now)) toastVersion++;
    }

    /** The toasts, stacked at the top centre over everything, newest at the bottom. Holds the lock. */
    private void addToasts(List<Layout.Placed> laid) {
        double y = 6;
        for (Toast t : toasts) {
            Node note = Node.text(t.text()).padding(5, 12).background(TOAST_BACKGROUND).outline(tone(t.tone()), 1)
                    .enter(Motion.Tween.of(Motion.Transform.translate(0, -8), 5, Motion.Ease.OUT))
                    .leave(Motion.Tween.of(new Motion.Transform(0, -6, 0.96), 3, Motion.Ease.IN));
            double[] size = Layout.measure(note);
            laid.add(new Layout.Placed(note, "~toast" + t.seq(), (screen.width() - size[0]) / 2, y, size[0], size[1],
                    Layout.OVERLAY + 20));
            y += size[1] + 3;
        }
    }

    /**
     * A gradient over a rect centred on {@code x}, {@code y}, {@code w} by {@code h} units: bands one each five
     * pixels of {@code length}, each mixed over {@code base}; neighbours of the same colour are one band.
     */
    static List<RectLook> bands(ScreenMath.Gradient gradient, int base, double x, double y, double w, double h,
                                int layer, double length) {
        int n = ScreenMath.bands(length);
        List<RectLook> out = new ArrayList<>(n);
        int start = 0;
        for (int i = 1; i <= n; i++) {
            int colour = ScreenMath.over(gradient.at((start + 0.5) / n), base);
            if (i < n && ScreenMath.over(gradient.at((i + 0.5) / n), base) == colour) continue;
            // Bands [start, i) of n, along the gradient's axis (y up in units, so the top band has the larger y).
            // Each band reaches a little into the next: edge to edge, the client leaves a hairline crack between them.
            double from = (double) start / n, to = Math.min(1, (i + 0.2) / n);
            if (gradient.vertical()) {
                out.add(new RectLook(x, y + h / 2 - h * (from + to) / 2, w, h * (to - from), layer, colour));
            } else {
                out.add(new RectLook(x - w / 2 + w * (from + to) / 2, y, w * (to - from), h, layer, colour));
            }
            start = i;
        }
        return out;
    }

    private static String tone(String tone) {
        return switch (tone == null ? "" : tone) {
            case "success" -> "{success}";
            case "error" -> "{error}";
            case "info" -> "{info}";
            case "warning" -> "{warning}";
            default -> "{primary}";
        };
    }

    /** The tree with the open confirm dialog over it, the last overlay, so it is the one drawn. */
    private Node withConfirm(Node root, Confirm ask) {
        String fill = ask.dangerous() ? "{error}" : "{primary}";
        Node yes = Node.text(net.exylia.lib.text.Text.of("{letters}" + (ask.yes() == null ? net.exylia.lib.text.Phrases.tr("Confirm") : ask.yes())).build())
                .padding(4, 12).background(fill).hover(ask.dangerous() ? "{error_light}" : "{secondary}")
                .active(null, Motion.Transform.scale(0.95)).id("~confirm.yes").onClick(event -> answer(true));
        Node no = Node.text(net.exylia.lib.text.Text.of("{letters}" + (ask.no() == null ? net.exylia.lib.text.Phrases.tr("Cancel") : ask.no())).build())
                .padding(4, 12).background("{letters_black}20").hover("{primary}50")
                .active(null, Motion.Transform.scale(0.95)).id("~confirm.no").onClick(event -> answer(false));
        Node dialog = Node.column(Node.text(ask.question()).wrap(200).textAlign(Node.Align.CENTER),
                        Node.row(no, yes).gap(6))
                .id("~confirm").padding(12, 16).gap(10).align(Node.Align.CENTER).background("{primary}20")
                .outline(ask.dangerous() ? "{error}80" : "{primary}60", 1).overlay(true).onDismiss(session -> answer(false))
                .enter(Motion.Tween.of(Motion.Transform.scale(0.9), 5, Motion.Ease.BACK))
                .leave(Motion.Tween.of(Motion.Transform.scale(0.94), 3, Motion.Ease.IN));
        if (root.kind() == Node.Kind.ROW || root.kind() == Node.Kind.COLUMN) return root.add(dialog);
        return Node.column(root).add(dialog);
    }

    /** On the viewer's thread, from a confirm's buttons or its dismissal. */
    private void answer(boolean yes) {
        Confirm asked = confirm;
        confirm = null;
        if (yes && asked != null) guarded(() -> asked.then().accept(this));
    }

    @Override
    public void confirm(@NotNull String question, boolean dangerous, @Nullable String yesLabel, @Nullable String noLabel,
                        @NotNull Consumer<ScreenSession> yes) {
        confirm = new Confirm(net.exylia.lib.text.Text.of(question).build(), dangerous, yesLabel, noLabel, yes);
        redraw();
    }

    @Override
    public @NotNull java.util.concurrent.CompletionStage<String> ask(@NotNull String prompt, @Nullable String hint,
                                                                      int max, @NotNull String current) {
        java.util.concurrent.CompletableFuture<String> answer = new java.util.concurrent.CompletableFuture<>();
        try {
            aside(() -> {
                var text = net.exylia.lib.input.Inputs.of(plugin).text(player, prompt).maxLength(Math.max(1, max));
                if (hint != null && !hint.isBlank()) text.hint(hint);
                if (!current.isEmpty()) text.defaultValue(current);
                return text.open();
            }).whenComplete((result, failure) -> {
                if (failure != null || result == null) {
                    answer.complete(null);
                    return;
                }
                String[] got = {null};
                result.ifCompleted(value -> got[0] = value);
                // Handed back on the viewer's thread, once the screen is back.
                tasks.runAtEntityLater(player, 1, () -> answer.complete(got[0]));
            });
        } catch (RuntimeException failure) {
            plugin.getLogger().log(Level.WARNING, "A screen input failed for " + player.getName(), failure);
            answer.complete(null);
        }
        return answer;
    }

    /** Renders as often as the fastest {@link Node#refresh} on screen asks, a second otherwise. Holds the lock. */
    private void retime(List<Layout.Placed> core) {
        int period = 20;
        for (Layout.Placed box : core) if (box.node().refresh() > 0) period = Math.min(period, Math.max(2, box.node().refresh()));
        if (period == refreshPeriod || ticker == null) return;
        refreshPeriod = period;
        ticker.cancel();
        ticker = tasks.runAtEntityTimer(player, period, period, this::renderNow);
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    /**
     * A transform after layout, applied to a look: its own about a box's centre, then each enclosing one, in look
     * units (x right, y up from the screen's centre).
     */
    private record Xf(double ox, double oy, double tx, double ty, double s, Xf outer) {
        double ax(double x) {
            double v = ox + (x - ox) * s + tx;
            return outer == null ? v : outer.ax(v);
        }

        double ay(double y) {
            double v = oy + (y - oy) * s + ty;
            return outer == null ? v : outer.ay(v);
        }

        double as() {
            return outer == null ? s : s * outer.as();
        }
    }

    /** What a box draws with: its transform chain, its transition, whether it animates, whether it is decoration. */
    private record Cx(Xf xf, Motion.Transition transition, boolean animated, boolean low) {
    }

    /** One box of a frame, kept to the next: where its arrivals and departures are measured from. */
    private record Box(String parent, Node node, double cx, double cy, int depth) {
    }

    /** What one frame wants on the client. */
    private record Frame(Map<String, Object> wanted, List<String> order, Map<String, String> boxOf,
                         Map<String, Box> boxes, Map<String, Motion.Transition> transitions, Set<String> animated,
                         boolean turns, boolean animates) {
    }

    private double centreX(Layout.Placed box) {
        return (box.x() + box.w() / 2 - screen.width() / 2) * unit;
    }

    private double centreY(Layout.Placed box) {
        return (screen.height() / 2 - box.y() - box.h() / 2) * unit;
    }

    /** Each box's draw context, parents first (their index is below their children's). Holds the lock. */
    private IdentityHashMap<Layout.Placed, Cx> contexts(List<Layout.Placed> list, long phaseAt, Map<String, Box> boxes) {
        IdentityHashMap<Layout.Placed, Cx> out = new IdentityHashMap<>();
        Cx[] at = new Cx[list.size()];
        boolean reduced = reduced();
        for (int i = 0; i < list.size(); i++) {
            Layout.Placed b = list.get(i);
            Cx parent = b.parent() >= 0 && b.parent() < i ? at[b.parent()] : null;
            Node n = b.node();
            Motion.Transform own = n.transform();
            if (b.key().equals(hovered) && n.hoverTransform() != null) own = own.with(n.hoverTransform());
            if (b.key().equals(activeKey) && n.activeTransform() != null) own = own.with(n.activeTransform());
            boolean animated = parent != null && parent.animated();
            if (n.animation() != null && !reduced) {
                Long start = animStart.get(b.key());
                own = own.with(n.animation().at(start == null ? 0 : Math.max(0, phaseAt - start)));
                animated = true;
            }
            Xf xf = parent == null ? null : parent.xf();
            if (!own.none()) xf = new Xf(centreX(b), centreY(b), own.x() * unit, -own.y() * unit, own.scale(), xf);
            Motion.Transition transition = n.transition() != null ? n.transition() : parent == null ? null : parent.transition();
            at[i] = new Cx(xf, transition, animated, n.decoration() || parent != null && parent.low());
            out.put(b, at[i]);
            String parentKey = b.parent() >= 0 && b.parent() < i ? list.get(b.parent()).key() : null;
            boxes.put(b.key(), new Box(parentKey, n, centreX(b), centreY(b), b.depth()));
        }
        return out;
    }

    /** Whether this viewer gets reduced motion. */
    private boolean reduced() {
        return ScreenRuntime.reducedMotion(player.getUniqueId());
    }

    /**
     * The looks of every box at a level of detail: 1 leaves out decoration, 2 stops bodies turning, 3 draws item
     * sprites as the 3D item (one display rather than four). Holds the lock.
     */
    private Frame compute(int lod, boolean animating) {
        Map<String, Object> wanted = new LinkedHashMap<>();
        Map<String, String> boxOf = new HashMap<>();
        Map<String, Box> boxes = new HashMap<>();
        Map<String, Motion.Transition> transitions = new HashMap<>();
        Set<String> animatedLooks = new HashSet<>();
        // The client sorts see-through displays by entity position, and every display here stands at the eyes,
        // so overlapping see-through rects blend in no fixed order. Each colour is mixed over the solid colour
        // of what holds it instead, and only opaque rects are sent. Boxes come parents first, depth by depth.
        int[] beneath = new int[64];
        int backdrop = backdropBase();
        long phaseAt = animating ? animClock + animStep : animClock;
        IdentityHashMap<Layout.Placed, Cx> cx = contexts(placed, phaseAt, boxes);
        List<Layout.Placed> tip = tooltip();
        cx.putAll(contexts(tip, phaseAt, boxes));
        // A dialog is drawn first, so a screen over the display budget loses its page, never the dialog that
        // takes every click.
        List<Layout.Placed> drawing = placed;
        boolean turns = false;
        boolean animates = false;
        if (overlayFrom > 0) {
            drawing = new ArrayList<>(placed.subList(overlayFrom, placed.size()));
            drawing.addAll(placed.subList(0, overlayFrom));
        }
        if (!tip.isEmpty()) {
            drawing = new ArrayList<>(drawing);
            drawing.addAll(tip);
        }
        for (Layout.Placed box : drawing) {
            Node node = box.node();
            Cx here = cx.get(box);
            if (lod >= 1 && here != null && here.low()) continue;
            animates |= node.animation() != null;
            boolean over = box.key().equals(hovered);
            boolean pressed = box.key().equals(activeKey);
            // A clickable box under the pointer rises a little, as a button does, unless its style moves it itself.
            boolean rises = over && node.onClick() != null && node.hoverTransform() == null && node.activeTransform() == null;
            Xf xf = here == null ? null : here.xf();
            List<String> mine = new ArrayList<>();
            if (box.depth() == Layout.OVERLAY - 1) {
                // The dimmer: the one see-through rect, over opaque ones only, so it blends in a fixed order.
                int dim = ScreenMath.argb(Layout.DIMMER, 0);
                beneath[box.depth()] = ScreenMath.over(dim, backdrop);
                String key = box.key() + "#bg";
                wanted.put(key, new RectLook(0, 0, BACKDROP_WIDE, BACKDROP_TALL, 2 + box.depth() * 3, dim));
                mine.add(key);
            } else {
                String colour = pressed && node.activeBackground() != null ? node.activeBackground()
                        : over && node.hover() != null ? node.hover() : node.background();
                int depth = Math.min(Math.max(0, box.depth()), beneath.length - 1);
                int base = depth == 0 ? backdrop : beneath[depth - 1];
                // A gradient is drawn as bands; what it holds is mixed over its middle colour.
                ScreenMath.Gradient gradient = ScreenMath.gradient(colour);
                int argb = gradient != null ? gradient.at(0.5) : ScreenMath.argb(colour, 0);
                argb = argb == 0 ? 0 : ScreenMath.over(argb, base);
                beneath[depth] = argb == 0 ? base : argb;
                double cxu = centreX(box);
                double cyu = centreY(box);
                // Outline, fill, text: three layers a box, deeper boxes nearer the eye.
                int layer = 2 + box.depth() * 3;
                int line = ScreenMath.argb(over && node.hoverOutline() != null ? node.hoverOutline() : node.outline(), 0);
                line = line == 0 ? 0 : ScreenMath.over(line, base);
                if (line != 0 && node.outlineWidth() > 0) {
                    double grow = node.outlineWidth() * 2 + (rises ? 2 : 0);
                    String key = box.key() + "#ol";
                    wanted.put(key, xf(new RectLook(cxu, cyu, (box.w() + grow) * unit, (box.h() + grow) * unit, layer - 1, line), xf));
                    mine.add(key);
                }
                if (argb != 0 && box.w() > 0 && box.h() > 0) {
                    String key = box.key() + "#bg";
                    double rise = rises ? 2 * unit : 0;
                    if (gradient != null) {
                        for (RectLook band : bands(gradient, base, cxu, cyu, box.w() * unit + rise, box.h() * unit + rise,
                                layer, gradient.vertical() ? box.h() : box.w())) {
                            String bandKey = mine.contains(key) ? key + mine.size() : key;
                            wanted.put(bandKey, xf(band, xf));
                            mine.add(bandKey);
                        }
                    } else {
                        wanted.put(key, xf(new RectLook(cxu, cyu, box.w() * unit + rise, box.h() * unit + rise, layer, argb), xf));
                        mine.add(key);
                    }
                }
                // A background picture sits over the fill; the box's own content then goes a layer nearer.
                Node backImage = node.backgroundImage();
                int top = backImage == null ? layer + 1 : layer + 2;
                if (backImage != null && box.w() > 0 && box.h() > 0) {
                    int pw = backImage.pixelWidth(), ph = backImage.pixelHeight();
                    double cell = Math.max(box.w() / pw, box.h() / ph);
                    int cols = Math.max(1, Math.min(pw, (int) Math.floor(box.w() / cell + 1e-6)));
                    int rows = Math.max(1, Math.min(ph, (int) Math.floor(box.h() / cell + 1e-6)));
                    int x0 = (pw - cols) / 2, y0 = (ph - rows) / 2;
                    int[] all = backImage.pixels(), cut = new int[cols * rows];
                    for (int y = 0; y < rows; y++) System.arraycopy(all, (y0 + y) * pw + x0, cut, y * cols, cols);
                    mine.addAll(pictureLooks(box.key() + "#bi", cxu, cyu, cell, layer + 1,
                            Pictures.glyphs(cut, cols, rows, beneath[depth]), rows, wanted, xf));
                }
                // An item is a solid model the dimmer cannot shade, so under an open overlay it is left out.
                if (node.item() != null && (overlayFrom < 0 || box.depth() >= Layout.OVERLAY - 1)) {
                    double side = Math.min(box.w() - node.padLeft() - node.padRight(), box.h() - node.padTop() - node.padBottom());
                    double ix = (box.x() + node.padLeft() + (box.w() - node.padLeft() - node.padRight()) / 2
                            - screen.width() / 2) * unit;
                    double iy = (screen.height() / 2 - box.y() - node.padTop()
                            - (box.h() - node.padTop() - node.padBottom()) / 2) * unit;
                    String key = box.key() + "#it";
                    // A hovered item grows a little, as a slot under the cursor would.
                    boolean lifted = over || hoveredBox != null && !hoveredBox.key().equals("~dim")
                            && hoveredBox.depth() < box.depth()
                            && hoveredBox.holds(box.x() + box.w() / 2, box.y() + box.h() / 2);
                    double grow = lifted && node.hoverTransform() == null ? 1.15 : 1;
                    // Read from disk off this thread the first time; drawn on the redraw its arrival asks for.
                    Pictures.Picture sprite = VanillaSprites.of(node.item(), tasks, this::redraw);
                    net.kyori.adventure.text.Component glyph = sprite != null && sprite != VanillaSprites.LOADING
                            ? spriteGlyph(node.item()) : null;
                    if (sprite == VanillaSprites.LOADING) {
                        // Nothing yet, rather than the 3D item for a moment.
                    } else if (glyph != null) {
                        // A 1.21.11+ client draws the sprite itself: one display.
                        wanted.put(key, xf(new TextLook(ix, iy, side * grow / 8, top, glyph, 1, 0), xf));
                        mine.add(key);
                    } else if (sprite == null || lod >= 3) {
                        wanted.put(key, xf(new ItemLook(ix, iy, side * unit * grow, top, node.item()), xf));
                        mine.add(key);
                    } else {
                        double cell = side * grow / Math.max(sprite.width(), sprite.height());
                        mine.addAll(pictureLooks(key, ix, iy, cell, top, Pictures.glyphs(sprite.argb(), sprite.width(),
                                sprite.height(), beneath[depth]), sprite.height(), wanted, xf));
                    }
                }
                // A body turns every second, and one whose skin is on its way must be looked for again: never skipped.
                boolean turnable = node.turntable() != null && lod < 2;
                turns |= turnable;
                List<Pictures.Face> model = turnable ? Pictures.model(node.turntable(), node.wearing()) : null;
                if (model != null) {
                    double innerW = box.w() - node.padLeft() - node.padRight();
                    double innerH = box.h() - node.padTop() - node.padBottom();
                    double cell = Math.min(innerW / 16, innerH / 32);
                    double mx = (box.x() + node.padLeft() + innerW / 2 - screen.width() / 2) * unit;
                    double my = (screen.height() / 2 - box.y() - node.padTop() - innerH / 2) * unit;
                    double turn = (System.currentTimeMillis() / 1000.0 * TURN_SPEED) % 360;
                    int gapX = ScreenPackets.IMAGE_ADVANCE - ScreenPackets.IMAGE_GLYPH;
                    int gapY = ScreenPackets.IMAGE_LINE - ScreenPackets.IMAGE_GLYPH;
                    for (int f = 0; f < model.size(); f++) {
                        Pictures.Face face = model.get(f);
                        Component text = Pictures.glyphs(face.picture().argb(), face.picture().width(),
                                face.picture().height(), 0);
                        for (int copy = 0; copy < 4; copy++) {
                            String key = box.key() + "#tt" + f + "." + copy;
                            wanted.put(key, xf(new TurnLook(mx, my, cell * unit, top, text, face.picture().height(),
                                    face.x(), face.y(), face.z(), face.yaw(), turn, (copy & 1) * gapX, (copy >> 1) * gapY,
                                    face.scale()), xf));
                            mine.add(key);
                        }
                    }
                } else if (node.pixels() != null) {
                    double innerW = box.w() - node.padLeft() - node.padRight();
                    double innerH = box.h() - node.padTop() - node.padBottom();
                    double cell = Math.min(innerW / node.pixelWidth(), innerH / node.pixelHeight());
                    if (cell > 0) {
                        double ix = (box.x() + node.padLeft() + innerW / 2 - screen.width() / 2) * unit;
                        double iy = (screen.height() / 2 - box.y() - node.padTop() - innerH / 2) * unit;
                        mine.addAll(pictureLooks(box.key() + "#im", ix, iy, cell, top,
                                Pictures.glyphs(node.pixels(), node.pixelWidth(), node.pixelHeight(), beneath[depth]),
                                node.pixelHeight(), wanted, xf));
                    }
                }
                if (node.text() != null) {
                    Layout.Wrapped wrapped = box.wrapped();
                    Component words = wrapped != null ? wrapped.text() : node.text();
                    int lines = wrapped != null ? wrapped.lines() : 1;
                    // Text sits in its padding box: centred on what is left inside it.
                    double inner = box.w() - node.padLeft() - node.padRight();
                    double wide = wrapped != null ? wrapped.width() * node.scale() : Layout.textWidth(node);
                    // Words wider than their box (a long name in an equal-width chip) are drawn smaller to fit,
                    // never past its edges.
                    double fit = wide > inner && inner > 0 ? inner / wide : 1;
                    double spare = Math.max(0, inner - wide * fit);
                    double shift = switch (node.textAlign()) {
                        case START, STRETCH -> -spare / 2;
                        case END -> spare / 2;
                        case CENTER -> 0;
                    };
                    double tx = (box.x() + node.padLeft() + inner / 2 + shift - screen.width() / 2) * unit;
                    double ty = (screen.height() / 2 - box.y() - node.padTop()
                            - (box.h() - node.padTop() - node.padBottom()) / 2) * unit;
                    int flags = (node.shadow() ? 1 : 0) | (lines > 1 ? switch (node.textAlign()) {
                        case CENTER -> 0;
                        case END -> 0x10;
                        default -> 0x08;
                    } : 0);
                    String key = box.key() + "#t";
                    wanted.put(key, xf(new TextLook(tx, ty, node.scale() * fit, top, words, lines, flags), xf));
                    mine.add(key);
                }
            }
            for (String key : mine) {
                boxOf.put(key, box.key());
                if (here != null && here.transition() != null) transitions.put(key, here.transition());
                if (here != null && here.animated()) animatedLooks.add(key);
            }
        }
        return new Frame(wanted, new ArrayList<>(wanted.keySet()), boxOf, boxes, transitions, animatedLooks, turns, animates);
    }

    /** The item as one sprite glyph, for a client and server that draw sprites; {@code null} otherwise. */
    private Component spriteGlyph(org.bukkit.inventory.ItemStack item) {
        if (!spriteGlyphs || item.getType().isBlock()) return null;
        return net.exylia.lib.text.Text.of("<white>{icon:items:item/" + item.getType().getKey().getKey() + "|◆}").build();
    }

    /** Sends what the layout wants and the client does not have. Holds the lock. */
    private void sync(boolean animating) {
        Frame detailed = compute(0, animating);
        for (int lod = 1; detailed.wanted().size() > BUDGET && lod <= 3; lod++) detailed = compute(lod, animating);
        Frame frame = detailed;
        Map<String, Object> wanted = frame.wanted();
        List<String> order = frame.order();
        if (wanted.size() > BUDGET) {
            // Past every level of detail: the tail is left out (the dialog is drawn first, so it stays).
            if (!overBudget) {
                overBudget = true;
                plugin.getLogger().warning("A screen drew more than " + BUDGET + " displays at its lowest detail; the rest are left out.");
            }
            for (String key : new ArrayList<>(order.subList(BUDGET, order.size()))) wanted.remove(key);
            order = new ArrayList<>(order.subList(0, BUDGET));
        }
        boolean reduced = reduced();
        // Animations start where a box first shows, after it has arrived.
        for (Map.Entry<String, Box> box : frame.boxes().entrySet()) {
            if (box.getValue().node().animation() != null && !animStart.containsKey(box.getKey())) {
                Motion.Tween in = lastBoxes.containsKey(box.getKey()) ? null : arrival(box.getKey(), frame.boxes());
                animStart.put(box.getKey(), animClock + (in == null ? 0 : in.ticks()));
            }
        }
        animStart.keySet().retainAll(frame.boxes().keySet());
        List<PacketWrapper<?>> out = new ArrayList<>();
        Map<Integer, List<Seg>> later = new HashMap<>();
        Set<String> gone = new HashSet<>(drawn.keySet());
        gone.removeAll(wanted.keySet());
        if (!gone.isEmpty()) {
            List<Integer> now = new ArrayList<>();
            int dimHold = 0;
            for (String key : gone) {
                Drawn d = drawn.get(key);
                Motion.Tween away = reduced ? null : departure(d.box(), frame.boxes());
                if (away != null && away.ticks() > 0 && d.box() != null) {
                    Box root = departureRoot(d.box(), frame.boxes());
                    if (root.depth() == Layout.OVERLAY) dimHold = Math.max(dimHold, away.ticks());
                }
            }
            for (String key : gone) {
                Drawn d = drawn.remove(key);
                bump(d.id());
                Motion.Tween away = reduced || d.box() == null ? null : departure(d.box(), frame.boxes());
                if (away != null && away.ticks() > 0 && !(d.look() instanceof TurnLook)) {
                    Box root = departureRoot(d.box(), frame.boxes());
                    Xf to = new Xf(root.cx(), root.cy(), away.transform().x() * unit, -away.transform().y() * unit,
                            away.transform().scale(), null);
                    glide(d.id(), d.look(), xf(d.look(), to), new Motion.Transition(away.ticks(), away.ease(), false),
                            0, out, later);
                    leave(d.id(), away.ticks() + 1);
                } else if (dimHold > 0 && key.startsWith("~dim")) {
                    // The dimmer stays while the dialog over it leaves.
                    leave(d.id(), dimHold + 1);
                } else {
                    now.add(d.id());
                }
            }
            if (!now.isEmpty()) out.add(ScreenPackets.destroy(now.stream().mapToInt(Integer::intValue).toArray()));
        }
        if (overlayFrom > 0) {
            // The client draws displays in the order they arrive, and the see-through dimmer hides whatever
            // arrives after it behind it: new boxes go back to front, and the dimmer comes again after any new
            // box behind it.
            order = new ArrayList<>(order);
            order.sort(java.util.Comparator.comparingInt(key -> layerOf(wanted.get(key))));
            String dim = null;
            for (String key : order) if (key.startsWith("~dim")) dim = key;
            if (dim != null && drawn.containsKey(dim)) {
                int dimLayer = layerOf(wanted.get(dim));
                for (String key : order) {
                    if (!drawn.containsKey(key) && layerOf(wanted.get(key)) < dimLayer) {
                        Drawn d = drawn.remove(dim);
                        bump(d.id());
                        out.add(ScreenPackets.destroy(new int[]{d.id()}));
                        break;
                    }
                }
            }
        }
        boolean eased = !reduced && motionAllowed();
        Map<String, Arrival> arrivals = new HashMap<>();
        for (String key : order) {
            Object look = wanted.get(key);
            Drawn current = drawn.get(key);
            if (current != null && current.look().equals(look)) continue;
            int id = current != null ? current.id() : ids.getAsInt();
            if (current != null && current.look().getClass() != look.getClass()) {
                out.add(ScreenPackets.destroy(new int[]{id}));
                bump(id);
                current = null;
                id = ids.getAsInt();
            }
            String box = frame.boxOf().get(key);
            if (current == null) {
                out.add(look instanceof ItemLook ? ScreenPackets.spawnItem(id, origin) : ScreenPackets.spawnDisplay(id, origin));
                Arrival in = key.startsWith("~dim") || reduced || box == null ? null
                        : arrivals.computeIfAbsent(box, b -> arrivalOf(b, frame.boxes()));
                if (in != null && in.tween().ticks() > 0) {
                    // A new box starts where its arrival begins and glides into place once the client has that
                    // start: a transform only glides from one it already shows.
                    Object first = xf(look, in.xf());
                    out.add(look(id, first, 0));
                    glide(id, first, look, new Motion.Transition(in.tween().ticks(), eased ? in.tween().ease()
                            : Motion.Ease.LINEAR, false), 1, out, later);
                } else {
                    out.add(look(id, look, 0));
                }
            } else if (look instanceof TurnLook) {
                out.add(look(id, look, GLIDE));
            } else if (animating && frame.animated().contains(key)) {
                out.add(look(id, look, animStep));
                motionPackets++;
            } else {
                Motion.Transition transition = frame.transitions().get(key);
                if (transition == null || reduced) {
                    transition = new Motion.Transition(Math.min(GLIDE, transition == null ? GLIDE : transition.ticks()),
                            Motion.Ease.LINEAR, false);
                } else if (!eased) {
                    transition = new Motion.Transition(transition.ticks(), Motion.Ease.LINEAR, transition.colours());
                }
                glide(id, current.look(), look, transition, 0, out, later);
            }
            drawn.put(key, new Drawn(id, look, box));
        }
        shown = true;
        turning = frame.turns();
        lastBoxes = frame.boxes();
        if (!out.isEmpty()) wire.send(out);
        later.forEach((delay, segments) -> tasks.runAtEntityLater(player, delay, () -> segments(segments)));
        animate(frame.animates() && !reduced, placed);
    }

    /** One step of a glide still to send: a display, the target it was given, where to go and for how long. */
    private record Seg(int id, int generation, Object look, int ticks) {
    }

    /** How a new box arrives: the tween and the transform it starts from. */
    private record Arrival(Motion.Tween tween, Xf xf) {
    }

    /** Gives a display a new target, so the queued steps of its last glide are dropped. Holds the lock. */
    private int bump(int id) {
        return generation.merge(id, 1, Integer::sum);
    }

    /**
     * Glides a display from {@code from} to {@code to}: one straight glide, or for an eased transition two to four
     * that follow the curve, the first sent with {@code out} (after {@code delay} ticks when it is not 0), the rest
     * queued. Colours step at each glide when the transition says so, and change at the first otherwise.
     */
    private void glide(int id, Object from, Object to, Motion.Transition transition, int delay,
                       List<PacketWrapper<?>> out, Map<Integer, List<Seg>> later) {
        int gen = bump(id);
        int ticks = Math.max(0, transition.ticks());
        int steps = transition.ease() == Motion.Ease.LINEAR || ticks < 2 || sameGeometry(from, to) ? 1
                : Math.max(2, Math.min(4, ticks / 2));
        int sent = 0;
        for (int i = 1; i <= steps; i++) {
            int end = ticks * i / steps;
            int dur = end - sent;
            Object at = i == steps ? to : lerp(from, to, transition.ease().at(i / (double) steps), transition.colours());
            int when = delay + sent;
            if (when == 0) out.add(look(id, at, dur));
            else later.computeIfAbsent(when, k -> new ArrayList<>()).add(new Seg(id, gen, at, dur));
            if (i > 1) motionPackets++;
            sent = end;
        }
    }

    /** On the viewer's thread: queued glide steps whose display has had no newer target. */
    private void segments(List<Seg> segments) {
        synchronized (this) {
            if (!open || away) return;
            List<PacketWrapper<?>> out = new ArrayList<>();
            for (Seg seg : segments) {
                if (generation.getOrDefault(seg.id(), 0) == seg.generation()) out.add(look(seg.id(), seg.look(), seg.ticks()));
            }
            if (!out.isEmpty()) wire.send(out);
        }
    }

    /** Removes a leaving display once its departure has played. Holds the lock. */
    private void leave(int id, int ticks) {
        leaving.add(id);
        tasks.runAtEntityLater(player, ticks, () -> {
            synchronized (this) {
                if (!leaving.remove(id) || !open) return;
                generation.remove(id);
                wire.send(List.of(ScreenPackets.destroy(new int[]{id})));
            }
        });
    }

    /**
     * How a new box arrives: the topmost new box above it (its arrival root) arrives with its own {@code enter},
     * or the nearest enclosing one's; without any, the screen zooms in on its first frame, a dialog pops from the
     * centre and anything else rises into place. About the arrival root's centre.
     */
    private Arrival arrivalOf(String box, Map<String, Box> boxes) {
        if (lastBoxes.containsKey(box)) return null;
        String root = box;
        while (true) {
            Box b = boxes.get(root);
            if (b == null || b.parent() == null || lastBoxes.containsKey(b.parent())) break;
            root = b.parent();
        }
        Box top = boxes.get(root);
        if (top == null) return null;
        Motion.Tween tween = arrival(root, boxes);
        if (tween == null || tween.ticks() <= 0 || tween.transform().none()) return null;
        return new Arrival(tween, new Xf(top.cx(), top.cy(), tween.transform().x() * unit, -tween.transform().y() * unit,
                tween.transform().scale(), null));
    }

    /** The tween a box arriving as a root takes: its own, the nearest enclosing box's, or the built-in one. */
    private Motion.Tween arrival(String root, Map<String, Box> boxes) {
        Box top = boxes.get(root);
        for (Box b = top; b != null; b = b.parent() == null ? null : boxes.get(b.parent())) {
            if (b.node().enter() != null) return b.node().enter();
        }
        if (top == null) return null;
        if (!shown) return Motion.Tween.of(Motion.Transform.scale(0.96), SETTLE, Motion.Ease.LINEAR);
        if (top.depth() >= Layout.OVERLAY - 1) return Motion.Tween.of(Motion.Transform.scale(0.9), SETTLE, Motion.Ease.LINEAR);
        return Motion.Tween.of(Motion.Transform.translate(0, 4), SETTLE, Motion.Ease.LINEAR);
    }

    /** The topmost box of the last frame above {@code box} that this frame no longer has. */
    private Box departureRoot(String box, Map<String, Box> now) {
        String root = box;
        while (true) {
            Box b = lastBoxes.get(root);
            if (b == null || b.parent() == null || now.containsKey(b.parent())) break;
            root = b.parent();
        }
        Box top = lastBoxes.get(root);
        return top != null ? top : new Box(null, Node.box(), 0, 0, 0);
    }

    /** How a box of the last frame leaves: its departure root's {@code leave}, or the nearest enclosing one's; else none. */
    private Motion.Tween departure(String box, Map<String, Box> now) {
        if (box == null || now.containsKey(box)) return null;
        Box top = departureRoot(box, now);
        for (Box b = top; b != null; b = b.parent() == null ? null : lastBoxes.get(b.parent())) {
            if (b.node().leave() != null) return b.node().leave() == Motion.Tween.NONE ? null : b.node().leave();
        }
        return null;
    }

    /** Whether motion may still spend packets this second: past the ceiling, eased glides go straight. */
    private boolean motionAllowed() {
        long second = System.currentTimeMillis() / 1000;
        if (second != motionSecond) {
            motionSecond = second;
            motionPackets = 0;
        }
        return motionPackets < ScreenSettings.get().motionCeiling();
    }

    /**
     * Keeps the animation clock running while a box on screen animates, one step (2 to 10 ticks, from the
     * shortest animation) at a time; each step sends the animating boxes to where they will be at the next.
     * Holds the lock.
     */
    private void animate(boolean any, List<Layout.Placed> boxes) {
        if (!any) {
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
            return;
        }
        int shortest = Integer.MAX_VALUE;
        for (Layout.Placed box : boxes) {
            if (box.node().animation() != null) shortest = Math.min(shortest, box.node().animation().ticks());
        }
        int step = Math.max(2, Math.min(10, shortest / 8));
        if (animator != null && step == animStep) return;
        if (animator != null) animator.cancel();
        animStep = step;
        animator = tasks.runAtEntityTimer(player, step, step, () -> {
            synchronized (this) {
                if (!open || away) return;
                // Past the ceiling the animations wait where they are.
                if (!motionAllowed()) return;
                animClock += animStep;
                sync(true);
            }
        });
    }

    /** A look moved by a transform chain. */
    private static Object xf(Object look, Xf f) {
        if (f == null) return look;
        double s = f.as();
        return switch (look) {
            case RectLook r -> new RectLook(f.ax(r.x()), f.ay(r.y()), r.w() * s, r.h() * s, r.layer(), r.argb());
            case ItemLook it -> new ItemLook(f.ax(it.x()), f.ay(it.y()), it.size() * s, it.layer(), it.item());
            case ImageLook im -> new ImageLook(f.ax(im.x()), f.ay(im.y()), im.cell() * s, im.layer(), im.text(), im.rows(),
                    im.dx(), im.dy());
            case TurnLook t -> new TurnLook(f.ax(t.x()), f.ay(t.y()), t.cell() * s, t.layer(), t.text(), t.rows(), t.lx(),
                    t.ly(), t.lz(), t.yaw(), t.turn(), t.dx(), t.dy(), t.size());
            default -> {
                TextLook t = (TextLook) look;
                yield new TextLook(f.ax(t.x()), f.ay(t.y()), t.scale() * s, t.layer(), t.text(), t.lines(), t.flags());
            }
        };
    }

    /** Part of the way from one look to another of the same kind; the colour steps along when {@code colours}. */
    static Object lerp(Object a, Object b, double f, boolean colours) {
        if (a.getClass() != b.getClass()) return b;
        return switch (b) {
            case RectLook r -> {
                RectLook q = (RectLook) a;
                yield new RectLook(mix(q.x(), r.x(), f), mix(q.y(), r.y(), f), mix(q.w(), r.w(), f), mix(q.h(), r.h(), f),
                        r.layer(), colours ? ScreenMath.blend(q.argb(), r.argb(), Math.max(0, Math.min(1, f))) : r.argb());
            }
            case ItemLook it -> {
                ItemLook q = (ItemLook) a;
                yield new ItemLook(mix(q.x(), it.x(), f), mix(q.y(), it.y(), f), mix(q.size(), it.size(), f), it.layer(), it.item());
            }
            case ImageLook im -> {
                ImageLook q = (ImageLook) a;
                yield new ImageLook(mix(q.x(), im.x(), f), mix(q.y(), im.y(), f), mix(q.cell(), im.cell(), f), im.layer(),
                        im.text(), im.rows(), im.dx(), im.dy());
            }
            case TextLook t -> {
                TextLook q = (TextLook) a;
                yield new TextLook(mix(q.x(), t.x(), f), mix(q.y(), t.y(), f), mix(q.scale(), t.scale(), f), t.layer(),
                        t.text(), t.lines(), t.flags());
            }
            default -> b;
        };
    }

    private static double mix(double a, double b, double f) {
        return a + (b - a) * f;
    }

    /** Whether two looks stand at the same place and size: a change of colour or text only. */
    private static boolean sameGeometry(Object a, Object b) {
        if (a.getClass() != b.getClass()) return false;
        return switch (a) {
            case RectLook r -> {
                RectLook q = (RectLook) b;
                yield r.x() == q.x() && r.y() == q.y() && r.w() == q.w() && r.h() == q.h();
            }
            case ItemLook it -> {
                ItemLook q = (ItemLook) b;
                yield it.x() == q.x() && it.y() == q.y() && it.size() == q.size();
            }
            case ImageLook im -> {
                ImageLook q = (ImageLook) b;
                yield im.x() == q.x() && im.y() == q.y() && im.cell() == q.cell();
            }
            case TextLook t -> {
                TextLook q = (TextLook) b;
                yield t.x() == q.x() && t.y() == q.y() && t.scale() == q.scale();
            }
            default -> true;
        };
    }

    /**
     * Layout units to drawn blocks at a layer. A layer nearer the eye is drawn that much smaller, so every
     * layer projects to the same place and size and nested boxes line up exactly.
     */
    private static double drawn(int layer) {
        return K * (DEPTH - layer * LAYER) / DEPTH;
    }

    private PacketWrapper<?> look(int id, Object look, int glide) {
        return switch (look) {
            case RectLook r -> rect(id, r, glide);
            case ItemLook it -> item(id, it, glide);
            case ImageLook im -> image(id, im, glide);
            case TurnLook tl -> turn(id, tl, glide);
            default -> text(id, (TextLook) look, glide);
        };
    }

    private static int layerOf(Object look) {
        return switch (look) {
            case RectLook r -> r.layer();
            case ItemLook it -> it.layer();
            case ImageLook im -> im.layer();
            case TurnLook tl -> tl.layer();
            default -> ((TextLook) look).layer();
        };
    }

    private PacketWrapper<?> rect(int id, RectLook r, int glide) {
        double k = drawn(r.layer());
        return ScreenPackets.rect(id, r.x() * k, r.y() * k, -DEPTH + r.layer() * LAYER, r.w() * k, r.h() * k,
                r.argb(), glide);
    }

    private PacketWrapper<?> item(int id, ItemLook look, int glide) {
        double k = drawn(look.layer());
        return ScreenPackets.item(id, look.x() * k, look.y() * k, -DEPTH + look.layer() * LAYER, look.size() * k,
                look.item(), glide);
    }

    private PacketWrapper<?> image(int id, ImageLook im, int glide) {
        double k = drawn(im.layer());
        double fx = im.cell() * k / ScreenPackets.IMAGE_ADVANCE;
        double fy = im.cell() * k / ScreenPackets.IMAGE_LINE;
        return ScreenPackets.image(id, im.x() * k + im.dx() * fx, im.y() * k - im.dy() * fy,
                -DEPTH + im.layer() * LAYER, fx, fy, im.rows(), im.text(), glide);
    }

    /** A side of a turning body: once there, it glides a whole turn step to the next angle. */
    private PacketWrapper<?> turn(int id, TurnLook t, int glide) {
        double k = drawn(t.layer());
        double a = Math.toRadians(t.turn());
        double fx = t.cell() * t.size() * k / ScreenPackets.IMAGE_ADVANCE;
        double fy = t.cell() * t.size() * k / ScreenPackets.IMAGE_LINE;
        // About the vertical: x across, z towards the viewer.
        double rx = t.lx() * Math.cos(a) + t.lz() * Math.sin(a);
        double rz = -t.lx() * Math.sin(a) + t.lz() * Math.cos(a);
        return ScreenPackets.image(id, (t.x() + rx * t.cell()) * k, (t.y() + t.ly() * t.cell()) * k - t.dy() * fy,
                -DEPTH + t.layer() * LAYER + rz * t.cell() * k, fx, fy, t.rows(), t.text(),
                glide == 0 ? 0 : TURN_TICKS, t.yaw() + t.turn(), t.dx());
    }

    /**
     * A picture's four copies: a full block is narrower and shorter than the cell it stands in, and the copies,
     * nudged by the gaps, cover every cell edge to edge. {@code cell} is one picture pixel in layout units.
     */
    private List<String> pictureLooks(String key, double x, double y, double cell, int layer, Component picture, int rows,
                                      Map<String, Object> wanted, Xf xf) {
        int gapX = ScreenPackets.IMAGE_ADVANCE - ScreenPackets.IMAGE_GLYPH;
        int gapY = ScreenPackets.IMAGE_LINE - ScreenPackets.IMAGE_GLYPH;
        List<String> keys = new ArrayList<>(4);
        for (int copy = 0; copy < 4; copy++) {
            wanted.put(key + copy, xf(new ImageLook(x, y, cell * unit, layer, picture, rows, (copy & 1) * gapX,
                    (copy >> 1) * gapY), xf));
            keys.add(key + copy);
        }
        return keys;
    }

    private PacketWrapper<?> text(int id, TextLook t, int glide) {
        double k = drawn(t.layer());
        double scale = t.scale() * unit * k / ScreenPackets.fontPixel();
        return ScreenPackets.text(id, t.x() * k, t.y() * k, -DEPTH + t.layer() * LAYER, scale, t.text(), glide,
                t.lines(), (byte) t.flags());
    }

    /** How much of the layout area a 16:9 window shows around it, each way: where scenes and backdrops are drawn. */
    private static final double VIEW = 1.35;
    private static final int BACK_LAYER = -2, SCENE_LAYER = -1;

    /** The backdrop's solid colour, or its gradient's middle: what the root box is mixed over. */
    private int backdropBase() {
        ScreenMath.Gradient gradient = ScreenMath.gradient(screen.backdrop());
        return ScreenMath.over(gradient != null ? gradient.at(0.5) : ScreenMath.argb(screen.backdrop(), 0xFF0B0912),
                0xFF000000);
    }

    /** The backdrop: one rect past every edge of the view, or a gradient's bands over the view, the outer two stretched. */
    private List<RectLook> backdropLooks() {
        ScreenMath.Gradient gradient = ScreenMath.gradient(screen.backdrop());
        if (gradient == null) return List.of(new RectLook(0, 0, BACKDROP_WIDE, BACKDROP_TALL, BACK_LAYER, backdropBase()));
        double vw = Screen.WIDTH * VIEW * unit, vh = Screen.HEIGHT * VIEW * unit;
        List<RectLook> bands = new ArrayList<>(bands(gradient, 0xFF000000, 0, 0, vw, vh, BACK_LAYER,
                (gradient.vertical() ? Screen.HEIGHT : Screen.WIDTH) * VIEW));
        for (int i = 0; i < bands.size(); i++) {
            RectLook b = bands.get(i);
            if (gradient.vertical()) {
                double top = i == 0 ? BACKDROP_TALL / 2 : b.y() + b.h() / 2;
                double bottom = i == bands.size() - 1 ? -BACKDROP_TALL / 2 : b.y() - b.h() / 2;
                bands.set(i, new RectLook(0, (top + bottom) / 2, BACKDROP_WIDE, top - bottom, BACK_LAYER, b.argb()));
            } else {
                double left = i == 0 ? -BACKDROP_WIDE / 2 : b.x() - b.w() / 2;
                double right = i == bands.size() - 1 ? BACKDROP_WIDE / 2 : b.x() + b.w() / 2;
                bands.set(i, new RectLook((left + right) / 2, 0, right - left, BACKDROP_TALL, BACK_LAYER, b.argb()));
            }
        }
        return bands;
    }

    /** The scene cut to cover the view (and its parallax drift) with square pixels, centred. Holds {@link #input}. */
    private void sceneSetup(Node scene) {
        int pw = scene.pixelWidth(), ph = scene.pixelHeight();
        double drift = 2 * screen.parallax() * unit;
        double vw = Screen.WIDTH * VIEW * unit + drift, vh = Screen.HEIGHT * VIEW * unit + drift;
        double cell = Math.max(vw / pw, vh / ph);
        int cols = Math.min(pw, (int) Math.ceil(vw / cell)), rows = Math.min(ph, (int) Math.ceil(vh / cell));
        int x0 = (pw - cols) / 2, y0 = (ph - rows) / 2;
        int[] all = scene.pixels(), cut = new int[cols * rows];
        for (int y = 0; y < rows; y++) System.arraycopy(all, (y0 + y) * pw + x0, cut, y * cols, cols);
        sceneText = Pictures.glyphs(cut, cols, rows, backdropBase());
        sceneRows = rows;
        sceneCell = cell;
    }

    /** The scene where the cursor puts it: drifting against it by up to the screen's parallax. Holds {@link #input}. */
    private void scene(List<PacketWrapper<?>> out, int glide) {
        if (sceneText == null) return;
        double halfW = screen.width() / 2 * unit, halfH = screen.height() / 2 * unit;
        double drift = screen.parallax() * unit;
        double x = halfW == 0 ? 0 : -cursorX / halfW * drift;
        double y = halfH == 0 ? 0 : -cursorY / halfH * drift;
        int gapX = ScreenPackets.IMAGE_ADVANCE - ScreenPackets.IMAGE_GLYPH;
        int gapY = ScreenPackets.IMAGE_LINE - ScreenPackets.IMAGE_GLYPH;
        for (int copy = 0; copy < sceneIds.length; copy++) {
            out.add(image(sceneIds[copy], new ImageLook(x, y, sceneCell, SCENE_LAYER, sceneText, sceneRows,
                    (copy & 1) * gapX, (copy >> 1) * gapY), glide));
        }
    }

    /**
     * The arrow, gliding one tick to where the cursor is: smooth, and never more than a packet behind. The same
     * picture every time, so the wire sends only its move. Holds {@link #input}.
     */
    private void cursor(List<PacketWrapper<?>> out, int glide) {
        double pixel = ARROW_PIXEL * unit / (SPAN / Screen.WIDTH);
        int w = ARROW[0].length(), h = ARROW.length;
        Component arrow = Pictures.glyphs(ARROW_ARGB, w, h, 0);
        // The tip is the pointer's top-left pixel, where the cursor is.
        double cx = cursorX + w * pixel / 2, cy = cursorY - h * pixel / 2;
        int gapX = ScreenPackets.IMAGE_ADVANCE - ScreenPackets.IMAGE_GLYPH;
        int gapY = ScreenPackets.IMAGE_LINE - ScreenPackets.IMAGE_GLYPH;
        for (int copy = 0; copy < cursorIds.length; copy++) {
            out.add(image(cursorIds[copy], new ImageLook(cx, cy, pixel, CURSOR_LAYER, arrow, h,
                    (copy & 1) * gapX, (copy >> 1) * gapY), glide));
        }
        if (screen.parallax() > 0) scene(out, glide);
    }

    private static int[] arrowPixels() {
        int w = ARROW[0].length();
        int[] argb = new int[w * ARROW.length];
        for (int y = 0; y < ARROW.length; y++) {
            for (int x = 0; x < w; x++) {
                argb[y * w + x] = switch (ARROW[y].charAt(x)) {
                    case '#' -> 0xFF15111D;
                    case 'o' -> 0xFFFFFFFF;
                    default -> 0;
                };
            }
        }
        return argb;
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

    void turned(float yaw, float pitch) {
        if (!open) return;
        boolean moved = false;
        synchronized (input) {
            if (away) {
                // The cursor stays where it was: the turn made while typing is not a pointer move.
                lastYaw = yaw;
                lastPitch = pitch;
                return;
            }
            double dx = ScreenMath.turn(lastYaw, yaw);
            double dy = pitch - lastPitch;
            // A fast move carries further: precise when slow, quick across the screen.
            double accel = ScreenSettings.get().cursorAcceleration();
            double boost = accel <= 0 ? 1 : Math.min(3, 1 + accel * Math.hypot(dx, dy) / 4);
            double perDegree = DISTANCE * Math.PI / 180 * ScreenRuntime.sensitivity(player.getUniqueId()) * boost;
            double halfW = screen.width() / 2 * unit;
            double halfH = screen.height() / 2 * unit;
            cursorX = ScreenMath.clamp(cursorX + dx * perDegree, -halfW, halfW);
            cursorY = ScreenMath.clamp(cursorY - dy * perDegree, -halfH, halfH);
            lastYaw = yaw;
            lastPitch = pitch;
            List<PacketWrapper<?>> out = new ArrayList<>();
            cursor(out, 1);
            moved = pointerMoved();
            if (recentreId == 0 && Math.abs(pitch) > RECENTRE_PITCH) {
                recentreId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
                recentreBy = pitch;
                out.add(ScreenPackets.recentre(recentreId, pitch));
            }
            wire.send(out);
        }
        // The hover is drawn on the viewer's thread, which owns the frame; a burst of moves asks once.
        if (moved && hoverQueued.compareAndSet(false, true)) tasks.runAtEntity(player, this::hoverMoved);
    }

    /** Whether the box under the cursor changed; plays the hover tick. Holds {@link #input}. */
    private boolean pointerMoved() {
        Layout.Placed under = pick(view.targets(), px(), py(), pointedBox);
        String now = under == null ? null : under.key();
        if (Objects.equals(now, pointed)) return false;
        pointed = now;
        pointedBox = under;
        if (now != null && under.node().hover() != null) sound("ui.button.click", 0.15f, 2.0f);
        return true;
    }

    boolean confirmed(int teleportId) {
        synchronized (input) {
            if (recentreId == 0 || teleportId != recentreId) return false;
            lastPitch -= recentreBy;
            recentreId = 0;
            return true;
        }
    }

    /** The cursor in layout pixels, read once. */
    double[] pointer() {
        synchronized (input) {
            return new double[]{px(), py()};
        }
    }

    void click(boolean right) {
        if (!open || away) return;
        List<Layout.Placed> targets = view.targets();
        double[] at = pointer();
        boolean shift;
        synchronized (input) {
            shift = sprinting;
        }
        Layout.Placed box = Layout.under(targets, at[0], at[1],
                node -> node.onClick() != null || shift && node.onShiftClick() != null);
        if (box == null) return;
        // The dimmer dismisses only from outside the overlay: a click on the overlay's own background is not one.
        if (box.key().equals("~dim") && Layout.under(targets, at[0], at[1], node -> true) != box) return;
        Consumer<ScreenEvent> handler = shift && box.node().onShiftClick() != null ? box.node().onShiftClick()
                : box.node().onClick();
        ScreenEvent event = new ScreenEvent(this, right ? ScreenEvent.Kind.RIGHT : ScreenEvent.Kind.LEFT, 0,
                box.node().id(), null, shift);
        sound("ui.button.click", 0.35f, 1.4f);
        press(box.key());
        handle(handler, event);
    }

    /** Shows a box pressed for a moment (its {@code :active} style). */
    private void press(String key) {
        tasks.runAtEntity(player, () -> {
            synchronized (this) {
                if (!open || away) return;
                activeKey = key;
                resync = true;
                int mine = ++activeGen;
                tasks.runAtEntityLater(player, ACTIVE_TICKS, () -> {
                    synchronized (this) {
                        if (activeGen != mine || !open || away) return;
                        activeKey = null;
                        sync(false);
                    }
                });
            }
        });
    }

    void wheel(int slot) {
        int steps;
        synchronized (input) {
            steps = slot - heldSlot;
            if (steps > 4) steps -= 9;
            if (steps < -4) steps += 9;
            heldSlot = slot;
        }
        if (steps == 0 || !open || away) return;
        double[] at = pointer();
        Layout.Placed box = Layout.under(view.targets(), at[0], at[1], node -> node.onScroll() != null);
        if (box == null) return;
        handle(box.node().onScroll(), new ScreenEvent(this, ScreenEvent.Kind.SCROLL, steps, box.node().id()));
    }

    private static final String[] KEYS = {"w", "a", "s", "d", "space"};
    private int keysDown;

    /** Movement keys as bits (w, a, s, d, space): a key just pressed clicks the box bound to it, or moves the focus. */
    void keys(int down) {
        int pressed;
        synchronized (input) {
            pressed = down & ~keysDown;
            keysDown = down;
        }
        if (pressed == 0 || !open || away) return;
        if (screen.navigate()) {
            if ((pressed & 16) != 0) {
                click(false);
                return;
            }
            for (int bit = 0; bit < 4; bit++) if ((pressed & 1 << bit) != 0) focus(bit);
            return;
        }
        List<Layout.Placed> targets = view.targets();
        for (int bit = 0; bit < KEYS.length; bit++) {
            if ((pressed & 1 << bit) == 0) continue;
            String key = KEYS[bit];
            for (Layout.Placed box : targets) {
                if (!key.equals(box.node().key()) || box.node().onClick() == null) continue;
                sound("ui.button.click", 0.35f, 1.4f);
                press(box.key());
                handle(box.node().onClick(), new ScreenEvent(this, ScreenEvent.Kind.LEFT, 0, box.node().id()));
                break;
            }
        }
    }

    /**
     * Moves the cursor to the nearest clickable box that way (0 up, 1 left, 2 down, 3 right): the focus is the
     * cursor, so the hover, {@code :focus} and Space all follow it.
     */
    private void focus(int direction) {
        boolean moved;
        synchronized (input) {
            double x = px(), y = py();
            Layout.Placed best = null;
            double bestScore = Double.MAX_VALUE;
            for (Layout.Placed box : view.targets()) {
                if (box.node().onClick() == null || box.key().equals("~dim") || box.holds(x, y)) continue;
                double dx = box.x() + box.w() / 2 - x;
                double dy = box.y() + box.h() / 2 - y;
                double along = switch (direction) {
                    case 0 -> -dy;
                    case 1 -> -dx;
                    case 2 -> dy;
                    default -> dx;
                };
                double across = direction % 2 == 0 ? Math.abs(dx) : Math.abs(dy);
                // Only boxes that lie that way (within about 60 degrees): never a far box barely past the cursor.
                if (along <= 1 || across > along * 2) continue;
                double score = along + across * 2;
                if (score < bestScore) {
                    bestScore = score;
                    best = box;
                }
            }
            if (best == null) return;
            cursorX = (best.x() + best.w() / 2 - screen.width() / 2) * unit;
            cursorY = (screen.height() / 2 - best.y() - best.h() / 2) * unit;
            List<PacketWrapper<?>> out = new ArrayList<>();
            cursor(out, 2);
            wire.send(out);
            moved = pointerMoved();
        }
        if (moved && hoverQueued.compareAndSet(false, true)) tasks.runAtEntity(player, this::hoverMoved);
    }

    /** Sprint held: the next click is a shift-click. */
    void sprint(boolean down) {
        synchronized (input) {
            sprinting = down;
        }
    }

    void shift(boolean down) {
        boolean pressed;
        long stamp;
        synchronized (input) {
            pressed = down && !shifting;
            shifting = down;
            stamp = pressed ? ++shiftStamp : shiftStamp;
        }
        if (!pressed || !open || away) return;
        // Shift steps back (a dialog, then the menu before); held, it closes from anywhere.
        tasks.runAtEntity(player, () -> {
            if (!open()) return;
            if (!back()) close();
        });
        tasks.runAtEntityLater(player, HOLD_TICKS, () -> {
            boolean held;
            synchronized (input) {
                held = shifting && shiftStamp == stamp;
            }
            if (held && open()) close();
        });
    }

    /** On the viewer's thread: closes the confirm or the dialog, then asks the screen to go back. */
    @Override
    public boolean back() {
        if (!open) return false;
        if (confirm != null) {
            confirm = null;
            renderNow();
            return true;
        }
        View now = view;
        if (now.overlayFrom() >= 0) {
            // The dialog is the box after its dimmer.
            int at = now.overlayFrom() + 1 < now.placed().size() ? now.overlayFrom() + 1 : now.overlayFrom();
            Consumer<ScreenSession> dismiss = now.placed().get(at).node().onDismiss();
            if (dismiss != null) {
                guarded(() -> dismiss.accept(this));
                renderNow();
                return true;
            }
        }
        java.util.function.Predicate<ScreenSession> onBack = screen.onBack();
        if (onBack != null) {
            boolean[] went = {false};
            guarded(() -> went[0] = onBack.test(this));
            if (went[0]) return true;
        }
        return false;
    }

    /** What the pointer can reach: only the overlay while one is open. Holds the lock. */
    private List<Layout.Placed> targets() {
        return overlayFrom < 0 ? placed : placed.subList(overlayFrom, placed.size());
    }

    @Override
    public void toast(@NotNull String text) {
        toast(null, text);
    }

    @Override
    public void toast(@Nullable String tone, @NotNull String text) {
        synchronized (this) {
            toasts.add(new Toast(net.exylia.lib.text.Text.of(text).build(), tone, System.currentTimeMillis() + TOAST_MILLIS,
                    ++toastSeq));
            while (toasts.size() > MAX_TOASTS) toasts.remove(0);
            toastVersion++;
        }
        if ("success".equals(tone)) sound("entity.experience_orb.pickup", 0.3f, 1.6f);
        else if ("error".equals(tone)) sound("entity.villager.no", 0.35f, 1f);
        redraw();
        if (open() && plugin.isEnabled()) tasks.runAtEntityLater(player, TOAST_MILLIS / 50 + 1, this::redraw);
    }

    /** Runs a handler on the viewer's thread, then renders once, taking along the redraws its set() calls asked for. */
    private void handle(Consumer<ScreenEvent> handler, ScreenEvent event) {
        tasks.runAtEntity(player, () -> {
            if (!open()) return;
            guarded(() -> handler.accept(event));
            renderNow();
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
