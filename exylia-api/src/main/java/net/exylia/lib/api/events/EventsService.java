package net.exylia.lib.api.events;

import net.exylia.lib.api.ExyliaAPI;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reading and driving ExyliaEvents.
 *
 * <pre>{@code
 * ExyliaAPI.get(EventsService.class).ifPresent(events ->
 *     events.eventOf(player.getUniqueId())
 *           .ifPresent(event -> player.sendMessage("Playing " + event.displayName())));
 * }</pre>
 *
 * <p>Registered with Bukkit's {@link org.bukkit.plugin.ServicesManager} when
 * ExyliaEvents enables. Reach it through {@link ExyliaAPI#get(Class)}, and treat
 * an empty result as "this server runs no events" rather than as a failure.
 *
 * <h2>Definitions and runs</h2>
 * A {@link EventDefinition} is what an admin set up; a {@link GameEvent} is one
 * playing of it. Definitions outlive runs and are what statistics are filed
 * under, so anything that persists — a leaderboard, a menu, a placeholder — keys
 * on a definition id, while a join, a spectate or a force-end names the id of a
 * run that exists right now.
 *
 * <h2>Queries are cheap, actions are not</h2>
 * Everything that returns a value reads from the plugin's caches and is safe to
 * call from a menu redraw or a placeholder. Everything that acts moves players
 * between worlds, saves and clears inventories and writes rows, so call those on
 * the main thread and no more often than a player could trigger them.
 *
 * @since 1.0.0
 */
public interface EventsService {

    // ── Running events ─────────────────────────────────────────────────────

    /**
     * The event a player is in.
     *
     * <p>Covers spectating as well as playing: a player watching an event is in
     * it as far as everything else on the server is concerned, which is the
     * question a chat, a scoreboard or a teleport plugin is really asking.
     *
     * @param player the player
     * @return their event, or empty when they are in none
     */
    @NotNull
    Optional<GameEvent> eventOf(@NotNull UUID player);

    /**
     * One running event by the id of the run.
     *
     * @param eventId the run's id
     * @return the event, or empty when no event is running under that id
     */
    @NotNull
    Optional<GameEvent> eventById(@NotNull String eventId);

    /**
     * Every event running right now.
     *
     * @return the running events, in no particular order
     */
    @NotNull
    @Unmodifiable
    List<GameEvent> runningEvents();

    /**
     * Every event a player could still be let into.
     *
     * <p>Filtered on state only. An event here may still be full, so a menu that
     * offers these should check {@link GameEvent#isFull()} before it promises
     * anything.
     *
     * @return the joinable events
     */
    @NotNull
    @Unmodifiable
    List<GameEvent> joinableEvents();

    /**
     * Whether a player is in any event, playing or spectating.
     *
     * @param player the player
     * @return {@code true} when an event has them
     */
    boolean isInEvent(@NotNull UUID player);

    /**
     * Whether a player is still alive in the event that has them.
     *
     * @param player the player
     * @return {@code true} when they are a participant who has not been
     *         eliminated
     */
    boolean isPlaying(@NotNull UUID player);

    /**
     * Whether a player is watching rather than playing.
     *
     * <p>Covers being eliminated as well as having joined to watch: an
     * eliminated player stays in the event, watching the rest of it, so the two
     * are the same thing to anything outside the event.
     *
     * @param player the player
     * @return {@code true} when they are watching a running event
     */
    boolean isSpectating(@NotNull UUID player);

    // ── Definitions ────────────────────────────────────────────────────────

    /**
     * One configured event.
     *
     * @param configId the configuration id
     * @return the definition, or empty when nothing is configured under that id
     */
    @NotNull
    Optional<EventDefinition> definition(@NotNull String configId);

    /**
     * Every configured event, disabled ones included.
     *
     * @return all definitions
     */
    @NotNull
    @Unmodifiable
    List<EventDefinition> definitions();

    /**
     * Every event that could be started right now.
     *
     * <p>Enabled, fully set up, and not already running. This is the list a
     * "start an event" menu should offer, because the other two would offer
     * entries {@link #start(String)} would refuse.
     *
     * @return the startable definitions
     */
    @NotNull
    @Unmodifiable
    List<EventDefinition> startableDefinitions();

    // ── Statistics ─────────────────────────────────────────────────────────

    /**
     * A player's counters across every event.
     *
     * <p>Read from the cache. A player nothing has asked about yet answers empty
     * and the read is started, so a placeholder drawn on the main thread never
     * waits on the database and the next draw has the number.
     *
     * @param player the player
     * @return their totals, or empty while the first read is in flight
     */
    @NotNull
    Optional<EventStats> stats(@NotNull UUID player);

    /**
     * A player's counters within one configured event.
     *
     * <p>Cached the same way as {@link #stats(UUID)}, and empty for the same
     * reason.
     *
     * @param player   the player
     * @param configId the configuration id the counters belong to
     * @return their totals for that event, or empty while the first read is in
     *         flight
     */
    @NotNull
    Optional<EventStats> eventStats(@NotNull UUID player, @NotNull String configId);

    // ── Actions ────────────────────────────────────────────────────────────
    //
    // Each of these runs the same flow the player's own command runs, including
    // the permission checks, the claim on the player and the messages they see.
    // A false is a refusal that has already been explained to them.

    /**
     * Puts a player into a running event.
     *
     * @param player  the player joining
     * @param eventId the run's id
     * @return {@code true} when they are in it afterwards
     */
    boolean join(@NotNull Player player, @NotNull String eventId);

    /**
     * Takes a player out of whatever event has them, playing or spectating.
     *
     * @param player the player leaving
     * @return {@code true} when an event let go of them
     */
    boolean leave(@NotNull Player player);

    /**
     * Puts a player into a running event as a spectator.
     *
     * @param player  the player watching
     * @param eventId the run's id
     * @return {@code true} when they are watching it afterwards
     */
    boolean spectate(@NotNull Player player, @NotNull String eventId);

    /**
     * Starts a new run of a configured event.
     *
     * <p>Refused when the definition is disabled, incompletely set up, or
     * already running: one configuration hosts one run at a time, because the
     * arena is part of the configuration.
     *
     * @param configId the configuration to start
     * @return the run that started, or empty when it was refused
     */
    @NotNull
    Optional<GameEvent> start(@NotNull String configId);

    /**
     * Ends a running event now, without a winner.
     *
     * <p>The administrative stop: players are given their inventories back and
     * sent home, and nothing is paid out. Use it for a moderation call or a
     * shutdown, not to finish a game.
     *
     * @param eventId the run's id
     * @return {@code true} when an event was running under that id
     */
    boolean forceEnd(@NotNull String eventId);

    /**
     * Begins play in a run now, without waiting out its countdown.
     *
     * <p>The administrative start, for a host who does not want to wait: the
     * minimum player count is not checked, only that there is somebody to play.
     * Refused when the run is already being played or ending, or when nobody is
     * in it yet.
     *
     * @param eventId the run's id
     * @return {@code true} when play began
     * @since 1.3.0
     */
    boolean forceStart(@NotNull String eventId);

    // ── Menus ──────────────────────────────────────────────────────────────

    /**
     * Opens the events menu for a player, the one {@code /events} opens.
     *
     * <p>The screen a lobby NPC or a hotbar item wants: the running events, a
     * way into them and the player's own record, drawn from the server owner's
     * menu files. Call it on the thread that owns the player, as an interaction
     * handler already is.
     *
     * @param player who to show it to
     * @since 1.3.0
     */
    void openMenu(@NotNull Player player);
}
