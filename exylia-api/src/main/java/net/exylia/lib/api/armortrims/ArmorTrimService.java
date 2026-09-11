package net.exylia.lib.api.armortrims;

import net.exylia.lib.api.ExyliaAPI;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reading and driving ExyliaArmorTrims.
 *
 * <pre>{@code
 * ExyliaAPI.get(ArmorTrimService.class).ifPresent(trims -> {
 *     trims.select(player, ArmorPiece.CHESTPLATE, "sentry/netherite");
 *     trims.trimItem("ruby").ifPresent(item -> player.getInventory().addItem(item));
 * });
 * }</pre>
 *
 * <p>Registered with Bukkit's {@link org.bukkit.plugin.ServicesManager} when
 * ExyliaArmorTrims enables. Reach it through {@link ExyliaAPI#get(Class)}, and
 * treat an empty result as "armor trims are not part of this server" rather
 * than as a failure.
 *
 * <h2>Two halves, and only one of them is on</h2>
 * A trim either lives on the armor as an item does, or belongs to the player
 * and lands on whatever they equip. {@link #mode()} says which, and the half
 * the server does not use answers {@code false} or empty rather than throwing:
 * an integration written for both runs on either.
 *
 * <h2>Ids, and what happens to unknown ones</h2>
 * Everything is addressed by trim id, matched without regard to case. That is
 * either a preset the server owner named, or the {@code pattern/material} form
 * of a combination — the slash tells them apart, and no registry key contains
 * one. An id that names neither is not an error: a reload can remove a preset
 * while a crate still hands it out, so lookups answer empty and writes answer
 * {@code false}.
 *
 * <h2>Queries are cheap, actions are not</h2>
 * Everything that returns a value reads from the plugin's cache and is safe to
 * call from a menu redraw or a placeholder. Everything that changes a player's
 * selection writes to the database and redraws them for everybody watching, so
 * call those on the main thread and no more often than a player could.
 *
 * @since 1.0.0
 */
public interface ArmorTrimService {

    // ── The catalogue ──────────────────────────────────────────────────────

    /**
     * Every preset the server declares, in the order a menu draws them.
     *
     * <p>Presets only. Combinations are built from {@link #patterns()} and
     * {@link #materials()}, and there are too many to list.
     *
     * @return all presets
     */
    @NotNull
    @Unmodifiable
    List<ArmorTrim> trims();

    /**
     * A trim by id.
     *
     * @param trimId a preset id, or a {@code pattern/material} combination
     * @return the trim, or empty when the id names neither
     */
    @NotNull
    Optional<ArmorTrim> trim(@NotNull String trimId);

    /**
     * Whether the catalogue declares a preset.
     *
     * <p>Presets only: a combination is valid whenever both of its halves are,
     * so ask {@link #trim(String)} instead for one of those.
     *
     * @param trimId the preset id, case insensitive
     * @return {@code true} when it exists
     */
    boolean exists(@NotNull String trimId);

    /**
     * The patterns a player can combine by hand.
     *
     * @return every pattern the server offers, in menu order
     */
    @NotNull
    @Unmodifiable
    List<TrimOption> patterns();

    /**
     * The materials a player can combine by hand.
     *
     * @return every material the server offers, in menu order
     */
    @NotNull
    @Unmodifiable
    List<TrimOption> materials();

    // ── Permissions ────────────────────────────────────────────────────────

    /**
     * Whether a player may wear a trim anywhere at all.
     *
     * <p>Answers by the server's own rule, which includes the setting that
     * turns permissions off entirely — so a shop asking this gets the same
     * answer the menu would give, not a raw permission check.
     *
     * @param player the player
     * @param trimId the trim id
     * @return {@code true} when they may wear it on at least one piece
     */
    boolean canUse(@NotNull Player player, @NotNull String trimId);

    /**
     * Whether a player may wear a trim on one piece.
     *
     * <p>Separate from {@link #canUse(Player, String)} because a rank can be
     * sold one piece at a time: a player given the helmet node wears the trim
     * on their head and nowhere else.
     *
     * @param player the player
     * @param trimId the trim id
     * @param piece  the armor slot
     * @return {@code true} when they may wear it there
     */
    boolean canUse(@NotNull Player player, @NotNull String trimId, @NotNull ArmorPiece piece);

    // ── What the player chose ──────────────────────────────────────────────

    /**
     * Where the trims this server draws come from.
     *
     * @return the mode
     */
    @NotNull
    TrimMode mode();

    /**
     * The trim a player chose for one slot, whatever they are wearing there.
     *
     * <p>A choice, not a result: it stays set while the slot is empty, and the
     * trim appears again the moment they equip armor that fits.
     *
     * @param player the player
     * @param piece  the armor slot
     * @return the chosen trim id, or empty when they chose none, the menu is
     *         off, or the player's selection is still being read
     */
    @NotNull
    Optional<String> selected(@NotNull UUID player, @NotNull ArmorPiece piece);

    /**
     * Chooses a trim for one slot.
     *
     * <p>Setting rather than toggling: running it twice leaves the trim on.
     *
     * <p>Not gated by permission, because the server owner's own setting says
     * it should not be: a trim handed out by a crate or a reward is owned
     * whether or not a node was ever granted for it. Ask
     * {@link #canUse(Player, String, ArmorPiece)} first when you do want the
     * permission to decide.
     *
     * @param player the wearer
     * @param piece  the armor slot
     * @param trimId the trim id
     * @return {@code true} when the choice was stored
     */
    boolean select(@NotNull Player player, @NotNull ArmorPiece piece, @NotNull String trimId);

    /**
     * Takes the chosen trim off one slot.
     *
     * @param player the wearer
     * @param piece  the armor slot
     * @return {@code true} when something was there to remove
     */
    boolean clear(@NotNull Player player, @NotNull ArmorPiece piece);

    /**
     * Takes every chosen trim off.
     *
     * @param player the wearer
     * @return {@code true} when something was there to remove
     */
    boolean clearAll(@NotNull Player player);

    // ── Trims on items ─────────────────────────────────────────────────────

    /**
     * The trim written onto a piece of armor.
     *
     * @param armor the item to read
     * @return the trim id it carries, or empty when it carries none
     */
    @NotNull
    Optional<String> trimOf(@NotNull ItemStack armor);

    /**
     * Writes a trim onto a piece of armor, in place.
     *
     * <p>The armor keeps everything else it had, so a plugin can trim a reward
     * without rebuilding the item it was going to give.
     *
     * @param armor  the item to change
     * @param trimId the trim id
     * @return {@code true} when the trim was written
     */
    boolean apply(@NotNull ItemStack armor, @NotNull String trimId);

    /**
     * Takes the trim off a piece of armor and gives nothing back.
     *
     * @param armor the item to change
     * @return {@code true} when a trim was there to remove
     */
    boolean strip(@NotNull ItemStack armor);

    /**
     * A trim item, ready to be given to a player.
     *
     * <p>What a crate or a shop hands out: applying it to armor is the player's
     * own business afterwards.
     *
     * @param trimId the trim id
     * @return the item, or empty when the id names no trim
     */
    @NotNull
    Optional<ItemStack> trimItem(@NotNull String trimId);

    /**
     * Whether an item is one of this plugin's trim items.
     *
     * <p>Worth asking before a container, a shop or a trade treats it as
     * ordinary loot: a trim item is drawn as whatever suits it and is not the
     * material it looks like.
     *
     * @param stack the item to test
     * @return {@code true} when it is a trim item
     */
    boolean isTrimItem(@NotNull ItemStack stack);

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
     * The trim a player is shown wearing on one slot right now.
     *
     * <p>The answer after every rule has had its say — the mode, the armor
     * actually in the slot, the wearer's permission, and whether their cosmetics
     * are shown at all — which neither {@link #selected(UUID, ArmorPiece)} nor
     * {@link #trimOf(ItemStack)} gives on its own. What a placeholder or a
     * scoreboard describing somebody's look should read.
     *
     * <p>Read from the state outgoing packets are rewritten with, so it is safe
     * from any thread. It follows the plugin's last redraw of the player.
     *
     * @param player the wearer
     * @param piece  the armor slot
     * @return the trim id drawn there — a preset id or a
     *         {@code pattern/material} combination — or empty when the slot
     *         shows the armor as it really is or the player is not online
     * @since 1.3.0
     */
    @NotNull
    Optional<String> shown(@NotNull UUID player, @NotNull ArmorPiece piece);

    // ── Handing things out ─────────────────────────────────────────────────

    /**
     * Gives a player trim items.
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
     * @param trimId a preset id, or a {@code pattern/material} combination
     * @param amount how many
     * @return {@code true} when the items were given or queued; {@code false}
     *         when the id names no trim or the amount is below one
     * @since 1.3.0
     */
    boolean giveTrimItem(@NotNull Player player, @NotNull String trimId, int amount);

    /**
     * The remover item, ready to be given to a player.
     *
     * <p>Dropping it onto trimmed armor takes the trim off and hands the trim
     * item back. It only does that on a server whose {@link #mode()} uses items;
     * it is still built on one that does not.
     *
     * @return a new remover
     * @since 1.3.0
     */
    @NotNull
    ItemStack removerItem();

    // ── The trim screen ────────────────────────────────────────────────────

    /**
     * Opens the trim menu for a player, as {@code /trims} does.
     *
     * <p>For an NPC, a sign or a lobby item that should lead somewhere. No
     * permission is checked: the caller decided this player may be here. A
     * selection still being read is read first, and the screen opens a moment
     * later when it arrives.
     *
     * <p>Call it on the thread that owns the player.
     *
     * @param player who to show it to
     * @return {@code true} when the menu opens or is about to; {@code false}
     *         when the server's {@link #mode()} has no menu
     * @since 1.3.0
     */
    boolean openMenu(@NotNull Player player);
}
