package net.exylia.lib.api.betcore;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Optional;

/**
 * A game's rules. One instance per game, for the life of the server.
 *
 * <h2>Two things kept apart</h2>
 * This is registered once, holds no match state, and is asked the same
 * questions by every match at once. The mutable half is {@code S}, the board
 * state, which the plugin creates with {@link #start} and hands back to
 * {@link #move} and {@link #render} without ever looking inside it.
 *
 * <h2>No Bukkit</h2>
 * An implementation must not import anything from {@code org.bukkit}. That is
 * not a style rule: it is what makes the rules unit-testable without a server,
 * and it is the only part of a betting game where a bug is silent — a board
 * that awards the wrong win pays the wrong player.
 *
 * <p>Seats are {@code 0} and {@code 1}: seat 0 is the host, seat 1 is whoever
 * joined. The rules never learn who is sitting in them.
 *
 * @param <S> this game's board state
 *
 * @since 1.0.0
 */
public interface BetGame<S> {

    /** The id this game is registered, configured and commanded under. */
    @NotNull
    String id();

    /** What menus and messages call it. */
    @NotNull
    String displayName();

    /** What the main menu draws it as. */
    @NotNull
    String icon();

    /** The lines under that icon on the main menu. */
    @NotNull
    @Unmodifiable
    List<String> description();

    /** The menu file its board is drawn from. */
    @NotNull
    String boardMenuId();

    /** The modes the owner turned on, in the order the file writes them. */
    @NotNull
    @Unmodifiable
    List<GameMode> modes();

    /** One mode by id, when it exists and is turned on. */
    @NotNull
    default Optional<GameMode> mode(@NotNull String modeId) {
        return modes().stream().filter(mode -> mode.id().equals(modeId)).findFirst();
    }

    /** The mode a create screen starts on. */
    @NotNull
    String defaultMode();

    /**
     * How many squares the board menu must offer.
     *
     * <p>Checked against the board menu's declared slots when the game loads. A
     * game that wants more squares than the file has is refused there, because
     * a board missing its last square is unplayable in a way nobody would
     * diagnose from a stack trace.
     */
    int cells(@NotNull GameMode mode);

    /**
     * Whether both seats choose at once rather than in turn.
     *
     * <p>True for Rock Paper Scissors, where a turn order would give the second
     * player the game.
     */
    boolean simultaneous(@NotNull GameMode mode);

    /**
     * A fresh board.
     *
     * @param mode which mode is being played
     * @param seed the match's seed, so anything random is the same on both
     *             boards and the same again after a redraw
     */
    @NotNull
    S start(@NotNull GameMode mode, long seed);

    /**
     * Applies a seat's click on a square.
     *
     * <p>Pure with respect to everything except {@code state}, which it may
     * mutate. Called on one thread only.
     *
     * @param state the board
     * @param mode  which mode is being played
     * @param seat  who clicked, {@code 0} or {@code 1}
     * @param cell  which square, in board order
     * @return what that did to the round
     */
    @NotNull
    MoveResult move(@NotNull S state, @NotNull GameMode mode, int seat, int cell);

    /**
     * What the clock running out does to the round.
     *
     * <p>Answering {@code null} — the default — means the game has no opinion,
     * and the match treats it as a forfeit by whoever was on the clock. That is
     * right for a game with turns: the player who did not move lost.
     *
     * <p>A simultaneous game has to answer, because there is no seat on the
     * clock to forfeit. Rock Paper Scissors scores whatever is on the table:
     * one choice beats none, and no choices at all is a round nobody played.
     *
     * @param state the board
     * @param mode  which mode is being played
     * @return the round's outcome, or {@code null} to forfeit the series
     */
    @Nullable
    default MoveResult timeOut(@NotNull S state, @NotNull GameMode mode) {
        return null;
    }

    /**
     * Which seat may move now, or {@code -1} when either may.
     *
     * <p>A simultaneous game answers {@code -1} for as long as anybody still
     * has a choice to make.
     */
    int turn(@NotNull S state, @NotNull GameMode mode);

    /**
     * The board as one viewer sees it.
     *
     * <p>Both seats get their own call, because a board is not symmetric: a
     * player sees their own pieces as theirs, and in a simultaneous game they
     * see their own choice and not the other one.
     *
     * @param state      the board
     * @param mode       which mode is being played
     * @param viewerSeat the seat looking, or {@code -1} for a spectator
     * @return exactly {@link #cells} views, in board order
     */
    @NotNull
    @Unmodifiable
    List<CellView> render(@NotNull S state, @NotNull GameMode mode, int viewerSeat);
}
