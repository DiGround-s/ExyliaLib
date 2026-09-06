package net.exylia.lib.api.sandbox;

import net.exylia.lib.api.ExyliaAPI;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reading and driving ExyliaSandBox.
 *
 * <pre>{@code
 * ExyliaAPI.get(SandBoxService.class).ifPresent(sandbox -> {
 *     if (sandbox.isInSandbox(player.getUniqueId())) {
 *         event.setCancelled(true);
 *     }
 * });
 * }</pre>
 *
 * <p>Registered with Bukkit's {@link org.bukkit.plugin.ServicesManager} when
 * ExyliaSandBox enables. Reach it through {@link ExyliaAPI#get(Class)}, and
 * treat an empty result as "this server has no sandbox" rather than as a
 * failure.
 *
 * <h2>Being in the sandbox is a claim, not a location</h2>
 * {@link #isInSandbox(UUID)} answers from the claim the plugin holds on the
 * player, not from the world they are standing in. The two disagree for the
 * length of every teleport that has not landed yet, and the claim is the one
 * that decides: a player being pulled out of a sandbox world is still the
 * sandbox's until it has finished dressing them.
 *
 * <h2>Queries are cheap, actions are not</h2>
 * Everything that returns a value reads from the plugin's caches and is safe to
 * call from a menu redraw or a placeholder. Everything that acts teleports
 * players, rewrites inventories and writes rows, so call those on the main
 * thread and no more often than a player could trigger them.
 *
 * @since 1.0.0
 */
public interface SandBoxService {

    // ── Session ────────────────────────────────────────────────────────────

    /**
     * Whether the sandbox is what has this player.
     *
     * @param player the player
     * @return {@code true} while the sandbox holds them
     */
    boolean isInSandbox(@NotNull UUID player);

    /**
     * Whether a player is waiting for a world rather than playing in one.
     *
     * @param player the player
     * @return {@code true} when they are in the queue
     */
    boolean isInQueue(@NotNull UUID player);

    /**
     * Takes a player out of the sandbox.
     *
     * <p>Reports whether the request was accepted, not whether it has finished:
     * leaving is a teleport and an inventory restore that end some ticks later,
     * and the sandbox lets go of the player at the end of those. Refused while
     * the player is in the middle of building a kit, because there would be
     * nothing to give back.
     *
     * @param player the player leaving
     * @return {@code true} when they are on their way out
     */
    boolean leave(@NotNull Player player);

    /**
     * Puts a player in the queue for a world.
     *
     * @param player  the player queueing
     * @param worldId the world they want
     * @param kitSlot the kit slot to dress them in on arrival
     * @return {@code false} when no world is configured under that id; anything
     *         else the queue refuses is said to the player
     */
    boolean joinQueue(@NotNull Player player, @NotNull String worldId, int kitSlot);

    /**
     * Takes a player out of the queue.
     *
     * @param player the player leaving the queue
     */
    void leaveQueue(@NotNull Player player);

    // ── Worlds ─────────────────────────────────────────────────────────────

    /**
     * Every configured sandbox world, unusable ones included.
     *
     * @return the worlds, in no particular order
     */
    @NotNull
    @Unmodifiable
    List<SandboxWorld> worlds();

    /**
     * One sandbox world by its id.
     *
     * @param worldId the world id
     * @return the world, or empty when nothing is configured under that id
     */
    @NotNull
    Optional<SandboxWorld> world(@NotNull String worldId);

    /**
     * The sandbox world a Bukkit world belongs to.
     *
     * <p>The question a listener asks: whether the world an event happened in is
     * one of the sandbox's, and which.
     *
     * @param world the Bukkit world
     * @return the sandbox world, or empty when the sandbox does not own it
     */
    @NotNull
    Optional<SandboxWorld> worldOf(@NotNull World world);

    /**
     * How far a world's pre-generation has got.
     *
     * @param worldId the world id
     * @return progress from {@code 0} to {@code 1}, and {@code 0} for a world
     *         that is not pre-generating — finished or never started
     */
    float pregenerationProgress(@NotNull String worldId);

    // ── Kits ───────────────────────────────────────────────────────────────

    /**
     * Every kit a player has saved.
     *
     * <p>Read from the cache, which is filled when the player joins, so a player
     * who is offline reports nothing rather than a database read.
     *
     * @param player the player
     * @return their kits, ordered by slot
     */
    @NotNull
    @Unmodifiable
    List<SandboxKit> kits(@NotNull UUID player);

    /**
     * One of a player's kits.
     *
     * @param player the player
     * @param slot   the kit slot
     * @return the kit, or empty when that slot is unused
     */
    @NotNull
    Optional<SandboxKit> kit(@NotNull UUID player, int slot);

    /**
     * The kit a player marked as their favourite.
     *
     * @param player the player
     * @return their favourite kit, or empty when they marked none
     */
    @NotNull
    Optional<SandboxKit> favoriteKit(@NotNull UUID player);

    /**
     * How many kit slots a player is allowed.
     *
     * <p>Reads permissions, so it needs the player rather than an id.
     *
     * @param player the player
     * @return their slot limit
     */
    int maxKits(@NotNull Player player);

    /**
     * The slot of the kit a player is currently wearing.
     *
     * @param player the player
     * @return the slot, or {@code -1} when they are wearing no saved kit
     */
    int activeKitSlot(@NotNull UUID player);

    /**
     * Dresses a player in one of their kits.
     *
     * <p>Replaces what they are carrying. Nothing happens when the slot is
     * unused.
     *
     * @param player the player
     * @param slot   the kit slot to apply
     */
    void applyKit(@NotNull Player player, int slot);

    // ── Statistics ─────────────────────────────────────────────────────────

    /**
     * A player's sandbox counters.
     *
     * <p>Never empty: a player nothing has loaded yet reads as zeroes and the
     * read is started, so a placeholder drawn on the main thread never waits on
     * the database and the next draw has the numbers.
     *
     * @param player the player
     * @return their counters
     */
    @NotNull
    SandboxStats stats(@NotNull UUID player);
}
