package net.exylia.lib.model.internal;

import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.Locale;

/**
 * A model's bones and the cubes hanging off them, exactly as Blockbench keeps
 * them: pixels, degrees, and every pivot where the author put it.
 *
 * <p>Nothing here is turned into blocks or into the game's axes yet; that is
 * {@link Poser}'s job, so what was read can be asserted against the numbers in
 * the file.
 *
 * @param bones the bones, every parent before its children
 * @param parts the cubes, in the file's order
 * @param min   the corner of the rest pose's bounds with the smallest coordinates, in pixels
 * @param max   the opposite corner
 */
@ApiStatus.Internal
public record Rig(List<Bone> bones, List<Part> parts, double[] min, double[] max) {

    /**
     * A group of the outliner.
     *
     * @param name     its name in the outliner
     * @param uuid     its id in the file, which animations refer to it by
     * @param parent   its parent's index, or {@code -1} at the root
     * @param origin   its pivot, in pixels, in the model's space
     * @param rotation its own turn, in degrees, about X, Y and Z
     */
    public record Bone(String name, String uuid, int parent, double[] origin, double[] rotation) {
    }

    /**
     * One cube, ready to be drawn.
     *
     * @param name     its name, which may carry an {@code @material}
     * @param bone     the bone it hangs off
     * @param origin   its pivot, in pixels, in the model's space
     * @param rotation its own turn about its pivot, in degrees
     * @param from     its corner with the smallest coordinates, inflated
     * @param to       its opposite corner, inflated
     * @param look     what it is drawn with
     */
    public record Part(String name, int bone, double[] origin, double[] rotation, double[] from, double[] to,
                       Look look) {

        /** How big it is, in cubic pixels. */
        public double volume() {
            return Math.abs((to[0] - from[0]) * (to[1] - from[1]) * (to[2] - from[2]));
        }
    }

    /** The bone a head turns with: the first called {@code head}, else the first whose name holds it. */
    public int head() {
        for (int index = 0; index < bones.size(); index++) {
            if (bones.get(index).name().equalsIgnoreCase("head")) {
                return index;
            }
        }
        for (int index = 0; index < bones.size(); index++) {
            if (bones.get(index).name().toLowerCase(Locale.ROOT).contains("head")) {
                return index;
            }
        }
        return -1;
    }

    /** The index of a bone by name or id, ignoring case, or {@code -1}. */
    public int bone(String nameOrUuid) {
        for (int index = 0; index < bones.size(); index++) {
            Bone bone = bones.get(index);
            if (bone.uuid().equals(nameOrUuid)) {
                return index;
            }
        }
        for (int index = 0; index < bones.size(); index++) {
            if (bones.get(index).name().equalsIgnoreCase(nameOrUuid)) {
                return index;
            }
        }
        return -1;
    }

    /** Width across X and Z, in blocks at scale 1. */
    public double width() {
        return Math.max(max[0] - min[0], max[2] - min[2]) / 16.0;
    }

    /** Height, in blocks at scale 1, from the lowest cube up. */
    public double height() {
        return (max[1] - Math.min(0, min[1])) / 16.0;
    }
}
