package net.exylia.lib.ui;

import net.exylia.lib.action.ActionExecution;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One open menu, belonging to one player.
 *
 * <p>The authoritative record of what is on screen. Clicks are validated
 * against this rather than against what the client's packet claims, which is
 * what makes a packet-driven menu safe: the client says "slot 13 was clicked",
 * and the server decides what slot 13 is.
 *
 * <p>A session outlives a single render. Changing page, refreshing a row and
 * replacing a list all update the same session, so the player's context — the
 * filter, the selection, the entry they were looking at — survives.
 *
 * @since 1.22.0
 */
public interface UiSession {

    /** Who is looking at it. */
    @NotNull Player viewer();

    /** The id of the definition being shown, such as {@code practice:queue_kits}. */
    @NotNull String menuId();

    /** What the menu was compiled from. */
    @NotNull UiDefinition definition();

    /** The inventory the player has open. */
    @NotNull Inventory inventory();

    // ---------------------------------------------------------------- paging

    /**
     * The page a list is showing, starting at one.
     *
     * @param section which list
     * @return the page, or one when there is no such list
     */
    int page(@NotNull String section);

    /**
     * How many pages a list has, at least one.
     *
     * @param section which list
     * @return the page count
     */
    int pages(@NotNull String section);

    /**
     * Shows another page of a list.
     *
     * <p>Clamped: a list that lost rows while somebody was on the last page
     * shows the last page that exists rather than an empty one.
     *
     * @param section which list
     * @param page    the page, starting at one
     * @return whether the page changed
     */
    boolean page(@NotNull String section, int page);

    /**
     * Moves a list forward or back.
     *
     * @param section which list
     * @param step    how many pages, negative to go back
     * @return whether the page changed
     */
    boolean turn(@NotNull String section, int step);

    /**
     * Shows another page of the only list, for menus that have one.
     *
     * @param page the page, starting at one
     * @return whether the page changed
     */
    boolean page(int page);

    /** Shows the next page of the only list. */
    boolean nextPage();

    /** Shows the previous page of the only list. */
    boolean previousPage();

    // --------------------------------------------------------------- entries

    /**
     * The rows of a list.
     *
     * @param section which list
     * @return the rows, in order; empty when there is no such list
     */
    @NotNull List<UiEntry> entries(@NotNull String section);

    /**
     * Replaces the rows of a list and redraws it.
     *
     * <p>The page is kept where it was, clamped to what still exists. A
     * leaderboard that refreshes under somebody reading page three leaves them
     * on page three.
     *
     * @param section which list
     * @param entries the new rows
     */
    void entries(@NotNull String section, @NotNull Collection<UiEntry> entries);

    /**
     * Replaces the rows of the only list, for menus that have one.
     *
     * @param entries the new rows
     */
    void entries(@NotNull Collection<UiEntry> entries);

    /**
     * The row drawn in a slot, if any.
     *
     * @param slot the slot
     * @return the row, or empty when nothing is listed there
     */
    @NotNull Optional<UiEntry> entryAt(int slot);

    // ------------------------------------------------------------ redrawing

    /**
     * Marks something as changed, so whatever depends on it is redrawn.
     *
     * <p>The heart of the reactive model. A leaderboard whose data changed
     * calls {@code invalidate("stats")}, and only the slots that declared they
     * depend on {@code stats} are re-rendered and re-sent. Everything else on
     * screen is untouched — no full rebuild, no flicker, and no packets for
     * slots that did not change.
     *
     * @param dependencies what changed
     * @return how many slots were redrawn
     */
    int invalidate(@NotNull String... dependencies);

    /** Redraws one slot, whatever it depends on. */
    boolean invalidateSlot(int slot);

    /** Redraws everything. The expensive option, and rarely the right one. */
    void refresh();

    // ---------------------------------------------------------------- context

    /**
     * Values this session carries, such as the thing the menu is about.
     *
     * <p>Also filled into the placeholders of everything the menu draws, so a
     * title reading {@code %kit_name%} needs no resolver of its own.
     */
    @NotNull Map<String, Object> context();

    /** Reads one context value. */
    <T> @NotNull Optional<T> context(@NotNull String key, @NotNull Class<T> type);

    /** Writes one context value, redrawing nothing. */
    @NotNull UiSession context(@NotNull String key, @NotNull Object value);

    // ----------------------------------------------------------------- input

    /** Slots the player is allowed to put items into. */
    @NotNull Set<Integer> inputSlots();

    /** Whatever the player has left in the input slots. */
    @NotNull Map<Integer, ItemStack> inputs();

    /**
     * Puts an item into one of the menu's input slots.
     *
     * <p>The other direction of {@link #inputs()}, for the buttons an editing
     * screen puts around its slots: "clear", "fill from my inventory", "load
     * the last saved layout". Those are the actions that make a grid of input
     * slots an editor rather than a drop box.
     *
     * <pre>{@code
     * session.input(slot, null);                 // empty it
     * session.input(0, player.getInventory().getHelmet());
     * }</pre>
     *
     * @param slot the slot, which must be one of {@link #inputSlots()}
     * @param item what to put there, or {@code null} to empty it
     * @throws IllegalArgumentException if the slot is not an input slot
     */
    void input(int slot, @Nullable ItemStack item);

    /**
     * Puts several items into input slots at once.
     *
     * <p>Every slot named must be an input slot, and it is checked before
     * anything is written: a half-applied layout is worse than a rejected one,
     * because the player cannot tell which half arrived.
     *
     * @param items what to put where; a {@code null} value empties that slot
     * @throws IllegalArgumentException if any slot is not an input slot
     */
    void inputs(@NotNull Map<Integer, ItemStack> items);

    // -------------------------------------------------------------- lifecycle

    /**
     * Registers something to cancel when this menu closes.
     *
     * <p>A delayed action sequence started by a button, an animation, a
     * pending lookup: without this they outlive the screen and run against a
     * menu nobody is looking at.
     *
     * @param execution what to stop
     */
    void cancelOnClose(@NotNull ActionExecution execution);

    /** Closes the menu. */
    void close();

    /** Returns whether this session is still the one the player has open. */
    boolean isOpen();

    /**
     * How many times this session has been re-opened or replaced.
     *
     * <p>Used to discard the answer to a question asked before the player
     * moved on: a lookup that returns after they opened something else must
     * not draw into the new screen.
     */
    int generation();
}
