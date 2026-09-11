package net.exylia.lib.api.shields;

import net.exylia.lib.api.ExyliaAPI;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Reading and driving ExyliaShields.
 *
 * <pre>{@code
 * ExyliaAPI.get(ShieldsService.class).ifPresent(shields ->
 *     shields.activeDesign(player.getUniqueId())
 *            .ifPresent(design -> player.sendMessage("Layers: " + design.layers().size())));
 * }</pre>
 *
 * <p>Registered with Bukkit's {@link org.bukkit.plugin.ServicesManager} when
 * ExyliaShields enables. Reach it through {@link ExyliaAPI#get(Class)}, and
 * treat an empty result as "shield designs are not part of this server" rather
 * than as a failure.
 *
 * <h2>Slots belong to online players</h2>
 * A player's slots are held in memory while they are online and written back
 * when they leave. Everything here that takes a {@link UUID} reads that memory:
 * a player who is offline, or whose row has not arrived yet, answers as though
 * they own nothing rather than blocking the caller on a database.
 *
 * <p>Slots are numbered from zero. The menus show them one higher, because
 * players count from one and this contract does not.
 *
 * <h2>The library is not</h2>
 * The shared library is a table, not a cache, so the two methods that read it
 * hand back a {@link CompletableFuture}. It completes off the main thread:
 * anything touching the world from there has to be scheduled back.
 *
 * @since 1.0.0
 */
public interface ShieldsService {

    // ── A player's slots ───────────────────────────────────────────────────

    /**
     * The design a player is wearing.
     *
     * @param player the player
     * @return the design in their active slot, or empty when that slot is
     *         empty or they are not loaded
     */
    @NotNull
    Optional<ShieldDesign> activeDesign(@NotNull UUID player);

    /**
     * The slot a player is wearing.
     *
     * <p>Zero even for a player who is not loaded, because zero is also the
     * slot everybody starts on: ask {@link #activeDesign(UUID)} to tell the two
     * apart.
     *
     * @param player the player
     * @return the slot number, counted from zero
     */
    int activeSlot(@NotNull UUID player);

    /**
     * The design in one of a player's slots.
     *
     * @param player the player
     * @param slot   the slot, counted from zero
     * @return the design, or empty when the slot is empty, out of range, or the
     *         player is not loaded
     */
    @NotNull
    Optional<ShieldDesign> designInSlot(@NotNull UUID player, int slot);

    /**
     * How many slots a player has designs in.
     *
     * <p>The size of their stored list, which includes the gaps a deleted
     * design leaves behind. It is what a browser counts, not what
     * {@link #maxSlots(Player)} allows.
     *
     * @param player the player
     * @return the slot count, {@code 0} when they are not loaded
     */
    int slotCount(@NotNull UUID player);

    /**
     * How many slots a player's permissions allow.
     *
     * <p>Counted down from the highest {@code exyliashields.max_slots.<n>} they
     * hold, so a rank grants more slots without having to revoke the lower
     * ones. A player holding none may use no slots at all.
     *
     * @param player the player
     * @return the number of slots they may use
     */
    int maxSlots(@NotNull Player player);

    // ── What the server offers ─────────────────────────────────────────────

    /**
     * The pattern ids this server lets players draw with, in menu order.
     *
     * <p>A curated list rather than every vanilla pattern: the owner chooses
     * what is on offer, and a pattern not on it is one nobody can add.
     *
     * @return the available pattern ids
     */
    @NotNull
    @Unmodifiable
    List<String> availablePatterns();

    /**
     * The dye colour ids this server lets players draw with, in menu order.
     *
     * @return the available colour ids
     */
    @NotNull
    @Unmodifiable
    List<String> availableColors();

    /**
     * How many layers one design may hold.
     *
     * @return the layer limit
     */
    int maxLayers();

    /**
     * Whether a player is allowed to draw with a pattern.
     *
     * <p>Permissions are checked again whenever a player joins, and layers they
     * have lost the right to are dropped from what they built. A design read
     * from a slot has therefore already been filtered; this is for callers
     * building one.
     *
     * @param player    the player
     * @param patternId the pattern id, case insensitive
     * @return {@code true} when they may use it
     */
    boolean mayUsePattern(@NotNull Player player, @NotNull String patternId);

    /**
     * Whether a player is allowed to draw with a colour.
     *
     * @param player  the player
     * @param colorId the colour id, case insensitive
     * @return {@code true} when they may use it
     */
    boolean mayUseColor(@NotNull Player player, @NotNull String colorId);

    // ── Drawing on shields ─────────────────────────────────────────────────

    /**
     * Puts a player on one of their slots and redraws the shield they hold.
     *
     * <p>The same thing clicking the slot in the menu does, message included:
     * the player is told which slot they are now wearing, because that is the
     * only feedback the menu leaves behind.
     *
     * @param player the player
     * @param slot   the slot, counted from zero
     */
    void selectSlot(@NotNull Player player, int slot);

    /**
     * Draws whatever a player is wearing onto the shield in their hands.
     *
     * <p>What to call after handing a player a shield: an item that arrived
     * from somewhere else carries no design until something draws one on it.
     * The off hand is preferred, then the main hand; a player holding no shield
     * is left alone.
     *
     * @param player the player
     */
    void applyActiveDesign(@NotNull Player player);

    /**
     * Draws a design onto a shield item.
     *
     * <p>The item is changed in place, and anything that is not a shield is
     * left alone. Layers naming a pattern or colour this server does not have
     * are skipped rather than refused: one unknown line is not a reason to draw
     * a blank shield.
     *
     * @param shield the shield item
     * @param design the design to draw
     */
    void applyDesign(@NotNull ItemStack shield, @NotNull ShieldDesign design);

    /**
     * Takes every pattern off a shield item, leaving it plain.
     *
     * @param shield the shield item
     */
    void stripDesign(@NotNull ItemStack shield);

    // ── The shared library ─────────────────────────────────────────────────

    /**
     * Copies a published design into a player's first free slot.
     *
     * <p>The copy stays attached to the row it came from until the player edits
     * it, which is what makes the use count mean anything. A player with no
     * free slot is told so and nothing is copied.
     *
     * @param player    the player receiving the copy
     * @param libraryId the library id of the design to take
     */
    void importDesign(@NotNull Player player, long libraryId);

    /**
     * One design from the shared library.
     *
     * @param libraryId the library id
     * @return the design, or empty when the library holds no such row, or holds
     *         one nothing can be read from any more
     */
    @NotNull
    CompletableFuture<Optional<PublishedDesign>> publishedDesign(long libraryId);

    /**
     * The most copied designs in the shared library.
     *
     * <p>What the in-game browser lists. Ordered by use count, highest first.
     *
     * @param limit how many to return at most
     * @return the designs
     */
    @NotNull
    CompletableFuture<@Unmodifiable List<PublishedDesign>> mostUsedDesigns(int limit);

    // ── Menus ──────────────────────────────────────────────────────────────

    /**
     * Opens a player's shield slots, as {@code /shields} does.
     *
     * <p>For an NPC or a lobby item that should lead to the designer. No
     * permission is checked: the caller decided this player may be here. A
     * player whose slots have not arrived yet is read first, and the menu opens
     * a moment later when they do.
     *
     * <p>Call it on the thread that owns the player.
     *
     * @param player who to show it to
     * @since 1.3.0
     */
    void openSlots(@NotNull Player player);

    /**
     * Opens the shared library browser for a player.
     *
     * <p>The designs other players published, most copied first; clicking one
     * copies it into the player's first free slot. The list is read before the
     * menu opens, so it appears a moment after the call rather than empty.
     *
     * <p>Callable from any thread: the menu opens on the player's own.
     *
     * @param player who to show it to
     * @since 1.3.0
     */
    void openLibrary(@NotNull Player player);
}
