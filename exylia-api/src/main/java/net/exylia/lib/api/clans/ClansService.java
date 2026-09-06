package net.exylia.lib.api.clans;

import net.exylia.lib.api.ExyliaAPI;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reading and driving ExyliaClans.
 *
 * <pre>{@code
 * ExyliaAPI.get(ClansService.class).ifPresent(clans ->
 *     clans.clanOf(player.getUniqueId())
 *          .ifPresent(clan -> player.sendMessage("Clan: " + clan.name())));
 * }</pre>
 *
 * <p>Registered with Bukkit's {@link org.bukkit.plugin.ServicesManager} when
 * ExyliaClans enables. Reach it through {@link ExyliaAPI#get(Class)}, and treat
 * an empty result as "clans are not part of this server" rather than as a
 * failure.
 *
 * <h2>Queries are cheap, actions are not</h2>
 * Everything that returns a value reads from the plugin's cache and is safe to
 * call from a menu redraw or a placeholder. Everything that returns
 * {@code void} runs the same flow the player's own command runs — permission
 * checks, cooldowns, database writes and the messages the player sees — so call
 * those on the main thread and no more often than a player could trigger them.
 *
 * @since 1.0.0
 */
public interface ClansService {

    // ── Membership ─────────────────────────────────────────────────────────

    /**
     * Whether a player belongs to any clan.
     *
     * @param player the player
     * @return {@code true} when they are in a clan
     */
    boolean isInClan(@NotNull UUID player);

    /**
     * Whether a player leads their clan.
     *
     * @param player the player
     * @return {@code true} when they are the leader
     */
    boolean isLeader(@NotNull UUID player);

    /**
     * The clan a player belongs to.
     *
     * @param player the player
     * @return their clan, or empty when they are in none
     */
    @NotNull
    Optional<Clan> clanOf(@NotNull UUID player);

    /**
     * A clan by its id.
     *
     * @param clanId the clan id
     * @return the clan, or empty when no clan has that id
     */
    @NotNull
    Optional<Clan> clanById(@NotNull String clanId);

    /**
     * A clan by the name players type.
     *
     * @param name the display name, case insensitive
     * @return the clan, or empty when no clan goes by that name
     */
    @NotNull
    Optional<Clan> clanByName(@NotNull String name);

    /**
     * Every clan on the server.
     *
     * <p>A snapshot of the cache. Fine for a leaderboard, wasteful in a loop.
     *
     * @return all clans
     */
    @NotNull
    @Unmodifiable
    Collection<Clan> allClans();

    /**
     * A player's membership record.
     *
     * @param player the player
     * @return their membership, or empty when they are in no clan
     */
    @NotNull
    Optional<ClanMember> memberOf(@NotNull UUID player);

    /**
     * Everyone in a clan.
     *
     * @param clanId the clan id
     * @return its members, empty when the clan does not exist
     */
    @NotNull
    @Unmodifiable
    List<ClanMember> membersOf(@NotNull String clanId);

    /**
     * A player's role within their clan.
     *
     * @param player the player
     * @return their role, or empty when they are in no clan
     */
    @NotNull
    Optional<ClanRole> roleOf(@NotNull UUID player);

    /**
     * The name of a player's role, for display.
     *
     * @param player the player
     * @return the role name, or an empty string when they are in no clan
     */
    @NotNull
    String roleNameOf(@NotNull UUID player);

    /**
     * How many players are in a clan.
     *
     * @param player any member of the clan
     * @return the member count, or {@code 0} when they are in no clan
     */
    int memberCount(@NotNull UUID player);

    // ── Relations ──────────────────────────────────────────────────────────

    /**
     * Whether two clans are allied.
     *
     * @param clanIdA one clan
     * @param clanIdB the other
     * @return {@code true} when they are allies
     */
    boolean areAllies(@NotNull String clanIdA, @NotNull String clanIdB);

    /**
     * Whether two clans are rivals.
     *
     * @param clanIdA one clan
     * @param clanIdB the other
     * @return {@code true} when they are rivals
     */
    boolean areRivals(@NotNull String clanIdA, @NotNull String clanIdB);

    /**
     * Whether two players share a clan.
     *
     * <p>The check a combat listener wants: {@code false} when either player is
     * clanless, so two unaffiliated players are never treated as clanmates.
     *
     * @param playerA one player
     * @param playerB the other
     * @return {@code true} when both are in the same clan
     */
    boolean sameClan(@NotNull UUID playerA, @NotNull UUID playerB);

    /**
     * Whether a player's clan lets its members hurt each other.
     *
     * @param player the player
     * @return {@code true} when friendly fire is on, {@code false} when it is
     *         off or the player is in no clan
     */
    boolean friendlyFireEnabled(@NotNull UUID player);

    /**
     * How many allies a player's clan has.
     *
     * @param player any member of the clan
     * @return the ally count, or {@code 0} when they are in no clan
     */
    int allyCount(@NotNull UUID player);

    // ── Progression ────────────────────────────────────────────────────────

    /**
     * A clan's level.
     *
     * @param clanId the clan id
     * @return the level, {@code 1} when the clan does not exist
     */
    int level(@NotNull String clanId);

    /**
     * Experience a clan has earned.
     *
     * @param clanId the clan id
     * @return the experience total
     */
    long exp(@NotNull String clanId);

    /**
     * Experience the clan's next level costs.
     *
     * @param clanId the clan id
     * @return the threshold, {@code 0} when the clan does not exist or is at the
     *         highest level
     */
    long expForNextLevel(@NotNull String clanId);

    /**
     * How many members the clan may hold at its current level.
     *
     * @param clanId the clan id
     * @return the member limit
     */
    int maxMembers(@NotNull String clanId);

    /**
     * How many alliances the clan may hold at its current level.
     *
     * @param clanId the clan id
     * @return the alliance limit
     */
    int maxAlliances(@NotNull String clanId);

    /**
     * How many rivalries the clan may hold at its current level.
     *
     * @param clanId the clan id
     * @return the rivalry limit
     */
    int maxRivals(@NotNull String clanId);

    // ── Statistics ─────────────────────────────────────────────────────────

    /**
     * A clan's kill, death and playtime totals.
     *
     * @param clanId the clan id
     * @return its statistics, or empty when the clan does not exist
     */
    @NotNull
    Optional<ClanStats> stats(@NotNull String clanId);

    // ── Raiding ────────────────────────────────────────────────────────────

    /**
     * Whether a clan's land can be raided right now.
     *
     * @param clanId the clan id
     * @return {@code true} when the clan is raidable
     */
    boolean isRaidable(@NotNull String clanId);

    /**
     * Where a clan stands in the DTR cycle.
     *
     * @param clanId the clan id
     * @return the state, {@link DtrState#NORMAL} when the clan does not exist or
     *         the server runs without DTR
     */
    @NotNull
    DtrState dtrState(@NotNull String clanId);

    /**
     * A clan's current deaths-till-raidable.
     *
     * @param clanId the clan id
     * @return the current value
     */
    double dtr(@NotNull String clanId);

    /**
     * The highest DTR a clan can reach at its current size and level.
     *
     * @param clanId the clan id
     * @return the maximum
     */
    double maxDtr(@NotNull String clanId);

    // ── Land ───────────────────────────────────────────────────────────────

    /**
     * The land a clan owns.
     *
     * @param clanId the clan id
     * @return its claim, or empty when it owns none
     */
    @NotNull
    Optional<ClanClaim> claimOf(@NotNull String clanId);

    // ── Actions ────────────────────────────────────────────────────────────
    //
    // Each of these runs the same flow the player's own command runs, including
    // the permission checks and the messages they see. They report nothing back:
    // the player is told what happened, and a caller that needs to know should
    // read the state afterwards.

    /**
     * Creates a clan led by a player.
     *
     * @param player the founder
     * @param name   the clan name to try
     */
    void createClan(@NotNull Player player, @NotNull String name);

    /**
     * Disbands a player's clan.
     *
     * @param leader the clan's leader
     */
    void disbandClan(@NotNull Player leader);

    /**
     * Removes a player from their clan.
     *
     * @param player the player leaving
     */
    void leaveClan(@NotNull Player player);

    /**
     * Invites a player to the inviter's clan.
     *
     * @param inviter a member allowed to invite
     * @param target  the player being invited
     */
    void invite(@NotNull Player inviter, @NotNull Player target);

    /**
     * Removes a member from the actor's clan.
     *
     * @param actor  a member allowed to kick
     * @param target the member being removed
     */
    void kick(@NotNull Player actor, @NotNull UUID target);

    /**
     * Hands leadership of a clan to another member.
     *
     * @param leader    the current leader
     * @param newLeader the member taking over
     */
    void transferLeader(@NotNull Player leader, @NotNull UUID newLeader);

    /**
     * Moves money from a player into their clan bank.
     *
     * @param player the depositor
     * @param amount how much
     */
    void deposit(@NotNull Player player, double amount);

    /**
     * Moves money from a clan bank to a player.
     *
     * @param player the withdrawer
     * @param amount how much
     */
    void withdraw(@NotNull Player player, double amount);

    /**
     * Sends a message to a player's clan chat.
     *
     * @param player  the sender
     * @param message what to say
     */
    void sendClanChat(@NotNull Player player, @NotNull String message);

    /**
     * Sets a clan's home to where the player is standing.
     *
     * @param player a member allowed to set the home
     */
    void setHome(@NotNull Player player);

    /**
     * Sends a player to their clan home.
     *
     * @param player the member travelling
     */
    void teleportHome(@NotNull Player player);
}
