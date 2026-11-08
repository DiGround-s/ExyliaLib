package net.exylia.lib.screen.internal;

import net.kyori.adventure.text.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * One player's chat while screens come and go: what they saw lately, so it can be pushed out of view and put back,
 * and the messages held while a screen is up. Pure, and called from the network threads.
 */
final class ChatHold {

    /** Chat fades after ten seconds: anything older is out of view already. */
    static final long RECENT_MILLIS = 10_000;
    private static final int RECENT_LINES = 30;
    private static final int HELD_LINES = 200;

    private record Line(Component text, long at) {
    }

    private final Deque<Line> recent = new ArrayDeque<>();
    private final Deque<Component> held = new ArrayDeque<>();
    private final List<Component> pushedOut = new ArrayList<>();
    private boolean holding;

    /** A line the client just drew. */
    synchronized void shown(Component line, long now) {
        recent.addLast(new Line(line, now));
        while (recent.size() > RECENT_LINES) recent.removeFirst();
    }

    /** Starts holding. Whether something is still in view, to be pushed out. */
    synchronized boolean begin(long now) {
        if (holding) return false;
        holding = true;
        pushedOut.clear();
        for (Line line : recent) {
            if (now - line.at() < RECENT_MILLIS) pushedOut.add(line.text());
        }
        recent.clear();
        return !pushedOut.isEmpty();
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

    /** Stops holding: what to send now, in order, the lines pushed out first. */
    synchronized List<Component> end() {
        if (!holding) return List.of();
        holding = false;
        List<Component> out = new ArrayList<>(pushedOut);
        out.addAll(held);
        pushedOut.clear();
        held.clear();
        return out;
    }
}
