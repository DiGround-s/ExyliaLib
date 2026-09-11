package net.exylia.lib.api.armorskin;

import net.exylia.lib.api.ExyliaAPI;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reading and driving ExyliaArmorSkin.
 *
 * <pre>{@code
 * ExyliaAPI.get(ArmorSkinService.class).ifPresent(skins -> {
 *     skins.select(player, ArmorPiece.HELMET, "sakura");
 *     skins.skinItem("sakura").ifPresent(item -> player.getInventory().addItem(item));
 * });
 * }</pre>
 *
 * <p>Registered with Bukkit's {@link org.bukkit.plugin.ServicesManager} when
 * ExyliaArmorSkin enables. Reach it through {@link ExyliaAPI#get(Class)}, and
 * treat an empty result as "armor skins are not part of this server" rather
 * than as a failure.
 *
 * <h2>Two halves, and only one of them is on</h2>
 * A skin either lives on the armor as an item does, or belongs to the player
 * and lands on whatever they equip. {@link #mode()} says which, and the half
 * the server does not use answers {@code false} or empty rather than throwing:
 * an integration written for both runs on either.
 *
 * <h2>Ids, and what happens to unknown ones</h2>
 * Everything is addressed by skin id, matched without regard to case. An id the
 * catalogue does not declare is not an error — a reload can remove one while a
 * crate still hands it out — so lookups answer empty and writes answer
 * {@code false}.
 *
 * <h2>Queries are cheap, actions are not</h2>
 * Everything that returns a value reads from the plugin's cache and is safe to
 * call from a menu redraw or a placeholder. Everything that changes a player's
 * wardrobe writes to the database and redraws them for everybody watching, so
 * call those on the main thread and no more often than a player could.
 *
 * @since 1.0.0
 */
public interface ArmorSkinService {

    // ── The catalogue ──────────────────────────────────────────────────────

    /**
     * Every skin the server declares, in the order a menu draws them.
     *
     * <p>A snapshot of the catalogue. Fine for building a shop, wasteful in a
     * loop.
     *
     * @return all skins
     */
    @NotNull
    @Unmodifiable
    List<ArmorSkin> skins();

    /**
     * A skin by id.
     *
     * @param skinId the skin id, case insensitive
     * @return the skin, or empty when the catalogue declares no such id
     */
    @NotNull
    Optional<ArmorSkin> skin(@NotNull String skinId);

    /**
     * Whether the catalogue declares a skin.
     *
     * @param skinId the skin id, case insensitive
     * @return {@code true} when it exists
     */
    boolean exists(@NotNull String skinId);

    // ── Permissions ────────────────────────────────────────────────────────

    /**
     * Whether a player may wear a skin anywhere at all.
     *
     * <p>Answers by the server's own rule, which includes the setting that
     * turns permissions off entirely — so a shop asking this gets the same
     * answer the wardrobe would give, not a raw permission check.
     *
     * @param player the player
     * @param skinId the skin id
     * @return {@code true} when they may wear it on at least one piece
     */
    boolean canUse(@NotNull Player player, @NotNull String skinId);

    /**
     * Whether a player may wear a skin on one piece.
     *
     * <p>Separate from {@link #canUse(Player, String)} because a rank can be
     * sold one piece at a time: a player given the helmet node wears the skin
     * on their head and nowhere else.
     *
     * @param player the player
     * @param skinId the skin id
     * @param piece  the armor slot
     * @return {@code true} when they may wear it there
     */
    boolean canUse(@NotNull Player player, @NotNull String skinId, @NotNull ArmorPiece piece);

    // ── The wardrobe ───────────────────────────────────────────────────────

    /**
     * Where the skins this server draws come from.
     *
     * @return the mode
     */
    @NotNull
    SkinMode mode();

    /**
     * The skin a player chose for one slot, whatever they are wearing there.
     *
     * <p>A choice, not a result: it stays set while the slot is empty, and the
     * skin appears again the moment they equip armor that fits.
     *
     * @param player the player
     * @param piece  the armor slot
     * @return the chosen skin id, or empty when they chose none, the wardrobe
     *         is off, or the player's wardrobe is still being read
     */
    @NotNull
    Optional<String> selected(@NotNull UUID player, @NotNull ArmorPiece piece);

    /**
     * Chooses a skin for one slot.
     *
     * <p>Setting rather than toggling: running it twice leaves the skin on.
     *
     * <p>Gated by permission, unlike its counterpart in ExyliaArmorTrims: the
     * wardrobe offers one write and that write enforces the server's rule, so
     * a player who may not wear the skin is refused. Ask
     * {@link #canUse(Player, String, ArmorPiece)} first if you want to know why
     * before asking.
     *
     * @param player the wearer
     * @param piece  the armor slot
     * @param skinId the skin id
     * @return {@code true} when the choice was stored
     */
    boolean select(@NotNull Player player, @NotNull ArmorPiece piece, @NotNull String skinId);

    /**
     * Takes the chosen skin off one slot.
     *
     * @param player the wearer
     * @param piece  the armor slot
     * @return {@code true} when something was there to remove
     */
    boolean clear(@NotNull Player player, @NotNull ArmorPiece piece);

    /**
     * Takes every chosen skin off.
     *
     * @param player the wearer
     * @return {@code true} when something was there to remove
     */
    boolean clearAll(@NotNull Player player);

    // ── Skins on items ─────────────────────────────────────────────────────

    /**
     * The skin written onto a piece of armor.
     *
     * @param armor the item to read
     * @return the skin id it carries, or empty when it carries none
     */
    @NotNull
    Optional<String> skinOf(@NotNull ItemStack armor);

    /**
     * Writes a skin onto a piece of armor, in place.
     *
     * <p>The armor keeps everything else it had, so a plugin can skin a reward
     * without rebuilding the item it was going to give.
     *
     * @param armor  the item to change
     * @param skinId the skin id
     * @return {@code true} when the skin was written
     */
    boolean apply(@NotNull ItemStack armor, @NotNull String skinId);

    /**
     * Takes the skin off a piece of armor and gives nothing back.
     *
     * @param armor the item to change
     * @return {@code true} when a skin was there to remove
     */
    boolean strip(@NotNull ItemStack armor);

    /**
     * A skin item, ready to be given to a player.
     *
     * <p>What a crate or a shop hands out: applying it to armor is the player's
     * own business afterwards.
     *
     * @param skinId the skin id
     * @return the item, or empty when the catalogue declares no such skin
     */
    @NotNull
    Optional<ItemStack> skinItem(@NotNull String skinId);

    /**
     * Whether an item is one of this plugin's skin items.
     *
     * <p>Worth asking before a container, a shop or a trade treats it as
     * ordinary loot: a skin item is drawn as whatever suits it and is not the
     * material it looks like.
     *
     * @param stack the item to test
     * @return {@code true} when it is a skin item
     */
    boolean isSkinItem(@NotNull ItemStack stack);

    // ── Redrawing ──────────────────────────────────────────────────────────

    /**
     * Recomputes what a player looks like and re-sends it to everyone who can
     * see them.
     *
     * <p>Only needed after something this service does not know about changed —
     * a permission granted at runtime, an item swapped by another plugin. Every
     * write here already redraws.
     *
     * @param player the player to redraw
     */
    void refresh(@NotNull Player player);

    /**
     * The skin a player is shown wearing on one slot right now.
     *
     * <p>The answer after every rule has had its say — the mode, the armor
     * actually in the slot, the wearer's permission, and whether their cosmetics
     * are shown at all — which neither {@link #selected(UUID, ArmorPiece)} nor
     * {@link #skinOf(ItemStack)} gives on its own. What a placeholder or a
     * scoreboard describing somebody's look should read.
     *
     * <p>Read from the state outgoing packets are rewritten with, so it is safe
     * from any thread. It follows the plugin's last redraw of the player.
     *
     * @param player the wearer
     * @param piece  the armor slot
     * @return the skin id drawn there, or empty when the slot shows the armor as
     *         it really is or the player is not online
     * @since 1.3.0
     */
    @NotNull
    Optional<String> shown(@NotNull UUID player, @NotNull ArmorPiece piece);

    // ── Handing things out ─────────────────────────────────────────────────

    /**
     * Gives a player skin items.
     *
     * <p>Through the plugin's reward queue rather than straight into the
     * inventory: what does not fit is kept and handed over on their next join
     * instead of dropped at their feet, so a crate or a shop never loses a
     * purchase to a full inventory. The player is told nothing; a plugin handing
     * something out usually has its own message to send.
     *
     * <p>Call it on the thread that owns the player.
     *
     * @param player who gets them
     * @param skinId the skin id
     * @param amount how many
     * @return {@code true} when the items were given or queued; {@code false}
     *         when the catalogue declares no such skin or the amount is below one
     * @since 1.3.0
     */
    boolean giveSkinItem(@NotNull Player player, @NotNull String skinId, int amount);

    /**
     * The remover item, ready to be given to a player.
     *
     * <p>Dropping it onto skinned armor takes the skin off and hands the skin
     * item back. It only does that on a server whose {@link #mode()} uses items;
     * it is still built on one that does not.
     *
     * @return a new remover
     * @since 1.3.0
     */
    @NotNull
    ItemStack removerItem();

    // ── The wardrobe screen ────────────────────────────────────────────────

    /**
     * Opens the wardrobe for a player, as {@code /wardrobe} does.
     *
     * <p>For an NPC, a sign or a lobby item that should lead somewhere. No
     * permission is checked: the caller decided this player may be here. A
     * wardrobe still being read is read first, and the screen opens a moment
     * later when it arrives.
     *
     * <p>Call it on the thread that owns the player.
     *
     * @param player who to show it to
     * @return {@code true} when the wardrobe opens or is about to; {@code false}
     *         when the server's {@link #mode()} has no wardrobe
     * @since 1.3.0
     */
    boolean openWardrobe(@NotNull Player player);
}
