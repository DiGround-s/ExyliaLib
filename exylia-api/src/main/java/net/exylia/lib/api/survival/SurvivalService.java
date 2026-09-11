package net.exylia.lib.api.survival;

import net.exylia.lib.api.ExyliaAPI;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.math.BigDecimal;
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
 * portals, regen zones, loot chests, blocked items and the other world-building
 * modules — are administrator tools whose state is a set of placed objects an
 * integration cannot do anything useful with. Mines publish their break and
 * their reset and nothing else, so a plugin breaking blocks for a player gets
 * the mine's loot and regeneration instead of a hole.
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

    // ── Random teleport ────────────────────────────────────────────────────

    /**
     * Sends a player somewhere random in a world.
     *
     * <p>For an NPC or a portal standing in for {@code /rtp <world>}. Checks
     * that the world is set up for it, then the player's cooldown and balance,
     * and runs the warmup exactly as their own command does, messages included.
     * The search for a safe spot runs after the warmup, so the player lands a
     * moment after this returns, or not at all when they move or are hurt.
     *
     * <p>Call it on the thread that owns the player.
     *
     * @param player  the player travelling
     * @param worldId the world's name, as the random teleport configuration
     *                lists it
     * @since 1.3.0
     */
    void randomTeleport(@NotNull Player player, @NotNull String worldId);

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
     * Gives a player a kit as a reward rather than as a claim.
     *
     * <p>For a quest, a vote or a crate paying out a kit. With
     * {@code ignoreLimits} the kit's cooldown and use limit are not checked, the
     * way an administrator's give and a first-join kit skip them; the claim is
     * still recorded, so it counts towards both for the player's own next
     * claim. Everything else is checked either way: the kit being enabled, the
     * player holding its permission and the room in their inventory.
     *
     * <p>Fires {@link net.exylia.lib.api.survival.event.KitClaimEvent}, and the
     * player hears about a refusal exactly as they would from
     * {@link #claimKit(Player, String)}, which this is with
     * {@code ignoreLimits} false.
     *
     * @param player       the player receiving it
     * @param kitId        the kit id
     * @param ignoreLimits {@code true} to skip the cooldown and the use limit
     * @return {@code true} when they got it
     * @since 1.3.0
     */
    boolean giveKit(@NotNull Player player, @NotNull String kitId, boolean ignoreLimits);

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

    // ── Crates ─────────────────────────────────────────────────────────────

    /**
     * How many keys for a crate a player holds in their balance.
     *
     * <p>The balance only: key items carried in an inventory are items like any
     * other and are not counted. A balance is held per online player, so this
     * answers {@code 0} for somebody offline rather than reading the database on
     * the calling thread.
     *
     * @param player the player
     * @param crateId the crate id
     * @return the keys in their balance, {@code 0} when they have none, are
     *         offline, the crate does not exist or the module is off
     * @since 1.3.0
     */
    int crateKeys(@NotNull UUID player, @NotNull String crateId);

    /**
     * Adds keys for a crate to a player's balance.
     *
     * <p>For a vote listener, a store or an event payout. Works on a player
     * who is offline or on another server: their balance is read first, so a
     * give never writes a row built from zero over the keys they already had,
     * and it is the same on every server of the network. That read makes the
     * change land asynchronously for somebody not already loaded here; for an
     * online player it is immediate. The player is told nothing.
     *
     * @param player  the player
     * @param crateId the crate id
     * @param amount  how many keys to add, at least {@code 1}
     * @return {@code true} when the crate exists and the keys are on their way,
     *         {@code false} for an unknown crate, a non-positive amount or the
     *         module being off
     * @since 1.3.0
     */
    boolean giveCrateKeys(@NotNull UUID player, @NotNull String crateId, int amount);

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

    /**
     * The whole rank ladder.
     *
     * <p>What a rank menu or a progress line draws from, without the player
     * having to be on any of the ranks.
     *
     * @return every rank, lowest first, empty when the module is off
     * @since 1.3.0
     */
    @NotNull
    @Unmodifiable
    List<Rank> ranks();

    /**
     * What a player's next rank-up will charge them.
     *
     * <p>Money only. A rank can also ask for playtime, a permission or a
     * placeholder condition, so a player who can afford this may still be
     * refused; ask {@link #canRankUp(Player)} for that.
     *
     * <p>Reads the rank a player is on from the cache, which holds it while they
     * are online, so this answers {@code 0} for somebody offline.
     *
     * @param player the player
     * @return the money the next rank costs, {@code 0} when it is free, they are
     *         at the top, they are offline or the module is off
     * @since 1.3.0
     */
    double nextRankCost(@NotNull UUID player);

    // ── Mines ──────────────────────────────────────────────────────────────

    /**
     * Breaks a block the way a player's own swing inside a mine would.
     *
     * <p>For plugins that break blocks on a player's behalf — a 3x3 pickaxe, a
     * vein miner. Inside a mine the server's break event is always cancelled and
     * the mine removes the block itself, so breaking a block there any other way
     * skips the mine's permission, loot and regeneration. Hand every block here
     * first, and break it yourself only on {@link MineBreakResult#UNCLAIMED}.
     *
     * <p>The mine checks its permission, fires
     * {@link net.exylia.lib.api.survival.event.MineBlockBreakEvent}, and breaks
     * the block with the item in the player's main hand: its enchantments shape
     * the drops and it takes the durability. The player is told nothing when the
     * mine refuses — a caller refused on several blocks at once is the one that
     * knows whether that is worth a message.
     *
     * <p>Call it on the thread that owns the block.
     *
     * @param player who is breaking the block
     * @param block  the block
     * @return what the mine made of it, {@link MineBreakResult#UNCLAIMED} when no
     *         mine owns it or the mines module is off
     * @since 1.2.0
     */
    @NotNull
    MineBreakResult breakMineBlock(@NotNull Player player, @NotNull Block block);

    // ── Bounties ───────────────────────────────────────────────────────────

    /**
     * How much is on a player's head.
     *
     * <p>The sum of every bounty placed on them, which is what their killer
     * collects. Bounties are all held in memory, so this answers for an offline
     * player too.
     *
     * @param player the player
     * @return the total, {@link BigDecimal#ZERO} when there is none or the
     *         module is off
     * @since 1.3.0
     */
    @NotNull
    BigDecimal bountyTotal(@NotNull UUID player);

    // ── Menus ──────────────────────────────────────────────────────────────
    //
    // Each of these opens a screen exactly as the player's own command does,
    // for an NPC, a sign or a lobby item leading into it. The permission the
    // command asks for is checked first, and a player without it is told so in
    // the plugin's own words rather than shown the screen. A module that is off
    // is also said to the player, the way the command says it, so a caller has
    // nothing to explain either way. Call them on the thread that owns the
    // player.

    /**
     * Opens the kit list, as {@code /kit} does.
     *
     * @param player who to show it to
     * @since 1.3.0
     */
    void openKitsMenu(@NotNull Player player);

    /**
     * Opens the warp list, as {@code /warps} does.
     *
     * @param player who to show it to
     * @since 1.3.0
     */
    void openWarpsMenu(@NotNull Player player);

    /**
     * Opens a player's homes, as {@code /homes} does.
     *
     * <p>A player without a single home is told they have none instead of
     * being shown an empty screen.
     *
     * @param player whose homes to show, to them
     * @since 1.3.0
     */
    void openHomesMenu(@NotNull Player player);

    /**
     * Opens the rank ladder, as {@code /rankup} does.
     *
     * <p>The screen shows the player's progress towards their next rank and is
     * where they rank up from, so nothing is charged by opening it.
     *
     * @param player who to show it to
     * @since 1.3.0
     */
    void openRankUpMenu(@NotNull Player player);

    /**
     * Opens the world picker for a random teleport, as {@code /rtp} does.
     *
     * <p>Choosing a world there starts the same flow as
     * {@link #randomTeleport(Player, String)}, cooldown, price and warmup
     * included.
     *
     * @param player who to show it to
     * @since 1.3.0
     */
    void openRandomTeleportMenu(@NotNull Player player);

    /**
     * Opens the playtime rewards, as {@code /playtime} does.
     *
     * <p>{@code /playtime} asks for no permission, so neither does this.
     *
     * @param player who to show it to
     * @since 1.3.0
     */
    void openPlaytimeRewardsMenu(@NotNull Player player);
}
