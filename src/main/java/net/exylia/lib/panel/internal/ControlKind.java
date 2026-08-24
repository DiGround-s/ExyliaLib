package net.exylia.lib.panel.internal;

import org.jetbrains.annotations.ApiStatus;

/**
 * What a drawn slot is, as the draw sink reports it.
 *
 * <p>The kind is chosen from a component's <em>declared type</em>, never from
 * its value: a {@code long} that happens to hold {@code 0} is still an integral
 * control. Reported rather than inferred, so a test asserts which control
 * landed in which slot without reading an {@code ItemStack} — which needs a
 * running server.
 */
@ApiStatus.Internal
public enum ControlKind {

    /** A free-text component. */
    TEXT,

    /** {@code int}, {@code long}, {@code short}, {@code byte} and their boxes. */
    INTEGER,

    /** {@code double}, {@code float} and their boxes. */
    DECIMAL,

    /** A {@code boolean} shown as a toggle. */
    TOGGLE,

    /** An enum, offered as a searchable choice over its constants. */
    CHOICE,

    /** A {@code List<E>} opening the list panel. */
    LIST,

    /** A nested record opening a sub-panel over its own schema. */
    SUB_PANEL,

    /**
     * A component this library has no control for.
     *
     * <p>Drawn read-only and passed through untouched rather than omitted: a
     * value nobody can see is a value nobody notices losing.
     */
    UNSUPPORTED,

    // ------------------------------------------------------------- chrome

    /** Writes the working copy back. */
    SAVE,

    /** Throws the working copy away. */
    CANCEL,

    /** Takes back the last committed edit. */
    UNDO,

    /** Adds an entry to a list. */
    ADD,

    /** Removes the entry a row carries. */
    DELETE,

    /** Copies the entry a row carries. */
    COPY,

    /** Pastes whatever the clipboard holds. */
    PASTE,

    /** Filters the view, never the working copy. */
    SEARCH,

    /** Moves a list forward. */
    PAGE_NEXT,

    /** Moves a list back. */
    PAGE_PREVIOUS,

    /**
     * One entry of a list, drawn as a row.
     *
     * <p>Announced with the {@link net.exylia.lib.ui.UiEntry} it was drawn from,
     * so what a test reads is the element the row carries rather than the item it
     * became — which is also exactly what a click reads.
     */
    ROW,

    /**
     * What a list with nothing to show is drawn as.
     *
     * <p>A different thing from {@link #BACKGROUND}, and deliberately so: this
     * one <em>says why</em>. A viewer whose search matched nothing and a viewer
     * whose list is genuinely empty are in two different situations, and one of
     * them has a search to clear.
     */
    EMPTY,

    /** Whatever fills a slot no control claimed. */
    BACKGROUND
}
