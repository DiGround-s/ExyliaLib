package net.exylia.lib.panel.internal;

import net.exylia.lib.panel.PanelDiff;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Compares what was open against what is about to be saved.
 *
 * <p>Pure: two maps in, a value out, no Bukkit API and no I/O. That is what
 * lets the save path be stated as one rule — write only when something changed —
 * and tested without a server or a config file.
 */
@ApiStatus.Internal
public final class Diff {

    private Diff() {
        throw new AssertionError("No instances.");
    }

    /**
     * What changed between two sets of component values.
     *
     * <p>Names are sorted so that two runs read the same. A diff is shown to a
     * player, and a list that reshuffles between opens looks like something
     * moved when nothing did.
     *
     * @param before what the panel opened with
     * @param after  what it holds now
     * @return the difference, never {@code null}
     */
    public static @NotNull PanelDiff between(@NotNull Map<String, Object> before,
                                             @NotNull Map<String, Object> after) {
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> changed = new ArrayList<>();

        for (String name : new TreeSet<>(union(before, after))) {
            boolean was = before.containsKey(name);
            boolean is = after.containsKey(name);
            if (was && !is) {
                removed.add(name);
            } else if (!was) {
                added.add(name);
            } else if (!equal(before.get(name), after.get(name))) {
                changed.add(name);
            }
        }
        return new PanelDiff(added, removed, changed);
    }

    /**
     * Writes only when something changed.
     *
     * <p>The rule the whole save path rests on. Opening a settings panel to look
     * at it and closing it again must not rewrite the owner's file: a config
     * write reorders nothing today, but it does touch the file, and "I opened it
     * and it changed" is indistinguishable from a bug.
     *
     * @param before what the panel opened with
     * @param after  what it holds now
     * @param write  what to do with the new values, called at most once
     * @return whether the write happened
     */
    public static boolean saveIfChanged(@NotNull Map<String, Object> before,
                                        @NotNull Map<String, Object> after,
                                        @NotNull Consumer<Map<String, Object>> write) {
        if (between(before, after).isEmpty()) {
            return false;
        }
        write.accept(after);
        return true;
    }

    private static List<String> union(Map<String, Object> before, Map<String, Object> after) {
        List<String> names = new ArrayList<>(before.keySet());
        for (String name : after.keySet()) {
            if (!before.containsKey(name)) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * Whether two values are the same.
     *
     * <p>Null-tolerant on both sides: a component that had no value and now has
     * one has changed, and so has one that was cleared. Arrays are compared by
     * content because a record component may legitimately be one, and reference
     * equality would report every save as a change.
     */
    private static boolean equal(@Nullable Object before, @Nullable Object after) {
        if (before instanceof Object[] left && after instanceof Object[] right) {
            return java.util.Arrays.deepEquals(left, right);
        }
        return Objects.deepEquals(before, after);
    }
}
