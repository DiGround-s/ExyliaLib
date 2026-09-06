package net.exylia.lib.api.survival;

import net.exylia.lib.api.ExyliaAPI;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Reading and driving ExyliaSurvivalCore.
 *
 * <pre>{@code
 * ExyliaAPI.get(SurvivalService.class).ifPresent(survival ->
 *     survival.homes(player.getUniqueId())
 *             .forEach(home -> player.sendMessage("Home: " + home.name())));
 * }</pre>
 *
 * <p>Registered with Bukkit's {@link org.bukkit.plugin.ServicesManager} when
 * ExyliaSurvivalCore enables. Reach it through {@link ExyliaAPI#get(Class)},
 * and treat an empty result as "this server does not run the survival core"
 * rather than as a failure.
 *
 * <h2>Modules can be switched off</h2>
 * The plugin is a set of modules an administrator enables one by one, so having
 * the service does not mean having homes, warps or kits. Every method here
 * degrades when its module is off — an empty list, an empty {@link Optional},
 * {@code false}, {@code 0}, or a no-op — and never throws. Check
 * {@link #isModuleEnabled(String)} when you want to know the difference between
 * "no homes" and "no homes module".
 *
 * <p>Only the modules a third party can act on are published. The rest —
 * portals, mines, regen zones, loot chests, blocked items and the other
 * world-building modules — are administrator tools whose state is a set of
 * placed objects an integration cannot do anything useful with.
 *
 * <h2>Queries are cheap, actions are not</h2>
 * Everything that returns a value reads a cache and is safe to call from a menu
 * redraw or a placeholder. Everything else teleports a player, gives them
 * items, or charges them money, and runs the same flow their own command runs —
 * including the permission checks and the messages they see — so call those on
 * the main thread and no more often than a player could trigger them.
 *
 * @since 1.0.0
 */
public interface SurvivalService {

    // ── Modules ────────────────────────────────────────────────────────────

    /**
     * Whether a module is running.
     *
     * @param moduleId the module id, such as {@code homes} or {@code warps}
     * @return {@code true} when it is enabled
     */
    boolean isModuleEnabled(@NotNull String moduleId);

    /**
     * Every module that is running.
     *
     * <p>The ids are the plugin's own vocabulary and the set grows between
     * releases, so treat an unfamiliar one as a module this integration does
     * not know about rather than as an error.
     *
     * @return the enabled module ids
     */
    @NotNull
    @Unmodifiable
    Set<String> enabledModules();

    // ── Spawn ──────────────────────────────────────────────────────────────

    /**
     * Where the server spawn is.
     *
     * @return the spawn, or empty when none is set or the module is off
     */
    @NotNull
    Optional<Location> spawn();

    /**
     * Sends a player to spawn.
     *
     * @param player the player travelling
     */
    void teleportToSpawn(@NotNull Player player);

    // ── Homes ──────────────────────────────────────────────────────────────

    /**
     * A player's homes.
     *
     * <p>Reads the cache, which holds a player's homes while they are online.
     * An offline player's homes are not cached, so this answers empty for them
     * rather than going to the database on the calling thread.
     *
     * @param player the player
     * @return their homes, empty when they have none
     */
    @NotNull
    @Unmodifiable
    List<Home> homes(@NotNull UUID player);

    /**
     * One of a player's homes by name.
     *
     * @param player the player
     * @param name   the home name, case insensitive
     * @return the home, or empty when they have none by that name
     */
    @NotNull
    Optional<Home> home(@NotNull UUID player, @NotNull String name);

    /**
     * How many homes a player is allowed.
     *
     * <p>Worked out from their permissions, so it needs the player rather than
     * their id and changes when their rank does.
     *
     * @param player the player
     * @return their limit
     */
    int maxHomes(@NotNull Player player);

    /**
     * Creates or moves a home.
     *
     * <p>Replaces one of the same name rather than adding a second, which is
     * what the player's own command does.
     *
     * @param player   whose home it is
     * @param name     what to call it
     * @param location where it goes
     */
    void setHome(@NotNull Player player, @NotNull String name, @NotNull Location location);

    /**
     * Removes a home.
     *
     * @param player the player
     * @param name   the home name
     */
    void deleteHome(@NotNull UUID player, @NotNull String name);

    /**
     * Sends a player to one of their homes.
     *
     * <p>Handles a home in an unloaded world or on another server, which is why
     * this exists rather than leaving callers to teleport to
     * {@link Home#location()} themselves.
     *
     * @param player the player travelling
     * @param name   the home name
     */
    void teleportToHome(@NotNull Player player, @NotNull String name);

    // ── Warps ──────────────────────────────────────────────────────────────

    /**
     * Every warp, enabled or not.
     *
     * @return all warps
     */
    @NotNull
    @Unmodifiable
    List<Warp> warps();

    /**
     * A warp by its id.
     *
     * @param warpId the warp id
     * @return the warp, or empty when none has that id
     */
    @NotNull
    Optional<Warp> warp(@NotNull String warpId);

    /**
     * Whether a player holds the permission a warp needs.
     *
     * <p>Permission only. A player who may use a warp can still be on cooldown
     * for it or unable to afford it, so this is what a menu greys a row out
     * with, not a guarantee the teleport will happen.
     *
     * @param player the player
     * @param warpId the warp id
     * @return {@code true} when they are allowed to use it
     */
    boolean canUseWarp(@NotNull Player player, @NotNull String warpId);

    /**
     * Sends a player to a warp.
     *
     * <p>Charges them, checks their permission and starts the warmup exactly as
     * their own command does.
     *
     * @param player the player travelling
     * @param warpId the warp id
     */
    void teleportToWarp(@NotNull Player player, @NotNull String warpId);

    /**
     * How long until a player may use a warp again.
     *
     * @param player the player
     * @param warpId the warp id
     * @return milliseconds remaining, {@code 0} when they may use it now
     */
    long warpCooldownMillis(@NotNull UUID player, @NotNull String warpId);

    // ── Kits ───────────────────────────────────────────────────────────────

    /**
     * Every kit, enabled or not.
     *
     * @return all kits
     */
    @NotNull
    @Unmodifiable
    List<SurvivalKit> kits();

    /**
     * A kit by its id.
     *
     * @param kitId the kit id
     * @return the kit, or empty when none has that id
     */
    @NotNull
    Optional<SurvivalKit> kit(@NotNull String kitId);

    /**
     * Whether a player could claim a kit right now, and why not.
     *
     * <p>The same answer {@link #claimKit(Player, String)} would give, worked
     * out without giving anything — which is what a menu draws a row from.
     *
     * @param player the player
     * @param kitId  the kit id
     * @return the reason, {@link KitClaimStatus#SUCCESS} when they could
     */
    @NotNull
    KitClaimStatus kitStatus(@NotNull Player player, @NotNull String kitId);

    /**
     * Gives a player a kit.
     *
     * <p>Runs every check first and gives nothing when one fails, so a refused
     * claim costs the player neither a use nor a cooldown.
     *
     * @param player the player claiming
     * @param kitId  the kit id
     * @return {@code true} when they got it
     */
    boolean claimKit(@NotNull Player player, @NotNull String kitId);

    /**
     * How long until a player may claim a kit again.
     *
     * <p>Answers {@code 0} for a player who is offline. Kit progress is held
     * per online player and dropped when they leave, so asking about somebody
     * absent has no answer to give and no reason to start holding one.
     *
     * @param player the player
     * @param kitId  the kit id
     * @return milliseconds remaining, {@code 0} when they may claim it now or
     *         are offline
     */
    long kitCooldownMillis(@NotNull UUID player, @NotNull String kitId);

    /**
     * How many claims a player has left of a kit.
     *
     * <p>Answers {@code 0} for a player who is offline, for the same reason as
     * {@link #kitCooldownMillis(UUID, String)}.
     *
     * @param player the player
     * @param kitId  the kit id
     * @return the claims left, {@code -1} when the kit has no limit, {@code 0}
     *         when there is no such kit or the player is offline
     */
    int kitUsesLeft(@NotNull UUID player, @NotNull String kitId);

    // ── Statistics ─────────────────────────────────────────────────────────

    /**
     * A player's combat counters.
     *
     * @param player the player
     * @return their counters, or empty when the statistics module is off
     */
    @NotNull
    Optional<SurvivalStats> stats(@NotNull UUID player);

    /**
     * Every statistic that can be ranked.
     *
     * <p>Configured rather than fixed: an administrator can add one that reads
     * a placeholder from another plugin, so ask here for the ids
     * {@link #leaderboard(String)} accepts rather than assuming a set.
     *
     * @return the statistic ids
     */
    @NotNull
    @Unmodifiable
    List<String> statistics();

    /**
     * A statistic's leaderboard.
     *
     * <p>Reads the podium the plugin keeps warm on a timer, so it is safe from
     * a placeholder and can be a few minutes behind.
     *
     * @param statisticId the statistic id
     * @return the leaderboard, best first, empty when the statistic is unknown
     *         or has not been built yet
     */
    @NotNull
    @Unmodifiable
    List<SurvivalRanking> leaderboard(@NotNull String statisticId);

    /**
     * Where a player sits on a statistic's leaderboard.
     *
     * @param player      the player
     * @param statisticId the statistic id
     * @return their position starting at {@code 1}, or {@code 0} when they are
     *         not ranked
     */
    int rank(@NotNull UUID player, @NotNull String statisticId);

    /**
     * How long a player has been on the server.
     *
     * <p>The plugin's own total, which is what its playtime rewards are paid
     * against — not the server's session counter.
     *
     * @param player the player
     * @return milliseconds played, {@code 0} when the module is off or they
     *         have never joined
     */
    long playtimeMillis(@NotNull UUID player);

    // ── Ranks ──────────────────────────────────────────────────────────────

    /**
     * The rank a player has reached.
     *
     * @param player the player
     * @return their rank, or empty when they are on none yet
     */
    @NotNull
    Optional<Rank> currentRank(@NotNull UUID player);

    /**
     * The rank a player is working towards.
     *
     * @param player the player
     * @return the next rank, or empty when they are at the top
     */
    @NotNull
    Optional<Rank> nextRank(@NotNull UUID player);

    /**
     * How many times a player has prestiged.
     *
     * @param player the player
     * @return the prestige level, {@code 0} when they never have
     */
    int prestigeLevel(@NotNull UUID player);

    /**
     * Whether a player meets every requirement of their next rank.
     *
     * <p>Needs the player rather than their id: a requirement can be a
     * permission or a placeholder that only resolves against somebody online.
     *
     * @param player the player
     * @return {@code true} when they could rank up now
     */
    boolean canRankUp(@NotNull Player player);

    /**
     * Ranks a player up.
     *
     * <p>Charges them, runs the reward commands and announces it exactly as
     * their own command does.
     *
     * @param player the player
     * @return {@code true} when they were promoted
     */
    boolean rankUp(@NotNull Player player);
}
