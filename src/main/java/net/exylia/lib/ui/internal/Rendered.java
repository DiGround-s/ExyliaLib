package net.exylia.lib.ui.internal;

import net.exylia.lib.ui.UiEntry;
import net.exylia.lib.ui.UiItem;
import org.jetbrains.annotations.Nullable;

/**
 * What is actually in one slot right now.
 *
 * <p>The record a click is validated against. The client sends a slot number
 * and nothing else, so the server has to know what it put there — and it must
 * be what it put there, not what the definition says it would have put there,
 * because a condition may have hidden it and a page may have moved it.
 *
 * @param item    the definition drawn, or {@code null} when the slot is filler
 * @param entry   the row it came from, when it came from a list
 * @param section which list, when it came from one
 */
record Rendered(@Nullable UiItem item, @Nullable UiEntry entry, @Nullable String section) {

    /** A slot holding a fixed item. */
    static Rendered of(UiItem item) {
        return new Rendered(item, null, null);
    }

    /** A slot holding a row of a list. */
    static Rendered of(UiItem item, UiEntry entry, String section) {
        return new Rendered(item, entry, section);
    }

    /** A slot holding filler, which is drawn but does nothing. */
    static final Rendered FILLER = new Rendered(null, null, null);

    /** Returns whether clicking this does anything. */
    boolean isClickable() {
        return item != null && !item.bindings().isEmpty();
    }
}
