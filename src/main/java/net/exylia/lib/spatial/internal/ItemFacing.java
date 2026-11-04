package net.exylia.lib.spatial.internal;

import net.exylia.lib.display.DisplayKeyframe;
import net.exylia.lib.display.DisplayMotion;
import net.exylia.lib.display.Rotation;

import java.util.ArrayList;
import java.util.List;

/**
 * How an item display shows an item to the viewer. An item display turns its model half round before its
 * display context; the item frame's context ({@link #FIXED}) turns it back, so a shield or a banner shows its
 * front, centred, to whoever the display's front faces, as in an item frame. The inventory's context
 * ({@link #GUI}) leaves them turned away and tilted: edge-on, or their back. Since 1.470.1.
 *
 * <p>Internal: free to change without notice.
 */
public final class ItemFacing {

    /** The protocol's display contexts: the inventory, and the item frame. */
    public static final int GUI = 6;
    public static final int FIXED = 8;
    /** How far a still item sways either way, and how long a sway takes. */
    public static final double SWAY_DEGREES = 4;
    public static final long SWAY_MILLIS = 7000L;

    private ItemFacing() {
        throw new AssertionError("No instances.");
    }

    /** Whether an item of {@code material} has a front and a back (a shield, a banner): never turned round. */
    public static boolean flat(String material) {
        return material.equals("SHIELD") || material.endsWith("_BANNER");
    }

    /** The context a shelf or a reel draws an item of {@code material} in: its inventory icon, or a flat one's front. */
    public static int context(String material) {
        return flat(material) ? FIXED : GUI;
    }

    /**
     * An item standing on a pedestal, facing {@code start} degrees from the viewer: at a {@code speed} of 0, still
     * with a sway of a few degrees; else turning round at {@code speed} degrees a second.
     */
    public static DisplayMotion stand(Rotation facing, double start, double speed, double size, long life) {
        List<DisplayKeyframe> frames = new ArrayList<>();
        long cycle = speed == 0 ? SWAY_MILLIS : (long) (360_000 / Math.max(1, Math.abs(speed)));
        int poses = 12;
        for (int pose = 0; pose <= poses; pose++) {
            double degrees = speed == 0 ? SWAY_DEGREES * Math.sin(Math.PI * 2 * pose / poses)
                    : 360.0 * pose / poses * Math.signum(speed);
            frames.add(new DisplayKeyframe(cycle * pose / poses, 0f, 0f, 0f, pose(facing, start + degrees),
                    (float) size, (float) size, (float) size));
        }
        return DisplayMotion.of(frames, life).looping(0, cycle, 1, 1);
    }

    /** {@code facing} turned {@code degrees} round its upright axis. */
    public static Rotation pose(Rotation facing, double degrees) {
        return Rotation.around(Rotation.Axis.Y, Math.toRadians(degrees)).then(facing);
    }
}
