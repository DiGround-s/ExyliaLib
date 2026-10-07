package net.exylia.lib.ui.internal;

import net.exylia.lib.item.Item;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * What each slot of one open menu was last drawn from.
 *
 * <p>Rendering is the expensive part of a redraw: every name and lore line is
 * parsed and an item is built. A timed redraw mostly asks for the same item
 * again, so a slot drawn from the same definition, the same values and the
 * same formatting choices is left as it is.
 *
 * <p>Only an item whose every placeholder is one of the values it was drawn
 * with can be judged this way. A placeholder nobody handed over — a
 * PlaceholderAPI one, a ping, a balance — is resolved by the renderer and can
 * change on its own, so such a slot is always drawn again, exactly as before
 * this existed.
 *
 * <p>Not thread-safe: it belongs to a session, which runs on one thread.
 */
final class DrawnSlots {

    /** Placeholder names an item uses, by item; {@code null} when it cannot be told. */
    private final Map<Item, Set<String>> placeholders = new IdentityHashMap<>();

    private final Map<Integer, Drawn> drawn = new HashMap<>();

    private record Drawn(Item item, Map<String, String> values, Set<String> formatted,
                         Set<String> verbatim) {
        boolean same(Item item, Map<String, String> values, Set<String> formatted,
                     Set<String> verbatim) {
            // The definition by identity: two equal definitions loaded from two
            // files are still two buttons, and identity is one comparison.
            return this.item == item && this.values.equals(values)
                    && this.formatted.equals(formatted) && this.verbatim.equals(verbatim);
        }
    }

    /**
     * Returns whether drawing this into the slot would put back what is there.
     *
     * @param slot      where
     * @param item      what definition
     * @param values    every value it is drawn with, context included
     * @param formatted which of them are parsed
     * @param verbatim  which of those keep their own letters
     */
    boolean unchanged(int slot, Item item, Map<String, String> values, Set<String> formatted,
                      Set<String> verbatim) {
        Drawn last = drawn.get(slot);
        return last != null && last.same(item, values, formatted, verbatim);
    }

    /**
     * Records what a slot was just drawn from, when that is all it depends on.
     *
     * <p>A slot that depends on something else is forgotten instead, so the
     * next redraw draws it again.
     */
    void record(int slot, Item item, Map<String, String> values, Set<String> formatted,
                Set<String> verbatim) {
        if (selfContained(item, values, formatted)) {
            drawn.put(slot, new Drawn(item, values, formatted, verbatim));
        } else {
            drawn.remove(slot);
        }
    }

    /** Forgets a slot something else drew into. */
    void forget(int slot) {
        drawn.remove(slot);
    }

    /** Forgets every slot, for a redraw that must draw everything. */
    void clear() {
        drawn.clear();
    }

    /**
     * Returns whether nothing but these values decides what the item looks like.
     *
     * <p>Package-private so the decision can be exercised without a server.
     */
    boolean selfContained(Item item, Map<String, String> values, Set<String> formatted) {
        Set<String> used = placeholders.computeIfAbsent(item, DrawnSlots::placeholdersOf);
        if (used == null || !values.keySet().containsAll(used)) {
            return false;
        }
        // A formatted value is parsed, so a placeholder inside it is resolved
        // too, and that one is nobody's value.
        for (String key : formatted) {
            String value = values.get(key);
            if (value != null && !placeholdersIn(value).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Every placeholder name an item's text uses, or {@code null} when part of
     * it cannot be read that way.
     *
     * <p>Traits are the part that cannot: a banner template or a trim pattern
     * resolves on its own terms, and a slot that holds one is simply never
     * skipped.
     */
    static Set<String> placeholdersOf(Item item) {
        if (item.traits().isDynamic()) {
            return null;
        }
        Set<String> names = new LinkedHashSet<>();
        names.addAll(placeholdersIn(item.source().raw()));
        names.addAll(placeholdersIn(item.name()));
        names.addAll(placeholdersIn(item.amount()));
        names.addAll(placeholdersIn(item.appearance().glow()));
        for (String line : item.lore()) {
            names.addAll(placeholdersIn(line));
        }
        return Set.copyOf(names);
    }

    /**
     * The placeholder names in a piece of text: each {@code %name%} with no
     * space inside, which is what the resolver reads. {@code 50% off 20%}
     * holds none.
     */
    static Set<String> placeholdersIn(String text) {
        if (text == null || text.indexOf('%') < 0) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        int open = text.indexOf('%');
        while (open >= 0) {
            int close = text.indexOf('%', open + 1);
            if (close < 0) {
                break;
            }
            String name = text.substring(open + 1, close);
            if (!name.isEmpty() && name.indexOf(' ') < 0) {
                names.add(name);
                open = text.indexOf('%', close + 1);
            } else {
                // "50% off 20%": the second sign may open the next one.
                open = close;
            }
        }
        return names;
    }
}
