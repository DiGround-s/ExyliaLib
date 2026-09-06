package net.exylia.lib.api.killeffect;

import net.exylia.lib.api.ExyliaAPI;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reading and driving ExyliaKillEffect.
 *
 * <pre>{@code
 * ExyliaAPI.get(KillEffectService.class).ifPresent(effects ->
 *     effects.chosenEffect(player.getUniqueId())
 *            .ifPresent(effect -> player.sendMessage("Kill effect: " + effect.name())));
 * }</pre>
 *
 * <p>Registered with Bukkit's {@link org.bukkit.plugin.ServicesManager} when
 * ExyliaKillEffect enables. Reach it through {@link ExyliaAPI#get(Class)}, and
 * treat an empty result as "kill effects are not part of this server" rather
 * than as a failure.
 *
 * <h2>Everything is an id</h2>
 * Effects and categories are named by the keys {@code effects.yml} declares.
 * Ids are normalised — lowercase, letters, digits and underscores — and matched
 * without regard to case, so the id a shop stored last month still resolves
 * after the owner retyped it in a different case.
 *
 * <h2>Nothing here checks permission</h2>
 * A plugin calling this has already decided the player may have the effect: a
 * crate opened, a rank bought, a reward claimed. Being overruled by a
 * permission node the buyer has not been given yet is not what it asked for.
 * {@link #mayUse(Player, String)} is there for callers that do want the check,
 * on their own terms.
 *
 * <h2>Twin plugin</h2>
 * {@code net.exylia.lib.api.hiteffect.HitEffectService} is the same contract
 * for effects played on a hit. The two are deliberately identical in shape, so
 * an integration written against one ports to the other by changing the types.
 *
 * @since 1.0.0
 */
public interface KillEffectService {

    // ── The catalogue ──────────────────────────────────────────────────────

    /**
     * Every effect the server declares, in file order.
     *
     * <p>A snapshot of the catalogue. Fine for building a shop page, wasteful
     * inside a loop that could ask for one id instead.
     *
     * @return all effects
     */
    @NotNull
    @Unmodifiable
    List<KillEffect> effects();

    /**
     * One effect by id.
     *
     * @param effectId the effect id, case insensitive
     * @return the effect, or empty when the file declares none by that id
     */
    @NotNull
    Optional<KillEffect> effect(@NotNull String effectId);

    /**
     * Whether an effect exists.
     *
     * <p>The cheap form of {@link #effect(String)} for callers that only want
     * to validate what a player typed or what a config named.
     *
     * @param effectId the effect id, case insensitive
     * @return {@code true} when the file declares it
     */
    boolean effectExists(@NotNull String effectId);

    /**
     * Every category, in the order the tabs are drawn.
     *
     * @return all categories
     */
    @NotNull
    @Unmodifiable
    List<KillEffectCategory> categories();

    /**
     * One category by id.
     *
     * @param categoryId the category id, case insensitive
     * @return the category, or empty when the file declares none by that id
     */
    @NotNull
    Optional<KillEffectCategory> category(@NotNull String categoryId);

    /**
     * The effects in a category, in the order they are drawn.
     *
     * @param categoryId the category id, case insensitive
     * @return its effects, empty when the category does not exist
     */
    @NotNull
    @Unmodifiable
    List<KillEffect> effectsIn(@NotNull String categoryId);

    /**
     * Whether a player is allowed to use an effect.
     *
     * <p>Granted by the effect's own permission, by every effect at once, or by
     * its whole category — the three shapes servers already sell. An unknown id
     * is not allowed, so a stale id in a menu reads as locked rather than free.
     *
     * @param player   the player
     * @param effectId the effect id, case insensitive
     * @return {@code true} when they may use it
     */
    boolean mayUse(@NotNull Player player, @NotNull String effectId);

    // ── What a player chose ────────────────────────────────────────────────

    /**
     * The effect a player picked in the menu.
     *
     * <p>Read from what is held in memory for a player who is online. Somebody
     * whose row has not arrived yet answers as though they picked nothing,
     * rather than blocking the caller on a database.
     *
     * @param player the player
     * @return their chosen effect, or empty when they chose none
     */
    @NotNull
    Optional<KillEffect> chosenEffect(@NotNull UUID player);

    /**
     * Sets the effect a player's kills play.
     *
     * @param player   the player
     * @param effectId the effect id, case insensitive
     * @return {@code false} when no effect goes by that id, in which case
     *         nothing was changed
     */
    boolean chooseEffect(@NotNull Player player, @NotNull String effectId);

    /**
     * Leaves a player with no chosen effect.
     *
     * @param player the player
     */
    void clearEffect(@NotNull Player player);

    /**
     * The effects a player starred, in the order they starred them.
     *
     * <p>Their own order rather than the catalogue's: a favourites list is a
     * shortlist somebody built, and re-sorting it takes that away.
     *
     * @param player the player
     * @return their favourites, empty when they have none or are not loaded
     */
    @NotNull
    @Unmodifiable
    List<KillEffect> favourites(@NotNull UUID player);

    /**
     * Whether a player starred an effect.
     *
     * @param player   the player
     * @param effectId the effect id, case insensitive
     * @return {@code true} when it is one of their favourites
     */
    boolean isFavourite(@NotNull UUID player, @NotNull String effectId);

    /**
     * Stars an effect for a player, or unstars it when it is already starred.
     *
     * @param player   the player
     * @param effectId the effect id, case insensitive
     * @return {@code false} when no effect goes by that id, in which case
     *         nothing was changed
     */
    boolean toggleFavourite(@NotNull Player player, @NotNull String effectId);

    /**
     * Empties a player's favourites.
     *
     * @param player the player
     */
    void clearFavourites(@NotNull Player player);

    // ── What a weapon carries ──────────────────────────────────────────────

    /**
     * Whether effects bound to weapons are read on this server.
     *
     * <p>The server picks where an effect comes from. Where it comes from the
     * menu only, binding writes a value nothing will ever play, so an
     * integration offering to bind should ask first.
     *
     * @return {@code true} when a weapon's own effect is played, and can be
     *         bound
     */
    boolean weaponBindingEnabled();

    /**
     * Whether the effect a player picked in the menu is read on this server.
     *
     * @return {@code true} when a player's own choice is played
     */
    boolean playerChoiceEnabled();

    /**
     * Whether an effect may be bound to an item of this kind.
     *
     * <p>An effect declares the weapons it fits, so an infernal sword effect is
     * not sold onto a bow. An effect that declares nothing fits every weapon.
     *
     * @param effectId the effect id, case insensitive
     * @param weapon   the material the effect would go on
     * @return {@code true} when the effect accepts it
     */
    boolean fitsWeapon(@NotNull String effectId, @NotNull Material weapon);

    /**
     * The effect a weapon carries.
     *
     * @param weapon the item to read
     * @return the effect bound to it, or empty when it carries none
     */
    @NotNull
    Optional<KillEffect> boundEffect(@NotNull ItemStack weapon);

    /**
     * Binds an effect to a weapon.
     *
     * <p>The item is changed in place, and only after every check has passed.
     * Give the caller's own copy, not one still sitting in an open inventory
     * view: what a player sees is redrawn when the stack is set back.
     *
     * @param weapon   the item to write on
     * @param effectId the effect id, case insensitive
     * @return what happened
     */
    @NotNull
    KillEffectBindResult bind(@NotNull ItemStack weapon, @NotNull String effectId);

    /**
     * Takes the effect off a weapon and gives nothing back.
     *
     * <p>For plugins that hand the token back themselves, or that meant to
     * destroy it. The item is changed in place.
     *
     * @param weapon the item to strip
     * @return what happened
     */
    @NotNull
    KillEffectBindResult unbind(@NotNull ItemStack weapon);

    /**
     * A token for an effect, ready to be given to a player.
     *
     * <p>The item a crate or a shop hands over: applying it to a weapon binds
     * the effect. Drawn as the viewer would see it, because the name and lore
     * carry placeholders the server may resolve per player.
     *
     * @param effectId the effect id, case insensitive
     * @param viewer   who the item is drawn for
     * @return the token, or empty when no effect goes by that id
     */
    @NotNull
    Optional<ItemStack> token(@NotNull String effectId, @NotNull Player viewer);

    /**
     * The remover item, ready to be given to a player.
     *
     * <p>Applying it to a weapon takes the effect off and hands the token back.
     *
     * @param viewer who the item is drawn for
     * @return the remover
     */
    @NotNull
    ItemStack remover(@NotNull Player viewer);
}
