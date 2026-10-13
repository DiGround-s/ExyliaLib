package net.exylia.lib.api.betcore;

import net.exylia.lib.api.ExyliaAPI;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * ExyliaBetCore: players bet on games they play against each other, on boards drawn in arenas.
 *
 * <pre>{@code
 * ExyliaAPI.get(BetCoreService.class).ifPresent(bets -> bets.openLobby(player, "tictactoe"));
 * }</pre>
 *
 * <p>Reads answer from memory and are safe from any thread. Everything that acts runs the same
 * flow the player's own command runs, with every permission, balance and busy check and the
 * messages the player sees; it returns before the stake is taken, so a caller that needs the
 * outcome listens for the events in {@code event}.
 *
 * @see ExyliaAPI#get(Class)
 * @since exylia-api 2.0.0
 */
public interface BetCoreService {

    // ── Games ──────────────────────────────────────────────────────────────

    /** Every game this build ships, playable or not. */
    @NotNull
    @Unmodifiable
    List<BetGameInfo> games();

    @NotNull
    Optional<BetGameInfo> game(@NotNull String gameId);

    // ── Matches ────────────────────────────────────────────────────────────

    @NotNull
    Optional<BetMatch> match(@NotNull String matchId);

    /** The live match a player has a seat in: an offer, a duel, or one being played. */
    @NotNull
    Optional<BetMatch> matchOf(@NotNull UUID player);

    /** Whether the player is in an arena, playing or about to. */
    boolean isPlaying(@NotNull UUID player);

    /** Every live match. */
    @NotNull
    @Unmodifiable
    List<BetMatch> matches();

    /** The open offers of a game anybody may take, oldest first. */
    @NotNull
    @Unmodifiable
    List<BetMatch> offers(@NotNull String gameId);

    // ── Acting as a player ─────────────────────────────────────────────────

    /** Opens a game's lobby, for an NPC or another plugin's menu. */
    void openLobby(@NotNull Player player, @NotNull String gameId);

    /** Lists an offer, as {@code /<game> create <stake> <currency>} does. */
    void createOffer(@NotNull Player host, @NotNull String gameId, @NotNull String currency,
                     @NotNull BigDecimal stake);

    /** Challenges one player, as {@code /<game> duel <target> <stake> <currency>} does. */
    void duel(@NotNull Player host, @NotNull Player target, @NotNull String gameId,
              @NotNull String currency, @NotNull BigDecimal stake);

    /** Takes a seat in an offer, or accepts a duel sent to this player. */
    void join(@NotNull Player player, @NotNull String matchId);

    /** Walks the player out of whatever they are in: an open offer is refunded, a match is lost. */
    void forfeit(@NotNull Player player);

    // ── Acting as the server ───────────────────────────────────────────────

    /**
     * Calls a live match off and refunds every stake.
     *
     * @return whether there was such a match
     */
    boolean cancel(@NotNull String matchId);

    // ── Records ────────────────────────────────────────────────────────────

    /** A player's record at a game; zeros when they never played it. */
    @NotNull
    CompletableFuture<BetStats> stats(@NotNull UUID player, @NotNull String gameId);
}
