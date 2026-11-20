package net.exylia.lib.packet.internal;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Which swaps a block palette accepts: only those after which the client predicts exactly what the
 * server simulates. Same collision shape, same movement (friction, climbing, bouncing, slowing), no
 * fluid and no block entity on either side. Plain Bukkit; no PacketEvents.
 */
final class PaletteRules {

    private PaletteRules() {
    }

    /** Blocks that carry a fluid without a waterlogged property: the client would count it wrong. */
    private static final Set<Material> FLUIDS = EnumSet.of(Material.WATER, Material.LAVA, Material.BUBBLE_COLUMN,
            Material.KELP, Material.KELP_PLANT, Material.SEAGRASS, Material.TALL_SEAGRASS);

    /** Blocks the client moves a player through differently, beyond friction and climbing. */
    private static final Set<Material> MOVEMENT = EnumSet.of(Material.SLIME_BLOCK, Material.HONEY_BLOCK,
            Material.SOUL_SAND, Material.COBWEB, Material.POWDER_SNOW, Material.SWEET_BERRY_BUSH,
            Material.SCAFFOLDING);

    private static final double EPSILON = 1.0E-6;

    /** Why a material may not be swapped for another at all, or {@code null} when it may. */
    static @Nullable String refuse(Material from, Material to) {
        for (Material side : new Material[] {from, to}) {
            if (!side.isBlock() || side.isLegacy()) return side + " is not a block";
            if (side.isAir()) return side + " is air (the client counts the blocks in a section)";
            if (FLUIDS.contains(side)) return side + " holds a fluid";
            if (side.createBlockData().createBlockState() instanceof TileState) {
                return side + " has a block entity";
            }
        }
        if (!movement(from).equals(movement(to))) {
            return from + " and " + to + " move a player differently (friction, climbing or bouncing)";
        }
        return null;
    }

    /** Why one state may not be shown as another, or {@code null} when it may. */
    static @Nullable String refuse(BlockData from, BlockData to, Location at) {
        if (from instanceof Waterlogged wet && wet.isWaterlogged()
                && !(to instanceof Waterlogged other && other.isWaterlogged())) {
            return from.getAsString() + " is waterlogged and " + to.getMaterial() + " cannot be";
        }
        if (!sameShape(from.getCollisionShape(at).getBoundingBoxes(), to.getCollisionShape(at).getBoundingBoxes())) {
            return from.getAsString() + " collides differently from " + to.getAsString();
        }
        return null;
    }

    private static String movement(Material block) {
        return block.getSlipperiness()
                + (Tag.CLIMBABLE.isTagged(block) ? "|climbs" : "")
                + (Tag.BEDS.isTagged(block) ? "|bounces" : "")
                + (MOVEMENT.contains(block) ? "|" + block : "");
    }

    /** Whether two collision shapes are the same boxes, in any order. */
    static boolean sameShape(Collection<BoundingBox> a, Collection<BoundingBox> b) {
        if (a.size() != b.size()) return false;
        List<BoundingBox> left = sorted(a);
        List<BoundingBox> right = sorted(b);
        for (int i = 0; i < left.size(); i++) {
            BoundingBox x = left.get(i);
            BoundingBox y = right.get(i);
            if (Math.abs(x.getMinX() - y.getMinX()) > EPSILON || Math.abs(x.getMinY() - y.getMinY()) > EPSILON
                    || Math.abs(x.getMinZ() - y.getMinZ()) > EPSILON || Math.abs(x.getMaxX() - y.getMaxX()) > EPSILON
                    || Math.abs(x.getMaxY() - y.getMaxY()) > EPSILON || Math.abs(x.getMaxZ() - y.getMaxZ()) > EPSILON) {
                return false;
            }
        }
        return true;
    }

    private static List<BoundingBox> sorted(Collection<BoundingBox> boxes) {
        List<BoundingBox> list = new ArrayList<>(boxes);
        list.sort(Comparator.comparingDouble(BoundingBox::getMinX).thenComparingDouble(BoundingBox::getMinY)
                .thenComparingDouble(BoundingBox::getMinZ).thenComparingDouble(BoundingBox::getMaxX)
                .thenComparingDouble(BoundingBox::getMaxY).thenComparingDouble(BoundingBox::getMaxZ));
        return list;
    }
}
