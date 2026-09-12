package net.exylia.lib.api.practice;

import net.exylia.lib.api.ExyliaAPI;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/**
 * Reading and driving ExyliaPracticeCore.
 *
 * <pre>{@code
 * ExyliaAPI.get(PracticeService.class).ifPresent(practice ->
 *     practice.matchOf(player.getUniqueId())
 *             .ifPresent(match -> player.sendMessage("Fighting on " + match.kitId())));
 * }</pre>
 *
 * <p>Registered with Bukkit's {@link org.bukkit.plugin.ServicesManager} when
 * ExyliaPracticeCore enables. Reach it through {@link ExyliaAPI#get(Class)}, and
 * treat an empty result as "this server does not run practice" rather than as a
 * failure.
 *
 * <h2>Ask before you move a player</h2>
 * Practice owns a player for as long as they are queued, fighting, watching or
 * editing a kit, and every one of those is broken by teleporting them somewhere
 * else. {@link #isAvailable(UUID)} is the one question another mode has to ask
 * first; {@link #state(UUID)} says why the answer was no.
 *
 * <h2>Queries are cheap, actions are not</h2>
 * Everything returning a value reads from the plugin's cache and is safe to call
 * from a menu redraw or a placeholder — the two futures are the exception, and
 * both name themselves. Everything else runs the same flow the player's own
 * command runs, including the permission checks, the cooldowns and the messages
 * the player sees, so call those on the main thread and no more often than a
 * player could trigger them. An action returns whether the plugin accepted it;
 * the player has already been told why not.
 *
 * @since 1.0.0
 */
public interface PracticeService {

    // ── Arenas ─────────────────────────────────────────────────────────────

    /**
     * An arena by its id.
     *
     * @param arenaId the arena id
     * @return the arena, or empty when no arena has that id
     */
    @NotNull
    Optional<Arena> arena(@NotNull String arenaId);

    /**
     * Every arena the server has, enabled or not.
     *
     * <p>A snapshot of the cache. Fine for a menu, wasteful in a loop.
     *
     * @return all arenas
     */
    @NotNull
    @Unmodifiable
    List<Arena> arenas();

    // ── Kits ───────────────────────────────────────────────────────────────

    /**
     * A kit by its id.
     *
     * @param kitId the kit id
     * @return the kit, or empty when no kit has that id
     */
    @NotNull
    Optional<Kit> kit(@NotNull String kitId);

    /**
     * Every kit the server has, enabled or not.
     *
     * @return all kits
     */
    @NotNull
    @Unmodifiable
    List<Kit> kits();

    /**
     * The kits that may be played in an arena.
     *
     * <p>A kit that names no arenas may be played in any, so this is not the
     * same as filtering {@link Kit#compatibleArenas()} yourself.
     *
     * @param arenaId the arena id
     * @return the kits playable there, empty when the arena does not exist
     */
    @NotNull
    @Unmodifiable
    List<Kit> kitsPlayableIn(@NotNull String arenaId);

    /**
     * Every kit category, enabled or not.
     *
     * @return all categories
     */
    @NotNull
    @Unmodifiable
    List<KitCategory> kitCategories();

    /**
     * A player's saved loadouts for a kit.
     *
     * <p>Read from the copy loaded when the player joined, so an offline
     * player has none as far as this is concerned.
     *
     * @param player the player
     * @param kitId  the kit id
     * @return their loadouts, empty when they have saved none
     */
    @NotNull
    @Unmodifiable
    List<KitLoadout> loadouts(@NotNull UUID player, @NotNull String kitId);

    /**
     * Gives a player a kit, as a match would.
     *
     * <p>Clears what they were holding and applies the kit's own effects and
     * rules. Their own loadout is used when they have saved one; a player with
     * several is never asked which, because an integration calling this has
     * already decided the player is being equipped now.
     *
     * @param player the player
     * @param kitId  the kit id
     * @return {@code false} when no kit has that id
     */
    boolean applyKit(@NotNull Player player, @NotNull String kitId);

    // ── Player state ───────────────────────────────────────────────────────

    /**
     * What a player is doing.
     *
     * @param player the player
     * @return their state, {@link PracticeState#AVAILABLE} for anybody the
     *         plugin is not holding
     */
    @NotNull
    PracticeState state(@NotNull UUID player);

    /**
     * Whether a player is free to be taken somewhere else.
     *
     * <p>The question to ask before moving a player: it is not the same as
     * comparing {@link #state(UUID)} to {@link PracticeState#AVAILABLE},
     * because a player can also be held by a plugin that is not this one.
     *
     * @param player the player
     * @return {@code true} when nothing has a claim on them
     */
    boolean isAvailable(@NotNull UUID player);

    /**
     * Whether a player is in a match.
     *
     * <p>True from the moment the match starts being built, and true for a
     * spectator watching from inside it. Check {@link MatchInfo#isActive()}
     * when you need them to actually be fighting.
     *
     * @param player the player
     * @return {@code true} when they are in one
     */
    boolean isInMatch(@NotNull UUID player);

    /**
     * Whether a player is waiting for a match.
     *
     * @param player the player
     * @return {@code true} when they are queued for at least one kit
     */
    boolean isInQueue(@NotNull UUID player);

    /**
     * Sends a player to the practice lobby.
     *
     * <p>Moves them and nothing else. A player who is in something should be
     * taken out of it with {@link #leave(Player)} first, which ends what they
     * were in and then does this.
     *
     * @param player the player
     */
    void sendToLobby(@NotNull Player player);

    /**
     * Takes a player out of whatever they are in.
     *
     * <p>The answer is whether the request was <em>accepted</em> rather than
     * whether it has finished: leaving a match forfeits it, and the player is
     * moved a tick or two later. A state with no way out says so by returning
     * {@code false} — a player whose match is still loading, or who is standing
     * inside a PvP zone, is not available to be taken.
     *
     * @param player the player
     * @return {@code true} when they are leaving
     */
    boolean leave(@NotNull Player player);

    // ── Menus ──────────────────────────────────────────────────────────────

    /**
     * Opens one of the player screens, as its command would.
     *
     * <p>What an NPC or another plugin's hub item wants: the plugin's own screen,
     * with its clicks and live contents, rather than a copy of it. The kit
     * editor and bot practice ask the same questions first that their commands
     * do - the kit editor whether the player is free, bot practice whether they
     * may and whether the bot plugin is running - and tell the player when the
     * answer is no.
     *
     * <p>Call it on the thread that owns the player, which on Paper is the main
     * thread.
     *
     * @param player who to show it to
     * @param menu   which screen
     * @return {@code false} when the screen refused to open
     * @since 1.3.0
     */
    boolean openMenu(@NotNull Player player, @NotNull PracticeMenu menu);

    // ── Matches ────────────────────────────────────────────────────────────

    /**
     * The match a player is in.
     *
     * @param player the player, fighting or watching from inside
     * @return their match, or empty when they are in none
     */
    @NotNull
    Optional<MatchInfo> matchOf(@NotNull UUID player);

    /**
     * A match by its id.
     *
     * @param matchId the match id
     * @return the match, or empty when it has already ended
     */
    @NotNull
    Optional<MatchInfo> match(@NotNull String matchId);

    /**
     * Every match running right now.
     *
     * @return the live matches, in no particular order
     */
    @NotNull
    @Unmodifiable
    Collection<MatchInfo> activeMatches();

    /**
     * Sends a player to watch a match.
     *
     * <p>Refused for a match that has not started or has already ended, for one
     * against bots, and for one whose fighters have turned spectators off.
     *
     * @param spectator who wants to watch
     * @param matchId   the match to watch
     * @return {@code true} when they are being moved in
     */
    boolean spectate(@NotNull Player spectator, @NotNull String matchId);

    /**
     * Sends a player to watch whatever another player is fighting.
     *
     * <p>The same checks as {@link #spectate}, plus one: watching somebody who
     * is themselves a spectator is refused rather than silently following them.
     *
     * @param spectator who wants to watch
     * @param target    the fighter to watch
     * @return {@code true} when they are being moved in
     */
    boolean spectatePlayer(@NotNull Player spectator, @NotNull Player target);

    /**
     * Takes a spectator out of the match they are watching.
     *
     * @param spectator the spectator
     * @return {@code false} when they were not watching anything
     */
    boolean stopSpectating(@NotNull Player spectator);

    /**
     * Ends a match with no result.
     *
     * <p>Nobody wins, no ELO moves, and nothing is written to any player's
     * statistics or match history. Everybody in it sees the result a draw shows
     * and is sent back to the lobby after the few seconds a finished match always
     * takes, and {@link net.exylia.lib.api.practice.event.PracticeMatchEndEvent}
     * fires with {@link net.exylia.lib.api.practice.event.PracticeMatchEndEvent.Reason#CANCELLED}.
     *
     * <p>Works on a match in any state short of ending, a loading one included.
     *
     * @param matchId the match
     * @return {@code false} when no match has that id or it is already ending
     * @since 1.3.0
     */
    boolean cancelMatch(@NotNull String matchId);

    // ── Queue ──────────────────────────────────────────────────────────────

    /**
     * The kits a player is queued for.
     *
     * <p>A player may wait in several queues at once and takes the first match
     * that comes up.
     *
     * @param player the player
     * @return the kit ids, empty when they are queued for nothing
     */
    @NotNull
    @Unmodifiable
    Set<String> queuedKits(@NotNull UUID player);

    /**
     * How many players are waiting for a match on a kit.
     *
     * @param kitId the kit id
     * @return the queue size, {@code 0} for a kit nobody is waiting on
     */
    int queueSize(@NotNull String kitId);

    /**
     * Puts a player in a queue.
     *
     * <p>Refused when the player is not available, when their statistics have
     * not finished loading, when the queue is locked for a season rotation, or
     * when the kit is not queueable.
     *
     * @param player the player
     * @param kitId  the kit to wait for
     * @return {@code true} when they joined
     */
    boolean joinQueue(@NotNull Player player, @NotNull String kitId);

    /**
     * Takes a player out of every queue they are in.
     *
     * <p>Refused once their match has been found: at that point they are no
     * longer waiting, and letting go here would leave them free to walk into
     * another mode while the match was still loading them in.
     *
     * @param player the player
     * @return {@code true} when they left
     */
    boolean leaveQueue(@NotNull Player player);

    /**
     * Lends a queued player to another plugin, leaving them in the queue.
     *
     * <p>For a waiting activity — an aim trainer, a parkour, a warm-up arena —
     * that takes the player somewhere else while their match is looked for.
     * Practice hands over the session claim and stops saying what the player is
     * doing, but keeps their place in every queue they are in: matchmaking runs
     * for them exactly as it does for a player standing in the lobby.
     *
     * <p>From here on {@link #state(UUID)} answers
     * {@link PracticeState#EXTERNAL} and {@link #isInQueue(UUID)} still answers
     * {@code true}. Both are true at once, which is the point: the borrower has
     * the player, practice has the queue.
     *
     * <p>{@code giveBack} runs the moment practice needs them — a match was
     * found, they left the queue, they disconnected — and is the same contract
     * as a session claim's release handler: put the player back as you found
     * them, drop your claim, and answer {@code true} when you accept. Drop the
     * claim from inside the handler — the restore may go on finishing over the
     * next ticks, the claim may not, because practice cannot build a match
     * around a player somebody else is still holding. Refusing costs the whole
     * match: everybody else in it is put back in the queue and no match is
     * started, so refuse only while handing the player back would genuinely
     * break something.
     *
     * <p>Inventory, location and everything else the borrower changes are the
     * borrower's to restore. Practice restores nothing on the way back, and a
     * player who returns still queued returns to the queue, not to the lobby.
     *
     * @param player   the player, who has to be queued for at least one kit
     * @param borrower the plugin taking them
     * @param giveBack how to hand the player back when practice asks
     * @return {@code true} when the player was lent, {@code false} when they
     *         are not waiting for a match or somebody else already holds them
     * @since 1.4.0
     */
    boolean borrowQueuedPlayer(@NotNull Player player, @NotNull Plugin borrower,
                               @NotNull BooleanSupplier giveBack);

    // ── Duels ──────────────────────────────────────────────────────────────

    /**
     * Sends a duel request from one player to another.
     *
     * <p>The arena is left to the plugin, which picks one the kit may be played
     * in. Both players are told what happened.
     *
     * @param sender the challenger
     * @param target who they are challenging
     * @param kitId  the kit to fight with
     * @param rounds how many rounds decide it, {@code 1} for a single fight
     * @return {@code true} when the request was sent
     */
    boolean sendDuelRequest(@NotNull Player sender, @NotNull Player target, @NotNull String kitId,
                            int rounds);

    /**
     * Accepts a duel request.
     *
     * @param player the player who was challenged
     * @param sender whose request to accept
     */
    void acceptDuelRequest(@NotNull Player player, @NotNull Player sender);

    /**
     * Declines a duel request.
     *
     * @param player the player who was challenged
     * @param sender whose request to decline
     */
    void declineDuelRequest(@NotNull Player player, @NotNull Player sender);

    /**
     * Who has a duel request waiting for a player.
     *
     * <p>Names rather than UUIDs, and only the ones still online: a request
     * from somebody who has logged out cannot be accepted.
     *
     * @param target the challenged player
     * @return the challengers' names
     */
    @NotNull
    @Unmodifiable
    List<String> pendingDuelSenders(@NotNull Player target);

    /**
     * Starts a duel between two players, with no request in between.
     *
     * <p>For whatever has already decided the two of them are fighting - a
     * tournament bracket, an NPC that pairs whoever walks up - where sending a
     * request only to accept it on the target's behalf would show both players a
     * prompt nobody is going to answer. Both must be free and in no party, which
     * is what an accepted request asks of them too, and the duel is unranked, as
     * a requested one is.
     *
     * <p>Unlike the other actions, a refusal tells neither player anything: they
     * did not ask for this, so the result says why and the caller explains it.
     * {@link DuelStartResult#ACCEPTED} means both are claimed and the match is
     * being built, not that it will be fought - see its documentation.
     *
     * @param first   one fighter
     * @param second  the other
     * @param kitId   the kit to fight with; it must be enabled and allow duels
     * @param rounds  how many rounds decide it, {@code 1} for a single fight
     * @param arenaId the arena to try first, or {@code null} to let the plugin
     *                pick. A preference: an arena the kit may not be played in,
     *                or one with no free copy, is passed over for another
     * @return what happened
     * @since 1.3.0
     */
    @NotNull
    DuelStartResult startDuel(@NotNull Player first, @NotNull Player second, @NotNull String kitId,
                              int rounds, @Nullable String arenaId);

    // ── Parties ────────────────────────────────────────────────────────────

    /**
     * The party a player belongs to.
     *
     * @param player the player
     * @return their party, or empty when they are in none
     */
    @NotNull
    Optional<Party> partyOf(@NotNull UUID player);

    /**
     * Creates a party led by a player.
     *
     * @param player the leader
     * @return {@code true} when the party was created
     */
    boolean createParty(@NotNull Player player);

    /**
     * Invites a player to the leader's party.
     *
     * @param leader the party's leader
     * @param target who to invite
     * @return {@code true} when the invitation was sent
     */
    boolean inviteToParty(@NotNull Player leader, @NotNull Player target);

    /**
     * Adds a player to another player's party.
     *
     * <p>The other half of {@link #inviteToParty}, and the way into an open
     * party. Refused when the player is not available or already in a party,
     * when the leader leads no party, when it is full, and when it is closed and
     * the player holds no invitation to it.
     *
     * @param player who is joining
     * @param leader the leader of the party to join
     * @return {@code true} when they joined
     * @since 1.3.0
     */
    boolean joinParty(@NotNull Player player, @NotNull Player leader);

    /**
     * Removes a player from their party.
     *
     * <p>A leader leaving hands the party to somebody else rather than ending
     * it; use {@link #disbandParty} to end it.
     *
     * @param player the player leaving
     * @return {@code true} when they left
     */
    boolean leaveParty(@NotNull Player player);

    /**
     * Ends a party.
     *
     * @param leader the party's leader
     * @return {@code true} when it was disbanded
     */
    boolean disbandParty(@NotNull Player leader);

    // ── Statistics and ranking ─────────────────────────────────────────────

    /**
     * A player's counters on one kit, this season.
     *
     * @param player the player
     * @param kitId  the kit id
     * @return their statistics, or empty when they have never finished a match
     *         on that kit
     */
    @NotNull
    Optional<PlayerStats> stats(@NotNull UUID player, @NotNull String kitId);

    /**
     * A player's counters across every kit, this season.
     *
     * @param player the player
     * @return their overall statistics, or empty when they have never finished
     *         a match
     */
    @NotNull
    Optional<PlayerStats> globalStats(@NotNull UUID player);

    /**
     * A player's ELO on a kit.
     *
     * <p>Answers with the server's starting ELO for a player who has never
     * played the kit, because that is what they would be matched at.
     *
     * @param player the player
     * @param kitId  the kit id
     * @return their ELO
     */
    int elo(@NotNull UUID player, @NotNull String kitId);

    /**
     * Where a player's ELO puts them on the visible ladder.
     *
     * @param player the player
     * @param kitId  the kit id
     * @return their rank, the lowest one for a player who has never played
     */
    @NotNull
    Rank rank(@NotNull UUID player, @NotNull String kitId);

    /**
     * The top of a kit's leaderboard.
     *
     * <p>A database read, cached for a short while after each one. Do not call
     * it per row of a menu.
     *
     * @param kitId the kit id, or {@link PlayerStats#GLOBAL_KIT} for the
     *              overall board
     * @param type  what to sort by
     * @return the leading players, best first
     */
    @NotNull
    CompletableFuture<@Unmodifiable List<PlayerStats>> leaderboard(@NotNull String kitId,
                                                                   @NotNull LeaderboardType type);

    /**
     * A player's recent matches on a kit, newest first.
     *
     * <p>A database read, cached for a short while after each one. Reaches back
     * only as far as the server's retention window.
     *
     * @param player the player
     * @param kitId  the kit id, or {@link PlayerStats#GLOBAL_KIT} for every kit
     * @return their matches, newest first
     */
    @NotNull
    CompletableFuture<@Unmodifiable List<MatchRecord>> matchHistory(@NotNull UUID player,
                                                                    @NotNull String kitId);
}
