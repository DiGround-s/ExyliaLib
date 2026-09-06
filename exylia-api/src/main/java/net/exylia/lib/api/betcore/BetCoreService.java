package net.exylia.lib.api.betcore;

import net.exylia.lib.api.ExyliaAPI;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Reading and driving ExyliaBetCore.
 *
 * <pre>{@code
 * ExyliaAPI.get(BetCoreService.class).ifPresent(bets ->
 *     bets.playedBy(player.getUniqueId())
 *         .ifPresent(match -> player.sendMessage("Playing " + match.gameId())));
 * }</pre>
 *
 * <p>Registered with Bukkit's {@link org.bukkit.plugin.ServicesManager} when
 * ExyliaBetCore enables. Reach it through {@link ExyliaAPI#get(Class)}, and
 * treat an empty result as "this server does not run betting games" rather than
 * as a failure.
 *
 * <h2>Queries are snapshots, actions are queued</h2>
 * Everything that returns a value reads the plugin's live registries and copies
 * what it found, so a {@link BetMatch} you are holding is the match as it was
 * and not as it is. Everything that returns {@code void} hops onto the thread
 * that owns matches and returns before the work is done — which is why none of
 * them report an outcome. Listen for
 * {@link net.exylia.lib.api.betcore.event.BetMatchStartEvent} and its relatives
 * to find out what happened.
 *
 * <h2>Adding a game of your own</h2>
 * {@link #registerGame(BetGame)} puts a {@link BetGame} this plugin did not
 * ship into the same registry the lobby, the create screen, the commands and
 * the placeholders all read, so a game added there is a game everywhere. What
 * it needs from you is a board menu of its own, at
 * {@link BetGame#boardMenuId()}, which you write out yourself.
 *
 * @since 1.0.0
 */
public interface BetCoreService {

    // ── Games ──────────────────────────────────────────────────────────────

    /**
     * Every game that is turned on, in the order the main menu draws them.
     *
     * @return the game ids
     */
    @NotNull
    @Unmodifiable
    List<String> games();

    /**
     * Whether a game is turned on right now.
     *
     * @param gameId the game id
     * @return {@code true} when it is registered
     */
    boolean isGameEnabled(@NotNull String gameId);

    /**
     * One game's rules.
     *
     * @param gameId the game id
     * @return the game, or empty when nothing is registered under that id
     */
    @NotNull
    Optional<BetGame<?>> game(@NotNull String gameId);

    /**
     * The modes one game offers, in the order its file writes them.
     *
     * @param gameId the game id
     * @return its modes, empty when the game is not registered
     */
    @NotNull
    @Unmodifiable
    List<GameMode> modes(@NotNull String gameId);

    /**
     * Adds a game of your own.
     *
     * <p>Call it from your own enable, after this plugin's. Its board menu is
     * yours to ship; this plugin loads whatever is at
     * {@link BetGame#boardMenuId()} the next time menus are read.
     *
     * @param game the game to register
     * @throws IllegalStateException when that id is already taken
     */
    void registerGame(@NotNull BetGame<?> game);

    /**
     * Removes a game you added.
     *
     * <p>Matches already being played on it are left alone: pulling the rules
     * out from under a live board would strand a pot with nobody able to win
     * it. Unregister on disable and let the running matches finish.
     *
     * @param gameId the game id
     */
    void unregisterGame(@NotNull String gameId);

    /**
     * The currencies a bet can actually be made in right now.
     *
     * <p>A currency the owner configured but whose economy plugin is missing is
     * not in here, which is the difference between what is written down and
     * what a player could stake.
     *
     * @return the usable currency ids
     */
    @NotNull
    @Unmodifiable
    Set<String> currencies();

    // ── Reading matches ────────────────────────────────────────────────────

    /**
     * One match by its id.
     *
     * @param matchId the match id
     * @return the match, or empty once it has been cleaned up
     */
    @NotNull
    Optional<BetMatch> match(@NotNull String matchId);

    /**
     * The match a player is playing in or watching.
     *
     * @param player the player
     * @return their match, or empty when they are in none
     */
    @NotNull
    Optional<BetMatch> matchOf(@NotNull UUID player);

    /**
     * The match a player is actually playing, ignoring anything they watch.
     *
     * <p>The check a listener that must not interrupt a bet wants: a spectator
     * can be pulled away, a player holding a stake cannot.
     *
     * @param player the player
     * @return the match they hold a seat in, or empty
     */
    @NotNull
    Optional<BetMatch> playedBy(@NotNull UUID player);

    /**
     * The match a player is watching.
     *
     * @param player the player
     * @return the match they are spectating, or empty
     */
    @NotNull
    Optional<BetMatch> spectating(@NotNull UUID player);

    /**
     * Bets waiting for a second player, across every game.
     *
     * @return the open matches
     */
    @NotNull
    @Unmodifiable
    List<BetMatch> waiting();

    /**
     * Bets on one game waiting for a second player.
     *
     * @param gameId the game id
     * @return the open matches of that game
     */
    @NotNull
    @Unmodifiable
    List<BetMatch> waiting(@NotNull String gameId);

    /**
     * Matches being played, which are the ones somebody could watch.
     *
     * @return the live matches
     */
    @NotNull
    @Unmodifiable
    List<BetMatch> inProgress();

    /**
     * Matches of one game being played.
     *
     * @param gameId the game id
     * @return the live matches of that game
     */
    @NotNull
    @Unmodifiable
    List<BetMatch> inProgress(@NotNull String gameId);

    // ── Driving matches ────────────────────────────────────────────────────
    //
    // Each of these runs the same flow the player's own click runs, including
    // the permission checks, the economy calls and the messages they see, and
    // each returns before any of it has happened. The player is told what
    // happened; a caller that needs to know should listen for the event.

    /**
     * Offers a bet on somebody's behalf.
     *
     * <p>Nothing happens when the game or the mode is not registered: an id
     * that does not resolve is the caller's mistake, and refusing quietly is
     * safer than charging a player for a match that cannot be built.
     *
     * @param host     the player putting the bet up
     * @param gameId   which game
     * @param modeId   which of its modes
     * @param currency what to stake in
     * @param wager    what each side puts up
     * @param rounds   the length of the series, best of this many
     */
    void create(@NotNull Player host, @NotNull String gameId, @NotNull String modeId,
                @NotNull String currency, @NotNull BigDecimal wager, int rounds);

    /**
     * Takes a bet on somebody's behalf.
     *
     * @param player  the player joining
     * @param matchId the match to join
     */
    void join(@NotNull Player player, @NotNull String matchId);

    /**
     * Plays a square, as if the player had clicked it.
     *
     * @param player the player whose turn it is
     * @param cell   which square, in board order
     */
    void move(@NotNull Player player, int cell);

    /**
     * Gives a match up on somebody's behalf, which pays their opponent.
     *
     * @param player the player surrendering
     */
    void surrender(@NotNull Player player);

    /**
     * Stops a match and hands both stakes back.
     *
     * @param matchId the match to cancel
     */
    void cancel(@NotNull String matchId);

    /**
     * Starts watching a match.
     *
     * @param player  the watcher
     * @param matchId the match to watch
     */
    void spectate(@NotNull Player player, @NotNull String matchId);

    /**
     * Stops watching whatever this player is watching.
     *
     * @param player the watcher
     */
    void stopSpectating(@NotNull Player player);

    // ── Records ────────────────────────────────────────────────────────────

    /**
     * One player's record at one game.
     *
     * <p>Answered from memory while the player is online and from the database
     * otherwise, which is why it is a future either way: a caller cannot know
     * which of the two it got, and one that blocks on the online case would
     * deadlock on the offline one.
     *
     * @param player the player
     * @param gameId a game id, or {@link BetStats#ALL_GAMES} for every game at once
     * @return their record, or empty when they have never finished a match
     */
    @NotNull
    CompletableFuture<Optional<BetStats>> stats(@NotNull UUID player, @NotNull String gameId);

    /**
     * Everything one player has staked and taken, every game and currency.
     *
     * @param player the player
     * @return one row per game and currency they have ever bet in
     */
    @NotNull
    CompletableFuture<List<BetMoney>> money(@NotNull UUID player);

    /**
     * Who is at the top of one game's board.
     *
     * <p>Read from a cache the plugin refreshes on its own, so this is safe on
     * a menu redraw. It is empty rather than blocking while that cache is still
     * being filled: a leaderboard that is one tick late is better than a server
     * thread waiting on a query.
     *
     * @param gameId a game id, or {@link BetStats#ALL_GAMES}
     * @param stat   what to sort by
     * @return the board, or empty while it is still being read
     */
    @NotNull
    Optional<List<BetStats>> top(@NotNull String gameId, @NotNull StatsType stat);

    /**
     * One player's last matches, newest first.
     *
     * @param player the player
     * @param limit  how many at most
     * @return their finished matches
     */
    @NotNull
    CompletableFuture<List<BetMatchRecord>> history(@NotNull UUID player, int limit);
}
