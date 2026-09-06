package net.exylia.lib.api.betcore;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * One match, as it was when you asked.
 *
 * <p>A snapshot, not a live view. The running match is mutated on the thread
 * that owns matches and lives in the plugin's own classloader, so neither
 * holding it nor reading it from elsewhere would be safe; this is a copy of the
 * fields a third party can act on. Ask
 * {@link BetCoreService#match(String)} again rather than keeping one across
 * ticks.
 *
 * <p>Seats are {@code 0} for the host and {@code 1} for whoever joined, which
 * is the same numbering {@link BetGame} sees. The guest is empty for as long as
 * the bet is still on offer.
 *
 * @param id             the match id, stable for the match's whole life
 * @param gameId         which game is being played
 * @param modeId         which of that game's modes
 * @param host           the player who offered the bet
 * @param guest          the player who took it, empty while nobody has
 * @param hostName       the host's name, frozen at creation
 * @param guestName      the guest's name, empty while there is no guest
 * @param currency       the currency the stake is in
 * @param stake          what each player put up
 * @param bestOf         how many rounds the series runs to at most
 * @param status         where the match is in its life
 * @param round          which round is being played, counting from {@code 1}
 * @param hostRoundsWon  rounds the host has taken
 * @param guestRoundsWon rounds the guest has taken
 * @param spectators     everybody watching
 * @param createdAt      when the bet was offered, in epoch milliseconds
 * @param startedAt      when play began, falling back to {@link #createdAt}
 * @since 1.0.0
 */
public record BetMatch(
        @NotNull String id,
        @NotNull String gameId,
        @NotNull String modeId,
        @NotNull UUID host,
        @NotNull Optional<UUID> guest,
        @NotNull String hostName,
        @NotNull Optional<String> guestName,
        @NotNull String currency,
        @NotNull BigDecimal stake,
        int bestOf,
        @NotNull MatchStatus status,
        int round,
        int hostRoundsWon,
        int guestRoundsWon,
        @NotNull @Unmodifiable Set<UUID> spectators,
        long createdAt,
        long startedAt) {

    /**
     * Both stakes together, which is what the winner is paid before the house
     * takes its share.
     *
     * @return the pot
     */
    @NotNull
    public BigDecimal pot() {
        return stake.add(stake);
    }

    /**
     * How many rounds one seat has to win to take the series.
     *
     * @return the rounds needed
     */
    public int roundsToWin() {
        return bestOf / 2 + 1;
    }

    /**
     * Whether both seats are taken.
     *
     * @return {@code true} once somebody has joined
     */
    public boolean isFull() {
        return guest.isPresent();
    }

    /**
     * Which seat a player is in.
     *
     * @param player the player
     * @return {@code 0} for the host, {@code 1} for the guest, {@code -1} when
     *         they are not playing this match
     */
    public int seatOf(@NotNull UUID player) {
        if (player.equals(host)) return 0;
        return guest.filter(player::equals).isPresent() ? 1 : -1;
    }

    /**
     * Whether a player is one of the two playing, ignoring spectators.
     *
     * @param player the player
     * @return {@code true} when they hold a seat
     */
    public boolean isPlayer(@NotNull UUID player) {
        return seatOf(player) >= 0;
    }

    /**
     * How many rounds a seat has taken.
     *
     * @param seat {@code 0} for the host, {@code 1} for the guest
     * @return the rounds won by that seat
     * @throws IllegalArgumentException when the seat is neither
     */
    public int roundsWon(int seat) {
        return switch (seat) {
            case 0 -> hostRoundsWon;
            case 1 -> guestRoundsWon;
            default -> throw new IllegalArgumentException("Seats are 0 and 1, got " + seat);
        };
    }
}
