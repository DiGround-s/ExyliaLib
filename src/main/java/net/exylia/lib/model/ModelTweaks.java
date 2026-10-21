package net.exylia.lib.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Adjustments one wearer makes to a model without touching its file: where it
 * sits, bones it hides or draws in another block, how fast each state plays,
 * and the size of the box it is hit by.
 *
 * <pre>{@code
 * ModelTweaks tweaks = ModelTweaks.NONE
 *         .withOffset(0, -0.2, 0)
 *         .withBone("cape", ModelTweaks.Bone.HIDDEN)
 *         .withBone("crown", new ModelTweaks.Bone(false, "gold_block"))
 *         .withSpeed(ModelState.WALK, 1.3);
 * ModelInstance live = models.attach(mob, "king", ModelOptions.DEFAULT.withTweaks(tweaks));
 * }</pre>
 *
 * <p>Stored by whoever wears the model, edited with
 * {@link PluginModels#tweakEditor}. Immutable; every {@code with} returns a copy.
 *
 * @param offsetX      blocks to the model's right, before it turns with its carrier
 * @param offsetY      blocks up
 * @param offsetZ      blocks backwards: the model's front is towards {@code -Z}
 * @param bones        per bone name, lower case, what is done to it and to every bone below it
 * @param speeds       per state ({@link ModelState}) or animation name, lower case, how fast it plays;
 *                     {@code 1} is as animated
 * @param hitboxWidth  the width of the one box it is hit by, in blocks; {@code 0} works it out from the model
 * @param hitboxHeight the height of that box; {@code 0} works it out from the model
 * @since 1.321.0
 */
public record ModelTweaks(double offsetX, double offsetY, double offsetZ, @NotNull Map<String, Bone> bones,
                          @NotNull Map<String, Double> speeds, double hitboxWidth, double hitboxHeight) {

    /** Nothing changed. */
    public static final ModelTweaks NONE = new ModelTweaks(0, 0, 0, Map.of(), Map.of(), 0, 0);

    /** The furthest a model may be moved from its carrier, in blocks, on any axis. */
    public static final double MAX_OFFSET = 8;
    /** The largest box, in blocks. */
    public static final double MAX_HITBOX = 16;

    /**
     * What is done to one bone, and to the bones below it that say nothing of their own.
     *
     * @param hidden   whether it is not drawn at all
     * @param material the block or item its cubes are drawn in instead of their own, blank for their own
     */
    public record Bone(boolean hidden, @NotNull String material) {

        /** Not drawn. */
        public static final Bone HIDDEN = new Bone(true, "");

        public Bone {
            material = material == null ? "" : material.trim().toLowerCase(Locale.ROOT);
            if (material.startsWith("minecraft:")) {
                material = material.substring("minecraft:".length());
            }
        }

        /** Whether it changes anything. */
        public boolean changes() {
            return hidden || !material.isEmpty();
        }
    }

    public ModelTweaks {
        offsetX = offset(offsetX);
        offsetY = offset(offsetY);
        offsetZ = offset(offsetZ);
        Map<String, Bone> boneCopy = new TreeMap<>();
        if (bones != null) {
            bones.forEach((name, bone) -> {
                if (name != null && !name.isBlank() && bone != null && bone.changes()) {
                    boneCopy.put(name.trim().toLowerCase(Locale.ROOT), bone);
                }
            });
        }
        bones = java.util.Collections.unmodifiableMap(boneCopy);
        Map<String, Double> speedCopy = new TreeMap<>();
        if (speeds != null) {
            speeds.forEach((state, speed) -> {
                if (state != null && !state.isBlank() && speed != null && Double.isFinite(speed) && speed > 0
                        && Math.abs(speed - 1) > 1e-9) {
                    speedCopy.put(state.trim().toLowerCase(Locale.ROOT), Math.clamp(speed, 0.1, 10));
                }
            });
        }
        speeds = java.util.Collections.unmodifiableMap(speedCopy);
        hitboxWidth = size(hitboxWidth);
        hitboxHeight = size(hitboxHeight);
    }

    private static double offset(double value) {
        return Double.isFinite(value) ? Math.clamp(value, -MAX_OFFSET, MAX_OFFSET) : 0;
    }

    private static double size(double value) {
        return Double.isFinite(value) && value > 0 ? Math.min(value, MAX_HITBOX) : 0;
    }

    /** Whether it changes nothing. */
    public boolean isEmpty() {
        return offsetX == 0 && offsetY == 0 && offsetZ == 0 && bones.isEmpty() && speeds.isEmpty()
                && hitboxWidth == 0 && hitboxHeight == 0;
    }

    /** Whether the box it is hit by was sized by hand. */
    public boolean hasHitbox() {
        return hitboxWidth > 0 && hitboxHeight > 0;
    }

    /** How fast a state plays: its own speed, else {@code 1}. */
    public double speed(@NotNull String state) {
        return speeds.getOrDefault(state.trim().toLowerCase(Locale.ROOT), 1.0);
    }

    /** What is done to one bone itself, or {@code null} for nothing of its own. */
    public @Nullable Bone bone(@NotNull String name) {
        return bones.get(name.trim().toLowerCase(Locale.ROOT));
    }

    public @NotNull ModelTweaks withOffset(double x, double y, double z) {
        return new ModelTweaks(x, y, z, bones, speeds, hitboxWidth, hitboxHeight);
    }

    /** The same, with one bone changed; a bone that changes nothing is dropped. */
    public @NotNull ModelTweaks withBone(@NotNull String name, @NotNull Bone bone) {
        Map<String, Bone> copy = new LinkedHashMap<>(bones);
        copy.put(name, bone);
        return new ModelTweaks(offsetX, offsetY, offsetZ, copy, speeds, hitboxWidth, hitboxHeight);
    }

    public @NotNull ModelTweaks withBones(@NotNull Map<String, Bone> bones) {
        return new ModelTweaks(offsetX, offsetY, offsetZ, bones, speeds, hitboxWidth, hitboxHeight);
    }

    /** The same, with one state's speed; {@code 1} drops it. */
    public @NotNull ModelTweaks withSpeed(@NotNull String state, double speed) {
        Map<String, Double> copy = new LinkedHashMap<>(speeds);
        copy.put(state, speed);
        return new ModelTweaks(offsetX, offsetY, offsetZ, bones, copy, hitboxWidth, hitboxHeight);
    }

    public @NotNull ModelTweaks withSpeeds(@NotNull Map<String, Double> speeds) {
        return new ModelTweaks(offsetX, offsetY, offsetZ, bones, speeds, hitboxWidth, hitboxHeight);
    }

    /** The same, hit by one box of this size; zeroes go back to the model's own boxes. */
    public @NotNull ModelTweaks withHitbox(double width, double height) {
        return new ModelTweaks(offsetX, offsetY, offsetZ, bones, speeds, width, height);
    }
}
