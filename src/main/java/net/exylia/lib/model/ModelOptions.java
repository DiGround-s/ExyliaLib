package net.exylia.lib.model;

import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * How one model is worn.
 *
 * <pre>{@code
 * ModelOptions options = ModelOptions.DEFAULT
 *         .withScale(1.4)
 *         .withState(ModelState.ATTACK, "animation.golem.smash");
 * }</pre>
 *
 * <p>Immutable; every {@code with} returns a copy.
 *
 * @param scale       how big it is drawn; 1 is the size it was modelled at.
 *                    Multiplied by the carrier's own {@code scale} attribute,
 *                    so a mob that grows takes its model with it
 * @param states      the animation each state plays, by state
 *                    ({@link ModelState}); a state left out is matched by name
 * @param lookAt      whether a bone called {@code head} turns towards what the
 *                    carrier is looking at
 * @param heads       whether cubes with a design may be drawn as textured heads
 *                    rather than as the block nearest their colours
 * @param viewRange   how far away it is drawn, in blocks
 * @param hitboxes    whether a model bigger than its carrier gets boxes of its
 *                    own that can be hit
 * @param nametag     whether the carrier's name is drawn above the model rather
 *                    than at the carrier's own height
 * @since 1.317.0
 */
public record ModelOptions(double scale, @NotNull Map<String, String> states, boolean lookAt, boolean heads,
                           double viewRange, boolean hitboxes, boolean nametag) {

    /** Modelled size, matched animations, a head that looks, textured heads, 48 blocks. */
    public static final ModelOptions DEFAULT = new ModelOptions(1, Map.of(), true, true, 48, true, true);

    public ModelOptions {
        scale = Double.isFinite(scale) && scale > 0 ? Math.min(scale, 16) : 1;
        Map<String, String> copy = new LinkedHashMap<>();
        if (states != null) {
            states.forEach((state, animation) -> {
                if (state != null && animation != null && !animation.isBlank()) {
                    copy.put(state.trim().toLowerCase(Locale.ROOT), animation.trim());
                }
            });
        }
        states = Map.copyOf(copy);
        viewRange = Double.isFinite(viewRange) && viewRange > 0 ? Math.min(viewRange, 128) : 48;
    }

    public @NotNull ModelOptions withScale(double scale) {
        return new ModelOptions(scale, states, lookAt, heads, viewRange, hitboxes, nametag);
    }

    public @NotNull ModelOptions withStates(@NotNull Map<String, String> states) {
        return new ModelOptions(scale, states, lookAt, heads, viewRange, hitboxes, nametag);
    }

    /** The same, with one state playing a named animation. */
    public @NotNull ModelOptions withState(@NotNull String state, @NotNull String animation) {
        Map<String, String> copy = new LinkedHashMap<>(states);
        copy.put(state, animation);
        return withStates(copy);
    }

    public @NotNull ModelOptions withLookAt(boolean lookAt) {
        return new ModelOptions(scale, states, lookAt, heads, viewRange, hitboxes, nametag);
    }

    public @NotNull ModelOptions withHeads(boolean heads) {
        return new ModelOptions(scale, states, lookAt, heads, viewRange, hitboxes, nametag);
    }

    public @NotNull ModelOptions withViewRange(double viewRange) {
        return new ModelOptions(scale, states, lookAt, heads, viewRange, hitboxes, nametag);
    }

    public @NotNull ModelOptions withHitboxes(boolean hitboxes) {
        return new ModelOptions(scale, states, lookAt, heads, viewRange, hitboxes, nametag);
    }

    public @NotNull ModelOptions withNametag(boolean nametag) {
        return new ModelOptions(scale, states, lookAt, heads, viewRange, hitboxes, nametag);
    }
}
