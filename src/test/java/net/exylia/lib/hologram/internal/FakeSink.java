package net.exylia.lib.hologram.internal;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A packet sink that records instead of sending.
 *
 * <p>A hologram is only observable through the packets it writes, so the tests
 * assert on these. Recording every call in order is what proves that a line
 * whose value did not change was not re-sent, and that a player walking out of
 * range is sent exactly one destroy.
 */
final class FakeSink implements DisplaySink {

    private final List<String> calls = new CopyOnWriteArrayList<>();

    @Override
    public void spawn(Player viewer, DisplayState display, Location at) {
        calls.add("spawn:" + name(viewer) + ":" + display.entityId()
                + ":" + format(at.getY()));
    }

    @Override
    public void text(Player viewer, DisplayState display, Component text) {
        calls.add("text:" + name(viewer) + ":" + display.entityId() + ":"
                + PlainTextComponentSerializer.plainText().serialize(text));
    }

    @Override
    public void teleport(Player viewer, DisplayState display, Location to) {
        calls.add("teleport:" + name(viewer) + ":" + display.entityId()
                + ":" + format(to.getY()));
    }

    @Override
    public void mount(Player viewer, int vehicleId, int[] passengers) {
        calls.add("mount:" + name(viewer) + ":" + vehicleId + ":" + passengers.length);
    }

    @Override
    public void destroy(Player viewer, int[] entityIds) {
        calls.add("destroy:" + name(viewer) + ":" + entityIds.length);
    }

    private static String name(Player player) {
        return player.getName();
    }

    private static String format(double value) {
        return String.valueOf(Math.round(value * 100) / 100.0);
    }

    List<String> calls() {
        return new ArrayList<>(calls);
    }

    /** Every call of a kind, such as {@code text} or {@code spawn}. */
    List<String> calls(String kind) {
        return calls.stream().filter(call -> call.startsWith(kind + ":")).toList();
    }

    long count(String kind) {
        return calls.stream().filter(call -> call.startsWith(kind + ":")).count();
    }

    void clear() {
        calls.clear();
    }
}
