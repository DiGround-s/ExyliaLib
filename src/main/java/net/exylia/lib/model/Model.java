package net.exylia.lib.model;

import net.exylia.lib.model.internal.Clip;
import net.exylia.lib.model.internal.Rig;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * One model, read from a Blockbench project: its bones, its cubes and its
 * animations, ready to be worn by any number of carriers.
 *
 * <pre>{@code
 * Model golem = Models.of(this).model("stone_golem");
 * if (golem != null) {
 *     getLogger().info(golem.name() + ": " + golem.parts() + " cubes, " + golem.animations());
 * }
 * }</pre>
 *
 * <p>Immutable and shared: a model reloaded from a changed file is a new
 * instance, and whoever is already wearing the old one keeps it until it is
 * attached again.
 *
 * @since 1.317.0
 */
public final class Model {

    private final String name;
    private final Rig rig;
    private final Map<String, Clip> clips;
    private final List<String> problems;

    @ApiStatus.Internal
    public Model(@NotNull String name, @NotNull Rig rig, @NotNull Map<String, Clip> clips,
                 @NotNull List<String> problems) {
        this.name = name;
        this.rig = rig;
        Map<String, Clip> byName = new LinkedHashMap<>();
        clips.forEach((key, clip) -> byName.put(key.toLowerCase(Locale.ROOT), clip));
        this.clips = Collections.unmodifiableMap(byName);
        this.problems = List.copyOf(problems);
    }

    /** Its name: the file's, without {@code .bbmodel}, lower case. */
    public @NotNull String name() {
        return name;
    }

    /** Every animation it has, by the name it was given in Blockbench, sorted. */
    public @NotNull Set<String> animations() {
        TreeSet<String> names = new TreeSet<>();
        for (Clip clip : clips.values()) {
            names.add(clip.name());
        }
        return Collections.unmodifiableSet(names);
    }

    /** Its bones, parents first. */
    public @NotNull List<String> bones() {
        List<String> names = new ArrayList<>();
        for (Rig.Bone bone : rig.bones()) {
            names.add(bone.name());
        }
        return names;
    }

    /** How many cubes it has, each of which is one display on a client. */
    public int parts() {
        return rig.parts().size();
    }

    /** How wide it is at scale 1, in blocks. */
    public double width() {
        return rig.width();
    }

    /** How tall it is at scale 1, in blocks. */
    public double height() {
        return rig.height();
    }

    /** What could not be read from its files, once each; empty when everything was. */
    public @NotNull List<String> problems() {
        return problems;
    }

    /** Whether it has an animation by that name, in full or by the part after its last dot. */
    public boolean hasAnimation(@NotNull String animation) {
        return clip(animation) != null;
    }

    /**
     * The animation it plays for a state, or {@code null} when it has none.
     *
     * <p>The options' choice first, when the model has that animation. Then by
     * name: an animation called the state ({@code walk}, or
     * {@code animation.golem.walk} by its last part), then one whose name holds
     * the state as a word ({@code golem_walk_cycle}). A cast tries the skill's
     * name, {@code cast_<skill>} and {@code <skill>_cast}, then {@code cast}, then
     * {@code attack}. A run with no animation of its own walks.
     *
     * @param state   a {@link ModelState}, or an animation's own name
     * @param options what the wearer chose
     * @return the animation's name, as {@link #animations()} lists it
     */
    public @Nullable String animationFor(@NotNull String state, @NotNull ModelOptions options) {
        Clip clip = clipFor(state, options);
        return clip == null ? null : clip.name();
    }

    /** The clip for a state, resolved as {@link #animationFor} describes. */
    @ApiStatus.Internal
    public @Nullable Clip clipFor(@NotNull String state, @NotNull ModelOptions options) {
        String key = state.trim().toLowerCase(Locale.ROOT);
        String chosen = options.states().get(key);
        if (chosen != null) {
            Clip clip = clip(chosen);
            if (clip != null) {
                return clip;
            }
        }
        Clip own = clip(key);
        if (own != null) {
            return own;
        }
        if (ModelState.isCast(key)) {
            String skill = key.substring(ModelState.CAST.length());
            for (String name : new String[]{skill, "cast_" + skill, skill + "_cast"}) {
                Clip clip = clip(name);
                if (clip != null) {
                    return clip;
                }
            }
            Clip cast = byWord("cast");
            return cast != null ? cast : clipFor(ModelState.ATTACK, options);
        }
        Clip word = byWord(key);
        if (word != null) {
            return word;
        }
        if (key.equals(ModelState.RUN)) {
            return clipFor(ModelState.WALK, options);
        }
        return null;
    }

    /** An animation by name, in full or by its last part; {@code null} when there is none. */
    @ApiStatus.Internal
    public @Nullable Clip clip(@NotNull String name) {
        String key = name.trim().toLowerCase(Locale.ROOT);
        Clip clip = clips.get(key);
        if (clip != null) {
            return clip;
        }
        for (Map.Entry<String, Clip> entry : clips.entrySet()) {
            String full = entry.getKey();
            int dot = full.lastIndexOf('.');
            if (dot >= 0 && full.substring(dot + 1).equals(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private @Nullable Clip byWord(String word) {
        for (Map.Entry<String, Clip> entry : clips.entrySet()) {
            String[] tokens = entry.getKey().split("[._\\- ]");
            if (phaseOf(tokens) > 0) {
                // A phase's own walk is not the walk.
                continue;
            }
            for (String token : tokens) {
                if (token.equals(word)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    /** An animation holding both words, one of them a phase's {@code p<n>}. */
    private @Nullable Clip byWords(String word, String tag) {
        for (Map.Entry<String, Clip> entry : clips.entrySet()) {
            boolean hasWord = false;
            boolean hasTag = false;
            for (String token : entry.getKey().split("[._\\- ]")) {
                hasWord |= token.equals(word);
                hasTag |= token.equals(tag);
            }
            if (hasWord && hasTag) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** The phase a name is for, {@code p2} and so on, or {@code 0}. */
    private static int phaseOf(String[] tokens) {
        for (String token : tokens) {
            if (token.length() > 1 && token.charAt(0) == 'p' && token.substring(1).chars().allMatch(Character::isDigit)) {
                return Integer.parseInt(token.substring(1));
            }
        }
        return 0;
    }

    /**
     * The animation it plays for a state while a fight is in a given phase.
     *
     * <p>Phase {@code n} beyond the first plays, in this order: the options'
     * choice for {@code p<n>:<state>}, an animation called {@code <state>_p<n>}
     * (by its last part too), one whose name holds both the state and
     * {@code p<n>} as words ({@code golem_walk_p2}). Without one it plays what the
     * state plays in every phase. A cast's state word is the skill's name.
     *
     * @param state   a {@link ModelState}, or an animation's own name
     * @param options what the wearer chose
     * @param phase   the fight's phase, {@code 1} at the start
     * @return the animation's name, as {@link #animations()} lists it
     * @since 1.321.0
     */
    public @Nullable String animationFor(@NotNull String state, @NotNull ModelOptions options, int phase) {
        Clip clip = clipFor(state, options, phase);
        return clip == null ? null : clip.name();
    }

    /** The clip for a state in a phase, resolved as {@link #animationFor(String, ModelOptions, int)} describes. */
    @ApiStatus.Internal
    public @Nullable Clip clipFor(@NotNull String state, @NotNull ModelOptions options, int phase) {
        if (phase > 1) {
            String key = state.trim().toLowerCase(Locale.ROOT);
            String tag = "p" + phase;
            String chosen = options.states().get(tag + ":" + key);
            if (chosen != null) {
                Clip clip = clip(chosen);
                if (clip != null) {
                    return clip;
                }
            }
            String word = ModelState.isCast(key) ? key.substring(ModelState.CAST.length()) : key;
            Clip own = clip(word + "_" + tag);
            if (own != null) {
                return own;
            }
            Clip both = byWords(word, tag);
            if (both != null) {
                return both;
            }
        }
        return clipFor(state, options);
    }

    /** Its bones and cubes. */
    @ApiStatus.Internal
    public @NotNull Rig rig() {
        return rig;
    }

    @Override
    public String toString() {
        return "Model[" + name + ", " + parts() + " cubes, " + clips.size() + " animations]";
    }
}
