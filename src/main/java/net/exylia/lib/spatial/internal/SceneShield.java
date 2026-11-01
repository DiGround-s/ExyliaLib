package net.exylia.lib.spatial.internal;

import net.exylia.lib.packet.Packets;
import net.exylia.lib.packet.PluginPackets;
import net.exylia.lib.packet.Volume;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;

import java.util.HashMap;
import java.util.Map;

/**
 * The space an in-world screen stands in, kept empty for its viewer while it is open: every entity in it
 * that is not the screen's own is out of sight ({@link net.exylia.lib.packet.Visibility#seal}: the
 * server's, other players, other plugins' packet NPCs and holograms), every entity anywhere with
 * {@code everything}, and with {@code clear} every block that reaches into it above the floor is sent
 * to them as air, so no wall, trunk or leaf cuts through a panel. Lowered, all of it comes back.
 *
 * <p>The floor stays: the layer the player stands on (a slab, a carpet, farmland count as theirs) and
 * everything under it is never touched, so nothing opens under them or under the pedestals; a block
 * whose top is under their feet stays too. Only what stands in front of them is cleared, from just
 * ahead of their face to just behind the board. Blocks are kept as air through block updates and chunk
 * resends ({@link net.exylia.lib.packet.FakeBlocks#keep}); a quit or a world change forgets them.
 *
 * <p>Internal: free to change without notice.
 */
public final class SceneShield {

    private static final double EYES = 1.62;
    /** Where clearing starts ahead of the feet: in front of the face, never the player's own column. */
    private static final double NEAR = 0.35;
    /** How far past the board everything is kept out: its backs, the pedestal's far side. */
    private static final double BEHIND = 0.6;
    private static final double MARGIN = 0.3;
    /** "Every entity": as far as anything is drawn. */
    private static final double EVERYWHERE = 100_000;

    private final PluginPackets packets;
    private final Player viewer;
    private final Map<Location, BlockData> cleared;

    private SceneShield(PluginPackets packets, Player viewer, Map<Location, BlockData> cleared) {
        this.packets = packets;
        this.viewer = viewer;
        this.cleared = cleared;
    }

    /**
     * Raises it for a screen standing {@code distance} ahead of the eyes of a player standing at
     * {@code feet} (facing its yaw), reaching {@code halfWidth} to each side and {@code top} over the
     * eyes. On the player's thread: it reads the blocks.
     *
     * @param library    the library, whose packets seal the box
     * @param clear      whether the blocks in it are sent as air
     * @param everything whether every entity anywhere is kept out of sight, not only those in it
     */
    public static SceneShield raise(Plugin library, Player viewer, Location feet, double distance, double halfWidth,
                                    double top, boolean clear, boolean everything) {
        PluginPackets packets = Packets.of(library);
        double far = distance + BEHIND;
        double yaw = Math.toRadians(feet.getYaw());
        double aheadX = -Math.sin(yaw);
        double aheadZ = Math.cos(yaw);
        double middle = (far - NEAR) / 2;
        double reach = Math.max(halfWidth + MARGIN, (far + NEAR) / 2);
        double topY = feet.getY() + EYES + top + MARGIN;
        Volume box = everything
                ? new Volume(feet.getX(), feet.getZ(), feet.getYaw(), EVERYWHERE, -EVERYWHERE, EVERYWHERE)
                : new Volume(feet.getX() + aheadX * middle, feet.getZ() + aheadZ * middle, feet.getYaw(), reach,
                feet.getY() - 0.6, topY);
        packets.visibility().seal(viewer, box);
        Map<Location, BlockData> air = clear ? clearing(feet, far, halfWidth + MARGIN, topY) : Map.of();
        if (!air.isEmpty()) packets.fakeBlocks().keep(viewer, air);
        return new SceneShield(packets, viewer, air);
    }

    /** Lowers it: the blocks come back and every entity it hid is drawn where it really is. Any thread. */
    public void lower() {
        packets.visibility().seal(viewer, null);
        if (!cleared.isEmpty() && viewer.isOnline()) packets.fakeBlocks().clear(viewer, cleared.keySet());
    }

    /** Every block in front of the feet, up to the board and to {@code top}, that is sent as air. */
    static Map<Location, BlockData> clearing(Location feet, double far, double half, double topY) {
        World world = feet.getWorld();
        Map<Location, BlockData> air = new HashMap<>();
        if (world == null) return air;
        double yaw = Math.toRadians(feet.getYaw());
        double aheadX = -Math.sin(yaw);
        double aheadZ = Math.cos(yaw);
        // The viewer's right, looking along ahead.
        double rightX = -aheadZ;
        double rightZ = aheadX;
        int floor = floorLayer(feet.getY());
        int span = (int) Math.ceil(Math.hypot(far, half)) + 1;
        BlockData nothing = Material.AIR.createBlockData();
        for (int dx = -span; dx <= span; dx++) {
            for (int dz = -span; dz <= span; dz++) {
                int bx = feet.getBlockX() + dx;
                int bz = feet.getBlockZ() + dz;
                if (!inFront(bx + 0.5 - feet.getX(), bz + 0.5 - feet.getZ(), aheadX, aheadZ, rightX, rightZ, far, half)) {
                    continue;
                }
                for (int y = floor + 1; y <= (int) Math.floor(topY); y++) {
                    // Air too: a block anyone places there while it is open stays out of the way.
                    Block block = world.getBlockAt(bx, y, bz);
                    if (!block.getType().isAir() && keeps(y, feet.getY(), block)) continue;
                    air.put(new Location(world, bx, y, bz), nothing);
                }
            }
        }
        return air;
    }

    /**
     * Whether a block (by its middle, grown by half a block so one that only reaches in counts) stands
     * in front of the feet: ahead between {@link #NEAR} and {@code far}, no further than {@code half} to
     * either side.
     */
    static boolean inFront(double px, double pz, double aheadX, double aheadZ, double rightX, double rightZ,
                           double far, double half) {
        double ahead = px * aheadX + pz * aheadZ;
        double across = px * rightX + pz * rightZ;
        return ahead + 0.5 >= NEAR && ahead - 0.5 <= far && Math.abs(across) - 0.5 <= half;
    }

    /** The layer the floor is in: the block under the feet, read half a block down (a slab, a carpet). */
    static int floorLayer(double feetY) {
        return (int) Math.floor(feetY - 0.5);
    }

    /** A block over the floor layer that still holds the feet up, or is lower: never cleared. */
    private static boolean keeps(int y, double feetY, Block block) {
        double top = -1;
        for (BoundingBox box : block.getCollisionShape().getBoundingBoxes()) top = Math.max(top, box.getMaxY());
        return top >= 0 && y + top <= feetY + 0.01;
    }
}
