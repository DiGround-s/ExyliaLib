package net.exylia.lib.api.aimtrainer;

import net.exylia.lib.api.ExyliaAPI;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Reading and driving ExyliaAimTrainer.
 *
 * <pre>{@code
 * ExyliaAPI.get(AimTrainerService.class).ifPresent(aim ->
 *     aim.activityOf(player.getUniqueId())
 *        .ifPresent(activity -> event.setCancelled(true)));
 * }</pre>
 *
 * <p>Registered with Bukkit's {@link org.bukkit.plugin.ServicesManager} when
 * ExyliaAimTrainer enables. Reach it through {@link ExyliaAPI#get(Class)}, and
 * treat an empty result as "this server does not run aim training" rather than
 * as a failure.
 *
 * <h2>What most integrations actually want</h2>
 * {@link #activityOf(UUID)} is the one question worth asking before anything
 * else: a player in a drill or a duel has had their inventory and their
 * location taken by this plugin, so teleporting them, giving them items or
 * opening a menu for them will be undone or will break their session.
 *
 * <h2>Queries are snapshots, actions run the player's own flow</h2>
 * Everything that returns a value copies what it found out of the plugin's
 * live registries. The actions run exactly what the player's own command runs
 * and return whether the plugin accepted the request; what happened arrives
 * as an event.
 *
 * <p>Call the actions from the main thread, or on Folia from the thread that
 * owns the player in question. The {@link CompletableFuture} methods go to
 * the database and are safe from anywhere.
 *
 * @since 1.5.0
 */
public interface AimTrainerService {

    // ── What the server offers ─────────────────────────────────────────────

    /** The drills this server has configured, in menu order. */
    @NotNull
    @Unmodifiable
    List<AimDrill> drills();

    /** One drill by its id. */
    @NotNull
    Optional<AimDrill> drill(@NotNull String drillId);

    /** Every arena. {@link AimArena#isReady()} says which can host anything. */
    @NotNull
    @Unmodifiable
    List<AimArena> arenas();

    @NotNull
    Optional<AimArena> arena(@NotNull String arenaId);

    // ── What a player is doing ─────────────────────────────────────────────

    /** What this plugin is doing with a player; empty when nothing. */
    @NotNull
    Optional<AimActivity> activityOf(@NotNull UUID player);

    @NotNull
    Optional<AimMatch> matchOf(@NotNull UUID player);

    @NotNull
    Optional<AimMatch> match(@NotNull UUID matchId);

    @NotNull
    @Unmodifiable
    List<AimMatch> matches();

    /** How many players an arena hosts right now, drills and duels together. */
    int playersIn(@NotNull String arenaId);

    @NotNull
    Optional<AimTrainingSession> training(@NotNull UUID player);

    @NotNull
    @Unmodifiable
    List<AimTrainingSession> trainingSessions();

    /** Who has challenged a player and is still waiting for an answer. */
    @NotNull
    Optional<UUID> pendingDuelFor(@NotNull UUID player);

    // ── Records ────────────────────────────────────────────────────────────

    /** A player's profile as cached for somebody online; empty while it loads or when they are offline. */
    @NotNull
    Optional<AimProfile> profile(@NotNull UUID player);

    /** A player's profile, from the database when it is not in memory. Safe from any thread. */
    @NotNull
    CompletableFuture<AimProfile> loadProfile(@NotNull UUID player);

    /** A player's settings, or the defaults while their row is loading. */
    @NotNull
    AimPreferences preferences(@NotNull UUID player);

    /** A player's last duels, newest first. */
    @NotNull
    CompletableFuture<List<AimMatchRecord>> history(@NotNull UUID player);

    /** A player's last solo sessions, newest first. */
    @NotNull
    CompletableFuture<List<AimSessionRecord>> sessions(@NotNull UUID player);

    /** A player's best results, one row per drill. */
    @NotNull
    CompletableFuture<List<AimRecord>> records(@NotNull UUID player);

    /** One drill's board, served from a cache the plugin refreshes on its own. */
    @NotNull
    CompletableFuture<List<AimRecord>> leaderboard(@NotNull String drillId, @NotNull AimRecordCategory category);

    /** Everybody by the sum of their best ratings across every drill. */
    @NotNull
    CompletableFuture<List<AimProfile>> overallLeaderboard();

    // ── Actions ────────────────────────────────────────────────────────────

    /** Puts a player into a drill; they are told why when this refuses. */
    boolean startTraining(@NotNull Player player, @NotNull String drillId);

    /** Challenges another player; the sender is told why when this refuses. */
    boolean duel(@NotNull Player from, @NotNull Player to, @NotNull String drillId, int bestOf);

    boolean acceptDuel(@NotNull Player player);

    boolean denyDuel(@NotNull Player player);

    /** Gives a duel up on somebody's behalf, which hands the series to their opponent. */
    boolean forfeit(@NotNull UUID player);

    /** Stops a duel with no winner and returns both players. */
    boolean cancelMatch(@NotNull UUID matchId);

    /** Takes a player out of whatever this plugin has them in. */
    boolean leave(@NotNull Player player);

    // ── Menus ──────────────────────────────────────────────────────────────
    //
    // The player's own screens, for an NPC, a hub item or another plugin's
    // menu to open. Safe from any thread. No permission is checked.

    void openMenu(@NotNull Player player);

    void openDrills(@NotNull Player player);

    void openSettings(@NotNull Player player);

    void openDuel(@NotNull Player player, @NotNull Player target);

    void openProfile(@NotNull Player viewer, @NotNull UUID target);

    void openLeaderboard(@NotNull Player viewer, @NotNull String drillId, @NotNull AimRecordCategory category);
}
