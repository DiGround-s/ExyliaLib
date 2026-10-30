package net.exylia.lib.spatial.internal;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * What a player has drafted in a spatial menu and not saved, against what is saved: the keys that
 * name a source are that source's selection, kept by the menu; every other key's draft is kept here.
 * A draft equal to the saved value is no draft.
 *
 * <p>Internal: free to change without notice.
 */
public final class Drafts {

    private final Map<String, Object> values = new HashMap<>();

    /** The draft of a plain key, or its saved value. */
    public Object get(String key, Map<String, ?> saved) {
        return values.containsKey(key) ? values.get(key) : saved.get(key);
    }

    /** Drafts a plain key; {@code null} or the saved value drops the draft. */
    public void put(String key, Object value, Map<String, ?> saved) {
        if (value == null || Objects.equals(value, saved.get(key))) values.remove(key);
        else values.put(key, value);
    }

    /**
     * The keys whose draft differs from what is saved, in the saved map's order, then drafted keys the
     * saved map lacks. {@code isSource} tells which keys are selections, {@code selected} reads them.
     */
    public Set<String> changed(Map<String, ?> saved, Predicate<String> isSource, Function<String, String> selected) {
        Set<String> changed = new LinkedHashSet<>();
        for (Map.Entry<String, ?> entry : saved.entrySet()) {
            String key = entry.getKey();
            Object now = isSource.test(key) ? selected.apply(key) : get(key, saved);
            if (!Objects.equals(now, entry.getValue())) changed.add(key);
        }
        for (String key : values.keySet()) {
            if (!saved.containsKey(key)) changed.add(key);
        }
        return changed;
    }

    /** Drops the drafts the saved state now equals: what a save leaves behind. */
    public void prune(Map<String, ?> saved) {
        values.entrySet().removeIf(entry -> Objects.equals(entry.getValue(), saved.get(entry.getKey())));
    }

    public void clear() {
        values.clear();
    }

    /** A copy of every plain key's draft, for {@link #restore}: what UNDO steps back to. */
    public Map<String, Object> snapshot() {
        return new HashMap<>(values);
    }

    /** Puts the drafts back to a {@link #snapshot}. */
    public void restore(Map<String, Object> snapshot) {
        values.clear();
        values.putAll(snapshot);
    }
}
