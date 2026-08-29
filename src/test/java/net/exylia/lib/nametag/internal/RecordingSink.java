package net.exylia.lib.nametag.internal;

import net.exylia.lib.nametag.NametagStyle;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A sink that records instead of writing to a client.
 *
 * <p>What matters about this module is which packets it decides are worth
 * sending — a team created once and added to afterwards, a colour that did not
 * change and so sent nothing. Recording them is the only way to prove that
 * without a client on the other end.
 */
final class RecordingSink implements NametagSink {

    private final List<String> calls = new CopyOnWriteArrayList<>();

    /** Set to make every send throw, standing in for a client that vanished. */
    volatile boolean broken;

    volatile boolean closed;

    @Override
    public void createTeam(Player viewer, String name, NametagStyle style,
                           Collection<String> members) {
        fail();
        calls.add("create:" + viewer.getName() + ":" + name + ":" + names(members));
    }

    @Override
    public void addToTeam(Player viewer, String name, Collection<String> members) {
        fail();
        calls.add("add:" + viewer.getName() + ":" + name + ":" + names(members));
    }

    @Override
    public void removeTeam(Player viewer, String name) {
        fail();
        calls.add("delteam:" + viewer.getName() + ":" + name);
    }

    @Override
    public void refreshFlags(Player viewer, Player target) {
        fail();
        calls.add("flags:" + viewer.getName() + ":" + target.getName());
    }

    @Override
    public void close() {
        closed = true;
    }

    private void fail() {
        if (broken) {
            throw new IllegalStateException("the client is gone");
        }
    }

    private static String names(Collection<String> members) {
        return String.join(",", new ArrayList<>(members));
    }

    List<String> calls() {
        return List.copyOf(calls);
    }

    List<String> calls(String kind) {
        return calls.stream().filter(call -> call.startsWith(kind + ":")).toList();
    }

    void clear() {
        calls.clear();
    }
}
