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
    private volatile int backdropId;
    private final int[] cursorIds = new int[4];
    private final int serverSlot;

    /** What is on the client now, by key. */
    private final Map<String, Drawn> drawn = new HashMap<>();
    private List<Layout.Placed> placed = List.of();
    private boolean open = true;
    /** Stepped aside for an input: nothing drawn, nothing taken, until it ends. */
    private boolean away;
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
    private double cursorX, cursorY;
    private float lastYaw, lastPitch;
    private int recentreId;
    private float recentreBy;
    private int heldSlot;
    private boolean shifting;
    private String hovered;
    /** Where the open overlay's boxes start in {@link #placed}, or -1. */
    private int overlayFrom = -1;
    private Layout.Placed hoveredBox;
    private volatile Component toast;
    /** Players this screen hid from the viewer, shown again when it closes. */
    private final Set<java.util.UUID> hidden = ConcurrentHashMap.newKeySet();
    private volatile long toastUntil;

    /** One display on the client: its entity and the last thing sent to it. */
    private record Drawn(int id, Object look) {
    }

    private record RectLook(double x, double y, double w, double h, int layer, int argb) {
    }

    /** One of a picture's four copies: x, y its centre, cell one picture pixel, dx and dy the copy's nudge. */
    private record ImageLook(double x, double y, double cell, int layer, Component text, int rows, int dx, int dy) {
    }

    /**
     * One side of a turning body: the body's centre ({@code x}, {@code y}), a skin pixel's size, where the side
     * stands from the centre in skin pixels, the way it faces, and the body's turn.
     */
    private record TurnLook(double x, double y, double cell, int layer, Component text, int rows, double lx,
                            double ly, double lz, double yaw, double turn, int dx, int dy) {
    }

    /** How fast a body turns, degrees a second, and how long each glide to the next angle lasts. */
    private static final double TURN_SPEED = 36;
    private static final int TURN_TICKS = 20;

    private record TextLook(double x, double y, double scale, int layer, Component text) {
    }

    private record ItemLook(double x, double y, double size, int layer, org.bukkit.inventory.ItemStack item) {
    }

    LiveScreen(Plugin plugin, Player player, Screen screen) {
        this.plugin = plugin;
        this.player = player;
        this.screen = screen;
        this.tasks = Tasks.of(plugin);
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
        hitboxId = ScreenPackets.newId();
        backdropId = ScreenPackets.newId();
        for (int i = 0; i < cursorIds.length; i++) cursorIds[i] = ScreenPackets.newId();
        List<PacketWrapper<?>> out = new ArrayList<>(ScreenPackets.spawnHitbox(hitboxId, origin));
        out.add(ScreenPackets.spawnDisplay(backdropId, origin));
        out.add(rect(backdropId, new RectLook(0, 0, BACKDROP_WIDE, BACKDROP_TALL, 0,
                ScreenMath.over(ScreenMath.argb(screen.backdrop(), 0xFF0B0912), 0xFF000000)), 0));
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
        }
        if (ticker != null) ticker.cancel();
        ids.add(hitboxId);
        ids.add(backdropId);
        for (int id : cursorIds) ids.add(id);
        ScreenPackets.send(player, List.of(ScreenPackets.destroy(ids.stream().mapToInt(Integer::intValue).toArray())));
        Packets.of(plugin).visibility().seal(player, null);
        java.util.concurrent.CompletionStage<T> future;
        try {
            future = input.get();
        } catch (RuntimeException failure) {
            back();
            throw failure;
        }
        future.whenComplete((done, failure) -> {
            if (plugin.isEnabled() && player.isOnline()) tasks.runAtEntity(player, this::back);
        });
        return future;
    }

    /** On the viewer's thread: the screen returns from {@link #aside}, drawn afresh. */
    private void back() {
        synchronized (this) {
            if (!open || !away) return;
            away = false;
        }
        show();
    }

    /** On the viewer's thread. */
    private void hide(Player other) {
        if (other.equals(player) || !other.isOnline() || !player.canSee(other)) return;
        if (!other.getWorld().equals(player.getWorld())
                || other.getLocation().distanceSquared(player.getEyeLocation()) > HIDE_RADIUS * HIDE_RADIUS) return;
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
        List<Layout.Placed> laid = new ArrayList<>(Layout.lay(root, screen.width(), screen.height()));
        Component line = toast;
        if (line != null && System.currentTimeMillis() < toastUntil) {
            Node note = Node.text(line).padding(5, 12).background("#1f1830").outline("{primary}", 1);
            double[] size = Layout.measure(note);
            laid.add(new Layout.Placed(note, "~toast", (screen.width() - size[0]) / 2, 6, size[0], size[1],
                    Layout.OVERLAY + 20));
        }
        synchronized (this) {
            if (!open) return;
            placed = laid;
            overlayFrom = Layout.overlayStart(laid);
            Layout.Placed under = Layout.under(targets(), px(), py(), Node::pointable);
            hovered = under == null ? null : under.key();
            hoveredBox = under;
            sync();
        }
    }

    /** Sends what the layout wants and the client does not have. Holds the lock. */
    private void sync() {
        Map<String, Object> wanted = new HashMap<>();
        List<String> order = new ArrayList<>();
        // The client sorts see-through displays by entity position, and every display here stands at the eyes,
        // so overlapping see-through rects blend in no fixed order. Each colour is mixed over the solid colour
        // of what holds it instead, and only opaque rects are sent. Boxes come parents first, depth by depth.
        int[] beneath = new int[64];
        int backdrop = ScreenMath.over(ScreenMath.argb(screen.backdrop(), 0xFF0B0912), 0xFF000000);
        // A dialog is drawn first, so a screen over the display budget loses its page, never the dialog that
        // takes every click.
        List<Layout.Placed> drawing = placed;
        if (overlayFrom > 0) {
            drawing = new ArrayList<>(placed.subList(overlayFrom, placed.size()));
            drawing.addAll(placed.subList(0, overlayFrom));
        }
        for (Layout.Placed box : drawing) {
            if (wanted.size() >= BUDGET) {
                if (!overBudget) {
                    overBudget = true;
                    plugin.getLogger().warning("A screen drew more than " + BUDGET + " displays; the rest are left out.");
                }
                break;
            }
            Node node = box.node();
            boolean over = box.key().equals(hovered);
            if (box.depth() == Layout.OVERLAY - 1) {
                // The dimmer: the one see-through rect, over opaque ones only, so it blends in a fixed order.
                int dim = ScreenMath.argb(Layout.DIMMER, 0);
                beneath[box.depth()] = ScreenMath.over(dim, backdrop);
                String key = box.key() + "#bg";
                wanted.put(key, new RectLook(0, 0, BACKDROP_WIDE, BACKDROP_TALL, 2 + box.depth() * 3, dim));
                order.add(key);
                continue;
            }
            String colour = over && node.hover() != null ? node.hover() : node.background();
            int depth = Math.min(box.depth(), beneath.length - 1);
            int base = depth == 0 ? backdrop : beneath[depth - 1];
            int argb = ScreenMath.argb(colour, 0);
            argb = argb == 0 ? 0 : ScreenMath.over(argb, base);
            beneath[depth] = argb == 0 ? base : argb;
            double cx = (box.x() + box.w() / 2 - screen.width() / 2) * unit;
            double cy = (screen.height() / 2 - box.y() - box.h() / 2) * unit;
            // Outline, fill, text: three layers a box, deeper boxes nearer the eye.
            int layer = 2 + box.depth() * 3;
            int line = ScreenMath.argb(over && node.hoverOutline() != null ? node.hoverOutline() : node.outline(), 0);
            line = line == 0 ? 0 : ScreenMath.over(line, base);
            if (line != 0 && node.outlineWidth() > 0) {
                double grow = node.outlineWidth() * 2 + (over && node.onClick() != null ? 2 : 0);
                String key = box.key() + "#ol";
                wanted.put(key, new RectLook(cx, cy, (box.w() + grow) * unit, (box.h() + grow) * unit, layer - 1, line));
                order.add(key);
            }
            if (argb != 0 && box.w() > 0 && box.h() > 0) {
                String key = box.key() + "#bg";
                // Something clickable under the pointer rises a little, as a button does.
                double rise = over && node.onClick() != null ? 2 * unit : 0;
                wanted.put(key, new RectLook(cx, cy, box.w() * unit + rise, box.h() * unit + rise, layer, argb));
                order.add(key);
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
                Pictures.Picture sprite = VanillaSprites.of(node.item());
                if (sprite == null) {
                    wanted.put(key, new ItemLook(ix, iy, side * unit * (lifted ? 1.15 : 1), layer + 1, node.item()));
                    order.add(key);
                } else {
                    double cell = side * (lifted ? 1.15 : 1) / Math.max(sprite.width(), sprite.height());
                    pictureLooks(key, ix, iy, cell, layer + 1, picture(sprite.argb(), sprite.width(), sprite.height(),
                            beneath[depth]), sprite.height(), wanted, order);
                }
            }
            List<Pictures.Face> model = node.turntable() == null ? null : Pictures.model(node.turntable());
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
                    Component text = picture(face.picture().argb(), face.picture().width(), face.picture().height(), 0);
                    for (int copy = 0; copy < 4; copy++) {
                        String key = box.key() + "#tt" + f + "." + copy;
                        wanted.put(key, new TurnLook(mx, my, cell * unit, layer + 1, text, face.picture().height(),
                                face.x(), face.y(), face.z(), face.yaw(), turn, (copy & 1) * gapX, (copy >> 1) * gapY));
                        order.add(key);
                    }
                }
            } else if (node.pixels() != null) {
                double innerW = box.w() - node.padLeft() - node.padRight();
                double innerH = box.h() - node.padTop() - node.padBottom();
                double cell = Math.min(innerW / node.pixelWidth(), innerH / node.pixelHeight());
                if (cell > 0) {
                    double ix = (box.x() + node.padLeft() + innerW / 2 - screen.width() / 2) * unit;
                    double iy = (screen.height() / 2 - box.y() - node.padTop() - innerH / 2) * unit;
                    pictureLooks(box.key() + "#im", ix, iy, cell, layer + 1,
                            picture(node.pixels(), node.pixelWidth(), node.pixelHeight(), beneath[depth]),
                            node.pixelHeight(), wanted, order);
                }
            }
            if (node.text() != null) {
                // Text sits in its padding box: centred on what is left inside it.
                double inner = box.w() - node.padLeft() - node.padRight();
                double wide = Layout.textWidth(node);
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
                String key = box.key() + "#t";
                wanted.put(key, new TextLook(tx, ty, node.scale() * fit, layer + 1, node.text()));
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
        if (overlayFrom > 0) {
            // The client draws displays in the order they arrive, and the see-through dimmer hides whatever
            // arrives after it behind it: new boxes go back to front, and the dimmer comes again after any new
            // box behind it.
            order.sort(java.util.Comparator.comparingInt(key -> layerOf(wanted.get(key))));
            String dim = null;
            for (String key : order) if (key.startsWith("~dim")) dim = key;
            if (dim != null && drawn.containsKey(dim)) {
                int dimLayer = layerOf(wanted.get(dim));
                for (String key : order) {
                    if (!drawn.containsKey(key) && layerOf(wanted.get(key)) < dimLayer) {
                        out.add(ScreenPackets.destroy(new int[]{drawn.remove(dim).id()}));
                        break;
                    }
                }
            }
        }
        Map<Integer, Object> settling = new HashMap<>();
        Map<Integer, String> settlingKeys = new HashMap<>();
        for (String key : order) {
            Object look = wanted.get(key);
            Drawn current = drawn.get(key);
            if (current != null && current.look().equals(look)) continue;
            int id = current != null ? current.id() : ScreenPackets.newId();
            if (current != null && current.look().getClass() != look.getClass()) {
                out.add(ScreenPackets.destroy(new int[]{id}));
                current = null;
                id = ScreenPackets.newId();
            }
            Object first = look;
            if (current == null) {
                out.add(look instanceof ItemLook ? ScreenPackets.spawnItem(id, origin) : ScreenPackets.spawnDisplay(id, origin));
                // A new box starts a little off and settles: the whole screen zooms in on open, a dialog pops
                // up from the centre, and what a click brings in slides up into place.
                if (!key.startsWith("~dim")) {
                    boolean dialog = layerOf(look) >= 2 + (Layout.OVERLAY - 1) * 3;
                    first = !shown ? moved(look, 0.96, 0) : dialog ? moved(look, 0.9, 0) : moved(look, 1, -4 * unit);
                    settling.put(id, look);
                    settlingKeys.put(id, key);
                }
            }
            out.add(look(id, first, current == null ? 0 : GLIDE));
            drawn.put(key, new Drawn(id, look));
        }
        shown = true;
        if (!out.isEmpty()) ScreenPackets.send(player, out);
        if (!settling.isEmpty()) {
            // A tick later, once the client has the start: a transform only glides from one it already shows.
            tasks.runAtEntityLater(player, 1, () -> {
                synchronized (this) {
                    if (!open) return;
                    List<PacketWrapper<?>> settle = new ArrayList<>();
                    settling.forEach((id, look) -> {
                        Drawn now = drawn.get(settlingKeys.get(id));
                        if (now != null && now.id() == id && now.look().equals(look)) settle.add(look(id, look, SETTLE));
                    });
                    if (!settle.isEmpty()) ScreenPackets.send(player, settle);
                }
            });
        }
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

    /** A look scaled about the screen's centre by {@code f} and lowered by {@code dy}. */
    private static Object moved(Object look, double f, double dy) {
        return switch (look) {
            case RectLook r -> new RectLook(r.x() * f, r.y() * f + dy, r.w() * f, r.h() * f, r.layer(), r.argb());
            case ItemLook it -> new ItemLook(it.x() * f, it.y() * f + dy, it.size() * f, it.layer(), it.item());
            case TurnLook tl -> tl;
            case ImageLook im -> new ImageLook(im.x() * f, im.y() * f + dy, im.cell() * f, im.layer(), im.text(),
                    im.rows(), im.dx(), im.dy());
            default -> {
                TextLook t = (TextLook) look;
                yield new TextLook(t.x() * f, t.y() * f + dy, t.scale() * f, t.layer(), t.text());
            }
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
        double fx = t.cell() * k / ScreenPackets.IMAGE_ADVANCE;
        double fy = t.cell() * k / ScreenPackets.IMAGE_LINE;
        // About the vertical: x across, z towards the viewer.
        double rx = t.lx() * Math.cos(a) + t.lz() * Math.sin(a);
        double rz = -t.lx() * Math.sin(a) + t.lz() * Math.cos(a);
        return ScreenPackets.image(id, (t.x() + rx * t.cell()) * k, (t.y() + t.ly() * t.cell()) * k - t.dy() * fy,
                -DEPTH + t.layer() * LAYER + rz * t.cell() * k, fx, fy, t.rows(), t.text(),
                glide == 0 ? 0 : TURN_TICKS, t.yaw() + t.turn(), t.dx());
    }

    /** Pictures already turned into glyphs, by their pixels and the colour behind them. */
    private record PictureKey(int width, int length, int hash, int behind) {
    }

    private final Map<PictureKey, Component> pictures = new HashMap<>();

    /**
     * A picture as lines of full blocks, each run of one colour one component. A pixel more than half see-through
     * takes the colour behind it, a partly see-through one is mixed over it: there is no see-through glyph.
     */
    /**
     * A picture's four copies: a full block is narrower and shorter than the cell it stands in, and the copies,
     * nudged by the gaps, cover every cell edge to edge. {@code cell} is one picture pixel in layout units.
     */
    private void pictureLooks(String key, double x, double y, double cell, int layer, Component picture, int rows,
                              Map<String, Object> wanted, List<String> order) {
        int gapX = ScreenPackets.IMAGE_ADVANCE - ScreenPackets.IMAGE_GLYPH;
        int gapY = ScreenPackets.IMAGE_LINE - ScreenPackets.IMAGE_GLYPH;
        for (int copy = 0; copy < 4; copy++) {
            wanted.put(key + copy, new ImageLook(x, y, cell * unit, layer, picture, rows, (copy & 1) * gapX,
                    (copy >> 1) * gapY));
            order.add(key + copy);
        }
    }

    private Component picture(int[] argb, int w, int h, int behind) {
        // A node copies its pixels, so the same picture is a new array every render: keyed by what it holds.
        PictureKey key = new PictureKey(w, argb.length, java.util.Arrays.hashCode(argb), behind);
        Component known = pictures.get(key);
        if (known != null) return known;
        net.kyori.adventure.text.TextComponent.Builder out = Component.text();
        for (int y = 0; y < h; y++) {
            if (y > 0) out.append(Component.newline());
            int x = 0;
            while (x < w) {
                boolean clear = (argb[y * w + x] >>> 24) < 128;
                int colour = solid(argb[y * w + x], behind);
                int end = x + 1;
                while (end < w && ((argb[y * w + end] >>> 24) < 128) == clear
                        && (clear || solid(argb[y * w + end], behind) == colour)) end++;
                if (clear) {
                    // A clear pixel is a gap as wide as a block: a space (4) and a bold space (5).
                    out.append(Component.text(" ".repeat(end - x)));
                    out.append(Component.text(" ".repeat(end - x)).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD));
                } else {
                    out.append(Component.text("█".repeat(end - x), net.kyori.adventure.text.format.TextColor.color(colour & 0xFFFFFF)));
                }
                x = end;
            }
        }
        Component built = out.build();
        if (pictures.size() > 64) pictures.clear();
        pictures.put(key, built);
        return built;
    }

    private static int solid(int argb, int behind) {
        int alpha = argb >>> 24;
        if (alpha == 255) return argb;
        if (alpha < 128) return behind;
        return ScreenMath.over(argb, behind);
    }

    private PacketWrapper<?> text(int id, TextLook t, int glide) {
        double k = drawn(t.layer());
        double scale = t.scale() * unit * k / ScreenPackets.fontPixel();
        return ScreenPackets.text(id, t.x() * k, t.y() * k, -DEPTH + t.layer() * LAYER, scale, t.text(), glide);
    }

    /** The arrow, gliding one tick to where the cursor is: smooth, and never more than a packet behind. */
    private void cursor(List<PacketWrapper<?>> out, int glide) {
        double pixel = ARROW_PIXEL * unit / (SPAN / Screen.WIDTH);
        int w = ARROW[0].length(), h = ARROW.length;
        Component arrow = picture(ARROW_ARGB, w, h, 0);
        // The tip is the pointer's top-left pixel, where the cursor is.
        double cx = cursorX + w * pixel / 2, cy = cursorY - h * pixel / 2;
        int gapX = ScreenPackets.IMAGE_ADVANCE - ScreenPackets.IMAGE_GLYPH;
        int gapY = ScreenPackets.IMAGE_LINE - ScreenPackets.IMAGE_GLYPH;
        for (int copy = 0; copy < cursorIds.length; copy++) {
            out.add(image(cursorIds[copy], new ImageLook(cx, cy, pixel, CURSOR_LAYER, arrow, h,
                    (copy & 1) * gapX, (copy >> 1) * gapY), glide));
        }
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

    synchronized void turned(float yaw, float pitch) {
        if (!open) return;
        if (away) {
            // The cursor stays where it was: the turn made while typing is not a pointer move.
            lastYaw = yaw;
            lastPitch = pitch;
            return;
        }
        double perDegree = DISTANCE * Math.PI / 180 * ScreenRuntime.sensitivity(player.getUniqueId());
        double halfW = screen.width() / 2 * unit;
        double halfH = screen.height() / 2 * unit;
        cursorX = ScreenMath.clamp(cursorX + ScreenMath.turn(lastYaw, yaw) * perDegree, -halfW, halfW);
        cursorY = ScreenMath.clamp(cursorY - (pitch - lastPitch) * perDegree, -halfH, halfH);
        lastYaw = yaw;
        lastPitch = pitch;
        List<PacketWrapper<?>> out = new ArrayList<>();
        cursor(out, 1);
        Layout.Placed under = Layout.under(targets(), px(), py(), Node::pointable);
        String now = under == null ? null : under.key();
        if (!Objects.equals(now, hovered)) {
            hovered = now;
            hoveredBox = under;
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
        if (!open || away) return;
        Layout.Placed box = Layout.under(targets(), px(), py(), node -> node.onClick() != null);
        if (box == null) return;
        // The dimmer dismisses only from outside the overlay: a click on the overlay's own background is not one.
        if (box.key().equals("~dim") && Layout.under(targets(), px(), py(), node -> true) != box) return;
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
        if (steps == 0 || !open || away) return;
        Layout.Placed box = Layout.under(targets(), px(), py(), node -> node.onScroll() != null);
        if (box == null) return;
        handle(box.node().onScroll(), new ScreenEvent(this, ScreenEvent.Kind.SCROLL, steps, box.node().id()));
    }

    private static final String[] KEYS = {"w", "a", "s", "d", "space"};
    private int keysDown;

    /** Movement keys as bits (w, a, s, d, space): a key just pressed clicks the box bound to it. */
    synchronized void keys(int down) {
        int pressed = down & ~keysDown;
        keysDown = down;
        if (pressed == 0 || !open || away) return;
        for (int bit = 0; bit < KEYS.length; bit++) {
            if ((pressed & 1 << bit) == 0) continue;
            String key = KEYS[bit];
            for (Layout.Placed box : targets()) {
                if (!key.equals(box.node().key()) || box.node().onClick() == null) continue;
                sound("ui.button.click", 0.35f, 1.4f);
                handle(box.node().onClick(), new ScreenEvent(this, ScreenEvent.Kind.LEFT, 0, box.node().id()));
                break;
            }
        }
    }

    synchronized void shift(boolean down) {
        boolean pressed = down && !shifting;
        shifting = down;
        if (!pressed || !open || away) return;
        // An open dialog is dismissed first; Shift again closes the screen.
        Consumer<ScreenSession> dismiss = overlayFrom < 0 ? null : placed.get(overlayFrom + 1 < placed.size()
                ? overlayFrom + 1 : overlayFrom).node().onDismiss();
        if (dismiss == null) {
            tasks.runAtEntity(player, this::close);
            return;
        }
        tasks.runAtEntity(player, () -> {
            if (!open()) return;
            guarded(() -> dismiss.accept(this));
            render();
        });
    }

    /** What the pointer can reach: only the overlay while one is open. Holds the lock. */
    private List<Layout.Placed> targets() {
        return overlayFrom < 0 ? placed : placed.subList(overlayFrom, placed.size());
    }

    @Override
    public void toast(@NotNull String text) {
        toast = net.exylia.lib.text.Text.of(text).build();
        toastUntil = System.currentTimeMillis() + TOAST_MILLIS;
        redraw();
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
