package net.exylia.lib.npc;

import net.exylia.lib.effect.Ticks;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A short gesture a placed NPC plays: arm swings, a flinch, a crouch, and the pauses
 * between them.
 *
 * <pre>{@code
 * NpcAnimation wave = NpcAnimation.parse("wave");
 * NpcAnimation custom = NpcAnimation.parse("swing, 300ms, offhand, 300ms, crouch, 500ms, stand");
 * placed.play(wave);
 * }</pre>
 *
 * <h2>The format</h2>
 * Words separated by commas, played left to right:
 * <ul>
 *   <li>{@code swing}: swings the main arm.</li>
 *   <li>{@code offhand}: swings the off-hand arm.</li>
 *   <li>{@code hurt}: flinches red.</li>
 *   <li>{@code crouch}: crouches.</li>
 *   <li>{@code stand}: back to its own pose.</li>
 *   <li>a length of time, {@code 250ms}, {@code 1s}, {@code 5t}: waits that long before
 *       the next word. A bare number is seconds, so write the unit.</li>
 *   <li>a preset name, which stands for its own words: {@code wave}, {@code wave-offhand},
 *       {@code nod}, {@code bow}, {@code flinch}, {@code punch}, {@code cheer}.</li>
 * </ul>
 * A word nobody knows is skipped, never thrown. An animation that crouched always ends
 * in its own pose, so a gesture cut short by a typo never leaves a body crouched.
 *
 * <p>Every one of these is a vanilla packet: nothing is modelled, so it works on any
 * client with no resource pack. Ticks are the resolution; a wait under 50 ms is the same
 * moment.
 *
 * <p>Immutable and cheap to keep: parse once when the file is read.
 *
 * @since 1.300.0
 */
public final class NpcAnimation {

    /** What a body can do in one packet. */
    public enum Move {
        /** Swings the main arm. */
        SWING,
        /** Swings the off-hand arm. */
        SWING_OFFHAND,
        /** Flinches red. */
        HURT,
        /** Crouches. */
        CROUCH,
        /** Back to the pose its spec says. */
        STAND
    }

    /**
     * One move at one moment.
     *
     * @param atMillis how long after the start it happens
     * @param move     what happens
     */
    public record Step(long atMillis, @NotNull Move move) {
    }

    /** The named gestures, written in the format itself so they read the same as a file. */
    private static final Map<String, String> PRESETS = Map.of(
            "wave", "swing, 250ms, swing, 250ms, swing",
            "wave-offhand", "offhand, 250ms, offhand, 250ms, offhand",
            "nod", "crouch, 200ms, stand, 200ms, crouch, 200ms, stand",
            "bow", "crouch, 800ms, stand",
            "flinch", "hurt",
            "punch", "swing, 150ms, offhand, 150ms, swing",
            "cheer", "swing, offhand, 300ms, swing, offhand, 300ms, crouch, 150ms, stand");

    private static final NpcAnimation NONE = new NpcAnimation(List.of());

    private final List<Step> steps;

    private NpcAnimation(List<Step> steps) {
        this.steps = steps;
    }

    /** An animation that does nothing. */
    public static @NotNull NpcAnimation none() {
        return NONE;
    }

    /** The preset names {@link #parse} knows, for a tab completion or a dialog. */
    public static @NotNull List<String> presets() {
        return PRESETS.keySet().stream().sorted().toList();
    }

    /**
     * Reads an animation from configuration, skipping any word it does not know.
     *
     * @param text what the file said; {@code null} or blank for {@link #none()}
     * @return the animation
     */
    public static @NotNull NpcAnimation parse(@Nullable String text) {
        return parse(text, null);
    }

    /**
     * Reads an animation, telling the caller about every word it skipped.
     *
     * <p>What a command or a dialog uses to tell the admin what was wrong rather than
     * playing half of what they wrote in silence.
     *
     * @param text    what was written; {@code null} or blank for {@link #none()}
     * @param unknown told each word that was skipped, or {@code null} to ignore them
     * @return the animation
     */
    public static @NotNull NpcAnimation parse(@Nullable String text, @Nullable Consumer<String> unknown) {
        if (text == null || text.isBlank()) {
            return NONE;
        }
        List<Step> steps = new ArrayList<>();
        long end = read(text, 0L, steps, unknown, 0);
        boolean posed = steps.stream().anyMatch(step -> step.move() == Move.CROUCH);
        if (posed && steps.getLast().move() != Move.STAND) {
            steps.add(new Step(end, Move.STAND));
        }
        return steps.isEmpty() ? NONE : new NpcAnimation(List.copyOf(steps));
    }

    /** Appends one text's steps from {@code at}; returns when it ends. Presets recurse once. */
    private static long read(String text, long at, List<Step> steps, Consumer<String> unknown, int depth) {
        for (String raw : text.split(",")) {
            String word = raw.trim().toLowerCase(Locale.ROOT);
            if (word.isEmpty()) {
                continue;
            }
            Move move = moveOf(word);
            if (move != null) {
                steps.add(new Step(at, move));
                continue;
            }
            String preset = depth == 0 ? PRESETS.get(word) : null;
            if (preset != null) {
                at = read(preset, at, steps, unknown, depth + 1);
                continue;
            }
            long ticks = Character.isDigit(word.charAt(0)) ? Ticks.parse(word, -1L) : -1L;
            if (ticks >= 0) {
                at += ticks * 50L;
            } else if (unknown != null) {
                unknown.accept(raw.trim());
            }
        }
        return at;
    }

    private static Move moveOf(String word) {
        return switch (word) {
            case "swing", "swing-main", "main" -> Move.SWING;
            case "offhand", "swing-offhand", "off-hand" -> Move.SWING_OFFHAND;
            case "hurt", "damage" -> Move.HURT;
            case "crouch", "sneak" -> Move.CROUCH;
            case "stand", "uncrouch", "unsneak" -> Move.STAND;
            default -> null;
        };
    }

    /** The moves in the order they happen; read-only. */
    public @NotNull List<Step> steps() {
        return steps;
    }

    /** Whether it does nothing. */
    public boolean isEmpty() {
        return steps.isEmpty();
    }

    /** When its last move happens, in milliseconds from the start. */
    public long lengthMillis() {
        return steps.isEmpty() ? 0L : steps.getLast().atMillis();
    }

    @Override
    public String toString() {
        return "NpcAnimation" + steps;
    }
}
