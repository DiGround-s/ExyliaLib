package net.exylia.lib.api.betcore;

import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * A match that is over, kept so a player can look back at it.
 *
 * <p>Both names are frozen on the row. A history screen that resolved names at
 * read time would be a lookup per line, and would show a renamed player as
 * somebody who was never there.
 *
 * @param id        the match id
 * @param gameId    which game was played
 * @param modeId    which of that game's modes
 * @param playerA   the host
 * @param playerB   the guest
 * @param nameA     the host's name as it was
 * @param nameB     the guest's name as it was
 * @param currency  the currency the stake was in
 * @param stake     what each player put up
 * @param pot       both stakes together
 * @param houseCut  what the house kept
 * @param payout    what the winner was actually paid
 * @param bestOf    how many rounds the series ran to at most
 * @param roundsA   rounds the host took
 * @param roundsB   rounds the guest took
 * @param winner    who won, empty for a draw or a match nobody finished
 * @param reason    why it ended
 * @param startedAt when play began, in epoch milliseconds
 * @param endedAt   when it was settled, in epoch milliseconds
 * @since 1.0.0
 */
public record BetMatchRecord(
        @NotNull String id,
        @NotNull String gameId,
        @NotNull String modeId,
        @NotNull UUID playerA,
        @NotNull UUID playerB,
        @NotNull String nameA,
        @NotNull String nameB,
        @NotNull String currency,
        @NotNull BigDecimal stake,
        @NotNull BigDecimal pot,
        @NotNull BigDecimal houseCut,
        @NotNull BigDecimal payout,
        int bestOf,
        int roundsA,
        int roundsB,
        @NotNull Optional<UUID> winner,
        @NotNull EndReason reason,
        long startedAt,
        long endedAt) {

    /**
     * Whether this player won it.
     *
     * @param player the player
     * @return {@code true} when they took the pot
     */
    public boolean wasWonBy(@NotNull UUID player) {
        return winner.filter(player::equals).isPresent();
    }

    /**
     * The other player's name, given one of them.
     *
     * @param player one of the two
     * @return the opponent's name as it was recorded
     */
    @NotNull
    public String opponentOf(@NotNull UUID player) {
        return player.equals(playerA) ? nameB : nameA;
    }
}
