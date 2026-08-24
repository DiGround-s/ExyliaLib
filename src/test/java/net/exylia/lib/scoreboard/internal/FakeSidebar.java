package net.exylia.lib.scoreboard.internal;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A sidebar that records what was sent to it.
 *
 * <p>A board is only observable through the packets its sidebar would write,
 * so the tests assert on this rather than on the board's internals. Recording
 * every call in order is what lets a test prove that an unchanged line was not
 * re-sent, which is the whole point of the diff.
 */
final class FakeSidebar implements SidebarHandle {

    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<String> titles = new CopyOnWriteArrayList<>();
    private final List<String> lines = new CopyOnWriteArrayList<>();

    private volatile boolean visible;
    private volatile boolean closed;
    private volatile boolean failing;

    /**
     * Makes every write throw, as a sidebar whose packet path is broken would.
     *
     * <p>The seam for the report-once path: a resolver that throws is caught a
     * level below, so the only way to reach the board's own catch is to break
     * what it writes to.
     */
    void failOnEveryCall() {
        failing = true;
    }

    @Override
    public void show() {
        visible = true;
        calls.add("show");
    }

    @Override
    public void hide() {
        visible = false;
        calls.add("hide");
    }

    @Override
    public void close() {
        closed = true;
        calls.add("close");
    }

    @Override
    public boolean closed() {
        return closed;
    }

    @Override
    public void title(Component title) {
        if (failing) {
            throw new IllegalStateException("Asynchronous scoreboard write!");
        }
        titles.add(plain(title));
        calls.add("title:" + plain(title));
    }

    @Override
    public void line(int index, @Nullable Component line) {
        if (line == null) {
            calls.add("clear:" + index);
            return;
        }
        lines.add(index + "=" + plain(line));
        calls.add("line:" + index + ":" + plain(line));
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** Every call this sidebar received, in order. */
    List<String> calls() {
        return new ArrayList<>(calls);
    }

    /** Every title that was sent, in order. */
    List<String> titles() {
        return new ArrayList<>(titles);
    }

    /** Every line that was sent, as {@code index=text}, in order. */
    List<String> lines() {
        return new ArrayList<>(lines);
    }

    /** How many calls of a kind were made, such as {@code line:0}. */
    long countStartingWith(String prefix) {
        return calls.stream().filter(call -> call.startsWith(prefix)).count();
    }

    boolean visible() {
        return visible;
    }

    /** Forgets everything recorded so far. */
    void clear() {
        calls.clear();
        titles.clear();
        lines.clear();
    }
}
