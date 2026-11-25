package net.exylia.lib.node.internal;

import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;

/**
 * The click box of a node, fitted to what the viewer sees: a solid block fills
 * its block, a carpet or pressure plate lies flat on the floor, a crop is as tall
 * as its stage, a plant is a little narrower and lower than a block, and
 * everything follows the node's scale.
 */
final class NodeBox {

    /** An item node's click box: a little smaller than a block, centred on the item. */
    static final float ITEM = 0.8f;
    private static final float CROP_WIDTH = 0.875f;
    private static final float CROP_LOWEST = 0.25f;
    private static final float PLANT_WIDTH = 0.75f;
    private static final float PLANT_HEIGHT = 0.8f;
    /** A carpet's or pressure plate's height: a sixteenth, as thin as it is drawn. */
    private static final float FLAT_HEIGHT = 0.0625f;

    private NodeBox() {
    }

    /** Width then height of the box for this node as it looks and is scaled now. */
    static float[] of(NodeImpl node) {
        Object look = node.look();
        if (!(look instanceof BlockData block)) return of(Shape.ITEM, 0, 0, node.size());
        boolean full;
        try {
            // Carpets and plates count as solid, but stand a sixteenth tall: a full box would tower over them.
            String name = block.getMaterial().name();
            if (name.endsWith("_CARPET") || name.endsWith("_PRESSURE_PLATE")) return of(Shape.FLAT, 0, 0, node.size());
            full = block.getMaterial().isOccluding() || block.getMaterial().isSolid();
        } catch (RuntimeException | LinkageError noRegistry) {
            full = true;
        }
        if (full) return of(Shape.FULL, 0, 0, node.size());
        if (block instanceof Ageable crop) return of(Shape.CROP, crop.getAge(), crop.getMaximumAge(), node.size());
        return of(Shape.PLANT, 0, 0, node.size());
    }

    enum Shape { FULL, FLAT, CROP, PLANT, ITEM }

    static float[] of(Shape shape, int age, int maxAge, float size) {
        float s = Math.max(0f, size);
        return switch (shape) {
            case FULL -> new float[]{s, s};
            case FLAT -> new float[]{s, FLAT_HEIGHT * s};
            case ITEM -> new float[]{ITEM * s, ITEM * s};
            case PLANT -> new float[]{PLANT_WIDTH * s, PLANT_HEIGHT * s};
            case CROP -> {
                float grown = maxAge <= 0 ? 1f : (Math.max(0, Math.min(age, maxAge)) + 1f) / (maxAge + 1f);
                yield new float[]{CROP_WIDTH * s, Math.max(CROP_LOWEST, grown) * s};
            }
        };
    }
}
