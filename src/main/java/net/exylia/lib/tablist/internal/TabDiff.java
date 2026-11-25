package net.exylia.lib.tablist.internal;

import net.kyori.adventure.text.Component;

import java.util.Objects;
import java.util.function.Function;

/** What a player's tab list last resolved and showed, per slot, so only changes are sent. */
final class TabDiff {

    static final int HEADER = 0, FOOTER = 1, NAME = 2, ORDER = 3;

    private final Object[] last = new Object[4];
    private final Component[] built = new Component[3];
    private final Component[] shown = new Component[3];
    private final boolean[] moving = new boolean[3];

    /** Records the value; true when it differs from the one recorded before. */
    synchronized boolean changed(int slot, Object value) {
        if (Objects.equals(last[slot], value)) {
            return false;
        }
        last[slot] = value;
        return true;
    }

    synchronized Object last(int slot) {
        return last[slot];
    }

    /**
     * The component for a text slot: parsed again only when the text changed or
     * moves (its frame comes from the clock), otherwise the one built before.
     */
    synchronized Component build(int slot, String text, Function<String, TabRuntime.Built> parse) {
        if (changed(slot, text) || moving[slot] || built[slot] == null && text != null) {
            TabRuntime.Built b = text == null ? null : parse.apply(text);
            built[slot] = b == null ? null : b.component();
            moving[slot] = b != null && b.moving();
        }
        return built[slot];
    }

    /** Records what is on screen; true when it differs from what was there. */
    synchronized boolean show(int slot, Component component) {
        if (Objects.equals(shown[slot], component)) {
            return false;
        }
        shown[slot] = component;
        return true;
    }

    /** Whether any text slot carries a moving effect and wants redrawing every frame. */
    synchronized boolean moving() {
        return moving[HEADER] || moving[FOOTER] || moving[NAME];
    }
}
