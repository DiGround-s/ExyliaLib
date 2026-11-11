package net.exylia.lib.dialogue;

import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * A conversation in a box at the bottom of the screen: lines written out one at a time, with the speaker's name and
 * face, and answers to choose from under the last line.
 *
 * <pre>{@code
 * Dialogue talk = Dialogue.of(List.of(
 *                 Dialogue.Line.of(Text.of("{letters}Welcome, traveller.").build()).speaker(name).face("Notch"),
 *                 Dialogue.Line.of(Text.of("{letters}Shall I show you around?").build()).speaker(name).face("Notch")))
 *         .answers(List.of(Text.of("Lead the way").build(), Text.of("Not now").build()));
 * Dialogues.of(plugin).open(player, talk).thenAccept(result -> ...);
 * }</pre>
 *
 * @param lines   what is said, in order; at least one
 * @param answers offered under the last line; empty for none
 * @param mode    {@link Mode#HUD} keeps the player walking, {@link Mode#SCREEN} holds them with a cursor
 * @param speed   letters written a second; 0 shows each line whole
 * @param width   pixels the text wraps at
 * @param onLine  runs on the player's thread when a line starts, with its index; {@code null} for nothing
 * @param hints   the hint lines under the box: reading, choosing (HUD) and choosing (screen); {@code null} for the
 *                library's own
 * @since 1.590.0
 */
public record Dialogue(@NotNull List<Line> lines, @NotNull List<Component> answers, @NotNull Mode mode,
                       double speed, int width, @Nullable IntConsumer onLine, @Nullable Hints hints) {

    /** Where the box is drawn. */
    public enum Mode {
        /** Over the hotbar on the HUD: the player keeps walking and looking round; the wheel picks an answer. */
        HUD,
        /** On a full screen with a cursor: the player stands still and clicks the line or an answer. */
        SCREEN;

        /** {@code hud} or {@code screen}, any case; {@code fallback} for anything else. */
        public static @NotNull Mode parse(@Nullable String name, @NotNull Mode fallback) {
            if (name == null) return fallback;
            return switch (name.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "hud" -> HUD;
                case "screen" -> SCREEN;
                default -> fallback;
            };
        }
    }

    /**
     * One thing said.
     *
     * @param text    the words
     * @param speaker the name over them; {@code null} for narration
     * @param face    a player name whose face is drawn beside them; {@code null} for none
     */
    public record Line(@NotNull Component text, @Nullable Component speaker, @Nullable String face) {

        public static @NotNull Line of(@NotNull Component text) {
            return new Line(text, null, null);
        }

        public @NotNull Line speaker(@Nullable Component name) {
            return new Line(text, name, face);
        }

        public @NotNull Line face(@Nullable String player) {
            return new Line(text, speaker, player == null || player.isBlank() ? null : player.trim());
        }
    }

    /** The hint lines under the box, already coloured. */
    public record Hints(@NotNull Component reading, @NotNull Component choosing, @NotNull Component clicking) {
    }

    /**
     * How a conversation ended.
     *
     * @param finished whether the player read it to the end (and chose, when there were answers)
     * @param answer   the index of the answer chosen; -1 when there were none or it did not finish
     */
    public record Result(boolean finished, int answer) {

        public static final Result CANCELLED = new Result(false, -1);
    }

    public Dialogue {
        lines = List.copyOf(lines);
        answers = List.copyOf(answers);
        if (lines.isEmpty()) throw new IllegalArgumentException("A dialogue needs at least one line");
        speed = Math.max(0, speed);
        width = Math.clamp(width, 80, 400);
    }

    /** A conversation on the HUD at 40 letters a second, wrapping at 220 pixels, with no answers. */
    public static @NotNull Dialogue of(@NotNull List<Line> lines) {
        return new Dialogue(lines, List.of(), Mode.HUD, 40, 220, null, null);
    }

    public @NotNull Dialogue answers(@NotNull List<Component> choices) {
        return new Dialogue(lines, choices, mode, speed, width, onLine, hints);
    }

    public @NotNull Dialogue mode(@NotNull Mode where) {
        return new Dialogue(lines, answers, where, speed, width, onLine, hints);
    }

    public @NotNull Dialogue speed(double lettersPerSecond) {
        return new Dialogue(lines, answers, mode, lettersPerSecond, width, onLine, hints);
    }

    public @NotNull Dialogue width(int pixels) {
        return new Dialogue(lines, answers, mode, speed, pixels, onLine, hints);
    }

    public @NotNull Dialogue onLine(@Nullable IntConsumer handler) {
        return new Dialogue(lines, answers, mode, speed, width, handler, hints);
    }

    public @NotNull Dialogue hints(@Nullable Hints lines) {
        return new Dialogue(this.lines, answers, mode, speed, width, onLine, lines);
    }
}
