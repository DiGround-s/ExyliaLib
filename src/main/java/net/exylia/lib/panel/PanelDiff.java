package net.exylia.lib.panel;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * What a panel would change if it saved.
 *
 * <p>Computed before writing anything, which is what makes "an unchanged panel
 * writes nothing" a rule rather than a hope: a save whose diff
 * {@link #isEmpty() is empty} never reaches the store at all. That matters more
 * than it sounds — opening a config panel to look at it, and closing it, must
 * not rewrite the owner's file and reorder their comments.
 *
 * <p>The three lists name components, not values. A diff is something a player
 * is shown before they confirm ("3 changed"), and a value could be a password,
 * a serialised inventory, or a megabyte of list.
 *
 * <h2>Threads and nullability</h2>
 * A pure value. Any thread, no Bukkit API. The three lists are immutable, and
 * none of them — nor this record — is ever {@code null}: nothing changed is an
 * empty list, never a null one.
 *
 * <pre>{@code
 * PanelDiff diff = session.diff();
 * if (!diff.isEmpty()) {
 *     player.sendMessage("Changing: " + String.join(", ", diff.changed()));
 * }
 * }</pre>
 *
 * @param added   components that have a value now and did not before
 * @param removed components that had a value and no longer do
 * @param changed components whose value is different
 * @since 1.50.0
 */
public record PanelDiff(@NotNull List<String> added,
                        @NotNull List<String> removed,
                        @NotNull List<String> changed) {

    /** Nothing changed. */
    public static final PanelDiff EMPTY = new PanelDiff(List.of(), List.of(), List.of());

    public PanelDiff {
        added = List.copyOf(added);
        removed = List.copyOf(removed);
        changed = List.copyOf(changed);
    }

    /**
     * Returns whether this diff would write anything.
     *
     * @return {@code true} when nothing was added, removed or changed
     */
    public boolean isEmpty() {
        return added.isEmpty() && removed.isEmpty() && changed.isEmpty();
    }

    /**
     * How many components this diff covers.
     *
     * @return the total across all three lists
     */
    public int size() {
        return added.size() + removed.size() + changed.size();
    }
}
