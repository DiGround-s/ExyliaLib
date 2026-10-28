package net.exylia.lib.api.journey;

import net.exylia.lib.api.ExyliaAPI;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Journeys in ExyliaJourney: guided chains of steps that teach a player the
 * server, and the cinematics they play.
 *
 * <pre>{@code
 * // a plugin of yours tells a journey something happened
 * ExyliaAPI.get(JourneyService.class).ifPresent(journeys -> journeys.trigger(player, "shop_opened", 1));
 *
 * // and asks where the player is
 * ExyliaAPI.get(JourneyService.class)
 *          .flatMap(journeys -> journeys.activeJourney(player))
 *          .ifPresent(progress -> player.sendMessage("Step " + (progress.stepIndex() + 1)));
 * }</pre>
 *
 * <p>Registered with Bukkit's {@link org.bukkit.plugin.ServicesManager} when
 * ExyliaJourney enables. Reach it through {@link ExyliaAPI#get(Class)}.
 *
 * <p>A player may have several journeys running at once, one per journey
 * group. Progress is read from memory and is only known for online players.
 * Every method is safe from any thread; anything that changes a player's
 * progress runs on that player's own thread and may complete a tick later.
 *
 * @since 3.4.0
 */
public interface JourneyService {

    /**
     * Every journey this server knows, enabled or not.
     *
     * @return the ids, in no particular order
     */
    @NotNull List<String> journeyIds();

    /**
     * Whether a journey exists.
     *
     * @param journeyId the journey id
     * @return {@code true} for a journey this server knows
     */
    boolean exists(@NotNull String journeyId);

    /**
     * Starts a journey for a player, as an admin command would.
     *
     * <p>Requirements, the journey's group and its repeat rules are checked
     * exactly as for any other start, then {@link
     * net.exylia.lib.api.journey.event.JourneyStartEvent} is fired. The cause
     * on that event is {@code API}.
     *
     * @param player    the player
     * @param journeyId the journey
     * @return what happened
     */
    @NotNull StartResult start(@NotNull Player player, @NotNull String journeyId);

    /**
     * Stops a running journey without completing it. No rewards; the
     * progress is dropped as if it had never started, and it may be started
     * again.
     *
     * @param player    the player
     * @param journeyId the journey
     * @return {@code true} when it was running
     */
    boolean stop(@NotNull Player player, @NotNull String journeyId);

    /**
     * Completes a running journey now, paying its completion rewards and
     * firing {@link net.exylia.lib.api.journey.event.JourneyCompleteEvent}.
     * The steps left are not paid.
     *
     * @param player    the player
     * @param journeyId the journey
     * @return {@code true} when it was running
     */
    boolean complete(@NotNull Player player, @NotNull String journeyId);

    /**
     * Forgets everything a player did in a journey: progress, completion and
     * cooldown. Works for an offline player; the change is written to the
     * database.
     *
     * @param player    the player's id
     * @param journeyId the journey
     * @return {@code true} when the journey exists
     */
    boolean reset(@NotNull UUID player, @NotNull String journeyId);

    /**
     * The journey the player is following now: the one shown on their
     * screen when several are running.
     *
     * @param player the player
     * @return its progress, or empty when they have none running
     */
    @NotNull Optional<JourneyProgress> activeJourney(@NotNull Player player);

    /**
     * Every journey running for a player, one per group at most.
     *
     * @param player the player
     * @return their progress in each, possibly empty
     */
    @NotNull List<JourneyProgress> activeJourneys(@NotNull Player player);

    /**
     * Where a player stands with a journey.
     *
     * @param player    the player
     * @param journeyId the journey
     * @return the state; {@link JourneyState#NOT_STARTED} for an unknown journey
     */
    @NotNull JourneyState state(@NotNull Player player, @NotNull String journeyId);

    /**
     * Reports something that happened, for every running step whose
     * objective is {@code CUSTOM} with this key.
     *
     * <p>This is how another plugin teaches a journey about its own features:
     * a step written as {@code CUSTOM} with key {@code shop_opened} advances
     * when this is called with that key.
     *
     * @param player the player it happened to
     * @param key    the key, matched without regard to case
     * @param amount how much it counts for, at least {@code 1}
     * @return how many running steps it advanced
     */
    int trigger(@NotNull Player player, @NotNull String key, int amount);

    /**
     * Every cinematic this server knows.
     *
     * @return the ids, in no particular order
     */
    @NotNull List<String> cinematicIds();

    /**
     * Plays a cinematic to a player, with its subtitles, bars and fade.
     *
     * <p>Refused for a player in combat, already watching one, in another
     * world than the cinematic, or on a server without PacketEvents. A
     * Bedrock player is sent the cinematic's summary in chat instead and this
     * answers {@code true}.
     *
     * @param player      the player
     * @param cinematicId the cinematic
     * @return {@code true} when it is playing (or was summarised)
     */
    boolean playCinematic(@NotNull Player player, @NotNull String cinematicId);
}
