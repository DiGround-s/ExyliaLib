package net.exylia.lib.api.specials;

import net.exylia.lib.api.ExyliaAPI;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.time.Duration;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;

/**
 * Reading and driving ExyliaSpecialsV3.
 *
 * <pre>{@code
 * ExyliaAPI.get(SpecialsService.class).ifPresent(specials ->
 *     specials.idOf(player.getInventory().getItemInMainHand())
 *             .ifPresent(id -> player.sendMessage("Holding special: " + id)));
 * }</pre>
 *
 * <p>Registered with Bukkit's {@link org.bukkit.plugin.ServicesManager} when
 * ExyliaSpecialsV3 enables. Reach it through {@link ExyliaAPI#get(Class)}, and
 * treat an empty result as "this server has no special items" rather than as a
 * failure.
 *
 * <h2>What is here and what is not</h2>
 * Special items, the cooldowns they impose, the player states they leave
 * behind, and the duels they open. The plugin's tools and stat-track items are
 * separate features with their own item formats and are deliberately not part
 * of this contract; neither is arena administration, which is a console
 * operation rather than an integration point.
 *
 * <h2>Queries are cheap, actions are not</h2>
 * Everything that returns a value reads a registry or a cache and is safe from
 * a menu redraw or a placeholder. The rest changes player state and must run on
 * the thread that owns the player.
 *
 * @since 1.0.0
 */
public interface SpecialsService {

    // ── Definitions ────────────────────────────────────────────────────────

    /**
     * Every special item id the server has loaded.
     *
     * <p>Lowercase, because that is how the plugin stores them and how every
     * other method here expects them.
     *
     * @return the ids
     */
    @NotNull
    @Unmodifiable
    Set<String> itemIds();

    /**
     * A special item's definition.
     *
     * @param itemId the item id, case insensitive
     * @return the definition, or empty when no item goes by that id
     */
    @NotNull
    Optional<SpecialItem> item(@NotNull String itemId);

    /**
     * Every special item's definition.
     *
     * <p>A snapshot of the registry. Fine for building a menu, wasteful in a
     * loop.
     *
     * @return all definitions
     */
    @NotNull
    @Unmodifiable
    Collection<SpecialItem> allItems();

    // ── Items in the world ─────────────────────────────────────────────────

    /**
     * Whether a stack is a special item.
     *
     * <p>Reads the item's own tag rather than comparing names or materials, so
     * a renamed copy still answers {@code true} and a look-alike built by hand
     * answers {@code false}.
     *
     * @param item the stack, which may be {@code null} or air
     * @return {@code true} when the plugin recognises it
     */
    boolean isSpecial(@NotNull ItemStack item);

    /**
     * Which special item a stack is.
     *
     * @param item the stack
     * @return its item id, or empty when the stack is not a special item
     */
    @NotNull
    Optional<String> idOf(@NotNull ItemStack item);

    /**
     * How many uses a stack has left.
     *
     * <p>Counted on the stack, not on the definition: two copies of the same
     * item wear out separately.
     *
     * @param item the stack
     * @return the remaining uses, or {@code -1} when the item never runs out
     *         and for a stack that is not a special item
     */
    int usesLeft(@NotNull ItemStack item);

    /**
     * Builds a fresh copy of a special item.
     *
     * <p>Rendered for the given player, because an item's name and lore may
     * contain placeholders that only mean something for one viewer. The stack
     * is returned rather than given: where it goes is the caller's decision.
     *
     * @param itemId the item id, case insensitive
     * @param player who the item is being made for
     * @return the new stack, or empty when no item goes by that id
     */
    @NotNull
    Optional<ItemStack> createItem(@NotNull String itemId, @NotNull Player player);

    /**
     * Gives a player fresh copies of a special item.
     *
     * <p>The same delivery the give command uses: what fits goes into the
     * inventory, and what does not is kept and handed over on the player's next
     * join rather than dropped where anybody could pick it up. Nothing is said
     * to the player. Call it on the thread that owns the player.
     *
     * @param player who receives the item
     * @param itemId the item id, case insensitive
     * @param amount how many copies, from 1 to 64, handed over as one stack
     * @return {@code true} when the item was given or queued, {@code false}
     *         when no item goes by that id, {@code amount} is out of range, or
     *         the item could be neither given nor kept
     * @since 1.3.0
     */
    boolean giveItem(@NotNull Player player, @NotNull String itemId, int amount);

    /**
     * Runs a special item's actions for a player who is not holding it.
     *
     * <p>The item behaves as its own trigger would have fired it, with
     * {@code target} as the player it acts on where it acts on one. Its region
     * rules and its cooldown still apply, and the player is told when either
     * refuses; a successful use starts the cooldown and plays the item's
     * effects. What is skipped is everything that belongs to a stack — no use
     * is consumed, nothing is eaten, and a combo item fires without waiting for
     * its hits. {@link net.exylia.lib.api.specials.event.SpecialItemUseEvent}
     * is fired first and may still refuse it.
     *
     * <p>Call it on the thread that owns the player.
     *
     * @param player who uses the item
     * @param itemId the item id, case insensitive
     * @param target the player it acts on, or {@code null} for an item that
     *               needs none
     * @return {@code true} when the actions started, {@code false} when no
     *         item goes by that id or the use was refused
     * @since 1.3.0
     */
    boolean activate(@NotNull Player player, @NotNull String itemId, @Nullable Player target);

    // ── Cooldowns ──────────────────────────────────────────────────────────

    /**
     * Whether a player is still waiting to use an item again.
     *
     * <p>Cooldowns are per player and per item id, so a player may be waiting
     * on one special and free to use another.
     *
     * @param player the player
     * @param itemId the item id
     * @return {@code true} when they cannot use it yet
     */
    boolean onCooldown(@NotNull Player player, @NotNull String itemId);

    /**
     * How long a player has left before an item is usable again.
     *
     * @param player the player
     * @param itemId the item id
     * @return the remaining wait, {@link Duration#ZERO} when there is none
     */
    @NotNull
    Duration cooldownRemaining(@NotNull Player player, @NotNull String itemId);

    /**
     * Puts an item on cooldown for a player.
     *
     * <p>Replaces any wait already running rather than adding to it, and paints
     * the client-side overlay on the item's own material so the player sees
     * the same countdown the plugin's own cooldowns produce.
     *
     * @param player   the player
     * @param itemId   the item id
     * @param duration how long to wait; zero or less clears the cooldown
     */
    void startCooldown(@NotNull Player player, @NotNull String itemId, @NotNull Duration duration);

    /**
     * Ends a player's wait on one item.
     *
     * @param player the player
     * @param itemId the item id
     */
    void clearCooldown(@NotNull Player player, @NotNull String itemId);

    /**
     * Ends a player's wait on every special item.
     *
     * @param player the player
     */
    void clearCooldowns(@NotNull Player player);

    // ── Combat ─────────────────────────────────────────────────────────────

    /**
     * Whether combat tagging is available at all.
     *
     * <p>The plugin talks to whichever combat plugin is installed, or to its
     * own built-in tagger. When none of them is running, every player reads as
     * out of combat — which this method is how you tell apart from a server
     * where nobody happens to be fighting.
     *
     * @return {@code true} when a combat system is active
     */
    boolean hasCombatSystem();

    /**
     * Whether a player is combat tagged.
     *
     * @param player the player
     * @return {@code true} when they are in combat
     */
    boolean isInCombat(@NotNull Player player);

    /**
     * How long a player stays combat tagged.
     *
     * @param player the player
     * @return the remaining tag, {@link Duration#ZERO} when they are not tagged
     *         or no combat system is active
     */
    @NotNull
    Duration combatRemaining(@NotNull Player player);

    // ── Player states ──────────────────────────────────────────────────────

    /**
     * Whether a special item is currently holding a player still.
     *
     * <p>Frozen players cannot move and, depending on the item, cannot fly.
     * Worth checking before teleporting somebody or starting a flow that
     * expects them to walk somewhere.
     *
     * @param player the player
     * @return {@code true} when they are frozen
     */
    boolean isFrozen(@NotNull Player player);

    /**
     * Whether a special item is currently making a player immune to damage.
     *
     * <p>Separate from {@link Player#isInvulnerable()}: this is the plugin's
     * own timed immunity, applied by cancelling damage rather than by setting
     * the vanilla flag.
     *
     * @param player the player
     * @return {@code true} when they cannot be hurt
     */
    boolean isInvulnerable(@NotNull Player player);

    // ── Duels ──────────────────────────────────────────────────────────────

    /**
     * Whether a player is in a one-versus-one duel.
     *
     * @param player the player
     * @return {@code true} when they are duelling
     */
    boolean isInMatch(@NotNull Player player);

    /**
     * The duel a player is in.
     *
     * @param player the player
     * @return their match, or empty when they are not duelling
     */
    @NotNull
    Optional<SpecialsMatch> matchOf(@NotNull Player player);

    /**
     * Every duel running right now.
     *
     * @return the active matches
     */
    @NotNull
    @Unmodifiable
    Collection<SpecialsMatch> activeMatches();

    /**
     * Stops a duel with no winner and returns both players.
     *
     * <p>For a plugin that needs the players back — an event starting, a
     * restart warning. No rewards are paid, and
     * {@link net.exylia.lib.api.specials.event.OneVsOneEndEvent} fires with the
     * error result. Call it on the main thread.
     *
     * @param matchId the id from {@link SpecialsMatch#matchId()}
     * @return {@code true} when a running duel was stopped
     * @since 1.3.0
     */
    boolean endMatch(@NotNull String matchId);
}
