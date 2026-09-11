package net.exylia.lib.api.capture;

import net.exylia.lib.api.ExyliaAPI;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Reading and driving ExyliaCapture.
 *
 * <pre>{@code
 * ExyliaAPI.get(CaptureService.class).ifPresent(capture ->
 *     capture.activeEvents().forEach(event ->
 *         getLogger().info(event.displayName() + " has " + event.participants() + " players")));
 * }</pre>
 *
 * <p>Registered with Bukkit's {@link org.bukkit.plugin.ServicesManager} when
 * ExyliaCapture enables. Reach it through {@link ExyliaAPI#get(Class)}, and
 * treat an empty result as "this server does not run capture events" rather
 * than as a failure.
 *
 * <h2>Configs and runs</h2>
 * A config is an event an administrator set up; a run is one occurrence of it.
 * At most one run of a config exists at a time, and a run carries its config's
 * id — so {@link #start(String)}, {@link #stop(String)} and
 * {@link #event(String)} all take the same string, and a config can be started
 * again once its run has ended.
 *
 * <h2>Queries are cheap, actions are not</h2>
 * Everything that returns a value reads from the plugin's caches and is safe to
 * call from a menu redraw or a placeholder, with the documented exceptions that
 * return a {@link CompletableFuture} and go to the database. Starting and
 * stopping an event registers regions, spawns displays and hands out rewards,
 * so call those from the main thread.
 *
 * @since 1.0.0
 */
public interface CaptureService {

    // ── Configured events ──────────────────────────────────────────────────

    /**
     * An event config by its id.
     *
     * @param configId the config id
     * @return the config, or empty when no config has that id
     */
    @NotNull
    Optional<CaptureConfig> config(@NotNull String configId);

    /**
     * Every event config, enabled or not.
     *
     * @return all configs
     */
    @NotNull
    @Unmodifiable
    List<CaptureConfig> configs();

    /**
     * The configs that could be started right now.
     *
     * <p>Enabled, fully configured and not already running. A config missing
     * its zone is left out here but still appears in {@link #configs()}, so an
     * admin menu can show it as unfinished rather than hide it.
     *
     * @return the startable configs
     */
    @NotNull
    @Unmodifiable
    List<CaptureConfig> startableConfigs();

    /**
     * Every event type the plugin knows how to run.
     *
     * <p>A registry rather than a fixed list: a mode registered by another
     * plugin appears here, which is why {@link CaptureConfig#type()} is a
     * string.
     *
     * @return the registered type ids
     */
    @NotNull
    @Unmodifiable
    Set<String> eventTypes();

    // ── Running events ─────────────────────────────────────────────────────

    /**
     * A running event by its run id.
     *
     * @param eventId the run id
     * @return the event, or empty when nothing with that id is running
     */
    @NotNull
    Optional<CaptureEvent> event(@NotNull String eventId);

    /**
     * Every event running right now.
     *
     * @return the running events, empty when none are
     */
    @NotNull
    @Unmodifiable
    List<CaptureEvent> activeEvents();

    /**
     * Whether a config has a run going.
     *
     * @param configId the config id
     * @return {@code true} when it is running
     */
    boolean isRunning(@NotNull String configId);

    /**
     * The event a player is taking part in.
     *
     * <p>A player counts as taking part from the first moment they stand in a
     * zone, and keeps counting until the event ends — so this still answers for
     * somebody who has since walked out, which is what a reward or a scoreboard
     * needs.
     *
     * @param player the player
     * @return their event, or empty when they are in none
     */
    @NotNull
    Optional<CaptureEvent> eventOf(@NotNull UUID player);

    /**
     * Everybody taking part in a running event.
     *
     * @param eventId the run id
     * @return their uuids, empty when nothing with that id is running
     */
    @NotNull
    @Unmodifiable
    List<UUID> participants(@NotNull String eventId);

    /**
     * What a player has scored in a running event.
     *
     * @param eventId the run id
     * @param player  the player
     * @return their points, {@code 0} when the event is not running or is not
     *         scored by points
     */
    int points(@NotNull String eventId, @NotNull UUID player);

    /**
     * Everybody's score in a running event.
     *
     * <p>A snapshot of the tallies, so it can be sorted and drawn without the
     * next tick changing it underneath. Empty for an event whose mode does not
     * score by points.
     *
     * @param eventId the run id
     * @return the scores by player
     */
    @NotNull
    @Unmodifiable
    Map<UUID, Integer> scores(@NotNull String eventId);

    // ── Actions ────────────────────────────────────────────────────────────

    /**
     * Starts a run of a config.
     *
     * <p>Refuses when the config is disabled, unfinished, or already running,
     * and says so in the plugin's log rather than throwing: an automation that
     * starts events on a schedule should not have to catch anything.
     *
     * @param configId the config to start
     * @return the new run's id, or empty when it could not start
     */
    @NotNull
    Optional<String> start(@NotNull String configId);

    /**
     * Ends a run early.
     *
     * <p>The event finishes the way its clock running out finishes it: a winner
     * is resolved from the scores so far and the rewards are handed out. There
     * is no way to cancel one without rewards, deliberately — a stopped event
     * players took part in is still an event they took part in.
     *
     * @param eventId the run id
     * @return {@code true} when something was running under that id
     */
    boolean stop(@NotNull String eventId);

    // ── Menus ──────────────────────────────────────────────────────────────

    /**
     * Opens the capture menu for a player, the one {@code /capture} opens.
     *
     * <p>The screen a lobby NPC or a hotbar item wants: what is live, what is
     * scheduled and when, drawn from the server owner's menu files. Call it on
     * the thread that owns the player, as an interaction handler already is.
     *
     * @param player who to show it to
     * @since 1.3.0
     */
    void openMenu(@NotNull Player player);

    // ── Statistics ─────────────────────────────────────────────────────────

    /**
     * A player's totals across every event.
     *
     * <p>Reads the cache and never fails: a player with no row gets one with
     * every counter at zero, and the real row is fetched in the background for
     * the next call.
     *
     * @param player the player
     * @return their totals
     */
    @NotNull
    CaptureStats stats(@NotNull UUID player);

    /**
     * A player's totals in one event.
     *
     * @param player   the player
     * @param configId the event config id
     * @return their totals for that event
     */
    @NotNull
    CaptureStats stats(@NotNull UUID player, @NotNull String configId);

    /**
     * A clan's totals across every event.
     *
     * @param clanId the clan id
     * @return its totals
     */
    @NotNull
    CaptureClanStats clanStats(@NotNull String clanId);

    /**
     * The best players overall.
     *
     * <p>Goes to the database and completes on a database thread. For a
     * placeholder or a menu redraw prefer {@link #cachedTopPlayers()}, which
     * never queries.
     *
     * @param limit how many rows
     * @return the leaderboard, best first
     */
    @NotNull
    CompletableFuture<List<CaptureStats>> topPlayers(int limit);

    /**
     * The best players in one event.
     *
     * @param configId the event config id
     * @param limit    how many rows
     * @return the leaderboard, best first
     */
    @NotNull
    CompletableFuture<List<CaptureStats>> topPlayers(@NotNull String configId, int limit);

    /**
     * The best clans overall.
     *
     * @param limit how many rows
     * @return the leaderboard, best first
     */
    @NotNull
    CompletableFuture<List<CaptureClanStats>> topClans(int limit);

    /**
     * The podium the plugin keeps warm for its own placeholders.
     *
     * <p>Ten rows, refreshed on a timer. Empty until the first refresh has run,
     * which is the distinction a placeholder needs: it can print a fallback
     * rather than block a tick on a query.
     *
     * @return the cached leaderboard, best first
     */
    @NotNull
    @Unmodifiable
    List<CaptureStats> cachedTopPlayers();

    /**
     * The same for clans.
     *
     * @return the cached clan leaderboard, best first
     */
    @NotNull
    @Unmodifiable
    List<CaptureClanStats> cachedTopClans();
}
