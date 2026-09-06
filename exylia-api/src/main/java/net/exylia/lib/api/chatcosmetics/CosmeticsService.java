package net.exylia.lib.api.chatcosmetics;

import net.exylia.lib.api.ExyliaAPI;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Reading and driving the chat cosmetics of ExyliaChatCosmetics.
 *
 * <pre>{@code
 * ExyliaAPI.get(CosmeticsService.class).ifPresent(cosmetics ->
 *     CosmeticKey.parse("tag:mvp").ifPresent(key ->
 *         cosmetics.grantFor(player.getUniqueId(), key, EntitlementSource.PURCHASE,
 *                 Duration.ofDays(30), "order-1234", "store")));
 * }</pre>
 *
 * <p>Registered with Bukkit's {@link org.bukkit.plugin.ServicesManager} when
 * ExyliaChatCosmetics enables. Reach it through {@link ExyliaAPI#get(Class)},
 * and treat an empty result as "this server has no chat cosmetics" rather than
 * as a failure.
 *
 * <h2>What answers now and what answers later</h2>
 * Everything about an online player answers from memory and is safe to call
 * from a chat thread or a placeholder. Everything that returns a
 * {@link CompletableFuture} touches the database: granting, revoking, and
 * asking about somebody who is offline. A grant for an offline player is simply
 * written and waits for their next join.
 *
 * <p>The methods taking a {@link Player} change what that player is wearing and
 * must be called on the thread that owns them. The methods taking a
 * {@link UUID} only read.
 *
 * @since 1.0.0
 */
public interface CosmeticsService {

    // ── Catalogue ──────────────────────────────────────────────────────────

    /**
     * One cosmetic from the catalogue.
     *
     * <p>Only what the files declare. A colour or a tag a player made for
     * themselves belongs to that player and is not found here.
     *
     * @param key what names it
     * @return the cosmetic, or empty when nothing goes by that key
     */
    @NotNull
    Optional<Cosmetic> cosmetic(@NotNull CosmeticKey key);

    /**
     * Every catalogue entry of one type, in the order a menu lists them.
     *
     * @param type the cosmetic type, for example {@code tag}
     * @return its entries, empty when no such type is registered
     */
    @NotNull
    @Unmodifiable
    List<Cosmetic> cosmetics(@NotNull String type);

    /**
     * Every cosmetic type this server has.
     *
     * <p>Types are registered at startup and the list does not change
     * afterwards, so it is the right thing to validate a config value against.
     *
     * @return the type ids
     */
    @NotNull
    @Unmodifiable
    List<String> types();

    // ── Ownership ──────────────────────────────────────────────────────────

    /**
     * Whether an online player has a cosmetic, and why.
     *
     * <p>The permission is asked live and the grants come from memory, so this
     * is the accurate answer and the cheap one.
     *
     * @param player the player
     * @param key    the cosmetic
     * @return the verdict, {@link Ownership#NONE} when nothing goes by that key
     */
    @NotNull
    Ownership ownership(@NotNull Player player, @NotNull CosmeticKey key);

    /**
     * Whether anybody has a cosmetic, online or not.
     *
     * <p>Permissions are not consulted for somebody offline — no permission
     * plugin can answer for an absent player without loading them — so an
     * offline verdict covers stored grants only.
     *
     * @param player the player
     * @param key    the cosmetic
     * @return the verdict once it has been read
     */
    @NotNull
    CompletableFuture<Ownership> ownership(@NotNull UUID player, @NotNull CosmeticKey key);

    /**
     * The short form of {@link #ownership(Player, CosmeticKey)}.
     *
     * @param player the player
     * @param key    the cosmetic
     * @return {@code true} when they have it right now
     */
    boolean owns(@NotNull Player player, @NotNull CosmeticKey key);

    /**
     * Every grant a player holds, active or not.
     *
     * <p>From memory when they are online, from the table otherwise. Expired
     * and revoked rows are included: ask {@link Entitlement#active(long)} for
     * the ones that still count.
     *
     * @param player the player
     * @return their grants
     */
    @NotNull
    CompletableFuture<List<Entitlement>> entitlements(@NotNull UUID player);

    /**
     * Gives a cosmetic for good.
     *
     * @param player    who gets it
     * @param key       what they get
     * @param source    where it came from
     * @param sourceRef what the source calls it: an order id, an event name
     * @param grantedBy who did it: a player uuid or your plugin's name
     * @return the grant that was written
     */
    @NotNull
    CompletableFuture<Entitlement> grant(@NotNull UUID player, @NotNull CosmeticKey key,
                                         @NotNull EntitlementSource source, @NotNull String sourceRef,
                                         @NotNull String grantedBy);

    /**
     * Gives a cosmetic for a while.
     *
     * <p>The clock starts now and runs whether the player is online or not,
     * which is what a subscription or a rental wants. Granting the same
     * cosmetic twice leaves two grants rather than extending one, so revoking
     * a purchase cannot take a reward away with it.
     *
     * @param player    who gets it
     * @param key       what they get
     * @param source    where it came from
     * @param duration  how long they keep it
     * @param sourceRef what the source calls it: an order id, an event name
     * @param grantedBy who did it: a player uuid or your plugin's name
     * @return the grant that was written
     */
    @NotNull
    CompletableFuture<Entitlement> grantFor(@NotNull UUID player, @NotNull CosmeticKey key,
                                            @NotNull EntitlementSource source, @NotNull Duration duration,
                                            @NotNull String sourceRef, @NotNull String grantedBy);

    /**
     * Takes one grant away by its id.
     *
     * @param entitlementId the grant id
     * @return the grant as it now reads, or empty when there was no such active
     *         grant
     */
    @NotNull
    CompletableFuture<Optional<Entitlement>> revoke(long entitlementId);

    /**
     * Takes every active grant of one cosmetic away.
     *
     * <p>Leaves a permission node alone: this plugin did not give that and
     * cannot take it back.
     *
     * @param player whose grants
     * @param key    which cosmetic
     * @return the grants that were revoked
     */
    @NotNull
    CompletableFuture<List<Entitlement>> revokeAll(@NotNull UUID player, @NotNull CosmeticKey key);

    /**
     * Takes every active grant of one cosmetic from one source away.
     *
     * <p>What a store plugin wants when a payment is reversed: the purchase
     * goes, the reward the player also earned stays.
     *
     * @param player whose grants
     * @param key    which cosmetic
     * @param source which source to take from
     * @return the grants that were revoked
     */
    @NotNull
    CompletableFuture<List<Entitlement>> revokeAll(@NotNull UUID player, @NotNull CosmeticKey key,
                                                   @NotNull EntitlementSource source);

    // ── Wearing ────────────────────────────────────────────────────────────

    /**
     * Puts a cosmetic on.
     *
     * <p>For a type worn one at a time this replaces what was on; for a type
     * worn as a set it toggles, which is why {@link EquipResult#UNEQUIPPED} is
     * a normal answer to an equip.
     *
     * @param player the player, on their own thread
     * @param key    what to wear
     * @return what happened
     */
    @NotNull
    EquipResult equip(@NotNull Player player, @NotNull CosmeticKey key);

    /**
     * Takes off everything of one type.
     *
     * @param player the player, on their own thread
     * @param type   the cosmetic type to clear
     * @return {@code true} when they were wearing something of that type
     */
    boolean unequip(@NotNull Player player, @NotNull String type);

    /**
     * What a player is wearing of one type.
     *
     * @param player the player
     * @param type   the cosmetic type
     * @return the keys they wear, empty when they wear none or are not loaded
     */
    @NotNull
    @Unmodifiable
    List<CosmeticKey> equipped(@NotNull UUID player, @NotNull String type);

    /**
     * Whether a player is wearing one particular cosmetic.
     *
     * @param player the player
     * @param key    the cosmetic
     * @return {@code true} when it is on
     */
    boolean isEquipped(@NotNull UUID player, @NotNull CosmeticKey key);

    /**
     * The catalogue entries a player starred.
     *
     * @param player the player
     * @return their favourites, empty when they have none or are not loaded
     */
    @NotNull
    @Unmodifiable
    List<Cosmetic> favorites(@NotNull UUID player);

    // ── Loadouts ───────────────────────────────────────────────────────────

    /**
     * The loadouts a player saved.
     *
     * @param player the player
     * @return their loadouts, empty when they have none or are not loaded
     */
    @NotNull
    @Unmodifiable
    List<Loadout> loadouts(@NotNull UUID player);

    /**
     * Puts a saved loadout on.
     *
     * <p>Everything comes off first, then each cosmetic the player still owns
     * goes back on; anything they have since lost is skipped rather than
     * failing the whole thing. Listen for
     * {@link net.exylia.lib.api.chatcosmetics.event.LoadoutAppliedEvent} to
     * learn how many were skipped.
     *
     * @param player the player, on their own thread
     * @param id     the loadout id
     * @return the loadout that was applied, or empty when the player holds none
     *         of that id
     */
    @NotNull
    Optional<Loadout> applyLoadout(@NotNull Player player, long id);

    // ── Rendering ──────────────────────────────────────────────────────────
    //
    // What the plugin would have drawn, for a scoreboard, a hologram, a tab
    // list or a chat plugin that lays out its own line. Memory only, and safe
    // from a chat thread.

    /**
     * A player's equipped tag inside its format.
     *
     * @param player the player
     * @return the tag, empty when they wear none
     */
    @NotNull
    Component tag(@NotNull Player player);

    /**
     * A player's name in their nick or rank colour.
     *
     * @param player the player
     * @return their name, styled
     */
    @NotNull
    Component nick(@NotNull Player player);

    /**
     * Text in a player's font, decorations and colour.
     *
     * <p>Takes the text rather than a component because the cosmetics are the
     * styling: whatever you pass is restyled whole.
     *
     * @param player  whose cosmetics to use
     * @param message the text
     * @return the styled message
     */
    @NotNull
    Component styleMessage(@NotNull Player player, @NotNull String message);
}
