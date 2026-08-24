package net.exylia.lib.panel.internal;

import net.exylia.lib.panel.FieldDescriptor;
import net.exylia.lib.panel.PanelDiff;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * How a list lives inside a {@link Session}, and what changed between two of them.
 *
 * <p>A session's working copy is a map of component values, because that is what
 * a settings panel edits. A list panel edits a list, so it lives under one name
 * in that map — which costs one indirection and buys the whole of undo, cancel,
 * the diff rule and the "nothing survives its owner" cleanup, already written
 * and already tested. A second working-copy type would have had to earn all of
 * that again.
 *
 * <h2>Threads</h2>
 * Pure and any-thread. No Bukkit API, no state.
 */
@ApiStatus.Internal
public final class ListEntries {

    /** The one name a list panel's working copy uses. */
    private static final String COMPONENT = "entries";

    private ListEntries() {
        throw new AssertionError("No instances.");
    }

    /** Puts a list where a session can hold it. */
    static @NotNull Map<String, Object> wrap(@NotNull List<?> entries) {
        Map<String, Object> values = new LinkedHashMap<>(1);
        values.put(COMPONENT, List.copyOf(entries));
        return values;
    }

    /** Reads it back out. */
    @SuppressWarnings("unchecked")
    static <T> @NotNull List<T> unwrap(@NotNull Map<String, Object> values) {
        Object held = values.get(COMPONENT);
        return held instanceof List<?> list ? (List<T>) List.copyOf(list) : List.of();
    }

    /**
     * What changed between two versions of a list, by entry.
     *
     * <p>Deliberately not {@link Diff#between}, which answers "which components
     * differ" — for a list that is always the single component, so it can only
     * ever say "one thing changed". What a viewer needs before they confirm a
     * save is how many rows they added, removed and edited.
     *
     * <p>Entries are paired by {@link FieldDescriptor#identity}, which is what
     * makes an <em>edit</em> distinguishable from a removal plus an addition:
     * the identity survives an edit and a new row's does not.
     *
     * @param before     the list as the panel opened it
     * @param after      the list as it holds it now
     * @param descriptor what knows an entry's identity and label
     * @return the difference, never {@code null}
     */
    static <T> @NotNull PanelDiff between(@NotNull List<T> before, @NotNull List<T> after,
                                          @NotNull FieldDescriptor<T> descriptor) {
        Map<String, T> was = byIdentity(before, descriptor);
        Map<String, T> is = byIdentity(after, descriptor);

        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> changed = new ArrayList<>();

        for (Map.Entry<String, T> entry : is.entrySet()) {
            T previous = was.get(entry.getKey());
            if (previous == null) {
                added.add(descriptor.label(entry.getValue()));
            } else if (!Objects.equals(previous, entry.getValue())) {
                changed.add(descriptor.label(entry.getValue()));
            }
        }
        for (Map.Entry<String, T> entry : was.entrySet()) {
            if (!is.containsKey(entry.getKey())) {
                removed.add(descriptor.label(entry.getValue()));
            }
        }
        return new PanelDiff(added, removed, changed);
    }

    /**
     * A list keyed by identity.
     *
     * <p>A duplicate identity keeps the first occurrence rather than throwing: a
     * descriptor whose identity is not unique is a bug in that descriptor, and
     * it must cost a confusing diff rather than a screen that will not open.
     */
    private static <T> Map<String, T> byIdentity(List<T> entries, FieldDescriptor<T> descriptor) {
        Map<String, T> byIdentity = new LinkedHashMap<>(entries.size());
        for (T entry : entries) {
            byIdentity.putIfAbsent(descriptor.identity(entry), entry);
        }
        return byIdentity;
    }
}
