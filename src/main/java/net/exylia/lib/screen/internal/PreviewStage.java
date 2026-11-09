package net.exylia.lib.screen.internal;

import com.github.retrooper.packetevents.util.Vector3d;
import net.exylia.lib.display.DisplayHandle;
import net.exylia.lib.display.DisplayModel;
import net.exylia.lib.display.DisplayMotion;
import net.exylia.lib.display.Displays;
import net.exylia.lib.display.PluginDisplays;
import net.exylia.lib.packet.FakeBlocks;
import net.exylia.lib.packet.Packets;
import net.exylia.lib.spatial.SpatialLayout;
import net.exylia.lib.spatial.SpatialPreview;
import net.exylia.lib.spatial.internal.PreviewPlayer;
import net.exylia.lib.stage.StageLook;
import net.exylia.lib.stage.StagePlane;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.TaskScheduler;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a screen's window looks at: a stage in the real world behind the panel, private to the viewer. The preview
 * plays there as it does on a spatial menu's pedestal, so an effect needs nothing of its own to be seen: the same lines,
 * bodies, displays and particles, sent to this viewer alone.
 *
 * <p>Every block between the eyes and the back wall inside the window's view is kept as air for them, a display wall
 * behind the stage hides what lies past it, and two fake light blocks keep it lit at night or underground. All of it
 * goes on {@link #close}: nothing was ever in the world. Particles already drawn belong to the client and fade by
 * themselves within a second or so.
 *
 * <p>Internal: free to change without notice.
 */
final class PreviewStage extends PreviewPlayer {

    /** How many blocks tall the view through the window is at the stage: a body, and room over it. */
    private static final double FRAME_TALL = 3.3;
    /** How far behind the stage the back wall stands: room for what an effect throws backwards. */
    private static final double BEHIND = 3.5;
    /** The nearest and furthest the stage stands: a very large screen size must not bring it into the eyes. */
    private static final double NEAREST = 2.5, FURTHEST = 12;
    /** How far above the window's bottom edge the floor is, as a share of the frame. */
    private static final double FLOOR_MARGIN = 0.1;
    /** How many degrees down the eyes look at the stage, so its floor shows. */
    private static final double LOOK_DOWN = 9;
    private static final double FLOOR_WIDE = 2.4;
    /** How large a body and an effect play on the stage. */
    private static final double SCALE = 1, EFFECT_SCALE = 0.8;
    private static final double TURN_SPEED = 18;
    /** How long a hover must rest before its preview starts: a sweep of the cursor over a list plays nothing. */
    static final long REST_MILLIS = 180L;
    private static final long POLL_TICKS = 2L;

    private final PluginDisplays displays;
    private final FakeBlocks blocks;
    private final ScreenPackets.Wire wire;
    private final int wallId;
    private final double x, y, w, h;
    private final Runnable settle;
    /** How many displays the screen leaves the stage. */
    private final java.util.function.IntSupplier room;
    private final SpatialLayout.Preview look;
    private final List<DisplayHandle> pieces = new ArrayList<>();
    private final List<Location> kept;
    private final TaskHandle poll;
    private final @Nullable DisplayHandle floor;
    /** What was last asked for, and when it may start; {@code null} once it has. */
    private SpatialPreview asked = SpatialPreview.none();
    private @Nullable SpatialPreview waiting;
    private long startsAt;
    private boolean closed;

    /**
     * @param wallId   an id for the back wall's display
     * @param eyes     the viewer's eyes, where every display of the screen stands
     * @param yaw      where the camera is held facing, {@code pitch} from {@link #pitch}
     * @param distance a ray through the layout's (x, y) runs x / distance across and y / distance up per block ahead
     * @param x        the window's middle across, layout units from the view's middle; {@code y} up, {@code w} and {@code h} its size
     * @param wall     the back wall's colour
     * @param settle   run every poll, to bring the camera back while the mouse rests
     */
    PreviewStage(Plugin plugin, Player viewer, TaskScheduler tasks, ScreenPackets.Wire wire, int wallId, Vector3d eyes, float yaw,
                 float pitch, double distance, double x, double y, double w, double h, int wall, boolean vanish, java.util.function.IntSupplier room, Runnable settle) {
        super(plugin, viewer, plane(viewer.getWorld(), eyes, yaw, pitch, distance, x, y, h),
                StageLook.facing(plane(viewer.getWorld(), eyes, yaw, pitch, distance, x, y, h)));
        this.displays = Displays.of(plugin);
        this.blocks = Packets.of(plugin).fakeBlocks();
        this.wire = wire;
        this.wallId = wallId;
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.settle = settle;
        this.room = room;
        net.exylia.lib.display.internal.DisplayRuntime.limitViewer(viewer.getUniqueId(), room, eyes.y + REHEARSAL_UP / 2);
        double ahead = ahead(distance, h);
        double wide = w / distance * ahead;
        this.look = new SpatialLayout.Preview(List.of(0.0, 0.0, 0.0), TURN_SPEED, false, SCALE, vanish, EFFECT_SCALE,
                List.of("others", "viewer"), "FRONT", false, "", 8, "AUTO", List.of(wide, FRAME_TALL * (1 - FLOOR_MARGIN)), true);
        // The back wall, as far behind as the frame needs and as wide as the window from there.
        double far = ahead + BEHIND;
        double over = 1.2;
        wire.send(List.of(ScreenPackets.spawnDisplay(wallId, eyes),
                ScreenPackets.rect(wallId, x / distance * far, y / distance * far, -far,
                        w / distance * far * over, h / distance * far * over, wall, 0)));
        // The view kept empty, and lit.
        Map<Location, BlockData> fake = clearing(viewer.getWorld(), eyes, yaw, pitch, distance, x, y, w, h, far);
        BlockData light = Material.LIGHT.createBlockData("[level=15]");
        fake.put(plane.point(0, 2.4, 1.2).toBlockLocation(), light);
        fake.put(plane.point(0, 0.7, 1.6).toBlockLocation(), light);
        this.kept = new ArrayList<>(fake.keySet());
        blocks.keep(viewer, fake);
        // The floor the body stands on.
        this.floor = show(DisplayModel.block(Material.POLISHED_BLACKSTONE.createBlockData()).light(15),
                still(rotation, FLOOR_WIDE, 0.1, FLOOR_WIDE), plane.point(0, -0.05, 0));
        this.poll = tasks.runAtEntityTimer(viewer, POLL_TICKS, POLL_TICKS, this::tick);
    }

    /** How far ahead the stage stands for the frame to fill a window this tall. */
    private static double ahead(double distance, double h) {
        return Math.clamp(FRAME_TALL * distance / h, NEAREST, FURTHEST);
    }

    /** The camera's pitch, degrees down, that looks at the stage through a window at {@code y} from a little above. */
    static float pitch(double distance, double y) {
        return (float) Math.clamp(Math.toDegrees(Math.atan2(y, distance)) + LOOK_DOWN, -30, 40);
    }

    /** {forward, right, up} of a camera, each {x, y, z}. */
    private static double[][] axes(float yaw, float pitch) {
        double a = Math.toRadians(yaw), p = Math.toRadians(pitch);
        double sa = Math.sin(a), ca = Math.cos(a), sp = Math.sin(p), cp = Math.cos(p);
        return new double[][]{{-sa * cp, -sp, ca * cp}, {-ca, 0, -sa}, {-sa * sp, cp, ca * sp}};
    }

    /** The stage's plane: where the window's middle looks, the floor at its bottom edge, turned to face the eyes. */
    private static StagePlane plane(World world, Vector3d eyes, float yaw, float pitch, double distance, double x, double y, double h) {
        double[][] axis = axes(yaw, pitch);
        double ahead = ahead(distance, h);
        double[] along = new double[3];
        double[] low = new double[3];
        for (int i = 0; i < 3; i++) {
            along[i] = ahead * (axis[0][i] + axis[1][i] * x / distance + axis[2][i] * y / distance);
            low[i] = ahead * (axis[0][i] + axis[1][i] * x / distance + axis[2][i] * (y - h / 2) / distance);
        }
        double floor = eyes.getY() + low[1] + FRAME_TALL * FLOOR_MARGIN;
        float facing = (float) Math.toDegrees(Math.atan2(-along[0], along[2]));
        return new StagePlane(world, eyes.getX() + along[0], floor, eyes.getZ() + along[2], facing, 1, 1, 1, 1);
    }

    /** Every block that is not air inside the window's view, out to the wall, as air. */
    private static Map<Location, BlockData> clearing(World world, Vector3d eyes, float yaw, float pitch, double distance,
                                                     double x, double y, double w, double h, double far) {
        double[][] axis = axes(yaw, pitch);
        double cx = x / distance, cy = y / distance, hw = w / distance / 2, hh = h / distance / 2;
        int reach = (int) Math.ceil(far * (1 + Math.abs(cx) + hw + Math.abs(cy) + hh)) + 2;
        int ex = (int) Math.floor(eyes.getX()), ey = (int) Math.floor(eyes.getY()), ez = (int) Math.floor(eyes.getZ());
        int minY = Math.max(world.getMinHeight(), ey - reach), maxY = Math.min(world.getMaxHeight() - 1, ey + reach);
        BlockData air = Material.AIR.createBlockData();
        Map<Location, BlockData> out = new HashMap<>();
        for (int bx = ex - reach; bx <= ex + reach; bx++) {
            for (int bz = ez - reach; bz <= ez + reach; bz++) {
                boolean loaded = world.isChunkLoaded(bx >> 4, bz >> 4);
                for (int by = minY; loaded && by <= maxY; by++) {
                    double dx = bx + 0.5 - eyes.getX(), dy = by + 0.5 - eyes.getY(), dz = bz + 0.5 - eyes.getZ();
                    double ahead = dx * axis[0][0] + dy * axis[0][1] + dz * axis[0][2];
                    if (ahead < 0.2 || ahead > far + 1) continue;
                    double across = dx * axis[1][0] + dz * axis[1][2];
                    double up = dx * axis[2][0] + dy * axis[2][1] + dz * axis[2][2];
                    if (Math.abs(across - cx * ahead) > hw * ahead + 1 || Math.abs(up - cy * ahead) > hh * ahead + 1) continue;
                    if (!world.getBlockAt(bx, by, bz).getType().isAir()) out.put(new Location(world, bx, by, bz), air);
                }
            }
        }
        return out;
    }

    /** Whether this stage was placed for a window here and this large. */
    boolean framedBy(double x, double y, double w, double h) {
        return Math.abs(this.x - x) < 1e-6 && Math.abs(this.y - y) < 1e-6 && Math.abs(this.w - w) < 1e-6 && Math.abs(this.h - h) < 1e-6;
    }

    /**
     * What to play. Anything else on stage stops at once; the new one starts when the hover has rested, so sweeping
     * the cursor down a list draws and plays nothing.
     */
    synchronized void want(SpatialPreview preview) {
        if (closed || preview.equals(asked)) return;
        boolean sameBody = asked instanceof SpatialPreview.Npc was && preview instanceof SpatialPreview.Npc now
                && was.skin().equals(now.skin()) && waiting == null;
        asked = preview;
        if (sameBody) {
            // Only what the body wears changed: no need to stop it, or to wait.
            update(preview);
            return;
        }
        update(SpatialPreview.none());
        waiting = preview;
        startsAt = System.currentTimeMillis() + REST_MILLIS;
    }

    private synchronized void tick() {
        if (closed) return;
        settle.run();
        if (waiting != null && System.currentTimeMillis() >= startsAt) {
            SpatialPreview now = waiting;
            waiting = null;
            update(now);
        }
        keep();
    }

    /** Stops everything and gives the world back. */
    synchronized void close() {
        if (closed) return;
        closed = true;
        poll.cancel();
        remove();
        net.exylia.lib.display.internal.DisplayRuntime.freeViewer(viewer.getUniqueId());
        drop(floor);
        for (DisplayHandle piece : new ArrayList<>(pieces)) drop(piece);
        wire.send(List.of(ScreenPackets.destroy(new int[]{wallId})));
        if (viewer.isOnline()) blocks.clear(viewer, kept);
    }

    @Override
    protected SpatialLayout.Preview look() {
        return look;
    }

    @Override
    protected @Nullable DisplayHandle show(DisplayModel model, DisplayMotion motion, Location at) {
        DisplayHandle handle = displays.show(model, motion, at, List.of(viewer));
        if (handle != null) pieces.add(handle);
        return handle;
    }

    @Override
    protected void drop(@Nullable DisplayHandle handle) {
        if (handle == null) return;
        pieces.remove(handle);
        handle.remove();
    }

    @Override
    protected java.util.Collection<?> pieces() {
        return pieces;
    }
}
