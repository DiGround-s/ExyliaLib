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
 * <p>Boxes, outlines, text and items are drawn as on a screen in front of the eyes; pictures and motion are not.
 * Each look has a display of its own, kept from frame to frame, so a redraw changes only what changed and a box
 * that goes is destroyed with it.
 *
 * <p>A {@linkplain #follow following} board is one player's menu instead: it stands in front of them, and when they
 * walk away from it or turn well away it glides back in front of them (its displays are teleported with the
 * client's position glide, never spawned again, unless they were teleported far).
 */
public final class WorldBoard {

    /** Billboard fixed: the display keeps the yaw it was spawned with. */
    private static final byte FIXED = 0;
    /** The look key's end that marks an item, drawn by an item display rather than a text display. */
    private static final String ITEM = "#it";
    /** Blocks between two layers, nearer the front for deeper boxes. */
    static final double LAYER = 0.004;

    private final Plugin plugin;
    private final TaskScheduler tasks;
    /** Where the panel stands and the way it faces: moved for a following board, on its viewer's thread. */
    private volatile Location centre;
    private volatile float yaw;
    private final Screen screen;
    private final double unit;
    /** The one player a following board is for; {@code null} for a board standing in the world. */
    private final @Nullable Player owner;
    private final double distance;
    private final boolean closeWithSneak;
    /** Where the owner's eyes were, and their yaw, when the board last sat down in front of them. */
    private Location seatFrom;
    private float seatYaw;
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
        this(plugin, centre, screen, width, null, 0, false);
    }

    private WorldBoard(Plugin plugin, Location centre, Screen screen, double width, @Nullable Player owner,
                       double distance, boolean closeWithSneak) {
        this.plugin = plugin;
        this.tasks = Tasks.of(plugin);
        this.centre = centre.clone();
        this.yaw = centre.getYaw();
        this.screen = screen;
        this.unit = width / screen.width();
        this.owner = owner;
        this.distance = distance;
        this.closeWithSneak = closeWithSneak;
    }

    // ------------------------------------------------------------------
    // Following boards: one player's menu floating in front of them
    // ------------------------------------------------------------------

    /** Blocks the eyes may wander from where the board sat down before it follows. */
    static final double WALK = 1.5;
    /** Degrees the view may turn from the board before it follows. */
    static final float TURN = 55;
    /** Blocks past which a move is a teleport: the board is drawn again there rather than glided across. */
    static final double JUMP = 8;
    /** Ticks a board takes to glide back in front of its player. */
    static final int GLIDE = 6;

    /** The boards following players, by player: one each, and a screen closes it. */
    private static final Map<UUID, View> FOLLOWING = new ConcurrentHashMap<>();

    /**
     * Opens {@code screen} as a board floating in front of {@code player}, sized and placed as {@code screens.yml}'s
     * {@code board} says, closing their screen or board first. On the player's thread.
     *
     * @return the session, or {@code null} without PacketEvents or while a camera holds the player
     */
    public static @Nullable ScreenSession follow(@NotNull Plugin plugin, @NotNull Player player, @NotNull Screen screen) {
        if (!net.exylia.lib.packet.Packets.isAvailable()) return null;
        if (ScreenRuntime.refused(player)) return null;
        closeAll(player.getUniqueId());
        net.exylia.lib.screen.ScreenSettings.Board settings = net.exylia.lib.screen.ScreenSettings.get().boardOrDefault();
        double distance = Math.max(1, Math.min(8, settings.distance()));
        double width = Math.max(0.5, Math.min(12, settings.width()));
        Location eyes = player.getEyeLocation();
        WorldBoard board = new WorldBoard(plugin, seat(eyes, distance), screen, width, player, distance,
                settings.closeWithSneak());
        board.seatFrom = eyes;
        board.seatYaw = eyes.getYaw();
        View view = board.new View(player, ScreenPackets.Wire.to(player), ScreenPackets::newId);
        board.views.put(player.getUniqueId(), view);
        FOLLOWING.put(player.getUniqueId(), view);
        view.attach();
        view.render();
        board.ticker = board.tasks.runTimer(20, 20, board::redrawAll);
        board.tracker = board.tasks.runAtEntityTimer(player, 2, 2, board::follow);
        return view;
    }

    /** The board this plugin has following {@code player}, or {@code null}. */
    public static @Nullable ScreenSession following(@NotNull Plugin plugin, @NotNull Player player) {
        View view = FOLLOWING.get(player.getUniqueId());
        return view != null && view.board().plugin == plugin ? view : null;
    }

    /** Closes the board following {@code player}, if any. */
    public static void close(@NotNull UUID player) {
        View view = FOLLOWING.get(player);
        if (view != null) view.drop();
    }

    /** Closes the player's board and their screen: they have one of either at a time. */
    static void closeAll(UUID player) {
        close(player);
        LiveScreen live = ScreenRuntime.live(player);
        if (live != null) live.close();
    }

    /** Redraws every board {@code plugin} has following a player whose session passes {@code which}. */
    public static void redrawAll(@NotNull Plugin plugin, @NotNull java.util.function.Predicate<ScreenSession> which) {
        for (View view : FOLLOWING.values()) {
            if (view.board().plugin == plugin && which.test(view)) view.redraw();
        }
    }

    /**
     * The middle of a board {@code distance} blocks ahead of the eyes at their height, facing back at them: upright,
     * whatever the pitch, so looking down at the ground does not tip it over.
     */
    static Location seat(Location eyes, double distance) {
        double rad = Math.toRadians(eyes.getYaw());
        return new Location(eyes.getWorld(), eyes.getX() - Math.sin(rad) * distance, eyes.getY(),
                eyes.getZ() + Math.cos(rad) * distance, Location.normalizeYaw(eyes.getYaw() + 180), 0);
    }

    /** Whether eyes this far from the seat and turned this far from it should bring the board back in front. */
    static boolean strayed(double movedSquared, float turned) {
        return movedSquared > WALK * WALK || Math.abs(Location.normalizeYaw(turned)) > TURN;
    }

    /** Every two ticks on the owner's thread: brings the board back in front of them when they strayed from it. */
    private void follow() {
        View view = owner == null ? null : views.get(owner.getUniqueId());
        if (view == null || removed) return;
        if (ScreenRuntime.cameraHeld(owner)) {
            view.drop();
            return;
        }
        Location eyes = owner.getEyeLocation();
        if (eyes.getWorld() != centre.getWorld()) {
            view.drop();
            return;
        }
        double moved = eyes.distanceSquared(seatFrom);
        if (!strayed(moved, eyes.getYaw() - seatYaw)) return;
        view.reseat(eyes, moved > JUMP * JUMP);
    }

    /** Closes following boards on what closes a screen, and on a sneak with no click. Called by the library on enable. */
    public static void init(@NotNull Plugin library) {
        library.getServer().getPluginManager().registerEvents(new Closer(), library);
    }

    static final class Closer implements org.bukkit.event.Listener {

        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
        public void onSneak(org.bukkit.event.player.PlayerToggleSneakEvent event) {
            View view = FOLLOWING.get(event.getPlayer().getUniqueId());
            if (view == null || !view.board().closeWithSneak) return;
            if (event.isSneaking()) {
                view.sneakArmed = true;
                view.sneakClicked = false;
            } else if (view.sneakArmed && !view.sneakClicked) {
                view.drop();
            } else {
                view.sneakArmed = false;
            }
        }

        /** A click while sneaking makes that sneak a shift-click, so letting go of it does not close the board. */
        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
        public void onSwing(org.bukkit.event.player.PlayerAnimationEvent event) {
            clicked(event.getPlayer());
        }

        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
        public void onInteract(org.bukkit.event.player.PlayerInteractEvent event) {
            clicked(event.getPlayer());
        }

        private static void clicked(Player player) {
            View view = FOLLOWING.get(player.getUniqueId());
            if (view != null && player.isSneaking()) view.sneakClicked = true;
        }

        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
        public void onDamage(org.bukkit.event.entity.EntityDamageEvent event) {
            if (event.getEntity() instanceof Player player) close(player.getUniqueId());
        }

        /** A window opening (another plugin's menu, a chest) takes over from the board. */
        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
        public void onInventory(org.bukkit.event.inventory.InventoryOpenEvent event) {
            close(event.getPlayer().getUniqueId());
        }

        /** {@code closeInventory()} from a board's own click closes it, as it closes the chest it stands for. */
        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
        public void onInventoryClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
            View view = FOLLOWING.get(event.getPlayer().getUniqueId());
            if (view != null && view.clicking && ScreenRuntime.closesScreen(event)) view.closeAfterClick = true;
        }

        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
        public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
            close(event.getPlayer().getUniqueId());
        }
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
        double coarse = (range + 16) * (range + 16);
        for (Player player : Bukkit.getOnlinePlayers()) {
            // A cheap look first, so a board does not queue a task per player online every half
            // second: someone with no view in another world or well out of range is left alone.
            // The read is from this thread, hence the margin; the real test runs on theirs.
            if (!views.containsKey(player.getUniqueId())) {
                Location roughly = player.getLocation();
                if (roughly.getWorld() != centre.getWorld() || roughly.distanceSquared(centre) > coarse) continue;
            }
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
        private Hotspot hotspot;
        private volatile boolean dropped;
        /** A sneak began while the board was open, and whether a click came during it (a shift-click). */
        volatile boolean sneakArmed;
        volatile boolean sneakClicked;
        /** Inside one of the board's clicks, and whether that click closed the inventory. */
        volatile boolean clicking;
        volatile boolean closeAfterClick;

        View(Player viewer, ScreenPackets.Wire wire, IntSupplier ids) {
            this.viewer = viewer;
            this.wire = wire;
            this.ids = ids;
            this.limits = IconDowngrade.limitsOf(viewer);
        }

        List<Layout.Placed> placed() {
            return placed;
        }

        WorldBoard board() {
            return WorldBoard.this;
        }

        /** Gives the viewer the pointer that hovers and clicks it. On the viewer's thread. */
        void attach() {
            pointer = SpatialInput.of(plugin).observe(viewer);
            hotspot = pointer.add(hotspot(plane()));
            pointer.onEnd(end -> {
                views.remove(viewer.getUniqueId(), this);
                drop();
            });
        }

        private Hotspot hotspot(StagePlane plane) {
            return Hotspot.custom(plane, hit -> pick(plane, hit))
                    .onCell(index -> hover(index))
                    .onPress(this::press);
        }

        /**
         * Sits the board down in front of {@code eyes} again: its displays glide there, or, after a teleport
         * ({@code jump}), are drawn there afresh. On the viewer's thread.
         */
        synchronized void reseat(Location eyes, boolean jump) {
            if (dropped) return;
            Location seat = seat(eyes, distance);
            centre = seat;
            yaw = seat.getYaw();
            seatFrom = eyes.clone();
            seatYaw = eyes.getYaw();
            Hotspot old = hotspot;
            hotspot = pointer.add(hotspot(plane()));
            pointer.remove(old);
            if (jump) {
                if (!sent.isEmpty()) {
                    wire.send(List.of(ScreenPackets.destroy(sent.values().stream().mapToInt(Integer::intValue).toArray())));
                    sent.clear();
                }
                render();
                return;
            }
            Vector3d at = new Vector3d(seat.getX(), seat.getY(), seat.getZ());
            List<PacketWrapper<?>> frame = new ArrayList<>(sent.size());
            for (int id : sent.values()) frame.add(ScreenPackets.moveTo(id, at, yaw));
            if (!frame.isEmpty()) wire.send(frame);
        }

        int pick(StagePlane plane, StagePlane.Hit hit) {
            if (hit == null) return -1;
            Location eyes = viewer.getEyeLocation();
            Location point = plane.point(hit.across(), hit.up(), 0);
            double distance = point.distanceSquared(eyes);
            if (distance > reach * reach) return -1;
            double[] at = pixel(hit, unit, screen.width(), screen.height());
            int index = at == null ? -1 : button(placed, at[0], at[1]);
            // Only a button is worth a block trace: a wall in between hides it from hovers and clicks. A board
            // following its player is theirs wherever it floats, a wall it sank into included.
            return index >= 0 && owner == null && blocked(eyes, point, Math.sqrt(distance)) ? -1 : index;
        }

        /** Whether a block stands between the eyes and a point on the panel. */
        static boolean blocked(Location eyes, Location point, double distance) {
            if (distance < 0.1) return false;
            var direction = point.toVector().subtract(eyes.toVector()).multiply(1 / distance);
            // Stops just short of the panel, so the wall it hangs on is not in the way.
            return eyes.getWorld().rayTraceBlocks(eyes, direction, distance - 0.05,
                    org.bukkit.FluidCollisionMode.NEVER, true) != null;
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
            if (shift) sneakClicked = true;
            clicking = true;
            closeAfterClick = false;
            try {
                handler.accept(new ScreenEvent(this, click.kind() == SpatialClick.Kind.RIGHT
                        ? ScreenEvent.Kind.RIGHT : ScreenEvent.Kind.LEFT, 0, node.id(), null, shift));
            } finally {
                clicking = false;
            }
            // Code written for a chest closes it with closeInventory() before a game or a prompt: so does the board,
            // unless the click went on to ask something.
            if (closeAfterClick && owner != null
                    && !net.exylia.lib.input.internal.InputRuntime.hasActive(viewer.getUniqueId())) {
                drop();
                return;
            }
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
            Location here = centre;
            float facing = yaw;
            Vector3d at = new Vector3d(here.getX(), here.getY(), here.getZ());
            for (Map.Entry<String, Look> look : looks.entrySet()) {
                if (!sent.containsKey(look.getKey())) {
                    int id = ids.getAsInt();
                    sent.put(look.getKey(), id);
                    frame.add(look.getKey().endsWith(ITEM) ? ScreenPackets.spawnItem(id, at, facing)
                            : ScreenPackets.spawnDisplay(id, at, facing));
                    // A following board glides when it moves.
                    if (owner != null) frame.add(ScreenPackets.moveTicks(id, GLIDE));
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
                if (node.item() != null) {
                    double side = Math.min(box.w() - node.padLeft() - node.padRight(), box.h() - node.padTop() - node.padBottom());
                    double ix = (box.x() + node.padLeft() + (box.w() - node.padLeft() - node.padRight()) / 2 - w / 2) * unit;
                    double iy = (h / 2 - box.y() - node.padTop() - (box.h() - node.padTop() - node.padBottom()) / 2) * unit;
                    // A hovered item grows a little, as a slot under the cursor would.
                    double size = side * unit * (over && node.hoverTransform() == null ? 1.15 : 1);
                    org.bukkit.inventory.ItemStack item = node.item();
                    if (side > 0) out.put(box.key() + ITEM, id -> ScreenPackets.item(id, ix, iy, (layer + 2) * LAYER,
                            size, 1, item, 0, FIXED));
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
            if (owner != null) {
                // A following board is this one view: it ends with it.
                removed = true;
                FOLLOWING.remove(viewer.getUniqueId(), this);
                if (tracker != null) tracker.cancel();
                if (ticker != null) ticker.cancel();
            }
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

        @Override
        public boolean back() {
            var back = screen.onBack();
            if (back == null || dropped) return false;
            boolean went = back.test(this);
            if (went) redraw();
            return went;
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
