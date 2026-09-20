package net.exylia.lib.api.events.custom;

import net.exylia.lib.api.events.GameState;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * One run of a custom minigame, as its {@link MinigameHandler} sees it.
 *
 * <p>This is the whole of what a handler is given, and it is deliberately the
 * whole of what it needs: who is in, who is still alive, where the arena is,
 * what the admin set the settings to, and the few verbs that move a game
 * along. Everything else a minigame usually has to build for itself —
 * the lobby, the countdown, the teleports, the inventory save and restore, the
 * spectator seat, the arena protection, the statistics, the rewards, the
 * scoreboard, the cross-server announcement — is ExyliaEvents' side of the
 * bargain and happens whether or not the handler does anything.
 *
 * <p>An arena is handed to the handler on every call rather than kept: one
 * {@link MinigameHandler} belongs to one run, but reading state off the
 * argument is what keeps a handler from holding a reference past
 * {@link MinigameHandler#onCleanup}.
 *
 * <h2>Threading</h2>
 * Every method here is called on, and expects to be called from, the server's
 * main thread — which every handler callback already runs on. Use
 * {@link #owner()} to schedule anything that has to happen later.
 *
 * @since 1.7.0
 */
public interface MinigameArena {

    // ── Who and where ──────────────────────────────────────────────────────

    /**
     * The id of this run, unique while it lasts.
     *
     * @return the run id
     */
    @NotNull String id();

    /**
     * The id of the arena configuration this run was started from.
     *
     * @return the configuration id, which statistics are filed under
     */
    @NotNull String configId();

    /**
     * What a menu calls this arena.
     *
     * @return the display name the admin gave the configuration
     */
    @NotNull String displayName();

    /**
     * Where the run is in its life.
     *
     * @return the state; {@link GameState#PLAYING} for everything between
     *         {@link MinigameHandler#onGameStart} and
     *         {@link MinigameHandler#onGameEnd}
     */
    @NotNull GameState state();

    /**
     * The plugin that registered this minigame.
     *
     * @return the owning plugin, for scheduling and for listener registration
     */
    @NotNull Plugin owner();

    // ── Players ────────────────────────────────────────────────────────────

    /**
     * Everybody who joined, eliminated players included.
     *
     * @return a snapshot of the participants
     */
    @NotNull @Unmodifiable Set<Player> players();

    /**
     * Everybody watching without having joined.
     *
     * @return a snapshot of the spectators
     */
    @NotNull @Unmodifiable Set<Player> spectators();

    /**
     * Everybody who joined and has not been eliminated.
     *
     * @return a snapshot of the players still in it
     */
    @NotNull @Unmodifiable Set<Player> alive();

    /**
     * Whether this player is still in the game.
     *
     * @param player the player
     * @return {@code true} when they joined and have not been eliminated
     */
    boolean isAlive(@Nullable Player player);

    /**
     * Takes a player out of the game and sits them in the spectator seat.
     *
     * <p>Counts a death against them, tells the handler through
     * {@link MinigameHandler#onEliminated}, and ends the run when one player is
     * left. This is the verb for losing.
     *
     * @param player the player being eliminated
     */
    void eliminate(@NotNull Player player);

    /**
     * Sits a player out without eliminating them.
     *
     * <p>For a player who has <em>finished</em> — crossed the line, filled the
     * board — rather than lost: no death on their record, no message, and their
     * name still available to {@link MinigameHandler#winners}. Without it a
     * finished player keeps standing in everybody else's way.
     *
     * @param player the player who is done
     */
    void finish(@NotNull Player player);

    /**
     * Ends the run now, paying out and announcing whatever
     * {@link MinigameHandler#winners} answers.
     *
     * <p>Safe to call from any handler callback; a second call while the run is
     * already ending does nothing.
     */
    void end();

    // ── The clock ──────────────────────────────────────────────────────────

    /**
     * What is left of the time limit.
     *
     * @return the seconds left, or {@code 0} for a run with no limit
     */
    int remainingSeconds();

    /**
     * Changes what is left of the time limit.
     *
     * <p>For a game that earns or loses time as it goes. Setting {@code 0} on a
     * run that has a limit runs the clock out on the next tick.
     *
     * @param seconds the seconds to leave on the clock
     */
    void remainingSeconds(int seconds);

    // ── Settings ───────────────────────────────────────────────────────────

    /**
     * What this arena's admin set a whole-number setting to.
     *
     * <p>Falls back to the default the {@link MinigameSetting} declared, so a
     * setting an old arena was created before still answers.
     *
     * @param key the setting key
     * @return the value
     */
    int settingInt(@NotNull String key);

    /**
     * What this arena's admin set a decimal setting to.
     *
     * @param key the setting key
     * @return the value
     */
    double settingDouble(@NotNull String key);

    /**
     * What this arena's admin set a switch to.
     *
     * @param key the setting key
     * @return the value
     */
    boolean settingFlag(@NotNull String key);

    /**
     * What this arena's admin set a text setting to.
     *
     * @param key the setting key
     * @return the value, never {@code null}
     */
    @NotNull String settingText(@NotNull String key);

    // ── The arena ──────────────────────────────────────────────────────────

    /**
     * The world the arena is in.
     *
     * @return the world, or {@code null} when it is not loaded
     */
    @Nullable World world();

    /**
     * The box the admin drew around the arena.
     *
     * <p>ExyliaEvents already keeps players inside it and draws the border, so
     * this is for a game that wants to measure its own map — spawning something
     * in the middle, picking a random block.
     *
     * @return the bounds, or empty when the arena has none
     */
    @NotNull Optional<BoundingBox> bounds();

    /**
     * Whether a place is inside the arena.
     *
     * @param location where to test
     * @return {@code true} when it is inside, and {@code true} for an arena
     *         with no bounds at all
     */
    boolean inBounds(@Nullable Location location);

    /**
     * Where players wait before the game starts.
     *
     * @return the lobby spawn, or {@code null} when the world is not loaded
     */
    @Nullable Location lobbySpawn();

    /**
     * Where eliminated players and spectators are put.
     *
     * @return the spectator spawn, or {@code null} when the world is not loaded
     */
    @Nullable Location spectatorSpawn();

    /**
     * The spawn points the admin set inside the arena.
     *
     * @return the spawn points, in the order they were set
     */
    @NotNull @Unmodifiable List<Location> spawnPoints();

    /**
     * The places marked under one of the minigame's declared roles.
     *
     * @param role the role, as its {@link MinigameMarker} declared it
     * @return every place marked for it, empty when none was
     */
    @NotNull @Unmodifiable List<MinigamePlace> places(@NotNull String role);

    /**
     * The first place marked under a role.
     *
     * @param role the role
     * @return that place, or empty when none was marked
     */
    @NotNull Optional<MinigamePlace> place(@NotNull String role);

    // ── Arena rules ────────────────────────────────────────────────────────

    /**
     * Changes what the arena allows.
     *
     * <p>Takes effect immediately and for every player in the run, so a game
     * that opens up when its grace period ends flips the flag rather than
     * tracking who may hit whom itself.
     *
     * @param flag    what to change
     * @param allowed whether it is allowed
     */
    void flag(@NotNull MinigameFlag flag, boolean allowed);

    /**
     * The only materials that may be broken, when
     * {@link MinigameFlag#BREAKABLE_BLOCKS_ONLY} is on.
     *
     * @param materials the breakable materials
     */
    void breakable(@NotNull Collection<Material> materials);

    /**
     * The only materials that may be placed, when
     * {@link MinigameFlag#ALLOWED_BLOCKS_ONLY} is on.
     *
     * @param materials the placeable materials
     */
    void placeable(@NotNull Collection<Material> materials);

    /**
     * How long a placed block survives, when
     * {@link MinigameFlag#TEMPORARY_BLOCKS} is on.
     *
     * @param seconds how long a placed block lasts
     */
    void temporaryBlockSeconds(int seconds);

    // ── Telling players things ─────────────────────────────────────────────

    /**
     * Sends a line to everybody in the run, spectators included.
     *
     * <p>Rendered the way every other ExyliaEvents message is: palette tokens,
     * colour codes and placeholders all work.
     *
     * @param message the line
     */
    void broadcast(@NotNull String message);

    /**
     * Shows a title to everybody in the run, spectators included.
     *
     * @param title    the big line, may be empty
     * @param subtitle the small line, may be empty
     */
    void broadcastTitle(@NotNull String title, @NotNull String subtitle);

    /**
     * Redraws the scoreboard for everybody now, rather than on its next refresh.
     *
     * <p>For the moment a number changes that a player is watching for — a
     * score, a round, a countdown they can see.
     */
    void refreshScoreboard();
}
