package net.exylia.lib.screen.internal;

import net.kyori.adventure.text.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * One player's chat while screens come and go: the messages held while a screen is up. What was already in view is
 * left alone and fades on its own; blank lines pushed over it (before 1.670.0) drew a row of empty translucent
 * boxes whenever the player opened the chat. Pure, and called from the network threads.
 */
final class ChatHold {

    private static final int HELD_LINES = 200;

    private final Deque<Component> held = new ArrayDeque<>();
    private boolean holding;

    /** Starts holding. Whether it was not holding already. */
    synchronized boolean begin() {
        if (holding) return false;
        holding = true;
        return true;
    }

    synchronized boolean holding() {
        return holding;
    }

    /** Keeps a line for later, if holding; past the cap the oldest goes. Whether it was kept. */
    synchronized boolean hold(Component line) {
        if (!holding) return false;
        held.addLast(line);
        while (held.size() > HELD_LINES) held.removeFirst();
        return true;
    }

    /** Stops holding: what to send now, in order. */
    synchronized List<Component> end() {
        if (!holding) return List.of();
        holding = false;
        List<Component> out = new ArrayList<>(held);
        held.clear();
        return out;
    }
}
