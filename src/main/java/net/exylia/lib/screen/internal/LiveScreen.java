package net.exylia.lib.screen.internal;

import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import net.exylia.lib.packet.Packets;
import net.exylia.lib.screen.Motion;
import net.exylia.lib.screen.Node;
import net.exylia.lib.screen.ScreenScene;
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
    /**
     * How far it really stands. A player's eyes are 0.3 from a wall and 0.18 under a ceiling, and every corner of the view
     * at this depth stays closer than that up to FOV 110 ({@code DepthTest}), so nothing a player can stand next to cuts
     * through it, and no particle draws over it unless it spawns inside the panel.
     */
    static final double DEPTH = 0.09;
    private static final double K = DEPTH / DISTANCE;
    /**
     * Depth between layers, so stacked rects never fight. The cursor's layer stands in front of the client's near
     * plane ({@link #NEAR}) at this share of {@link #DEPTH}.
     */
    static final double LAYER = DEPTH / 600;
    /** Where the client stops drawing, in blocks from the eye. */
    static final double NEAR = 0.05;
    /** The default layout area spans this many units across at {@link #DISTANCE}: about three quarters of a 16:9 view at FOV 70. */
    private static final double SPAN = 5.6;
    /**
     * What a camera view draws the layout at, against {@link #SPAN}: with the field of view left to the player's FOV
     * slider alone (see {@link net.exylia.lib.packet.internal.PacketRuntime#sliderFov}), 400 pixels are three quarters
     * of a 16:9 window at FOV 70.
     */
    private static final double CAMERA_VIEW_SCALE = 0.674;
    /** Layout pixels the cursor crosses per degree the mouse turns, at speed 1: the same however large the screen is drawn. */
    private static final double DEGREE = DISTANCE * Math.PI / 180 / (SPAN * 1.255 / Screen.WIDTH);
    /** How far past the layout's edges the cursor goes, in layout pixels: far enough for the settings button. */
    private static final double GUTTER_X = 24, GUTTER_Y = 6;
    /** Sprint and jump together open the settings from anywhere, whatever the screen binds its keys to. */
    static final String SETTINGS_KEYS = "sprint + jump";
    /** The settings button's side, in layout pixels. */
    private static final double GEAR = 16;
    /** The backdrop, in units: past the edge of any view. */
    private static final double BACKDROP_WIDE = 16, BACKDROP_TALL = 10;
    /** Past this pitch the client's rotation is turned back silently; the view does not move. */
    private static final float RECENTRE_PITCH = 60f;
    /** How high over the feet a client's vehicle stands for it to ride with its feet where they are. */
    private static final double RIDE_HEIGHT = 0.6;
    /** How far the camera may turn from where the screen opened while a 3D item is on screen, in degrees. */
    private static final float CONE = 8f;
    /** As {@link #CONE} while a window is on screen: the world moves behind the hole with the camera, so it barely may. */
    private static final float WINDOW_CONE = 1f;
    /** How long the mouse rests before the camera is put back exactly, while a window is on screen. */
    private static final long SETTLE_MILLIS = 120L;
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
    static final int CURSOR_LAYER = 250;
    /** How long a toast stays, in milliseconds. */
    private static final long TOAST_MILLIS = 3000;
    /** A toast with links stays long enough to reach and click. */
    private static final long LINK_TOAST_MILLIS = 8000;
    /** What one screen may draw; past it the rest is left out and reported once. */
    static final int BUDGET = 400;
    /**
     * What a screen and the stage in its window may draw together. Measured on a 1.21.4 client (RTX 3050, 1080p): a
     * screen alone (about 195 displays) runs at about 100 fps, and at 71-83 with the heaviest shipped kill effect at
     * its peak (520 in all: 316 on top of about 205). This holds every shipped effect whole and stops only an author's
     * own effect that wants far more.
     */
    static final int WINDOW_BUDGET = 560;

    private final Plugin plugin;
    /** The library's lines in the plugin's language, read once for this screen. */
    private java.util.function.UnaryOperator<String> phrases;
    private final Player player;
    private final Screen screen;
    private final TaskScheduler tasks;
    private final Map<String, Object> state = new ConcurrentHashMap<>();
    private final Vector3d origin;
    /** Blocks a layout pixel stands for; read everywhere, written under {@code this} and {@link #input}. */
    private volatile double unit;
    /** {@link #unit} at 100%. */
    private final double fullUnit;
    /** What scenes and backdrops are measured in: they have to reach the view's edges however small the screen is. */
    private volatile double viewUnit;
    // Given out again on every show, after the world is sealed: only ids newer than the seal are the screen's.
    private volatile int hitboxId;
    private final CursorLead lead = new CursorLead();
    private final java.util.concurrent.atomic.AtomicBoolean leadWatched = new java.util.concurrent.atomic.AtomicBoolean();
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
    /** A toast with links under the cursor: it can be clicked. */
    private static final String TOAST_HOVER = "{primary}60";
    private static final int TIP_DEPTH = Layout.OVERLAY + 8;
    /** A tooltip's widest line, in layout pixels, and its text sizes: the name, then the lines under it. */
    static final double TIP_WIDTH = 150;
    static final double TIP_NAME = 0.8;
    static final double TIP_LORE = 0.7;

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
    /** Ticks a turning item takes to its next angle, and a new box to settle in. */
    private static final int GLIDE = 2;
    /** Ticks a hover or a moved box takes when no transition says otherwise: what the mouse did should show soon. */
    private static final int BOX_GLIDE = 1;
    private static final int SETTLE = 4;
    /** Blocks around the eyes where another player could stand in front of the screen. */
    private static final double HIDE_RADIUS = 4;
    /** How far around the viewer the world is emptied while a screen is up: past any view distance's entities. */
    private static final double SEAL_REACH = 192;
    private TaskHandle ticker;
    /** A hovered box keeps its hover this many pixels past its edges. */
    private static final double HYSTERESIS = 3;
    /** The furthest prediction draws the arrow from the pointer, in layout units. */
    private static final double LEAD_BOUND = 10;
    /** How long without a move means the mouse stopped: a moving client sends one every 50. */
    private static final long LEAD_REST_MILLIS = 70;
    /** How long a clicked box shows pressed, and how long Shift is held to close from anywhere. */
    private static final int ACTIVE_TICKS = 3;
    private static final int MAX_TOASTS = 1;
    /** How large a toast's words are, and how far it sits from the bottom edge. */
    private static final double TOAST_SCALE = 0.7;
    private static final double TOAST_MARGIN = 6;
    /** Toasts on screen, oldest first; guarded by {@code this}. */
    private final List<Toast> toasts = new ArrayList<>();
    /** A chat line this soon after a click is the answer to it. */
    private static final long ANSWER_MILLIS = 1000;
    private volatile long lastPress;
    private int toastSeq;
    private int toastVersion;
    private int lastToastVersion = -1;
    /** {@code links}: the line carries links, so clicking the toast offers them in a dialog. */
    private record Toast(Component text, String tone, long until, int seq, boolean links) {
    }
    /** Whether the settings panel is open. */
    private volatile boolean settingsOpen;
    /** The size changed: the next frame sends every look again, though each may compare equal. Guarded by {@code this}. */
    private boolean rescaled;
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
    /** What the motion ceiling's seconds are read from: a test holds it still, so a slow machine cannot change what is counted. */
    private java.util.function.LongSupplier clock = System::currentTimeMillis;
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
    private float recentreYaw;
    /** Where the camera faced when the screen opened: with a 3D item on screen the camera is kept near it. */
    private float homeYaw;
    /** The stage a window looks at. On the viewer's thread, under this lock. */
    private @Nullable PreviewStage stage;
    /** The window the last frame cut, or {@code null}, and whether the camera is held for it. */
    private @Nullable RectLook lastHole;
    private volatile boolean windowed;
    /** When the mouse last turned the camera. */
    private volatile long lastTurn;
    /** The pitch the camera is held at while a window is on screen. */
    private volatile float homePitch;
    /**
     * The still entity the client looks out of and the one it rides, or 0 when it keeps its own camera (a client before
     * 1.21.2, a player really riding something). Read by the netty thread.
     */
    private volatile int cameraId;
    private int vehicleId;
    /** Where the held camera faces: a spawn packet carries a turn in 256 steps. */
    private float cameraYaw;
    /** Where the eyes looked when the screen opened: a window tips them, and closing gives that back. */
    private final float openPitch;
    private volatile boolean tipped;
    /** How many displays the screen itself has drawn: what a window's stage has left of the budget. */
    private volatile int drawnCount;
    /** Whether a 3D item is on screen, which keeps the camera within {@link #CONE} of {@link #homeYaw}. */
    private volatile boolean cone;
    private int heldSlot;
    private boolean shifting;
    private boolean sprinting;
    private String hovered;
    /** What the netty thread last saw under the pointer. Guarded by {@link #input}. */
    private String pointed;
    private Layout.Placed pointedBox;
    /** The clickable box the pointer just left and when, for cursor assist's click tolerance. Guarded by {@link #input}. */
    private String leftKey;
    private long leftAt;
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
    private record ImageLook(double x, double y, double cell, int layer, Component text, int rows, int dx, int dy,
                             double squeeze) {
        ImageLook(double x, double y, double cell, int layer, Component text, int rows, int dx, int dy) {
            this(x, y, cell, layer, text, rows, dx, dy, 1);
        }
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
    /** How far a body reaches from its centre as it turns, in skin pixels. */
    private static final double BODY_RADIUS = 8;

    /**
     * The layer a turning model stands on: far enough out of {@code layer} that the half swinging away, {@code radius}
     * layout units at most and flattened by {@link ScreenPackets#TURN_DEPTH}, never goes behind the box holding it.
     * On a nearer layer {@link #drawn} keeps its place and size on screen.
     */
    private static int turnLayer(int layer, double radius) {
        return layer + 1 + (int) Math.ceil(radius * K * ScreenPackets.TURN_DEPTH / LAYER);
    }

    /** How far a flat item swings either way, degrees: its face always shows, never a sliver of its edge. */
    private static final double SWING = 40;

    /**
     * A 3D item's angle at {@code millis}: {@code spin} degrees a second round and round, or for a flat item the same
     * speed at most, swinging {@value #SWING} degrees either way.
     */
    static double turnAngle(double spin, boolean flat, long millis) {
        if (spin == 0) return 0;
        double seconds = millis / 1000.0;
        return flat ? SWING * Math.sin(seconds * spin / SWING) : seconds * spin;
    }

    /** Words: {@code lines} of them joined by newlines, {@code flags} the display's text flags (shadow, alignment). */
    private record TextLook(double x, double y, double scale, int layer, Component text, int lines, int flags,
                            double squeeze) {
        TextLook(double x, double y, double scale, int layer, Component text, int lines, int flags) {
            this(x, y, scale, layer, text, lines, flags, 1);
        }
    }

    /**
     * An item: flat ({@code solid} false), or a 3D model turned {@code angle} degrees about the upright; a block
     * leans towards the viewer so its top shows, what is drawn from a sprite ({@code leans} false) stands upright.
     */
    private record ItemLook(double x, double y, double size, int layer, org.bukkit.inventory.ItemStack item,
                            boolean solid, double angle, boolean leans, double squeeze) {
        ItemLook(double x, double y, double size, int layer, org.bukkit.inventory.ItemStack item) {
            this(x, y, size, layer, item, false, 0, false, 1);
        }

        ItemLook(double x, double y, double size, int layer, org.bukkit.inventory.ItemStack item,
                 boolean solid, double angle, boolean leans) {
            this(x, y, size, layer, item, solid, angle, leans, 1);
        }
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
        this.fullUnit = (screen.wheel() ? SPAN : SPAN * CAMERA_VIEW_SCALE) / Screen.WIDTH;
        this.unit = fullUnit * ScreenRuntime.size(player.getUniqueId()) / 100.0;
        this.viewUnit = Math.max(unit, fullUnit);
        this.serverSlot = player.getInventory().getHeldItemSlot();
        this.heldSlot = serverSlot;
        Location eyes = player.getEyeLocation();
        // A camera-view client stands up as it turns spectator, so a sneaking or swimming viewer's eyes rise.
        double eyeY = screen.wheel() ? eyes.getY() : eyes.getY() - player.getEyeHeight() + player.getEyeHeight(true);
        this.origin = new Vector3d(eyes.getX(), eyeY, eyes.getZ());
        this.lastYaw = eyes.getYaw();
        this.homeYaw = lastYaw;
        this.lastPitch = eyes.getPitch();
        this.openPitch = lastPitch;
        state.putAll(Persisted.load(player, screen));
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

    @Override
    public String phrase(String english) {
        if (phrases == null) phrases = net.exylia.lib.text.Phrases.of(plugin);
        return phrases.apply(english);
    }

    int hitboxId() {
        return hitboxId;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** On the viewer's thread. */
    void show() {
        ScreenRuntime.quietBegin(player);
        if (!screen.wheel()) {
            Packets.of(plugin).fakeGameMode().cameraView(player, true);
            net.exylia.lib.packet.internal.PacketRuntime.sliderFov(player, true);
        }
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
        boolean holds = !player.isInsideVehicle() && ScreenPackets.canHoldCamera(player);
        if (holds) {
            cameraYaw = Math.round(homeYaw * 256 / 360) * 360f / 256;
            vehicleId = ids.getAsInt();
            cameraId = ids.getAsInt();
            out.add(ScreenPackets.spawnStill(cameraId, origin, cameraYaw, 0f));
            out.add(ScreenPackets.spawnStill(vehicleId, new Vector3d(origin.x, player.getLocation().getY() + RIDE_HEIGHT, origin.z), 0f, 0f));
            out.add(ScreenPackets.riders(vehicleId, player.getEntityId()));
            out.add(ScreenPackets.blankBar());
        }
        synchronized (this) {
            // Drawn afresh: nothing on the client is the last render's any more.
            lastLaid = null;
            lastHole = null;
            lastBoxes = Map.of();
            refreshPeriod = 20;
            synchronized (input) {
                if (scene != null) sceneSetup(scene);
                cursor(out, 0);
                if (scene != null && screen.parallax() <= 0) scene(out, 0);
            }
        }
        wire.send(out);
        if (holds) ScreenPackets.look(player, cameraId);
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
        closeStage();
        ids.add(hitboxId);
        for (int id : backdropIds) ids.add(id);
        if (screen.scene() != null) for (int id : sceneIds) ids.add(id);
        for (int id : cursorIds) ids.add(id);
        if (player.isOnline()) {
            giveBackView(ids);
            wire.send(List.of(
                    ScreenPackets.destroy(ids.stream().mapToInt(Integer::intValue).toArray()),
                    ScreenPackets.heldSlot(player.getInventory().getHeldItemSlot())));
            giveBackPitch();
        }
        Packets.of(plugin).movement().unfreeze(player);
        Packets.of(plugin).visibility().seal(player, null);
        if (!hidden.isEmpty() && player.isOnline()) {
            // Now when we can, so a screen opened right after this one finds everyone visible to hide again.
            if (org.bukkit.Bukkit.isOwnedByCurrentRegion(player)) reveal();
            else if (plugin.isEnabled()) tasks.runAtEntity(player, this::reveal);
        }
        if (!screen.wheel()) {
            net.exylia.lib.packet.internal.PacketRuntime.sliderFov(player, false);
            Packets.of(plugin).fakeGameMode().cameraView(player, false);
        }
        if (player.isOnline()) ScreenRuntime.quietEndSoon(player);
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
        closeStage();
        ids.add(hitboxId);
        for (int id : backdropIds) ids.add(id);
        if (screen.scene() != null) for (int id : sceneIds) ids.add(id);
        for (int id : cursorIds) ids.add(id);
        giveBackView(ids);
        wire.send(List.of(ScreenPackets.destroy(ids.stream().mapToInt(Integer::intValue).toArray())));
        giveBackPitch();
        Packets.of(plugin).visibility().seal(player, null);
        ScreenRuntime.quietEnd(player);
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

    boolean hides(Player other) {
        return hidden.contains(other.getUniqueId());
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
        if (screen.persisted().contains(key)) Persisted.save(plugin, player, screen, key, value);
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
        if (settingsOpen) root = over(root, SettingsPanel.of(this));
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
            stage(core);
            lastPrint = print;
            lastToastVersion = toastVersion;
            List<Layout.Placed> laid = new ArrayList<>(core);
            addToasts(laid);
            overlayFrom = Layout.overlayStart(laid);
            // Outside the layout, and gone under a dialog: a dialog takes every click.
            if (overlayFrom < 0) laid.add(new Layout.Placed(gear(), "~gear", screen.width() + 3, screen.height() - GEAR, GEAR, GEAR, 0));
            placed = laid;
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

    private Node gearNode;

    /** The settings button, one node for the whole screen. Holds the lock. */
    private Node gear() {
        if (gearNode == null) gearNode = SettingsPanel.button(this);
        return gearNode;
    }

    /** On the viewer's thread: opens or closes the settings panel. */
    void settings(boolean open) {
        settingsOpen = open;
        renderNow();
    }

    /**
     * Applies the viewer's size, on an open screen too: every look is measured in {@link #unit}, so a full redraw
     * covers the boxes, the items and the cursor; the backdrop and the scene are sent here.
     */
    void rescale() {
        double next = fullUnit * ScreenRuntime.size(player.getUniqueId()) / 100.0;
        List<PacketWrapper<?>> out = new ArrayList<>();
        synchronized (this) {
            if (!open || next == unit) return;
            synchronized (input) {
                double by = next / unit;
                cursorX *= by;
                cursorY *= by;
                unit = next;
                viewUnit = Math.max(next, fullUnit);
                Node scene = screen.scene();
                if (scene != null) sceneSetup(scene);
                if (!away) {
                    cursor(out, 0);
                    if (scene != null && screen.parallax() <= 0) scene(out, 0);
                }
            }
            if (!away) {
                sendBackdrop(out);
            }
            rescaled = true;
            resync = true;
        }
        wire.send(out);
        redraw();
    }

    /**
     * The hovered box's {@link Node#tooltip}, laid out beside it as an item tooltip is: the first line its name, the
     * rest one left-aligned block in small text (one text display, so every line starts at the same x on every
     * client), at most {@value #TIP_WIDTH} pixels wide. To the box's right, or its left when that is out of the
     * layout area, kept inside it top to bottom. Above every page and dialog. Holds the lock.
     */
    private List<Layout.Placed> tooltip() {
        Layout.Placed box = hoveredBox;
        if (box == null || box.node().tooltip() == null || box.node().tooltip().isEmpty()) return List.of();
        Node panel = tipPanel(box.node().tooltip());
        double[] size = Layout.measure(panel);
        double x = box.x() + box.w() + 4;
        if (x + size[0] > screen.width()) x = box.x() - 4 - size[0];
        x = Math.max(2, Math.min(x, screen.width() - size[0] - 2));
        double y = Math.max(2, Math.min(box.y(), screen.height() - size[1] - 2));
        List<Layout.Placed> out = new ArrayList<>();
        for (Layout.Placed part : Layout.lay(panel, size[0], size[1])) {
            out.add(part.moved("~tip" + part.key(), x, y, TIP_DEPTH, 0));
        }
        return out;
    }

    /** A tooltip's panel: the name, then the rest as one block, blank lines at its ends left out. */
    static Node tipPanel(List<net.kyori.adventure.text.Component> lines) {
        Node panel = Node.column().align(Node.Align.START).padding(4, 5).gap(2).background(TIP_BACKGROUND)
                .outline("{primary}", 1).enter(Motion.Tween.of(Motion.Transform.scale(0.96), 2, Motion.Ease.OUT));
        panel.add(Node.text(lines.get(0)).scale(TIP_NAME).wrap(TIP_WIDTH));
        int from = 1;
        int to = lines.size();
        while (from < to && blank(lines.get(from))) from++;
        while (to > from && blank(lines.get(to - 1))) to--;
        if (from < to) {
            panel.add(Node.text(net.kyori.adventure.text.Component.join(
                    net.kyori.adventure.text.JoinConfiguration.newlines(), lines.subList(from, to)))
                    .scale(TIP_LORE).wrap(TIP_WIDTH));
        }
        return panel;
    }

    private static boolean blank(net.kyori.adventure.text.Component line) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(line).isBlank();
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

    private final Map<String, net.exylia.lib.screen.ScreenScene> scenes = new java.util.concurrent.ConcurrentHashMap<>();
    private final net.exylia.lib.screen.ScreenScene.Host sceneHost = new net.exylia.lib.screen.ScreenScene.Host() {
        @Override
        public void redraw() {
            LiveScreen.this.redraw();
        }

        @Override
        public void later(int ticks, Runnable step) {
            tasks.runAtEntityLater(player, Math.max(1, ticks), () -> {
                if (open()) guarded(step);
            });
        }

        @Override
        public boolean reduced() {
            return LiveScreen.this.reduced();
        }

        @Override
        public boolean spare() {
            synchronized (LiveScreen.this) {
                return motionAllowed();
            }
        }
    };

    @Override
    public net.exylia.lib.screen.@NotNull ScreenScene scene(@NotNull String id) {
        return scenes.computeIfAbsent(id, name -> new net.exylia.lib.screen.ScreenScene(name, sceneHost));
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

    /** The toast, small at the bottom centre over everything, so it never hides the screen's title or cards. Holds the lock. */
    private void addToasts(List<Layout.Placed> laid) {
        for (Toast t : toasts) {
            Node note = Node.text(t.text()).scale(TOAST_SCALE).wrap(screen.width() - 80).padding(3, 8).background(TOAST_BACKGROUND).outline(tone(t.tone()), 1)
                    .enter(Motion.Tween.of(Motion.Transform.translate(0, 6), 5, Motion.Ease.OUT))
                    .leave(Motion.Tween.of(new Motion.Transform(0, 4, 0.96), 3, Motion.Ease.IN));
            if (t.links()) {
                // The chat cannot be clicked while a screen is up: the toast offers the line's links instead.
                Component line = t.text();
                note.hover(TOAST_HOVER).tooltip(net.exylia.lib.text.Phrases.tr("{letters}Click to open its links"))
                        .onClick(event -> net.exylia.lib.input.Links.show(player, line));
            }
            double[] size = Layout.measure(note);
            laid.add(new Layout.Placed(note, "~toast" + t.seq(), (screen.width() - size[0]) / 2, screen.height() - size[1] - TOAST_MARGIN, size[0], size[1],
                    Layout.OVERLAY + 20));
        }
    }

    /** The four sides of a frame {@code w} by {@code h} around {@code x, y}, each {@code [x, y, w, h]}. */
    static double[][] edges(double x, double y, double w, double h, double thick) {
        double across = (w - thick) / 2, up = (h - thick) / 2, inner = h - 2 * thick;
        return new double[][]{{x, y + up, w, thick}, {x, y - up, w, thick}, {x - across, y, thick, inner}, {x + across, y, thick, inner}};
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

    /** The tree with a dialog over it: the last overlay is the one drawn. */
    private static Node over(Node root, Node dialog) {
        if (root.kind() == Node.Kind.ROW || root.kind() == Node.Kind.COLUMN) return root.add(dialog);
        return Node.column(root).add(dialog);
    }

    /** The tree with the open confirm dialog over it, the last overlay, so it is the one drawn. */
    private Node withConfirm(Node root, Confirm ask) {
        String fill = ask.dangerous() ? "{error}" : "{primary}";
        Node yes = Node.text(net.exylia.lib.text.Text.of("{letters}" + (ask.yes() == null ? phrase("Confirm") : ask.yes())).build())
                .padding(4, 12).background(fill).hover(ask.dangerous() ? "{error_light}" : "{secondary}")
                .active(null, Motion.Transform.scale(0.95)).id("~confirm.yes").assist(!ask.dangerous()).onClick(event -> answer(true));
        Node no = Node.text(net.exylia.lib.text.Text.of("{letters}" + (ask.no() == null ? phrase("Cancel") : ask.no())).build())
                .padding(4, 12).background("{letters_black}20").hover("{primary}50")
                .active(null, Motion.Transform.scale(0.95)).id("~confirm.no").onClick(event -> answer(false));
        Node dialog = Node.column(Node.text(ask.question()).wrap(200).textAlign(Node.Align.CENTER),
                        Node.row(no, yes).gap(6))
                .id("~confirm").padding(12, 16).gap(10).align(Node.Align.CENTER).background("{primary}20")
                .outline(ask.dangerous() ? "{error}80" : "{primary}60", 1).overlay(true).onDismiss(session -> answer(false))
                .enter(Motion.Tween.of(Motion.Transform.scale(0.9), 5, Motion.Ease.BACK))
                .leave(Motion.Tween.of(Motion.Transform.scale(0.94), 3, Motion.Ease.IN));
        return over(root, dialog);
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
    private record Xf(double ox, double oy, double tx, double ty, double s, double sq, Xf outer) {
        Xf(double ox, double oy, double tx, double ty, double s, Xf outer) {
            this(ox, oy, tx, ty, s, 1, outer);
        }

        double ax(double x) {
            double v = ox + (x - ox) * s * sq + tx;
            return outer == null ? v : outer.ax(v);
        }

        double ay(double y) {
            double v = oy + (y - oy) * s + ty;
            return outer == null ? v : outer.ay(v);
        }

        double as() {
            return outer == null ? s : s * outer.as();
        }

        /** How narrowed across, on top of {@link #as}. */
        double asq() {
            return outer == null ? sq : sq * outer.asq();
        }
    }

    /** What a box draws with: its transform chain, its transition, whether it animates, whether it is decoration. */
    private record Cx(Xf xf, Motion.Transition transition, boolean animated, boolean low) {
    }

    /** One box of a frame, kept to the next: where its arrivals and departures are measured from. */
    private record Box(String parent, Node node, double cx, double cy, int depth) {
    }

    /** What one frame wants on the client. */
    private record Frame(@Nullable RectLook hole, Map<String, Object> wanted, List<String> order, Map<String, String> boxOf,
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
            if (!own.none()) xf = new Xf(centreX(b), centreY(b), own.x() * unit, -own.y() * unit, own.scale(), own.squeeze(), xf);
            Motion.Transition transition = n.transition() != null ? n.transition() : parent == null ? null : parent.transition();
            at[i] = new Cx(xf, transition, animated, n.decoration() || parent != null && parent.low());
            out.put(b, at[i]);
            String parentKey = b.parent() >= 0 && b.parent() < i ? list.get(b.parent()).key() : null;
            // Two boxes may share a key (the same id written twice); the first placed keeps it, so a walk up the
            // parents always reaches an earlier box and ends.
            boxes.putIfAbsent(b.key(), new Box(parentKey, n, centreX(b), centreY(b), b.depth()));
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
        RectLook hole = null;
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
                    double wide = (box.w() + grow) * unit, tall = (box.h() + grow) * unit;
                    if (argb == 0 && node.preview() == null && node.backgroundImage() == null) {
                        // The outline is a rect behind the fill: with no fill to cover its middle, four edges instead.
                        double[][] edges = edges(cxu, cyu, wide, tall, node.outlineWidth() * unit);
                        for (int i = 0; i < edges.length; i++) {
                            double[] e = edges[i];
                            wanted.put(key + i, xf(new RectLook(e[0], e[1], e[2], e[3], layer - 1, line), xf));
                            mine.add(key + i);
                        }
                    } else {
                        wanted.put(key, xf(new RectLook(cxu, cyu, wide, tall, layer - 1, line), xf));
                        mine.add(key);
                    }
                }
                if (node.preview() != null) {
                    // A window: no fill of its own, its colour is the stage's back wall.
                    hole = (RectLook) xf(new RectLook(cxu, cyu, box.w() * unit, box.h() * unit, layer, argb), xf);
                } else if (argb != 0 && box.w() > 0 && box.h() > 0) {
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
                    Pictures.Picture sprite = node.solid() ? null : VanillaSprites.of(node.item(), tasks, this::redraw);
                    net.kyori.adventure.text.Component glyph = sprite != null && sprite != VanillaSprites.LOADING
                            ? spriteGlyph(node.item()) : null;
                    if (node.solid()) {
                        // <item3d>: the model itself, turning a step every second like a body.
                        boolean flat = VanillaSprites.flat(node.item().getType());
                        double angle = turnAngle(node.spin(), flat, System.currentTimeMillis());
                        turns |= node.spin() != 0;
                        double size = side * unit * grow;
                        wanted.put(key, xf(new ItemLook(ix, iy, size, turnLayer(top, size * (flat ? 0.5 : 0.71)),
                                node.item(), true, angle, !flat), xf));
                        mine.add(key);
                    } else if (sprite == VanillaSprites.LOADING) {
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
                    int bodyLayer = turnLayer(top, BODY_RADIUS * cell * unit);
                    int gapX = ScreenPackets.IMAGE_ADVANCE - ScreenPackets.IMAGE_GLYPH;
                    int gapY = ScreenPackets.IMAGE_LINE - ScreenPackets.IMAGE_GLYPH;
                    for (int f = 0; f < model.size(); f++) {
                        Pictures.Face face = model.get(f);
                        Component text = Pictures.glyphs(face.picture().argb(), face.picture().width(),
                                face.picture().height(), 0);
                        for (int copy = 0; copy < 4; copy++) {
                            String key = box.key() + "#tt" + f + "." + copy;
                            wanted.put(key, xf(new TurnLook(mx, my, cell * unit, bodyLayer, text, face.picture().height(),
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
        if (hole != null) wanted = cut(wanted, hole, boxOf, transitions, animatedLooks);
        return new Frame(hole, wanted, new ArrayList<>(wanted.keySet()), boxOf, boxes, transitions, animatedLooks, turns, animates);
    }

    /**
     * Every rect behind the window, in pieces around it, and the backdrop's. A dialog or a tooltip over the window
     * stands nearer the eye than it, and stays whole.
     */
    private Map<String, Object> cut(Map<String, Object> wanted, RectLook hole, Map<String, String> boxOf,
                                    Map<String, Motion.Transition> transitions, Set<String> animated) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : wanted.entrySet()) {
            String key = entry.getKey();
            if (!(entry.getValue() instanceof RectLook rect) || rect.layer() >= hole.layer() || !overlaps(rect, hole)) {
                out.put(key, entry.getValue());
                continue;
            }
            List<RectLook> pieces = around(rect, hole);
            for (int i = 0; i < pieces.size(); i++) {
                String piece = key + "^" + i;
                out.put(piece, pieces.get(i));
                if (boxOf.containsKey(key)) boxOf.put(piece, boxOf.get(key));
                if (transitions.containsKey(key)) transitions.put(piece, transitions.get(key));
                if (animated.contains(key)) animated.add(piece);
            }
        }
        List<RectLook> back = backdropLooks();
        for (int b = 0; b < back.size(); b++) {
            if (!overlaps(back.get(b), hole)) continue;
            List<RectLook> pieces = around(back.get(b), hole);
            for (int i = 0; i < pieces.size(); i++) out.put("~back" + b + "^" + i, pieces.get(i));
        }
        return out;
    }

    private static boolean overlaps(RectLook a, RectLook b) {
        return Math.abs(a.x() - b.x()) * 2 < a.w() + b.w() && Math.abs(a.y() - b.y()) * 2 < a.h() + b.h();
    }

    /** What is left of {@code rect} with {@code hole} taken out. */
    private static List<RectLook> around(RectLook rect, RectLook hole) {
        List<RectLook> out = new ArrayList<>(4);
        for (double[] piece : ScreenMath.around(rect.x(), rect.y(), rect.w(), rect.h(), hole.x(), hole.y(), hole.w(), hole.h())) {
            out.add(new RectLook(piece[0], piece[1], piece[2], piece[3], rect.layer(), rect.argb()));
        }
        return out;
    }

    /** The backdrop's displays: a band a window cuts is drawn by its pieces instead, so it shrinks away. Holds the lock. */
    private void sendBackdrop(List<PacketWrapper<?>> out) {
        List<RectLook> back = backdropLooks();
        int[] backIds = backdropIds;
        for (int i = 0; i < Math.min(backIds.length, back.size()); i++) {
            RectLook band = back.get(i);
            if (lastHole != null && overlaps(band, lastHole)) band = new RectLook(band.x(), band.y(), 1e-3, 1e-3, band.layer(), band.argb());
            out.add(rect(backIds[i], band, 0));
        }
    }

    /** Opens, moves or closes the stage the laid-out window looks at, and hands it what to play. Holds the lock. */
    private void stage(List<Layout.Placed> core) {
        Layout.Placed window = null;
        for (Layout.Placed box : core) {
            if (box.node().preview() != null) {
                window = box;
                break;
            }
        }
        if (window == null) {
            if (stage != null) {
                stage.close();
                stage = null;
            }
            return;
        }
        double x = centreX(window), y = centreY(window), w = window.w() * unit, h = window.h() * unit;
        if (stage != null && !stage.framedBy(x, y, w, h)) {
            stage.close();
            stage = null;
        }
        if (stage == null) {
            int wall = ScreenMath.argb(window.node().background(), 0);
            wall = wall == 0 ? backdropBase() : ScreenMath.over(wall, backdropBase());
            homePitch = PreviewStage.PITCH;
            boolean held = cameraId != 0;
            tipped = !held;
            stage = new PreviewStage(plugin, player, tasks, wire, ids.getAsInt(), origin, held ? cameraYaw : homeYaw, homePitch, DISTANCE, x, y, w, h,
                    wall, window.node().vanish(), () -> WINDOW_BUDGET - drawnCount, this::settle);
        }
        stage.want(window.node().preview());
    }

    /** Takes the stage down, on the viewer's thread when it can be reached. Safe from any thread. */
    private void closeStage() {
        PreviewStage going;
        synchronized (this) {
            going = stage;
            stage = null;
            windowed = false;
        }
        if (going == null) return;
        if (!player.isOnline() || !plugin.isEnabled() || org.bukkit.Bukkit.isOwnedByCurrentRegion(player)) going.close();
        else tasks.runAtEntity(player, going::close);
    }

    /**
     * Off the vehicle, the rotation the mouse left back to where the eyes looked when the screen opened, and the view back
     * in the client's own eyes, unless a cinematic has taken it since. A client must be told before the entity it looks
     * out of goes: it would go on drawing from where that stood. {@code ids} takes both entities to destroy.
     */
    private void giveBackView(List<Integer> ids) {
        int camera = cameraId;
        if (camera == 0) return;
        cameraId = 0;
        ids.add(camera);
        ids.add(vehicleId);
        wire.send(List.of(ScreenPackets.riders(vehicleId),
                ScreenPackets.recentre(ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE),
                        (float) ScreenMath.turn(homeYaw, lastYaw), lastPitch - openPitch)));
        if (!net.exylia.lib.packet.internal.PacketRuntime.cameraHeld(player.getUniqueId())) {
            ScreenPackets.look(player, player.getEntityId());
        }
    }

    /** The eyes back where they looked when the screen opened, if a window tipped them. */
    private void giveBackPitch() {
        if (!tipped) return;
        tipped = false;
        wire.send(List.of(ScreenPackets.recentre(ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE),
                (float) ScreenMath.turn(homeYaw, lastYaw), lastPitch - openPitch)));
    }

    /** Whether a window is on screen. Safe from any thread. */
    boolean windowed() {
        return windowed;
    }

    /** Brings a camera the mouse has left at rest back to where the stage was placed from. */
    private void settle() {
        synchronized (input) {
            if (cameraId != 0 || away || recentreId != 0 || !windowed || System.currentTimeMillis() - lastTurn < SETTLE_MILLIS) return;
            float off = (float) ScreenMath.turn(homeYaw, lastYaw);
            float down = lastPitch - homePitch;
            if (Math.abs(off) < 0.02f && Math.abs(down) < 0.02f) return;
            recentreId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
            recentreBy = down;
            recentreYaw = off;
            wire.send(List.of(ScreenPackets.recentre(recentreId, recentreYaw, recentreBy)));
        }
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
        drawnCount = Math.min(wanted.size(), BUDGET);
        if (wanted.size() > BUDGET) {
            // Past every level of detail: the tail is left out (the dialog is drawn first, so it stays).
            if (!overBudget) {
                overBudget = true;
                plugin.getLogger().warning("A screen drew more than " + BUDGET + " displays at its lowest detail; the rest are left out.");
            }
            for (String key : new ArrayList<>(order.subList(BUDGET, order.size()))) wanted.remove(key);
            order = new ArrayList<>(order.subList(0, BUDGET));
        }
        if (!Objects.equals(frame.hole(), lastHole)) {
            lastHole = frame.hole();
            windowed = lastHole != null;
            List<PacketWrapper<?>> back = new ArrayList<>();
            sendBackdrop(back);
            wire.send(back);
        }
        boolean reduced = reduced();
        boolean resend = rescaled;
        rescaled = false;
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
                            away.transform().scale(), away.transform().squeeze(), null);
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
            if (current != null && !resend && current.look().equals(look)) continue;
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
                // A burst's shard: its spawn, first look and removal are motion too (its flight counts as a glide).
                if (key.contains(ScreenScene.SHARD)) motionPackets += 3;
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
            } else if (look instanceof TurnLook || look instanceof ItemLook it && it.solid()
                    && it.angle() != ((ItemLook) current.look()).angle() && sameGeometry(look, current.look())) {
                out.add(look(id, look, GLIDE));
            } else if (animating && frame.animated().contains(key)) {
                out.add(look(id, look, animStep));
                motionPackets++;
            } else {
                Motion.Transition transition = frame.transitions().get(key);
                if (transition == null || reduced) {
                    transition = new Motion.Transition(Math.min(BOX_GLIDE, transition == null ? BOX_GLIDE : transition.ticks()),
                            Motion.Ease.LINEAR, false);
                } else if (!eased) {
                    transition = new Motion.Transition(transition.ticks(), Motion.Ease.LINEAR, transition.colours());
                }
                glide(id, current.look(), look, transition, 0, out, later);
                motionPackets++;
            }
            drawn.put(key, new Drawn(id, look, box));
        }
        shown = true;
        turning = frame.turns();
        boolean holds = false;
        for (Object look : wanted.values()) holds |= look instanceof ItemLook it && it.solid();
        cone = holds || windowed;
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
                tween.transform().scale(), tween.transform().squeeze(), null));
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

    void clock(java.util.function.LongSupplier clock) {
        this.clock = clock;
    }

    /** Whether motion may still spend packets this second: past the ceiling, eased glides go straight. */
    private boolean motionAllowed() {
        long second = clock.getAsLong() / 1000;
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
        double sq = f.asq();
        return switch (look) {
            case RectLook r -> new RectLook(f.ax(r.x()), f.ay(r.y()), r.w() * s * sq, r.h() * s, r.layer(), r.argb());
            case ItemLook it -> new ItemLook(f.ax(it.x()), f.ay(it.y()), it.size() * s, it.layer(), it.item(), it.solid(),
                    it.angle(), it.leans(), it.squeeze() * sq);
            case ImageLook im -> new ImageLook(f.ax(im.x()), f.ay(im.y()), im.cell() * s, im.layer(), im.text(), im.rows(),
                    im.dx(), im.dy(), im.squeeze() * sq);
            case TurnLook t -> new TurnLook(f.ax(t.x()), f.ay(t.y()), t.cell() * s, t.layer(), t.text(), t.rows(), t.lx(),
                    t.ly(), t.lz(), t.yaw(), t.turn(), t.dx(), t.dy(), t.size());
            default -> {
                TextLook t = (TextLook) look;
                yield new TextLook(f.ax(t.x()), f.ay(t.y()), t.scale() * s, t.layer(), t.text(), t.lines(), t.flags(),
                        t.squeeze() * sq);
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
                yield new ItemLook(mix(q.x(), it.x(), f), mix(q.y(), it.y(), f), mix(q.size(), it.size(), f), it.layer(), it.item(),
                        it.solid(), it.angle(), it.leans(), mix(q.squeeze(), it.squeeze(), f));
            }
            case ImageLook im -> {
                ImageLook q = (ImageLook) a;
                yield new ImageLook(mix(q.x(), im.x(), f), mix(q.y(), im.y(), f), mix(q.cell(), im.cell(), f), im.layer(),
                        im.text(), im.rows(), im.dx(), im.dy(), mix(q.squeeze(), im.squeeze(), f));
            }
            case TextLook t -> {
                TextLook q = (TextLook) a;
                yield new TextLook(mix(q.x(), t.x(), f), mix(q.y(), t.y(), f), mix(q.scale(), t.scale(), f), t.layer(),
                        t.text(), t.lines(), t.flags(), mix(q.squeeze(), t.squeeze(), f));
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
                yield it.x() == q.x() && it.y() == q.y() && it.size() == q.size() && it.squeeze() == q.squeeze();
            }
            case ImageLook im -> {
                ImageLook q = (ImageLook) b;
                yield im.x() == q.x() && im.y() == q.y() && im.cell() == q.cell() && im.squeeze() == q.squeeze();
            }
            case TextLook t -> {
                TextLook q = (TextLook) b;
                yield t.x() == q.x() && t.y() == q.y() && t.scale() == q.scale() && t.squeeze() == q.squeeze();
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
        if (look.solid()) {
            return ScreenPackets.item3d(id, look.x() * k, look.y() * k, -DEPTH + look.layer() * LAYER, look.size() * k,
                    look.item(), glide == 0 ? 0 : TURN_TICKS, look.angle(), look.leans());
        }
        return ScreenPackets.item(id, look.x() * k, look.y() * k, -DEPTH + look.layer() * LAYER, look.size() * k,
                look.squeeze(), look.item(), glide);
    }

    private PacketWrapper<?> image(int id, ImageLook im, int glide) {
        double k = drawn(im.layer());
        double fx = im.cell() * im.squeeze() * k / ScreenPackets.IMAGE_ADVANCE;
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
                -DEPTH + t.layer() * LAYER + rz * ScreenPackets.TURN_DEPTH * t.cell() * k, fx, fy, t.rows(), t.text(),
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
        return ScreenPackets.text(id, t.x() * k, t.y() * k, -DEPTH + t.layer() * LAYER, scale, t.squeeze(), t.text(), glide,
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
        double vw = Screen.WIDTH * VIEW * viewUnit, vh = Screen.HEIGHT * VIEW * viewUnit;
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
        double vw = Screen.WIDTH * VIEW * viewUnit + drift, vh = Screen.HEIGHT * VIEW * viewUnit + drift;
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
        arrow(out, cursorX, cursorY, glide);
        if (screen.parallax() > 0) scene(out, glide);
    }

    /** The arrow drawn at {@code x}, {@code y}, which is not where the pointer is in prediction mode. Holds {@link #input}. */
    private void arrow(List<PacketWrapper<?>> out, double x, double y, int glide) {
        double pixel = ARROW_PIXEL * unit / (SPAN / Screen.WIDTH);
        int w = ARROW[0].length(), h = ARROW.length;
        Component arrow = Pictures.glyphs(ARROW_ARGB, w, h, 0);
        // The tip is the pointer's top-left pixel, where the cursor is.
        double cx = x + w * pixel / 2, cy = y - h * pixel / 2;
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
            // A client that rides something repeats its rotation every tick.
            if (yaw == lastYaw && pitch == lastPitch) {
                // A client that repeats itself says at once that the mouse stopped; any other leaves it to leadRest.
                if (leadWatched.get()) {
                    lead.rest();
                    List<PacketWrapper<?>> back = new ArrayList<>();
                    cursor(back, 1);
                    wire.send(back);
                }
                return;
            }
            double dx = ScreenMath.turn(lastYaw, yaw);
            double dy = pitch - lastPitch;
            // A fast move carries further: precise when slow, quick across the screen.
            double accel = ScreenSettings.get().cursorAcceleration();
            double boost = accel <= 0 ? 1 : Math.min(3, 1 + accel * Math.hypot(dx, dy) / 4);
            double perDegree = DEGREE * unit * (screen.wheel() ? 1.255 : 1) * ScreenRuntime.sensitivity(player.getUniqueId()) * boost;
            double halfW = (screen.width() / 2 + GUTTER_X) * unit;
            double halfH = (screen.height() / 2 + GUTTER_Y) * unit;
            double moveX = dx * perDegree, moveY = dy * perDegree;
            if (ScreenRuntime.cursorAssist(player.getUniqueId())) {
                double[] assisted = assisted(moveX / unit, moveY / unit);
                moveX = assisted[0] * unit;
                moveY = assisted[1] * unit;
            }
            cursorX = ScreenMath.clamp(cursorX + moveX, -halfW, halfW);
            cursorY = ScreenMath.clamp(cursorY - moveY, -halfH, halfH);
            lastYaw = yaw;
            lastPitch = pitch;
            lastTurn = System.currentTimeMillis();
            List<PacketWrapper<?>> out = new ArrayList<>();
            switch (ScreenRuntime.cursorMode(player.getUniqueId())) {
                case RAW -> cursor(out, 0);
                case PREDICTION -> {
                    double[] ahead = lead.moved(cursorX, cursorY, lastTurn, LEAD_BOUND * unit);
                    arrow(out, ScreenMath.clamp(ahead[0], -halfW, halfW), ScreenMath.clamp(ahead[1], -halfH, halfH), 1);
                    if (screen.parallax() > 0) scene(out, 1);
                    if (leadWatched.compareAndSet(false, true)) tasks.runAtEntityLater(player, 2, this::leadRest);
                }
                default -> cursor(out, 1);
            }
            moved = pointerMoved();
            // The client turns the camera, the server turns it back: past RECENTRE_PITCH always, and with a 3D item on
            // screen past CONE either way, so the world's fixed lights shade the item the same. The screen faces the
            // eye whatever it looks at, so the snap is not seen.
            float off = (float) ScreenMath.turn(homeYaw, yaw);
            int camera = cameraId;
            boolean held = cone && camera == 0;
            float limit = windowed && camera == 0 ? WINDOW_CONE : CONE;
            float down = windowed && camera == 0 ? pitch - homePitch : pitch;
            if (recentreId == 0 && (Math.abs(down) > (held ? limit : RECENTRE_PITCH) || held && Math.abs(off) > limit)) {
                recentreId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
                recentreBy = down;
                recentreYaw = held ? off : 0;
                // A client on a vehicle ignores a turn: off it and back on within the bundle, its view elsewhere.
                if (camera != 0) out.add(ScreenPackets.riders(vehicleId));
                out.add(ScreenPackets.recentre(recentreId, recentreYaw, down));
                if (camera != 0) {
                    out.add(ScreenPackets.riders(vehicleId, player.getEntityId()));
                    out.add(ScreenPackets.blankBar());
                }
            }
            wire.send(out);
        }
        // The hover is drawn on the viewer's thread, which owns the frame; a burst of moves asks once.
        if (moved && hoverQueued.compareAndSet(false, true)) tasks.runAtEntity(player, this::hoverMoved);
    }

    /**
     * On the viewer's thread, while prediction draws ahead: a client sends nothing once the mouse stops, so this puts
     * the arrow back on the pointer when no move came for a while.
     */
    private void leadRest() {
        synchronized (input) {
            if (open && !away && System.currentTimeMillis() - lastTurn < LEAD_REST_MILLIS) {
                tasks.runAtEntityLater(player, 1, this::leadRest);
                return;
            }
            leadWatched.set(false);
            lead.rest();
            if (!open || away) return;
            List<PacketWrapper<?>> out = new ArrayList<>();
            cursor(out, 1);
            wire.send(out);
        }
    }

    /** The move cursor assist makes of the mouse's, in layout pixels. Holds {@link #input}. */
    private double[] assisted(double dx, double dy) {
        View now = view;
        double x = px(), y = py();
        double[] raw = {dx, dy};
        if (assistBarred(now, x, y)) return raw;
        Layout.Placed over = pick(now.targets(), x, y, pointedBox);
        if (over != null && !helped(now, over)) over = null;
        List<Layout.Placed> near = new ArrayList<>();
        if (over == null) {
            for (Layout.Placed box : now.targets()) {
                if (box.holds(x, y, CursorAssist.MAGNET_RANGE) && helped(now, box)) near.add(box);
            }
        }
        double[] move = CursorAssist.move(x, y, dx, dy, over, near);
        // A bend never lands the pointer on what assist must stay off.
        return over == null && assistBarred(now, x + move[0], y + move[1]) ? raw : move;
    }

    /** Whether the point lies on a box marked to keep assist away, a dialog over one included. */
    private static boolean assistBarred(View view, double x, double y) {
        for (Layout.Placed box : view.placed()) {
            if (!box.node().assist() && box.holds(x, y)) return true;
        }
        return false;
    }

    /** Whether assist may act for this box: a button, not a background, away from anything marked to keep it off. */
    private boolean helped(View view, Layout.Placed box) {
        return box.node().onClick() != null && !box.key().equals("~dim")
                && box.w() * box.h() <= screen.width() * screen.height() * CursorAssist.LARGEST_SHARE
                && !assistBarred(view, box.x() + box.w() / 2, box.y() + box.h() / 2);
    }

    /** The box a click with nothing clickable under it goes to by cursor assist's tolerance, {@code null} for none. */
    private Layout.Placed forgiven(List<Layout.Placed> targets, double x, double y) {
        String key;
        long at;
        synchronized (input) {
            key = leftKey;
            at = leftAt;
        }
        if (key == null || !ScreenRuntime.cursorAssist(player.getUniqueId())) return null;
        View now = view;
        for (Layout.Placed box : targets) {
            if (!box.key().equals(key)) continue;
            return helped(now, box) && CursorAssist.forgives(box, x, y, System.currentTimeMillis() - at) ? box : null;
        }
        return null;
    }

    /** Whether the box under the cursor changed; plays the hover tick. Holds {@link #input}. */
    private boolean pointerMoved() {
        Layout.Placed under = pick(view.targets(), px(), py(), pointedBox);
        String now = under == null ? null : under.key();
        if (Objects.equals(now, pointed)) return false;
        if (under != null && under.node().onClick() != null && !now.equals("~dim")) {
            leftKey = null;
        } else if (pointedBox != null && pointedBox.node().onClick() != null && !pointedBox.key().equals("~dim")) {
            leftKey = pointedBox.key();
            leftAt = System.currentTimeMillis();
        }
        pointed = now;
        pointedBox = under;
        if (now != null && under.node().hover() != null) sound("ui.button.click", 0.15f, 2.0f);
        return true;
    }

    boolean confirmed(int teleportId) {
        synchronized (input) {
            if (recentreId == 0 || teleportId != recentreId) return false;
            lastPitch -= recentreBy;
            lastYaw -= recentreYaw;
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
        Layout.Placed lit;
        synchronized (input) {
            shift = sprinting;
            lit = pointedBox;
        }
        // What is lit is what gets clicked, near the edge it stays lit past as well.
        Layout.Placed shown = pick(targets, at[0], at[1], lit);
        Layout.Placed box = shown != null && (shown.node().onClick() != null || shift && shown.node().onShiftClick() != null)
                ? shown : Layout.under(targets, at[0], at[1],
                node -> node.onClick() != null || shift && node.onShiftClick() != null);
        // Just off the box that was lit, with no other to go to: the click was meant for it.
        if (box == null || box.key().equals("~dim")) {
            Layout.Placed kept = forgiven(targets, at[0], at[1]);
            if (kept != null) box = kept;
        }
        synchronized (input) {
            leftKey = null;
        }
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
        lastPress = System.currentTimeMillis();
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
        boolean sprint;
        synchronized (input) {
            sprint = sprinting;
        }
        if ((pressed & 16) != 0 && sprint) {
            sound("ui.button.click", 0.35f, 1.4f);
            tasks.runAtEntity(player, () -> {
                if (open() && confirm == null) settings(!settingsOpen);
            });
            return;
        }
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
        synchronized (input) {
            pressed = down && !shifting;
            shifting = down;
        }
        if (!pressed || !open || away) return;
        // Shift closes the screen, from anywhere: going back is the Back button's (or the back action's) job.
        tasks.runAtEntity(player, () -> {
            if (open()) close();
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

    /** On a network thread. A held chat line that answers the click just made also shows on the screen. */
    void answer(Component line) {
        if (System.currentTimeMillis() - lastPress > ANSWER_MILLIS || !open() || !plugin.isEnabled()) return;
        tasks.runAtEntity(player, () -> {
            // The line is the same news as a result on the frame, and would sit on top of it; it still arrives on close.
            if (!"".equals(String.valueOf(state.getOrDefault(net.exylia.lib.screen.GameFrame.RESULT, "")))) return;
            toastLine(line, null);
        });
    }

    @Override
    public void toast(@NotNull String text) {
        toast(null, text);
    }

    @Override
    public void toast(@Nullable String tone, @NotNull String text) {
        toastLine(net.exylia.lib.text.Text.of(text).build(), tone);
    }

    private void toastLine(Component text, @Nullable String tone) {
        boolean links = net.exylia.lib.input.Links.showable(player, text);
        long millis = links ? LINK_TOAST_MILLIS : TOAST_MILLIS;
        synchronized (this) {
            toasts.add(new Toast(text, tone, System.currentTimeMillis() + millis, ++toastSeq, links));
            while (toasts.size() > MAX_TOASTS) toasts.remove(0);
            toastVersion++;
        }
        if ("success".equals(tone)) sound("entity.experience_orb.pickup", 0.3f, 1.6f);
        else if ("error".equals(tone)) sound("entity.villager.no", 0.35f, 1f);
        redraw();
        if (open() && plugin.isEnabled()) tasks.runAtEntityLater(player, millis / 50 + 1, this::redraw);
    }

    /** Runs a handler on the viewer's thread, then renders once, taking along the redraws its set() calls asked for. */
    private void handle(Consumer<ScreenEvent> handler, ScreenEvent event) {
        tasks.runAtEntity(player, () -> {
            if (!open()) return;
            Consumer<ScreenSession> input = screen.onInput();
            if (input != null) guarded(() -> input.accept(this));
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
