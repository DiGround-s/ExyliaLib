package net.exylia.lib.ui;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * A paginated list inside a menu.
 *
 * <p>A menu can have several, side by side on the same screen and paging
 * independently: a leaderboard shows the players in the middle and the stat to
 * sort by along the bottom, each with its own arrows. That is why a section is
 * a thing rather than a property of the menu.
 *
 * <p>Configuration writes them two ways and both mean this. The older form is a
 * single {@code pagination} block, which is read as one section named
 * {@link #MAIN}; the newer is a {@code sections} block naming each one. A menu
 * with one list never has to know the difference.
 *
 * <h2>Templates</h2>
 * A row is not always drawn the same way. The same kit is a different item when
 * it is the selected one, and a different one again when the viewer cannot use
 * it, so a section carries templates by name:
 *
 * <pre>
 * sections:
 *   effects:
 *     slots: "19-25,28-34"
 *     selected_template:       { ... }
 *     not_selected_template:   { ... }
 *     no_permissions_template: { ... }
 * </pre>
 *
 * <p>Which one a row uses is decided by whoever fills the list, not by the menu:
 * the plugin knows what is selected and what is permitted. Any name is accepted,
 * including ones no plugin has invented yet.
 *
 * @param id       what this list is called, such as {@code players}
 * @param slots    where its rows are drawn, in order
 * @param templates how a row can look, by name; always holds {@link #DEFAULT}
 * @param previous the previous-page button, if any
 * @param next     the next-page button, if any
 * @param filler   what fills leftover slots, if anything
 * @since 1.22.0
 */
public record UiSection(
        @NotNull String id,
        @NotNull List<Integer> slots,
        @NotNull Map<String, UiItem> templates,
        @Nullable Placed previous,
        @Nullable Placed next,
        @Nullable UiItem filler) {

    /**
     * The name given to the list of a menu written with {@code pagination}.
     *
     * <p>So that one modelled shape serves both spellings, and a plugin with a
     * single list can ignore section names entirely.
     */
    public static final String MAIN = "main";

    /**
     * The template used by a row that does not ask for one.
     *
     * <p>{@code item_template} in configuration, which is what all hundred and
     * fifty single-list menus write.
     */
    public static final String DEFAULT = "default";

    public UiSection {
        slots = List.copyOf(slots);
        templates = Map.copyOf(templates);
    }

    /**
     * Returns whether this list can draw a row on its own.
     *
     * <p>Most can: they declare a template and fill in its placeholders. A few
     * cannot, and are not broken — a kit room lists the stacks it stores, and
     * no template could describe an arbitrary saved item. Those rows bring
     * their own, through {@link UiEntry.Builder#item}.
     */
    public boolean hasTemplates() {
        return !templates.isEmpty();
    }

    /** An item and where it goes. */
    public record Placed(int slot, @NotNull UiItem item) {
    }

    /** How many rows fit on one page. */
    public int perPage() {
        return slots.size();
    }

    /**
     * The template a row asked for.
     *
     * <p>Falls back to {@link #DEFAULT}: a plugin naming a template the file
     * does not declare gets the ordinary row rather than an empty slot, which
     * is easier to notice and far easier to recover from.
     *
     * @param name the template name, or {@code null} for the default
     * @return the template
     */
    public @Nullable UiItem template(@Nullable String name) {
        if (name == null) {
            return templates.get(DEFAULT);
        }
        UiItem named = templates.get(name);
        return named != null ? named : templates.get(DEFAULT);
    }

    /** Returns whether this section declares a template by that name. */
    public boolean hasTemplate(@NotNull String name) {
        return templates.containsKey(name);
    }

    /**
     * How many pages a number of rows needs.
     *
     * @param entries how many rows there are
     * @return the page count, at least one
     */
    public int pagesFor(int entries) {
        return Pages.count(entries, slots.size());
    }
}
