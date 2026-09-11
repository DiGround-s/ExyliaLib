package net.exylia.lib.api.ffa;

import net.exylia.lib.api.ExyliaAPI;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Reading and driving ExyliaFFA.
 *
 * <pre>{@code
 * ExyliaAPI.get(FfaService.class).ifPresent(ffa ->
 *     ffa.arenaOf(player.getUniqueId())
 *        .ifPresent(arena -> player.sendMessage("Fighting in " + arena)));
 * }</pre>
 *
 * <p>Registered with Bukkit's {@link org.bukkit.plugin.ServicesManager} when
 * ExyliaFFA enables. Reach it through {@link ExyliaAPI#get(Class)}, and treat
 * an empty result as "this server does not run FFA" rather than as a failure.
 *
 * <h2>One service, three subjects</h2>
 * The arena, the statistics and the per-player toggles are one plugin from a
 * consumer's point of view — a stats menu wants all three — so they are one
 * interface with three sections rather than three services to look up
 * separately.
 *
 * <h2>Queries are cheap, actions are not</h2>
 * Everything that returns a value reads from the plugin's caches and is safe to
 * call from a menu redraw or a placeholder, with the two documented exceptions
 * that return a {@link CompletableFuture} and go to the database. Everything
 * that returns {@code void} runs the same flow the player's own command runs —
 * permission checks, the messages they see, and in some cases a menu they must
 * answer — so call those on the main thread and no more often than a player
 * could trigger them.
 *
 * @since 1.0.0
 */
public interface FfaService {

    /**
     * The arena id that holds a player's totals across every arena.
     *
     * <p>The plugin writes a second row under this key on every counter change,
     * so a server-wide leaderboard is the same query as an arena one rather
     * than a sum computed at read time.
     */
    String GLOBAL_ARENA = "global";

    // ── Arenas and state ───────────────────────────────────────────────────

    /**
     * An arena by its id.
     *
     * @param arenaId the arena id
     * @return the arena, or empty when no arena has that id
     */
    @NotNull
    Optional<FfaArena> arena(@NotNull String arenaId);

    /**
     * Every arena the plugin has loaded.
     *
     * <p>A snapshot of the cache, including arenas an administrator has
     * disabled — a menu wants to draw those greyed out rather than not at all.
     *
     * @return all arenas
     */
    @NotNull
    @Unmodifiable
    List<FfaArena> arenas();

    /**
     * Whether a player is in any arena, alive or spectating.
     *
     * @param player the player
     * @return {@code true} when they are in one
     */
    boolean isInFfa(@NotNull UUID player);

    /**
     * The arena a player is in.
     *
     * @param player the player
     * @return the arena id, or empty when they are in none
     */
    @NotNull
    Optional<String> arenaOf(@NotNull UUID player);

    /**
     * Whether a player is fighting rather than watching.
     *
     * @param player the player
     * @return {@code true} when they are alive in an arena
     */
    boolean isAlive(@NotNull UUID player);

    /**
     * Whether a player is watching rather than fighting.
     *
     * @param player the player
     * @return {@code true} when they are spectating an arena
     */
    boolean isSpectating(@NotNull UUID player);

    /**
     * Everybody alive in an arena.
     *
     * @param arenaId the arena id
     * @return their uuids, empty when the arena does not exist
     */
    @NotNull
    @Unmodifiable
    List<UUID> alivePlayers(@NotNull String arenaId);

    /**
     * Everybody spectating an arena.
     *
     * @param arenaId the arena id
     * @return their uuids, empty when the arena does not exist
     */
    @NotNull
    @Unmodifiable
    List<UUID> spectators(@NotNull String arenaId);

    /**
     * How many players are alive in an arena.
     *
     * @param arenaId the arena id
     * @return the count, {@code 0} when the arena does not exist
     */
    int aliveCount(@NotNull String arenaId);

    /**
     * How many players an arena holds, fighting and watching together.
     *
     * @param arenaId the arena id
     * @return the count, {@code 0} when the arena does not exist
     */
    int playerCount(@NotNull String arenaId);

    /**
     * Whether an arena has reached its player limit.
     *
     * @param arenaId the arena id
     * @return {@code true} when nobody else may join
     */
    boolean isFull(@NotNull String arenaId);

    /**
     * How long until an arena rebuilds itself.
     *
     * @param arenaId the arena id
     * @return seconds remaining, {@code 0} when the arena does not exist or
     *         never regenerates
     */
    int secondsUntilRegeneration(@NotNull String arenaId);

    /**
     * Whether a location is inside any arena's protected spawn zone.
     *
     * <p>The question a combat or block listener wants. Asking here rather than
     * rebuilding the test from an arena's configuration means a shape or a
     * radius changed by an administrator is answered correctly without the
     * caller knowing the zone geometry exists.
     *
     * @param location the location
     * @return {@code true} when it is protected
     */
    boolean isProtected(@NotNull Location location);

    /**
     * Whether nothing else on the server currently has this player.
     *
     * <p>One question to the server-wide session registry, so a mode added
     * later — an event, a duel, the sandbox — is covered without this method
     * changing. A {@code true} is not a reservation: the join itself takes the
     * claim atomically and is what actually decides.
     *
     * @param player the player
     * @return {@code true} when they are free to be put into an arena
     */
    boolean isAvailable(@NotNull Player player);

    /**
     * Why a player cannot be taken, in words meant for a log.
     *
     * @param player the player
     * @return the reason, or empty when they are free
     */
    @NotNull
    Optional<String> unavailableReason(@NotNull Player player);

    // ── Actions ────────────────────────────────────────────────────────────
    //
    // Each of these runs the same flow the player's own command runs, including
    // the permission checks and the messages they see. They report nothing back:
    // the player is told what happened, and a caller that needs to know should
    // read the state afterwards.

    /**
     * Puts a player into an arena.
     *
     * <p>The player's own join: when the arena offers more than one spawn or
     * more than one selectable kit, they are shown the menu that asks, and the
     * join finishes when they answer. Use
     * {@link #join(Player, String, String, String)} when the caller has already
     * decided and no menu should appear.
     *
     * @param player  the player joining
     * @param arenaId the arena id
     */
    void join(@NotNull Player player, @NotNull String arenaId);

    /**
     * Puts a player into an arena at a chosen spawn with a chosen kit.
     *
     * <p>No menu, whatever the arena's configuration: both choices are already
     * made. An unknown spawn or kit id falls back to the arena's own selection
     * rather than failing the join.
     *
     * @param player  the player joining
     * @param arenaId the arena id
     * @param spawnId the spawn to arrive at
     * @param kitId   the kit to fight with
     */
    void join(@NotNull Player player, @NotNull String arenaId, @NotNull String spawnId,
              @NotNull String kitId);

    /**
     * Takes a player out of the arena they are in.
     *
     * @param player the player leaving
     */
    void leave(@NotNull Player player);

    /**
     * Puts a player into an arena as a spectator.
     *
     * @param player  the player watching
     * @param arenaId the arena id
     */
    void spectate(@NotNull Player player, @NotNull String arenaId);

    /**
     * Brings a spectating player back into the fight.
     *
     * <p>Does nothing when the player is not spectating, which is the same
     * answer their own respawn button gives.
     *
     * @param player the player returning
     */
    void respawn(@NotNull Player player);

    /**
     * Removes a player from an arena on somebody else's authority.
     *
     * <p>The kicker's permissions are checked exactly as they are for their own
     * command, so this cannot be used to bypass them.
     *
     * @param kicker the player doing the kicking
     * @param target the player being removed
     */
    void kick(@NotNull Player kicker, @NotNull Player target);

    /**
     * Invites a player to join the inviter's arena.
     *
     * @param inviter the player inviting
     * @param target  the player being invited
     */
    void invite(@NotNull Player inviter, @NotNull Player target);

    /**
     * Hands a fighting player their kit again, as a fresh copy.
     *
     * <p>The kit they are fighting with, laid out the way they saved it in the
     * kit editor; their current inventory is cleared first. A refill for a
     * reward or a round reset, not a way to change kits. Call it on the thread
     * that owns the player.
     *
     * @param player the player
     * @return {@code true} when they were alive in an arena and were given it,
     *         {@code false} when they are spectating or in no arena
     * @since 1.3.0
     */
    boolean giveKit(@NotNull Player player);

    // ── Menus ──────────────────────────────────────────────────────────────
    //
    // The player's own screens, for an NPC, a hub item or another plugin's
    // menu to open. Each is the screen their command opens, with the checks it
    // makes. Call them on the main thread; a screen that reads the database
    // first opens a moment later, on the player's own thread.

    /**
     * Opens the arena list, where a player picks an arena to join.
     *
     * @param player who to show it to
     * @since 1.3.0
     */
    void openArenaSelector(@NotNull Player player);

    /**
     * Opens a player's own settings toggles.
     *
     * @param player who to show it to
     * @since 1.3.0
     */
    void openSettings(@NotNull Player player);

    /**
     * Opens the kit editor, where a player rearranges a kit's layout.
     *
     * <p>Refused, with a message, for a player already editing or held by
     * another mode — the kit editor takes their inventory while it is open.
     *
     * @param player who to show it to
     * @since 1.3.0
     */
    void openKitEditor(@NotNull Player player);

    /**
     * Opens a player's statistics, one row per arena.
     *
     * <p>Works for an offline target by the name stored on their rows; a name
     * that has never played is reported to the viewer as not found.
     *
     * @param viewer     who to show it to
     * @param targetName whose statistics, which may be the viewer's own name
     * @since 1.3.0
     */
    void openStats(@NotNull Player viewer, @NotNull String targetName);

    /**
     * Opens an arena's leaderboard.
     *
     * @param viewer  who to show it to
     * @param arenaId the arena id, or {@link #GLOBAL_ARENA} for the whole server
     * @since 1.3.0
     */
    void openLeaderboard(@NotNull Player viewer, @NotNull String arenaId);

    // ── Statistics ─────────────────────────────────────────────────────────

    /**
     * A player's counters in one arena.
     *
     * <p>Reads the cache, so it only answers for a player who is online. Use
     * {@link #statsOf(UUID)} for anybody else.
     *
     * @param player  the player
     * @param arenaId the arena id, or {@link #GLOBAL_ARENA} for their totals
     * @return their counters, or empty when nothing is cached for that pair
     */
    @NotNull
    Optional<FfaStats> stats(@NotNull UUID player, @NotNull String arenaId);

    /**
     * Every row a player has, one per arena plus their totals.
     *
     * <p>Goes to the database, so it answers for offline players and must not
     * be called on the main thread's critical path. The returned future
     * completes on a database thread.
     *
     * @param player the player
     * @return their rows, empty when they have never played
     */
    @NotNull
    CompletableFuture<List<FfaStats>> statsOf(@NotNull UUID player);

    /**
     * The top players of an arena by one counter.
     *
     * <p>Goes to the database unless the plugin has a warm copy, and completes
     * on a database thread either way. For a placeholder or a menu redraw
     * prefer {@link #cachedLeaderboard(String, FfaStatistic)}, which never
     * queries.
     *
     * @param arenaId   the arena id, or {@link #GLOBAL_ARENA} for the whole server
     * @param statistic what to sort by
     * @return the leaderboard, best first
     */
    @NotNull
    CompletableFuture<List<FfaStats>> leaderboard(@NotNull String arenaId,
                                                  @NotNull FfaStatistic statistic);

    /**
     * The leaderboard the plugin already has in memory.
     *
     * <p>Empty means it has not been built yet, not that the arena has no
     * players — which is exactly the distinction a placeholder needs, since it
     * can then print a placeholder value instead of blocking a tick on a query.
     *
     * @param arenaId   the arena id, or {@link #GLOBAL_ARENA} for the whole server
     * @param statistic what to sort by
     * @return the leaderboard, or empty when none is cached
     */
    @NotNull
    Optional<List<FfaStats>> cachedLeaderboard(@NotNull String arenaId,
                                               @NotNull FfaStatistic statistic);

    /**
     * A player's live kill streak.
     *
     * <p>The counter the kill-streak rewards run off, which is reset by a death
     * and by leaving. It is not {@link FfaStats#currentStreak()}: that one is
     * the stored value for one arena, this one is what the player has going
     * right now.
     *
     * @param player the player
     * @return the streak, {@code 0} when they have none
     */
    int currentStreak(@NotNull UUID player);

    /**
     * Clears a player's counters in one arena.
     *
     * <p>Works for an offline player, and leaves the row in place with every
     * counter at zero rather than deleting it.
     *
     * @param player  the player
     * @param arenaId the arena id, or {@link #GLOBAL_ARENA} for their totals
     */
    void resetStats(@NotNull UUID player, @NotNull String arenaId);

    // ── Player settings ────────────────────────────────────────────────────

    /**
     * A player's own answer for one setting.
     *
     * <p>Only their choice. An administrator can turn a feature off for the
     * whole server, and this still reports what the player picked, so anything
     * deciding whether to actually show the feature has to check
     * {@link #isSettingAvailable(FfaSetting)} too. The two are separate because
     * a settings menu needs to draw the player's answer on a row it has greyed
     * out.
     *
     * <p>A player who has never opened the settings menu has every setting on:
     * an absent choice is a default, not an off.
     *
     * @param player  the player
     * @param setting the setting
     * @return {@code true} when they have it on
     */
    boolean isSettingEnabled(@NotNull UUID player, @NotNull FfaSetting setting);

    /**
     * Whether a setting is available on this server at all.
     *
     * <p>Whether an administrator has left the feature on. A setting that is
     * not available is not a choice the player can make, whatever
     * {@link #isSettingEnabled(UUID, FfaSetting)} reports for them.
     *
     * @param setting the setting
     * @return {@code true} when players may use it
     */
    boolean isSettingAvailable(@NotNull FfaSetting setting);

    /**
     * Flips a setting for a player.
     *
     * @param player  the player
     * @param setting the setting
     * @return the value it now has
     */
    boolean toggleSetting(@NotNull Player player, @NotNull FfaSetting setting);
}
