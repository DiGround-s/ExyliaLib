package net.exylia.lib.util.mob;

import net.exylia.lib.model.ModelOptions;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The 3D model a mob wears instead of its vanilla body.
 *
 * <pre>{@code
 * MobTemplate golem = MobTemplate.of("golem", EntityType.IRON_GOLEM)
 *         .withModel(MobModel.of("stone_golem").withScale(1.3)
 *                 .withState(ModelState.cast("slam"), "animation.golem.slam"));
 * }</pre>
 *
 * <p>The model is a {@code .bbmodel} in the plugin's own {@code models} folder
 * (see {@link net.exylia.lib.model.Models}). The mob underneath is still the
 * mob &mdash; its AI, its health, its hitbox &mdash; and the engine plays the
 * model's animations for it: idle, walk and run as it moves, {@code attack}
 * when it lands a blow, {@code hurt} when it takes one, {@code spawn} as it
 * appears, the cast's animation as a skill winds up, and {@code death}.
 *
 * @param model  the model's name, blank for none
 * @param scale  how big it is drawn; 1 is modelled size
 * @param states the animation each state plays, by state; a state left out is
 *               matched by name (see {@link net.exylia.lib.model.Model#animationFor})
 * @param death  how it dies
 * @param lookAt whether its head turns towards what it is looking at
 * @since 1.317.0
 */
public record MobModel(@NotNull String model, double scale, @NotNull Map<String, String> states,
                       @NotNull Death death, boolean lookAt) {

    /** How a model mob dies. */
    public enum Death {
        /** Its death animation and then it shrinks away; with none, it comes apart. */
        AUTO,
        /** Its death animation, then it shrinks away. */
        ANIMATION,
        /** It comes apart at once. */
        APART,
        /** Its death animation, then it comes apart. */
        ANIMATION_APART,
        /** The model goes and the template's own death reaction plays, on the vanilla body. */
        VANILLA
    }

    /** No model: the vanilla body. */
    public static final MobModel NONE = new MobModel("", 1, Map.of(), Death.AUTO, true);

    public MobModel {
        model = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
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
        death = death == null ? Death.AUTO : death;
    }

    /** A model at its modelled size, with every animation matched by name. */
    public static @NotNull MobModel of(@NotNull String model) {
        return NONE.withModel(model);
    }

    /** Whether there is a model at all. */
    public boolean has() {
        return !model.isEmpty();
    }

    /** How it is worn, as the model module takes it. */
    public @NotNull ModelOptions options() {
        return ModelOptions.DEFAULT.withScale(scale).withStates(states).withLookAt(lookAt);
    }

    public @NotNull MobModel withModel(@NotNull String model) {
        return new MobModel(model, scale, states, death, lookAt);
    }

    public @NotNull MobModel withScale(double scale) {
        return new MobModel(model, scale, states, death, lookAt);
    }

    public @NotNull MobModel withStates(@NotNull Map<String, String> states) {
        return new MobModel(model, scale, states, death, lookAt);
    }

    /** The same, with one state playing a named animation; a blank animation goes back to matching by name. */
    public @NotNull MobModel withState(@NotNull String state, @NotNull String animation) {
        Map<String, String> copy = new LinkedHashMap<>(states);
        if (animation.isBlank()) {
            copy.remove(state.trim().toLowerCase(Locale.ROOT));
        } else {
            copy.put(state, animation);
        }
        return withStates(copy);
    }

    public @NotNull MobModel withDeath(@NotNull Death death) {
        return new MobModel(model, scale, states, death, lookAt);
    }

    public @NotNull MobModel withLookAt(boolean lookAt) {
        return new MobModel(model, scale, states, death, lookAt);
    }
}
