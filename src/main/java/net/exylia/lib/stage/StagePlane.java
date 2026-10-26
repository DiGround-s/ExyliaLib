package net.exylia.lib.stage;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * A flat grid of cells standing upright in front of a seat, facing whoever sits there.
 *
 * <p>Pure geometry, so every in-world screen shares one answer to "where is cell (row, column)" and
 * "which cell is this player looking at": a 3×3 board is a grid of nine, a row of menu cards a
 * grid of one row. Cells are numbered row by row from the top left, as the player sees them.
 *
 * <pre>{@code
 * StagePlane plane = StagePlane.inFrontOf(player.getLocation(), 1.62, 2.5, 0, 3, 3, 0.5, 0.45);
 * int cell = plane.pick(player.getEyeLocation());     // -1 when the crosshair is on no cell
 * }</pre>
 *
 * <p>Picking a cell is a ray against the plane, done on the server from the player's eyes. No
 * hitbox entity is spawned, so there is nothing a client can click that the server did not draw,
 * and nothing left behind if the server stops.
 *
 * <p>Not thread-safe: a plane belongs to the {@link Stage} drawn on it and is used from the thread
 * that runs it. Everything but {@link #inFrontOf} and the {@link Location} overloads is server-free.
 *
 * @since 1.356.0
 */
public final class StagePlane {

    private final World world;
    private double cx;
    private double cy;
    private double cz;
    /** Which way the seat looks, horizontally: the board's back faces it. */
    private final double fx;
    private final double fz;
    /** The seat's right hand. */
    private final double rx;
    private final double rz;
    private final int columns;
    private final int rows;
    private final double pitch;
    private final double half;
    private final float yaw;
    /**
     * Where a board made by {@link #inFrontOf} was placed from, {eyes x, y, z, distance, lift}, so it can
     * still be brought nearer by {@link #fit}; {@code null} for a board that cannot.
     */
    private double[] seat;
    /** Whether anything was placed on the board yet: from then on it stays where it is. */
    private boolean used;

    /**
     * @param world   the world it stands in; {@code null} for pure maths
     * @param cx      the middle of the board, x
     * @param cy      its y
     * @param cz      its z
     * @param yaw     which way the seat looks, in Minecraft degrees
     * @param columns cells across
     * @param rows    cells down
     * @param pitch   centre-to-centre distance between two neighbouring cells
     * @param cell    the size of one cell, which may be smaller than the pitch to leave a gap
     */
    public StagePlane(World world, double cx, double cy, double cz, float yaw,
                      int columns, int rows, double pitch, double cell) {
        this.world = world;
        this.cx = cx;
        this.cy = cy;
        this.cz = cz;
        this.yaw = yaw;
        double radians = Math.toRadians(yaw);
        this.fx = -Math.sin(radians);
        this.fz = Math.cos(radians);
        this.rx = -Math.cos(radians);
        this.rz = -Math.sin(radians);
        this.columns = columns;
        this.rows = rows;
        this.pitch = pitch;
        this.half = cell / 2.0;
    }

    /**
     * The board for a seat: {@code distance} blocks ahead of the eyes, raised or lowered by {@code lift}.
     *
     * @param seat where the player sits, looking the way the board should face them
     */
    public static StagePlane inFrontOf(Location seat, double eyeHeight, double distance, double lift,
                                       int columns, int rows, double pitch, double cell) {
        double radians = Math.toRadians(seat.getYaw());
        double x = seat.getX() - Math.sin(radians) * distance;
        double z = seat.getZ() + Math.cos(radians) * distance;
        StagePlane plane = new StagePlane(seat.getWorld(), x, seat.getY() + eyeHeight + lift, z, seat.getYaw(),
                columns, rows, pitch, cell);
        plane.seat = new double[]{seat.getX(), seat.getY() + eyeHeight, seat.getZ(), distance, lift};
        return plane;
    }

    /**
     * Brings the board nearer so it fills more of the view: {@code zoom} times nearer, its lift scaled
     * with it so it stays where the eyes found it, and every piece (sized in blocks) {@code zoom} times
     * larger on screen. Capped so the board, from {@code left} to {@code right} and {@code bottom} to
     * {@code top} of its centre, never spans more than {@code maxWide} by {@code maxTall} degrees, and
     * never stands nearer than {@code nearest} blocks. Never shrinks a board below its own size.
     *
     * <p>Only before anything is placed on it: a board already drawn on stays where it is.
     *
     * @return the zoom applied
     */
    public double fit(double left, double right, double bottom, double top, double zoom,
                      double maxWide, double maxTall, double nearest) {
        if (seat == null || used) return 1;
        double applied = zoom(seat[3], seat[4], left, right, bottom, top, zoom, maxWide, maxTall, nearest);
        double distance = seat[3] / applied;
        double radians = Math.toRadians(yaw);
        cx = seat[0] - Math.sin(radians) * distance;
        cy = seat[1] + seat[4] / applied;
        cz = seat[2] + Math.cos(radians) * distance;
        seat = null;
        return applied;
    }

    /**
     * The zoom {@link #fit} applies to a board {@code distance} ahead and {@code lift} above the eyes:
     * the largest, up to {@code zoom}, that keeps it inside the view and beyond {@code nearest}.
     */
    public static double zoom(double distance, double lift, double left, double right, double bottom, double top,
                              double zoom, double maxWide, double maxTall, double nearest) {
        if (zoom <= 1 || distance <= 0) return 1;
        double most = Math.min(zoom, distance / Math.max(0.1, nearest));
        double low = 1;
        double high = most;
        if (fits(distance, lift, left, right, bottom, top, high, maxWide, maxTall)) return Math.max(1, high);
        if (high <= 1 || !fits(distance, lift, left, right, bottom, top, 1, maxWide, maxTall)) return 1;
        for (int i = 0; i < 30; i++) {
            double mid = (low + high) / 2;
            if (fits(distance, lift, left, right, bottom, top, mid, maxWide, maxTall)) low = mid;
            else high = mid;
        }
        return low;
    }

    /** Whether the board, brought {@code zoom} times nearer, spans no more than the given degrees. */
    private static boolean fits(double distance, double lift, double left, double right, double bottom, double top,
                                double zoom, double maxWide, double maxTall) {
        double wide = 2 * Math.toDegrees(Math.atan(Math.max(Math.abs(left), Math.abs(right)) * zoom / distance));
        double tall = Math.toDegrees(Math.atan((lift + top * zoom) / distance) - Math.atan((lift + bottom * zoom) / distance));
        return wide <= maxWide && tall <= maxTall;
    }

    /**
     * Another grid on this board's plane, centred {@code across} and {@code up} from its middle: what
     * a screen with more than one grid on it (two boards side by side) picks cells from.
     */
    public StagePlane grid(double across, double up, int columns, int rows, double pitch, double cell) {
        used = true;
        Location centre = point(across, up, 0);
        return new StagePlane(world, centre.getX(), centre.getY(), centre.getZ(), yaw, columns, rows, pitch, cell);
    }

    public int columns() {
        return columns;
    }

    public int rows() {
        return rows;
    }

    public int cells() {
        return columns * rows;
    }

    /** Which way the seat looks, so a model turned by {@link #facing()} faces back at it. */
    public float yaw() {
        return yaw;
    }

    /**
     * The turn, in radians around the vertical axis, that makes a display's front face the seat.
     *
     * <p>A display faces south (+Z) unturned; the board must face the opposite of where the seat
     * looks.
     */
    public double facing() {
        return Math.PI - Math.toRadians(yaw);
    }

    /** The middle of a cell, pushed {@code forward} blocks out of the board towards the seat. */
    public Location cell(int index, double forward) {
        int row = index / columns;
        int column = index % columns;
        double across = (column - (columns - 1) / 2.0) * pitch;
        double down = (row - (rows - 1) / 2.0) * pitch;
        return point(across, -down, forward);
    }

    /**
     * A point on the board.
     *
     * @param across  to the seat's right
     * @param up      upwards
     * @param forward out of the board, towards the seat
     */
    public Location point(double across, double up, double forward) {
        used = true;
        return new Location(world, cx + rx * across - fx * forward, cy + up, cz + rz * across - fz * forward);
    }

    /**
     * Where a ray from the eyes meets the board's plane, in the board's own terms.
     *
     * @param across to the seat's right of the centre
     * @param up     above the centre
     */
    public record Hit(double across, double up) {

        /** Whether the hit lands inside a rectangle centred on a point of the board. */
        public boolean inside(double centreAcross, double centreUp, double width, double height) {
            return Math.abs(across - centreAcross) <= width / 2 && Math.abs(up - centreUp) <= height / 2;
        }
    }

    /** Where a ray meets the plane, or {@code null} when it points away from the board. */
    public Hit hit(double ex, double ey, double ez, double dx, double dy, double dz) {
        // The board's plane holds every point whose offset from the centre has no forward part.
        double facing = dx * fx + dz * fz;
        if (facing <= 1.0E-6) {
            return null;
        }
        double t = ((cx - ex) * fx + (cz - ez) * fz) / facing;
        if (t <= 0) {
            return null;
        }
        double ox = ex + dx * t - cx;
        double oz = ez + dz * t - cz;
        return new Hit(ox * rx + oz * rz, ey + dy * t - cy);
    }

    /** Where the player's eyes meet the plane, or {@code null}. */
    public Hit hit(Location eyes) {
        if (eyes.getWorld() != world) {
            return null;
        }
        var direction = eyes.getDirection();
        return hit(eyes.getX(), eyes.getY(), eyes.getZ(), direction.getX(), direction.getY(), direction.getZ());
    }

    /**
     * The cell a ray from the eyes goes through.
     *
     * @return the cell, or {@code -1} when the ray misses every cell or points away from the board
     */
    public int pick(double ex, double ey, double ez, double dx, double dy, double dz) {
        return pick(hit(ex, ey, ez, dx, dy, dz));
    }

    /** The cell a hit lands on, or {@code -1}; a hit in the gap between two cells is a miss. */
    public int pick(Hit hit) {
        if (hit == null) {
            return -1;
        }
        double column = hit.across() / pitch + (columns - 1) / 2.0;
        double row = -hit.up() / pitch + (rows - 1) / 2.0;
        int c = (int) Math.round(column);
        int r = (int) Math.round(row);
        if (c < 0 || c >= columns || r < 0 || r >= rows) {
            return -1;
        }
        if (Math.abs(column - c) * pitch > half || Math.abs(row - r) * pitch > half) {
            return -1;
        }
        return r * columns + c;
    }

    /**
     * The column a hit is over, anywhere from the top of the board to its bottom: what a game that
     * drops pieces into columns asks.
     *
     * @return the column, or {@code -1} beside or above or below the board
     */
    public int column(Hit hit) {
        if (hit == null || Math.abs(hit.up()) > rows * pitch / 2) {
            return -1;
        }
        int c = (int) Math.floor(hit.across() / pitch + columns / 2.0);
        return c < 0 || c >= columns ? -1 : c;
    }

    /** Centre-to-centre distance between two neighbouring cells. */
    public double pitch() {
        return pitch;
    }

    /** The cell the player's eyes are on; see {@link #pick(double, double, double, double, double, double)}. */
    public int pick(Location eyes) {
        return pick(hit(eyes));
    }
}
