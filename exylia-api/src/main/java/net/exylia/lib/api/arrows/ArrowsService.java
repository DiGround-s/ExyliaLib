package net.exylia.lib.api.arrows;

import net.exylia.lib.api.ExyliaAPI;
import net.exylia.lib.api.arrows.event.ArrowEffectPlayEvent;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reading and driving ExyliaArrows.
 *
 * <pre>{@code
 * ExyliaAPI.get(ArrowsService.class).ifPresent(arrows -> {
 *     arrows.select(player, "storm");
 *     arrows.effectItem("storm").ifPresent(item -> player.getInventory().addItem(item));
 * });
 * }</pre>
 *
 * <p>Registered with Bukkit's {@link org.bukkit.plugin.ServicesManager} when
 * ExyliaArrows enables. Reach it through {@link ExyliaAPI#get(Class)}, and
 * treat an empty result as "arrow effects are not part of this server" rather
 * than as a failure.
 *
 * <h2>Two halves, and only one of them is on</h2>
 * An effect either lives on the bow as an item does, or belongs to the player
 * and plays on whatever they fire. {@link #mode()} says which, and the half the
 * server does not use answers {@code false} or empty rather than throwing: an
 * integration written for both runs on either.
 *
 * <h2>Ids, and what happens to unknown ones</h2>
 * Everything is addressed by effect id, matched without regard to case. An id
 * the catalogue does not declare is not an error — a reload can remove one
 * while a crate still hands it out — so lookups answer empty and writes answer
 * {@code false}.
 *
 * <h2>Queries are cheap, actions are not</h2>
 * Everything that returns a value reads from the plugin's cache and is safe to
 * call from a menu redraw or a placeholder. A player who has not finished
 * loading reads as one who chose nothing, which is why every read here answers
 * rather than waits. Everything that changes a player's choice writes to the
 * database, so call those on the main thread and no more often than a player
 * could.
 *
 * @since 1.0.0
 */
public interface ArrowsService {

    // ── The catalogue ──────────────────────────────────────────────────────

    /**
     * Every effect the server declares, in the order a menu draws them.
     *
     * <p>A snapshot of the catalogue. Fine for building a shop, wasteful in a
     * loop.
     *
     * @return all effects
     */
    @NotNull
    @Unmodifiable
    List<ArrowEffect> effects();

    /**
     * An effect by id.
     *
     * @param effectId the effect id, case insensitive
     * @return the effect, or empty when the catalogue declares no such id
     */
    @NotNull
    Optional<ArrowEffect> effect(@NotNull String effectId);

    /**
     * Whether the catalogue declares an effect.
     *
     * @param effectId the effect id, case insensitive
     * @return {@code true} when it exists
     */
    boolean exists(@NotNull String effectId);

    /**
     * Every category, in the order the menu draws its tabs.
     *
     * @return all categories
     */
    @NotNull
    @Unmodifiable
    List<ArrowCategory> categories();

    /**
     * A category by id.
     *
     * @param categoryId the category id, case insensitive
     * @return the category, or empty when the catalogue declares no such id
     */
    @NotNull
    Optional<ArrowCategory> category(@NotNull String categoryId);

    /**
     * The effects in one category, in menu order.
     *
     * @param categoryId the category id, case insensitive
     * @return its effects, empty when the category does not exist
     */
    @NotNull
    @Unmodifiable
    List<ArrowEffect> effectsIn(@NotNull String categoryId);

    // ── Permissions ────────────────────────────────────────────────────────

    /**
     * Whether a player may use an effect.
     *
     * <p>Answers by the server's own rule: the effect's node, the node for its
     * whole category, the wildcard, or the setting that turns permissions off
     * entirely. A shop asking this gets the same answer the menu would give,
     * not a raw permission check.
     *
     * @param player   the player
     * @param effectId the effect id
     * @return {@code true} when they may use it
     */
    boolean canUse(@NotNull Player player, @NotNull String effectId);

    // ── What the player chose ──────────────────────────────────────────────

    /**
     * Where the effects this server plays come from.
     *
     * @return the mode
     */
    @NotNull
    EffectMode mode();

    /**
     * The effect a player chose, whatever they are holding.
     *
     * @param player the player
     * @return their chosen effect id, or empty when they chose none, the menu
     *         is off, or the player's row is still being read
     */
    @NotNull
    Optional<String> selected(@NotNull UUID player);

    /**
     * Chooses the effect that follows a player.
     *
     * <p>Setting rather than toggling: running it twice leaves the effect on.
     *
     * <p>Not gated by permission, because the server owner's own setting says
     * it should not be: an effect handed out by a crate or a reward is owned
     * whether or not a node was ever granted for it. Ask {@link #canUse} first
     * when you do want the permission to decide.
     *
     * @param player   the player
     * @param effectId the effect id
     * @return {@code true} when the choice was stored
     */
    boolean select(@NotNull Player player, @NotNull String effectId);

    /**
     * Takes a player's chosen effect off.
     *
     * @param player the player
     */
    void clear(@NotNull Player player);

    // ── Favourites ─────────────────────────────────────────────────────────

    /**
     * The effects a player starred, in the order they starred them.
     *
     * <p>Their own order rather than the catalogue's: a shortlist is something
     * somebody built, and re-sorting it takes that away.
     *
     * @param player the player
     * @return their starred effect ids, empty when they starred none or are
     *         still being read
     */
    @NotNull
    @Unmodifiable
    List<String> favourites(@NotNull UUID player);

    /**
     * Whether a player starred an effect.
     *
     * @param player   the player
     * @param effectId the effect id
     * @return {@code true} when it is starred
     */
    boolean isFavourite(@NotNull UUID player, @NotNull String effectId);

    /**
     * Stars an effect, or unstars it when it is already starred.
     *
     * <p>A toggle rather than a pair of methods because that is what the star
     * is: one control with two states, and a caller that has to read the state
     * first would race the player clicking it.
     *
     * @param player   the player
     * @param effectId the effect id
     * @return {@code true} when the id named an effect that exists
     */
    boolean toggleFavourite(@NotNull Player player, @NotNull String effectId);

    /**
     * Unstars everything.
     *
     * @param player the player
     */
    void clearFavourites(@NotNull Player player);

    // ── Particle visibility ────────────────────────────────────────────────

    /**
     * Who currently sees a player's arrow particles.
     *
     * @param player the player
     * @return their setting, {@link ParticleVisibility#ALL} when they never
     *         changed it or are still being read
     */
    @NotNull
    ParticleVisibility visibility(@NotNull UUID player);

    /**
     * Sets who sees a player's arrow particles.
     *
     * @param player     the player
     * @param visibility the setting to store
     */
    void setVisibility(@NotNull Player player, @NotNull ParticleVisibility visibility);

    // ── Effects on items ───────────────────────────────────────────────────

    /**
     * The effect bound to a bow, crossbow or trident.
     *
     * @param bow the item to read
     * @return the effect id it carries, or empty when it carries none
     */
    @NotNull
    Optional<String> boundTo(@NotNull ItemStack bow);

    /**
     * Binds an effect to a bow, in place.
     *
     * <p>The bow keeps everything else it had, so a plugin can enchant a reward
     * without rebuilding the item it was going to give. Refused when the item
     * is not something an effect can be bound to, or when the effect declares
     * itself not to fit it.
     *
     * @param bow      the item to change
     * @param effectId the effect id
     * @return {@code true} when the effect was bound
     */
    boolean bind(@NotNull ItemStack bow, @NotNull String effectId);

    /**
     * Takes the effect off a bow and gives nothing back.
     *
     * @param bow the item to change
     * @return {@code true} when an effect was there to remove
     */
    boolean unbind(@NotNull ItemStack bow);

    /**
     * An effect token, ready to be given to a player.
     *
     * <p>What a crate or a shop hands out: binding it to a bow is the player's
     * own business afterwards.
     *
     * @param effectId the effect id
     * @return the item, or empty when the catalogue declares no such effect
     */
    @NotNull
    Optional<ItemStack> effectItem(@NotNull String effectId);

    /**
     * Whether an item is one of this plugin's effect tokens.
     *
     * <p>Worth asking before a container, a shop or a trade treats it as
     * ordinary loot: a token is drawn as whatever suits what it carries and is
     * not the material it looks like.
     *
     * @param stack the item to test
     * @return {@code true} when it is an effect token
     */
    boolean isEffectItem(@NotNull ItemStack stack);

    // ── Playing an effect ──────────────────────────────────────────────────

    /**
     * Plays one moment of an effect at a place, as though a shot were there.
     *
     * <p>The path a real shot takes once it knows its effect: the region flag
     * that silences arrow effects is honoured, {@link ArrowEffectPlayEvent} is
     * fired and may cancel it, and every observer's own particle setting
     * decides whether they see it. Everything that picks the effect is
     * skipped — the bow, the menu choice, the projectile types, permission —
     * because the caller named one.
     *
     * <p>Exactly the moment asked for: a {@link ArrowTrigger#HIT_ENTITY} on an
     * effect that only declares {@link ArrowTrigger#HIT} draws nothing, and a
     * {@link ArrowTrigger#TRAIL} is one step of the line rather than a flight.
     * For an effect that follows something through the air, see
     * {@link #attach}.
     *
     * <p>Call it on the thread that owns {@code where}: the main thread on
     * Paper, its region thread on Folia.
     *
     * @param effectId the effect id
     * @param trigger  which moment of it to play
     * @param where    where it plays; copied, never held
     * @param shooter  who the effect belongs to, which is whose own it counts
     *                 as for a player who only sees their own particles
     * @param hit      what it landed on, for steps that need a body, or
     *                 {@code null} for none
     * @return {@code false} when no effect goes by that id, it draws nothing
     *         at that moment, the region silences it, or a listener cancelled
     *         it
     * @since 1.3.0
     */
    boolean play(@NotNull String effectId, @NotNull ArrowTrigger trigger, @NotNull Location where,
                 @NotNull Player shooter, @Nullable Entity hit);

    /**
     * Makes a projectile carry an effect, as though it had just been fired
     * with it.
     *
     * <p>For a shot the plugin never picked an effect for, or one that should
     * carry a different one: an ability's volley, a turret, a spell drawn as an
     * arrow. The launch plays where the projectile is now, the trail follows it,
     * and the impact plays where it lands. An effect it already carried is
     * replaced, trail and all. The server's list of projectile types and the
     * shooter's own choice are not asked, because the caller named both.
     *
     * <p>A projectile a player launches through the server's own methods
     * already carries their effect; this is for overriding it or for a
     * projectile nothing launched.
     *
     * <p>The effect belongs to the projectile's shooter, so there has to be a
     * player there. Call it on the thread that owns the projectile, straight
     * after spawning it.
     *
     * @param projectile the projectile, shot by a player
     * @param effectId   the effect id
     * @return {@code false} when no effect goes by that id, it draws nothing,
     *         or the projectile's shooter is not a player; in each case nothing
     *         was changed
     * @since 1.3.0
     */
    boolean attach(@NotNull Projectile projectile, @NotNull String effectId);

    /**
     * Shows a player an effect on the server's preview stage.
     *
     * <p>The preview the menu's button starts, showing the impact: the player
     * sees it on the stage, alone, and is put back afterwards. Owning the
     * effect is not required, which is what makes this a shop's "try before you
     * buy". On a server with no stage set the player is told so in the server's
     * own words, and this answers {@code false}.
     *
     * <p>Call it on the thread that owns the player.
     *
     * @param viewer   who sees it
     * @param effectId the effect id
     * @return {@code true} when the preview started
     * @since 1.3.0
     */
    boolean preview(@NotNull Player viewer, @NotNull String effectId);

    // ── Menus ──────────────────────────────────────────────────────────────

    /**
     * Opens the effect menu for a player, exactly as the command does.
     *
     * <p>For an NPC, a hub item, or a shop that hands over to the plugin's own
     * screen. The player's row is read first when it is not in memory yet, so
     * the menu can appear a moment after the call rather than on it. On a
     * server whose {@link #mode()} does not use the menu, the player is told it
     * is off instead.
     *
     * <p>Safe from any thread.
     *
     * @param player who to show it to
     * @since 1.3.0
     */
    void openMenu(@NotNull Player player);

    /**
     * Opens the crate for a player, exactly as the command and the crate
     * blocks do.
     *
     * <p>The screen that asks how many to open and spends the player's keys.
     * On a server with the crate turned off, the player is told so instead.
     *
     * <p>Safe from any thread.
     *
     * @param player who to show it to
     * @since 1.3.0
     */
    void openCrate(@NotNull Player player);

    // ── Owning, and crate keys ─────────────────────────────────────────────

    /**
     * Gives a player an effect for good, as winning it from the crate does.
     *
     * <p>Ownership without a permission node, so a vote reward or a season pass
     * can hand over an effect without a rank plugin. It counts towards
     * {@link #canUse(Player, String)} from then on. Nothing is selected and no
     * item is given: follow it with {@link #select} or {@link #effectItem} for
     * that.
     *
     * @param player   the player
     * @param effectId the effect id
     * @return {@code false} when no effect goes by that id, the crate already
     *         gave it to them, or their row is still being read; in each case
     *         nothing was changed
     * @since 1.3.0
     */
    boolean unlock(@NotNull Player player, @NotNull String effectId);

    /**
     * How many crate keys a player holds.
     *
     * @param player the player
     * @return their keys, {@code 0} when they have none or are still being read
     * @since 1.3.0
     */
    int keys(@NotNull UUID player);

    /**
     * Gives a player crate keys, or takes some away.
     *
     * <p>A key is what one spin of the crate costs, so this is how a store, a
     * vote or a quest pays one out. The count never goes below zero: taking
     * more than they hold leaves them with none rather than a debt. Writes to
     * the database, like every other change here.
     *
     * @param player the player; one who is still being read is left unchanged
     * @param keys   how many to add, or a negative number to take
     * @since 1.3.0
     */
    void addKeys(@NotNull Player player, int keys);
}
