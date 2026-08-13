package net.exylia.lib.ui;

import net.exylia.lib.action.ActionExecution;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
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
 * reloading a definition all update the same session, so the player's context
 * — filters, selection, the entry they were looking at — survives.
 *
 * @since 1.22.0
 */
public interface UiSession {

    /** Who is looking at it. */
    @NotNull Player viewer();

    /** The id of the definition being shown, such as {@code practice:queue_kits}. */
    @NotNull String menuId();

    /** The inventory the player has open. */
    @NotNull Inventory inventory();

    /** The page being shown, starting at one. */
    int page();

    /** How many pages there are, at least one. */
    int pages();

    /**
     * Shows another page.
     *
     * <p>Clamped: a menu that lost rows while somebody was on the last page
     * shows the last page that exists rather than an empty one.
     *
     * @param page the page, starting at one
     * @return whether the page changed
     */
    boolean page(int page);

    /** Shows the next page, if there is one. */
    boolean nextPage();

    /** Shows the previous page, if there is one. */
    boolean previousPage();

    /**
     * Marks something as changed, so whatever depends on it is redrawn.
     *
     * <p>The heart of the reactive model. A leaderboard whose data changed
     * calls {@code invalidate("stats")}, and only the slots that declared they
     * depend on {@code stats} are re-rendered and re-sent. Everything else on
     * screen is untouched — no full rebuild, no flicker, no packets for slots
     * that did not change.
     *
     * @param dependencies what changed
     * @return how many slots were redrawn
     */
    int invalidate(@NotNull String... dependencies);

    /** Redraws one slot, whatever it depends on. */
    boolean invalidateSlot(int slot);

    /** Redraws everything. The expensive option, and rarely the right one. */
    void refresh();

    /**
     * Values this session carries, such as the thing the menu is about.
     *
     * <p>Restored when the player comes back through {@code back}, which is
     * what makes returning to a filtered list actually return to it.
     */
    @NotNull Map<String, Object> context();

    /** Reads one context value. */
    <T> @NotNull Optional<T> context(@NotNull String key, @NotNull Class<T> type);

    /** Writes one context value. */
    @NotNull UiSession context(@NotNull String key, @NotNull Object value);

    /**
     * The entries of a paginated list, if it has any.
     *
     * @return the entries, in order
     */
    @NotNull List<?> entries();

    /**
     * Replaces the entries of a paginated list and redraws it.
     *
     * @param entries the new entries
     */
    void entries(@NotNull Collection<?> entries);

    /** Slots the player is allowed to put items into. */
    @NotNull Set<Integer> inputSlots();

    /** Whatever the player has left in the input slots. */
    @NotNull Map<Integer, org.bukkit.inventory.ItemStack> inputs();

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
