package net.exylia.lib.guide.internal;

import net.exylia.lib.guide.GuideTrail;
import net.exylia.lib.guide.Route;
import net.exylia.lib.guide.TrailStyle;
import net.exylia.lib.task.TaskScheduler;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Markers on the ground for the next stretch of a route, for one player.
 *
 * <h2>Cheap because the markers stay put</h2>
 * Markers sit at fixed distances along the route ({@link Route#stations}), so
 * as the player walks the ones already drawn stay where they are: one is
 * destroyed behind and one spawned ahead per spacing walked. A trail of
 * fifteen markers costs a couple of packets a second, not fifteen moves a
 * tick. A new marker is spawned flat and grown to full size on the next tick,
 * so the trail unrolls ahead of the player instead of popping in.
 *
 * <h2>Off the route</h2>
 * Past the style's distance a short line of markers in the way-back block is
 * drawn from the route towards the player, turned to point at the route, so a
 * player who wandered off can see where to go back to. Those few markers do
 * move, gliding as the player moves; they are gone the moment the player is
 * back.
 *
 * <h2>The ground</h2>
 * A recorded route is already at foot height, so each marker is only nudged
 * onto the surface it stands over, read once when it spawns, on the player's
 * own thread, and only from a chunk that thread owns and that is loaded. A
 * block it may not read leaves the marker at the route's own height.
 */
final class LiveTrail extends Piece implements GuideTrail {

    private static final int MAX_WAY_BACK = 8;
    private static final float WIDTH = 0.22f;
    private static final float THICKNESS = 0.03f;
    private static final float LENGTH = 0.48f;
    private static final double LIFT = 0.02;

    private final World world;
    private final Route route;
    private final TrailStyle style;

    private final Map<Integer, Integer> drawn = new HashMap<>();
    private final List<Integer> growing = new ArrayList<>();
    private final List<Integer> wayBack = new ArrayList<>();

    private volatile double progress;
    private volatile double gap = Double.NaN;
    private volatile boolean off;

    LiveTrail(ViewerSession session, World world, Route route, TrailStyle style) {
        super(session);
        this.world = world;
        this.route = route;
        this.style = style;
    }

    @Override
    public double progress() {
        return progress;
    }

    @Override
    public double length() {
        return route.length();
    }

    @Override
    public double distanceFromRoute() {
        return gap;
    }

    @Override
    public boolean isOffRoute() {
        return off;
    }

    @Override
    void draw(Player player, long tick) {
        if (!player.getWorld().equals(world)) {
            clear(player);
            gap = Double.NaN;
            return;
        }
        if (!growing.isEmpty()) {
            for (int id : growing) {
                GuidePackets.resizeBlock(player, id, WIDTH, THICKNESS, LENGTH, 4);
            }
            growing.clear();
        }
        Location feet = player.getLocation();
        Route.Nearest nearest = route.closest(feet.getX(), feet.getY(), feet.getZ());
        progress = nearest.arc();
        gap = nearest.distance();
        off = nearest.distance() > style.offRoute();

        Route.Ground ground = ground(player);
        int[] stations = route.stations(nearest.arc(), style.ahead(), style.spacing());
        List<Integer> gone = new ArrayList<>();
        int lowest = stations.length == 0 ? Integer.MAX_VALUE : stations[0];
        int highest = stations.length == 0 ? Integer.MIN_VALUE : stations[stations.length - 1];
        for (Iterator<Map.Entry<Integer, Integer>> it = drawn.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Integer> entry = it.next();
            if (entry.getKey() < lowest || entry.getKey() > highest) {
                gone.add(entry.getValue());
                it.remove();
            }
        }
        for (int station : stations) {
            if (drawn.containsKey(station)) {
                continue;
            }
            Route.Station at = route.at(station * style.spacing());
            double y = Route.snap(at.x(), at.y(), at.z(), ground) + LIFT;
            int id = GuidePackets.newId();
            GuidePackets.spawnBlock(player, id, at.x(), y, at.z(), at.yaw(), style.marker(),
                    0f, 0f, 0f, style.colour(), 1f, 0);
            growing.add(id);
            drawn.put(station, id);
        }
        drawWayBack(player, feet, nearest, ground, gone);
        if (!gone.isEmpty()) {
            GuidePackets.destroy(player, gone.stream().mapToInt(Integer::intValue).toArray());
        }
    }

    private void drawWayBack(Player player, Location feet, Route.Nearest nearest, Route.Ground ground,
                             List<Integer> gone) {
        int wanted = 0;
        double dx = feet.getX() - nearest.x();
        double dz = feet.getZ() - nearest.z();
        double flat = Math.sqrt(dx * dx + dz * dz);
        if (off && flat > 1e-6) {
            wanted = (int) Math.min(MAX_WAY_BACK, Math.floor((flat - 1.5) / style.spacing()));
        }
        while (wayBack.size() > Math.max(0, wanted)) {
            gone.add(wayBack.remove(wayBack.size() - 1));
        }
        float yaw = GuideGeometry.yawTowards(feet.getX(), feet.getZ(), nearest.x(), nearest.z());
        for (int index = 0; index < wanted; index++) {
            double along = (index + 1) * style.spacing() / flat;
            double x = nearest.x() + dx * along;
            double z = nearest.z() + dz * along;
            double baseY = nearest.y() + (feet.getY() - nearest.y()) * along;
            double y = Route.snap(x, baseY, z, ground) + LIFT;
            if (index < wayBack.size()) {
                GuidePackets.move(player, wayBack.get(index), x, y, z, yaw);
            } else {
                int id = GuidePackets.newId();
                GuidePackets.spawnBlock(player, id, x, y, z, yaw, style.returnMarker(), WIDTH, THICKNESS,
                        LENGTH, style.returnColour(), 1f, 3);
                wayBack.add(id);
            }
        }
    }

    /** The world's answer to {@link Route.Ground}, limited to blocks this thread may read. */
    private Route.Ground ground(Player player) {
        TaskScheduler tasks = session.tasks();
        World here = player.getWorld();
        return (x, y, z) -> {
            if (y < here.getMinHeight() || y >= here.getMaxHeight() || !here.isChunkLoaded(x >> 4, z >> 4)) {
                return Double.NaN;
            }
            Location spot = new Location(here, x, y, z);
            if (!tasks.isOwnedBy(spot)) {
                return Double.NaN;
            }
            Block block = here.getBlockAt(x, y, z);
            if (block.isPassable()) {
                return Double.NaN;
            }
            double top = block.getBoundingBox().getMaxY();
            return top > y ? top : Double.NaN;
        };
    }

    @Override
    void clear(Player player) {
        List<Integer> ids = new ArrayList<>(drawn.values());
        ids.addAll(wayBack);
        if (!ids.isEmpty()) {
            GuidePackets.destroy(player, ids.stream().mapToInt(Integer::intValue).toArray());
        }
        forget();
    }

    @Override
    void forget() {
        drawn.clear();
        growing.clear();
        wayBack.clear();
    }
}
