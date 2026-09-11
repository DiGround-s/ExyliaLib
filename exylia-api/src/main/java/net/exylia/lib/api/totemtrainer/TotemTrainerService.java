package net.exylia.lib.api.totemtrainer;

import net.exylia.lib.api.ExyliaAPI;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Reading and driving ExyliaTotemTrainer.
 *
 * <pre>{@code
 * ExyliaAPI.get(TotemTrainerService.class).ifPresent(totems ->
 *     totems.activityOf(player.getUniqueId())
 *           .ifPresent(activity -> event.setCancelled(true)));
 * }</pre>
 *
 * <p>Registered with Bukkit's {@link org.bukkit.plugin.ServicesManager} when
 * ExyliaTotemTrainer enables. Reach it through {@link ExyliaAPI#get(Class)},
 * and treat an empty result as "this server does not run totem training"
 * rather than as a failure.
 *
 * <h2>What most integrations actually want</h2>
 * {@link #activityOf(UUID)} is the one question worth asking before anything
 * else: a player in training or in a duel has had their inventory and their
 * location taken by this plugin, so teleporting them, giving them items or
 * opening a menu for them will be undone or will break their session.
 *
 * <h2>Queries are snapshots, actions run the player's own flow</h2>
 * Everything that returns a value copies what it found out of the plugin's live
 * registries, so a {@link TotemMatch} is the match as it was rather than as it
 * is. The actions run exactly what the player's own command runs — the checks,
 * the claims and the messages they see — and return whether the plugin accepted
 * the request, not how it turned out. What happened arrives as an event.
 *
 * <p>Call the actions from the main thread, or on Folia from the thread that
 * owns the player in question. The two {@link CompletableFuture} methods go to
 * the database and are safe from anywhere.
 *
 * @since 1.0.0
 */
public interface TotemTrainerService {

    // ── What the server offers ─────────────────────────────────────────────

    /**
     * The training modes this server has configured, in menu order.
     *
     * <p>Modes are configuration rather than code, so this set differs between
     * servers and an id that works on one is not guaranteed on another. Check
     * before offering one.
     *
     * @return the mode ids
     */
    @NotNull
    @Unmodifiable
    List<String> modes();

    /**
     * The arenas duels are fought in.
     *
     * <p>Many matches share one arena at a time, so this is not a list of free
     * space; it is what the server has set up. {@link TotemArena#isReady()}
     * says which of them can host anything.
     *
     * @return every arena
     */
    @NotNull
    @Unmodifiable
    List<TotemArena> arenas();

    /**
     * One arena by its id.
     *
     * @param arenaId the arena id
     * @return the arena, or empty when there is none by that id
     */
    @NotNull
    Optional<TotemArena> arena(@NotNull String arenaId);

    // ── What a player is doing ─────────────────────────────────────────────

    /**
     * What this plugin is doing with a player.
     *
     * <p>Empty means the plugin has no hold on them. Anything else means their
     * inventory and location belong to it until they leave.
     *
     * @param player the player
     * @return their activity, or empty when they are in neither
     */
    @NotNull
    Optional<PlayerActivity> activityOf(@NotNull UUID player);

    /**
     * The duel a player is in.
     *
     * @param player the player
     * @return their match, or empty when they are in none
     */
    @NotNull
    Optional<TotemMatch> matchOf(@NotNull UUID player);

    /**
     * One duel by its id.
     *
     * @param matchId the match id
     * @return the match, or empty once it has been cleaned up
     */
    @NotNull
    Optional<TotemMatch> match(@NotNull UUID matchId);

    /**
     * Every duel running right now.
     *
     * @return the live matches
     */
    @NotNull
    @Unmodifiable
    List<TotemMatch> matches();

    /**
     * How many duels are being fought in one arena.
     *
     * <p>Arenas are shared, so this is a load figure rather than an
     * availability one.
     *
     * @param arenaId the arena id
     * @return the number of matches in it
     */
    int matchesIn(@NotNull String arenaId);

    /**
     * The solo session a player is in.
     *
     * @param player the player
     * @return their session, or empty when they are not training
     */
    @NotNull
    Optional<TrainingSession> training(@NotNull UUID player);

    /**
     * Every solo session running right now.
     *
     * @return the live training sessions
     */
    @NotNull
    @Unmodifiable
    List<TrainingSession> trainingSessions();

    /**
     * Who has challenged a player and is still waiting for an answer.
     *
     * @param player the player being challenged
     * @return the challenger, or empty when nobody is waiting
     */
    @NotNull
    Optional<UUID> pendingDuelFor(@NotNull UUID player);

    // ── Records ────────────────────────────────────────────────────────────

    /**
     * A player's profile as it is cached for somebody online.
     *
     * <p>Empty for a player who is offline, and also for one whose load is
     * still in flight — which is deliberate: a caller on the server thread
     * should degrade rather than wait, and one that can wait should ask
     * {@link #loadProfile(UUID)} instead.
     *
     * @param player the player
     * @return their profile, or empty when it is not in memory
     */
    @NotNull
    Optional<TotemProfile> profile(@NotNull UUID player);

    /**
     * A player's profile, from the database when it is not in memory.
     *
     * <p>Safe from any thread. A player who has never trained gets a fresh,
     * empty profile rather than nothing, because "no rows" and "no games" are
     * the same answer to every screen that asks.
     *
     * @param player the player
     * @return their profile
     */
    @NotNull
    CompletableFuture<TotemProfile> loadProfile(@NotNull UUID player);

    /**
     * A player's last duels, newest first.
     *
     * @param player the player
     * @return their finished duels, as many as the server keeps
     */
    @NotNull
    CompletableFuture<List<MatchRecord>> history(@NotNull UUID player);

    /**
     * A player's best training results, one row per mode and speed.
     *
     * @param player the player
     * @return their records
     */
    @NotNull
    CompletableFuture<List<TrainingRecord>> records(@NotNull UUID player);

    /**
     * One mode's training leaderboard.
     *
     * <p>Served from a cache the plugin refreshes on its own, so a menu redraw
     * is cheap and a cold board is one query rather than one per viewer.
     *
     * @param modeId   which mode
     * @param category what to sort by
     * @param ticks    the interval to restrict the board to, or {@code 0} for
     *                 every speed at once
     * @return the board, longest-standing records first
     */
    @NotNull
    CompletableFuture<List<TrainingRecord>> leaderboard(@NotNull String modeId,
                                                        @NotNull RecordCategory category,
                                                        int ticks);

    // ── Actions ────────────────────────────────────────────────────────────

    /**
     * Puts a player into a solo session.
     *
     * <p>The player is told why when this refuses: the mode is unknown, they
     * are already busy, or no arena can take them.
     *
     * @param player the player
     * @param modeId which mode
     * @param ticks  the interval between hits, in ticks
     * @return {@code true} when the session started
     */
    boolean startTraining(@NotNull Player player, @NotNull String modeId, int ticks);

    /**
     * Challenges another player to a duel.
     *
     * <p>The sender is told why when this refuses. The challenge expires on its
     * own if it is never answered.
     *
     * @param from   who is challenging
     * @param to     who is being challenged
     * @param modeId which mode to play
     * @param ticks  the interval between hits, in ticks
     * @param bestOf the length of the series
     * @return {@code true} when the challenge was sent
     */
    boolean duel(@NotNull Player from, @NotNull Player to, @NotNull String modeId,
                 int ticks, int bestOf);

    /**
     * Accepts the challenge waiting for a player.
     *
     * @param player the player who was challenged
     * @return {@code true} when a challenge was accepted
     */
    boolean acceptDuel(@NotNull Player player);

    /**
     * Turns down the challenge waiting for a player.
     *
     * @param player the player who was challenged
     * @return {@code true} when a challenge was turned down
     */
    boolean denyDuel(@NotNull Player player);

    /**
     * Gives a duel up on somebody's behalf, which hands the series to their
     * opponent.
     *
     * @param player the player forfeiting
     * @return {@code true} when they were in a duel to forfeit
     */
    boolean forfeit(@NotNull UUID player);

    /**
     * Stops a duel with no winner and returns both players.
     *
     * <p>For an administrator or another plugin that needs the players back.
     * A forfeit is what ends a duel the players themselves are done with.
     *
     * @param matchId the match to stop
     * @return {@code true} when a running match was stopped
     */
    boolean cancelMatch(@NotNull UUID matchId);

    /**
     * Takes a player out of whatever this plugin has them in.
     *
     * <p>Leaving a duel is a forfeit; leaving training simply ends the session.
     * Their inventory and their location come back either way.
     *
     * @param player the player
     * @return {@code true} when they were in something to leave
     */
    boolean leave(@NotNull Player player);

    // ── Menus ──────────────────────────────────────────────────────────────
    //
    // The player's own screens, for an NPC, a hub item or another plugin's
    // menu to open. Safe from any thread: each one opens on the thread that
    // owns the player, and a screen that reads the database first fills in a
    // moment later. No permission is checked — deciding who sees a screen is
    // the caller's call, as it is for any menu a server owner wires up.

    /**
     * Opens the hub every other screen is reached from.
     *
     * @param player who to show it to
     * @since 1.3.0
     */
    void openMenu(@NotNull Player player);

    /**
     * Opens the solo training picker, where a player chooses a mode and a speed.
     *
     * @param player who to show it to
     * @since 1.3.0
     */
    void openTraining(@NotNull Player player);

    /**
     * Opens the duel settings for challenging one player.
     *
     * <p>The screen a player reaches by picking an opponent: choosing a mode
     * there sends the request, through the same checks as {@link #duel}.
     *
     * @param player who to show it to
     * @param target the player they would challenge
     * @since 1.3.0
     */
    void openDuel(@NotNull Player player, @NotNull Player target);

    /**
     * Opens a player's profile.
     *
     * <p>Works for an offline target, whose profile is read from the database
     * before the screen opens.
     *
     * @param viewer who to show it to
     * @param target whose profile, which may be the viewer's own
     * @since 1.3.0
     */
    void openProfile(@NotNull Player viewer, @NotNull UUID target);

    /**
     * Opens one mode's training leaderboard.
     *
     * <p>An unknown mode id opens the mode picker instead of an empty board.
     *
     * @param viewer   who to show it to
     * @param modeId   which mode, from {@link #modes()}
     * @param category what to sort by
     * @since 1.3.0
     */
    void openLeaderboard(@NotNull Player viewer, @NotNull String modeId,
                         @NotNull RecordCategory category);
}
