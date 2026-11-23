package net.exylia.lib.camera;

import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The solid blocks of the ground a camera films over, read once on the thread
 * that owns them and asked from any thread afterwards.
 *
 * <p>A camera steered every frame from an asynchronous timer (a replay's
 * {@link net.exylia.lib.replay.ReplayPlayback.Shot}) must not read the world
 * itself. Take this where the scene is, on its region thread, and pull every
 * seat in front of the walls with it:
 *
 * <pre>{@code
 * tasks.runAtLocation(body, () -> walls = CameraWalls.around(body, killer));
 * // later, every frame, on any thread
 * Location seat = walls.clear(subject, wanted);
 * }</pre>
 *
 * <p>It is a copy: a block placed after it was taken is not seen.
 *
 * @since 1.652.0
 */
public final class CameraWalls {

    /** How far from a wall a pulled seat stands, in blocks. */
    private static final double CLEARANCE = 0.35;
    /** How near the subject a seat may be pulled, in blocks. */
    private static final double MIN_DISTANCE = 0.45;
    /** How far a seat moves in at a time while looking for one that sees everything, in blocks. */
    private static final double STEP = 0.25;
    /** Chunks read past the two points, on every side. */
    private static final int MARGIN = 1;
    /** At most this many chunks, so a scene across a whole map stays cheap. */
    private static final int MAX_CHUNKS = 81;

    private final @Nullable World world;
    private final Map<Long, ChunkSnapshot> chunks;

    private CameraWalls(@Nullable World world, Map<Long, ChunkSnapshot> chunks) {
        this.world = world;
        this.chunks = chunks;
    }

    /**
     * Reads the loaded chunks around two points and between them, plus one on
     * every side. Call it on the thread that owns that place; chunks this
     * thread does not own, or that are not loaded, are left out and count as
     * open air.
     *
     * @param from one end of the scene, such as the killer
     * @param to   the other, such as the body; the same point for a small scene
     * @return the walls
     */
    public static @NotNull CameraWalls around(@NotNull Location from, @NotNull Location to) {
        World world = from.getWorld();
        Map<Long, ChunkSnapshot> chunks = new HashMap<>();
        if (world == null || world != to.getWorld()) {
            return new CameraWalls(world, chunks);
        }
        int minX = (Math.min(from.getBlockX(), to.getBlockX()) >> 4) - MARGIN;
        int maxX = (Math.max(from.getBlockX(), to.getBlockX()) >> 4) + MARGIN;
        int minZ = (Math.min(from.getBlockZ(), to.getBlockZ()) >> 4) - MARGIN;
        int maxZ = (Math.max(from.getBlockZ(), to.getBlockZ()) >> 4) + MARGIN;
        read:
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (chunks.size() >= MAX_CHUNKS) {
                    break read;
                }
                if (!world.isChunkLoaded(x, z) || !Bukkit.isOwnedByCurrentRegion(world, x, z)) {
                    continue;
                }
                chunks.put(key(x, z), world.getChunkAt(x, z).getChunkSnapshot(false, false, false));
            }
        }
        return new CameraWalls(world, chunks);
    }

    /**
     * A seat that is not inside a wall: pulled along the line from what it
     * films towards it, to just short of the first solid block. Blocks a body
     * walks through (grass, flowers, water) are seen through.
     *
     * @param subject what the camera films
     * @param seat    where it would stand
     * @return the seat, nearer the subject and keeping its heading when
     *         something was in the way; the seat itself when the way is clear
     */
    public @NotNull Location clear(@NotNull Location subject, @NotNull Location seat) {
        return clear(subject, seat, List.of());
    }

    /**
     * A seat that sees the subject and every one of {@code seen}: pulled in
     * front of the first wall like {@link #clear(Location, Location)}, then on
     * towards the subject until nothing stands between it and any of them, so a
     * tree beside two players does not hide one of them. When no seat on the way
     * sees them all, the one that sees the subject.
     *
     * @param subject what the camera aims at
     * @param seat    where it would stand
     * @param seen    the other points that must stay in sight, such as both bodies' chests
     * @return the seat, nearer the subject and keeping its heading when
     *         something was in the way; the seat itself when the way is clear
     * @since 1.652.0
     */
    public @NotNull Location clear(@NotNull Location subject, @NotNull Location seat,
                                   @NotNull Collection<Location> seen) {
        if (chunks.isEmpty() || seat.getWorld() != world || subject.getWorld() != world) {
            return seat;
        }
        double length = seat.distance(subject);
        double reach = reach(subject, seat, seen, this::solid);
        if (reach >= length) {
            return seat;
        }
        Location pulled = subject.clone().add(seat.toVector().subtract(subject.toVector()).multiply(reach / length));
        pulled.setYaw(seat.getYaw());
        pulled.setPitch(seat.getPitch());
        return pulled;
    }

    /**
     * Whether nothing solid stands between two points, such as a seat and a
     * body: a way to score candidate seats before choosing one.
     *
     * @param from one point
     * @param to   the other
     * @return {@code true} when the line between them is clear, or the walls know nothing of that place
     * @since 1.652.0
     */
    public boolean sees(@NotNull Location from, @NotNull Location to) {
        if (chunks.isEmpty() || from.getWorld() != world || to.getWorld() != world) {
            return true;
        }
        return seesAll(to.getX(), to.getY(), to.getZ(), List.of(from), this::solid);
    }

    /** How far from the subject, towards the seat, a camera stands to see the subject and all of {@code seen}. */
    static double reach(@NotNull Location subject, @NotNull Location seat, @NotNull Collection<Location> seen,
                        @NotNull Blocks solid) {
        double dx = seat.getX() - subject.getX();
        double dy = seat.getY() - subject.getY();
        double dz = seat.getZ() - subject.getZ();
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0E-3) {
            return length;
        }
        dx /= length;
        dy /= length;
        dz /= length;
        double wall = firstSolid(subject.getX(), subject.getY(), subject.getZ(), dx, dy, dz, length, solid);
        double start = wall >= length ? length : Math.min(length, Math.max(MIN_DISTANCE, wall - CLEARANCE));
        for (double reach = start; reach >= MIN_DISTANCE - 1.0E-9; reach -= STEP) {
            if (seesAll(subject.getX() + dx * reach, subject.getY() + dy * reach, subject.getZ() + dz * reach,
                    seen, solid)) {
                return reach;
            }
        }
        return start;
    }

    private static boolean seesAll(double x, double y, double z, Collection<Location> seen, Blocks solid) {
        for (Location point : seen) {
            double dx = x - point.getX();
            double dy = y - point.getY();
            double dz = z - point.getZ();
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (length < 1.0E-3) {
                continue;
            }
            if (firstSolid(point.getX(), point.getY(), point.getZ(), dx / length, dy / length, dz / length,
                    length, solid) < length) {
                return false;
            }
        }
        return true;
    }

    /**
     * How far along the ray the first solid block starts, or {@code length}
     * when there is none before it. The block the ray starts in is skipped: a
     * subject standing in a doorway still gets a camera.
     */
    static double firstSolid(double x, double y, double z, double ux, double uy, double uz, double length,
                             @NotNull Blocks solid) {
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);
        int stepX = (int) Math.signum(ux);
        int stepY = (int) Math.signum(uy);
        int stepZ = (int) Math.signum(uz);
        double deltaX = stepX == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(ux);
        double deltaY = stepY == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(uy);
        double deltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(uz);
        double nextX = stepX == 0 ? Double.POSITIVE_INFINITY : (stepX > 0 ? bx + 1 - x : x - bx) * deltaX;
        double nextY = stepY == 0 ? Double.POSITIVE_INFINITY : (stepY > 0 ? by + 1 - y : y - by) * deltaY;
        double nextZ = stepZ == 0 ? Double.POSITIVE_INFINITY : (stepZ > 0 ? bz + 1 - z : z - bz) * deltaZ;
        while (true) {
            double t;
            if (nextX <= nextY && nextX <= nextZ) {
                t = nextX;
                bx += stepX;
                nextX += deltaX;
            } else if (nextY <= nextZ) {
                t = nextY;
                by += stepY;
                nextY += deltaY;
            } else {
                t = nextZ;
                bz += stepZ;
                nextZ += deltaZ;
            }
            if (t >= length) {
                return length;
            }
            if (solid.at(bx, by, bz)) {
                return t;
            }
        }
    }

    private boolean solid(int x, int y, int z) {
        if (world == null || y < world.getMinHeight() || y >= world.getMaxHeight()) {
            return false;
        }
        ChunkSnapshot chunk = chunks.get(key(x >> 4, z >> 4));
        if (chunk == null) {
            return false;
        }
        Material type = chunk.getBlockType(x & 15, y, z & 15);
        return type.isSolid();
    }

    /** Whether a block stops the camera. */
    interface Blocks {
        boolean at(int x, int y, int z);
    }

    private static long key(int x, int z) {
        return (long) x << 32 | (z & 0xFFFFFFFFL);
    }
}
