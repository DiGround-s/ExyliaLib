package net.exylia.lib.api.events.custom;

import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Map;

/**
 * The rules of one custom minigame, for one run of it.
 *
 * <p>A fresh handler is built for every run through
 * {@link MinigameDefinition.Builder#handler}, so a handler may keep whatever
 * state the game needs in its own fields without ever clearing it: the object
 * is thrown away when the run ends.
 *
 * <p>Every method has a default that does nothing sensible-by-omission, so a
 * minigame implements only the parts it actually has. The smallest useful
 * handler is:
 *
 * <pre>{@code
 * public final class RedLightHandler implements MinigameHandler {
 *
 *     @Override
 *     public void onGameStart(MinigameArena arena) {
 *         arena.flag(MinigameFlag.PVP, false);
 *         arena.broadcast("{success}Go!");
 *     }
 *
 *     @Override
 *     public Map<String, String> placeholders(MinigameArena arena, Player viewer) {
 *         return Map.of("light", light ? "{success}GREEN" : "{error}RED");
 *     }
 * }
 * }</pre>
 *
 * <h2>What you do not have to do</h2>
 * Do not teleport players in or out, save or restore inventories, put anybody
 * into spectator mode, protect the arena, count kills and deaths, pay rewards,
 * show the lobby board, or clean up when the run ends. All of that already
 * happened by the time a callback here runs, and undoing it by hand is how a
 * player ends up stuck in an arena that no longer exists.
 *
 * @since 1.7.0
 */
public interface MinigameHandler {

    /**
     * The game has begun: everybody is in the arena and the clock is running.
     *
     * <p>Where the arena's rules are set, the first round is started and the
     * players are given whatever the game hands out beyond their kit.
     *
     * @param arena the run
     */
    default void onGameStart(@NotNull MinigameArena arena) {
    }

    /**
     * One second of play has passed.
     *
     * <p>Called once a second while the game is being played, never before it
     * starts or after it ends. For anything on a timer: a round, a shrinking
     * floor, a spawn wave.
     *
     * @param arena the run
     */
    default void onTick(@NotNull MinigameArena arena) {
    }

    /**
     * A player is about to be put into the arena at the start of the game.
     *
     * <p>Where a game hands out its own items, effects or game mode. The
     * player's own inventory is already saved and cleared, and the arena kit
     * the admin configured has already been given.
     *
     * @param arena  the run
     * @param player the player
     */
    default void onPrepare(@NotNull MinigameArena arena, @NotNull Player player) {
    }

    /**
     * A player has arrived in the lobby, before the game starts.
     *
     * <p>For anything a game shows while it fills up — a chooser item, a hint.
     * Called for the ordinary join and for an admin forcing somebody in.
     *
     * @param arena  the run
     * @param player the player
     */
    default void onLobbyJoin(@NotNull MinigameArena arena, @NotNull Player player) {
    }

    /**
     * A player has been taken out of the game.
     *
     * <p>They are already in the spectator seat. For a game that has to react —
     * rebalancing, ending a round early, awarding a point to whoever did it.
     *
     * @param arena  the run
     * @param player the eliminated player
     */
    default void onEliminated(@NotNull MinigameArena arena, @NotNull Player player) {
    }

    /**
     * The time limit ran out.
     *
     * <p>The default ends the run, which is what a timed game wants. Override
     * it for a game that adds time or plays another round instead — but then
     * the run only ends when something calls {@link MinigameArena#end()}.
     *
     * @param arena the run
     */
    default void onTimeUp(@NotNull MinigameArena arena) {
        arena.end();
    }

    /**
     * A player walked into one of the arena's marked places.
     *
     * <p>Only fires for a role the minigame declared through
     * {@link MinigameDefinition.Builder#markers}.
     *
     * @param arena  the run
     * @param player the player
     * @param place  the place they entered
     */
    default void onMarkerEnter(@NotNull MinigameArena arena, @NotNull Player player,
                               @NotNull MinigamePlace place) {
    }

    /**
     * A player walked out of one of the arena's marked places.
     *
     * @param arena  the run
     * @param player the player
     * @param place  the place they left
     */
    default void onMarkerExit(@NotNull MinigameArena arena, @NotNull Player player,
                              @NotNull MinigamePlace place) {
    }

    /**
     * The game is over and the winners are about to be announced.
     *
     * <p>The last chance to work out a result. Called before
     * {@link #winners(MinigameArena)}.
     *
     * @param arena the run
     */
    default void onGameEnd(@NotNull MinigameArena arena) {
    }

    /**
     * Who won.
     *
     * <p>The default is everybody left alive, which is right for an elimination
     * game and wrong for a scored one — a game with points answers whoever has
     * the most of them. An empty answer announces that nobody won, which is a
     * real result rather than a failure.
     *
     * @param arena the run
     * @return the winners; they are paid the winner rewards and announced
     */
    default @NotNull Collection<Player> winners(@NotNull MinigameArena arena) {
        return arena.alive();
    }

    /**
     * The values this game's scoreboard lines are filled with.
     *
     * <p>Keys are placeholder names without their percent signs, so
     * {@code "score"} fills {@code %score%}. Read once per viewer per refresh,
     * so it must be cheap and must not block.
     *
     * @param arena  the run
     * @param viewer whose board is being drawn; may be a spectator
     * @return the values, empty when the board needs none of its own
     */
    default @NotNull Map<String, String> placeholders(@NotNull MinigameArena arena,
                                                      @NotNull Player viewer) {
        return Map.of();
    }

    /**
     * A Bukkit listener that lives exactly as long as the run.
     *
     * <p>Registered when the game starts and unregistered when it is cleaned
     * up, so a game listens for its own blocks and hits without leaking a
     * listener per run. Return {@code null} for a game that needs none.
     *
     * @param arena the run
     * @return the listener, or {@code null}
     */
    default @Nullable Listener listener(@NotNull MinigameArena arena) {
        return null;
    }

    /**
     * The run is being torn down.
     *
     * <p>Everything ExyliaEvents owns has already been undone. For whatever the
     * handler itself started: a task, a hologram, an entity it spawned.
     *
     * @param arena the run
     */
    default void onCleanup(@NotNull MinigameArena arena) {
    }
}
