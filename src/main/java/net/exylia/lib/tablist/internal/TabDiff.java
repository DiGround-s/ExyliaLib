package net.exylia.lib.tablist.internal;

import java.util.Objects;

/** What a player's tab list last showed, per slot, so only changes are sent. */
final class TabDiff {

    static final int HEADER = 0, FOOTER = 1, NAME = 2, ORDER = 3;

    private final Object[] last = new Object[4];

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
}
