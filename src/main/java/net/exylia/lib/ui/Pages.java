package net.exylia.lib.ui;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * The arithmetic of paging a list.
 *
 * <p>Small enough to look obvious and wrong often enough to be worth having in
 * one place: off-by-one on the last page, a page number that survived the list
 * shrinking under it, a slice that runs off the end.
 *
 * <p>Pages are one-based, because that is what a player reads on a button.
 *
 * @since 1.22.0
 */
public final class Pages {

    private Pages() {
    }

    /**
     * How many pages a number of rows needs.
     *
     * @param entries how many rows there are
     * @param perPage how many fit on a page
     * @return the page count, always at least one
     */
    public static int count(int entries, int perPage) {
        if (perPage <= 0) {
            return 1;
        }
        return Math.max(1, (entries + perPage - 1) / perPage);
    }

    /**
     * Brings a page number back into range.
     *
     * <p>Clamped rather than refused. A list that lost rows while somebody was
     * reading the last page should show them the last page that exists, not an
     * empty one and not an error.
     *
     * @param page    the page asked for
     * @param entries how many rows there are
     * @param perPage how many fit on a page
     * @return a page that exists
     */
    public static int clamp(int page, int entries, int perPage) {
        return Math.clamp(page, 1, count(entries, perPage));
    }

    /**
     * The rows shown on a page.
     *
     * <p>The end is clamped, so the last page is short rather than out of
     * bounds.
     *
     * @param entries all the rows
     * @param page    which page, one-based
     * @param perPage how many fit on a page
     * @param <T>     the row type
     * @return that page's rows, in order
     */
    public static <T> @NotNull List<T> slice(@NotNull List<T> entries, int page, int perPage) {
        if (perPage <= 0 || entries.isEmpty()) {
            return List.of();
        }
        int safe = clamp(page, entries.size(), perPage);
        int from = (safe - 1) * perPage;
        if (from >= entries.size()) {
            return List.of();
        }
        return entries.subList(from, Math.min(from + perPage, entries.size()));
    }

    /**
     * Which row a slot on a page corresponds to.
     *
     * @param page    which page, one-based
     * @param perPage how many fit on a page
     * @param index   which slot of the page, zero-based
     * @return the index into the whole list
     */
    public static int indexOf(int page, int perPage, int index) {
        return Math.max(0, (page - 1)) * perPage + index;
    }
}
