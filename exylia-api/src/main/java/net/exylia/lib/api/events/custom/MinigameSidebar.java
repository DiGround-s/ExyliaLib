package net.exylia.lib.api.events.custom;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

/**
 * The scoreboard a minigame shows while it is being played.
 *
 * <p>Only the game board is declared here. The lobby board — the one players
 * see while the event fills up and counts down — belongs to the server owner
 * and is the same for every minigame, so ExyliaEvents draws it from its own
 * {@code scoreboards.yml} without asking.
 *
 * <p>The lines take ExyliaLib palette tokens ({@code {primary}},
 * {@code {letters}}), PlaceholderAPI placeholders, and the handler's own
 * placeholders from {@link MinigameHandler#placeholders}. These are always
 * available:
 *
 * <ul>
 *   <li>{@code %event_name%} — the arena's display name</li>
 *   <li>{@code %event_type%} — the minigame's display name</li>
 *   <li>{@code %players%}, {@code %total%} — how many joined</li>
 *   <li>{@code %alive%} — how many have not been eliminated</li>
 *   <li>{@code %time%} — what is left on the clock, already formatted</li>
 * </ul>
 *
 * @param title  the title frames; one entry for a still title, several to
 *               animate it
 * @param lines  the lines, top first, at most 15
 * @param update how often the board redraws, in <b>ticks</b>
 * @since 1.7.0
 */
public record MinigameSidebar(
        @NotNull @Unmodifiable List<String> title,
        @NotNull @Unmodifiable List<String> lines,
        long update) {

    private static final long DEFAULT_UPDATE_TICKS = 10L;

    /** Freezes the lines and fills in a refresh rate nobody chose. */
    public MinigameSidebar {
        title = title == null ? List.of() : List.copyOf(title);
        lines = lines == null ? List.of() : List.copyOf(lines);
        update = update <= 0 ? DEFAULT_UPDATE_TICKS : update;
    }

    /**
     * A board with a still title, refreshing twice a second.
     *
     * @param title the title
     * @param lines the lines, top first
     * @return the board
     */
    public static @NotNull MinigameSidebar of(@NotNull String title, @NotNull List<String> lines) {
        return new MinigameSidebar(List.of(title), lines, DEFAULT_UPDATE_TICKS);
    }

    /** The same board, redrawing every {@code ticks} ticks. */
    public @NotNull MinigameSidebar every(long ticks) {
        return new MinigameSidebar(title, lines, ticks);
    }
}
