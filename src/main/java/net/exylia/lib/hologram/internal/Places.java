package net.exylia.lib.hologram.internal;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Where a viewer must be for a hologram, or a line, to show: worlds and
 * WorldGuard regions, as {@code <world:...>}, {@code <region:...>} and the
 * {@code view} section write them.
 *
 * <p>One rule for both: when any entry is written plain, the viewer must match
 * one of them; and they must match none of the entries written {@code !name}.
 * A region may name its world, {@code world:spawn}, and then only counts there.
 */
final class Places {

    private Places() {
    }

    /** Whether a viewer in {@code world} passes these world rules; no rules pass everyone. */
    static boolean worlds(List<String> rules, String world) {
        return passes(rules, entry -> entry.equalsIgnoreCase(world));
    }

    /**
     * Whether a viewer in {@code world}, standing in the regions {@code here}
     * gives (lower case, asked only if a rule needs them), passes these rules.
     */
    static boolean regions(List<String> rules, String world, Supplier<List<String>> here) {
        if (rules.isEmpty()) {
            return true;
        }
        List<String> ids = here.get();
        return passes(rules, entry -> {
            int colon = entry.indexOf(':');
            if (colon > 0 && !entry.substring(0, colon).equalsIgnoreCase(world)) {
                return false;
            }
            return ids.contains(entry.substring(colon + 1).toLowerCase(Locale.ROOT));
        });
    }

    private static boolean passes(List<String> rules, java.util.function.Predicate<String> matches) {
        boolean wanted = false;
        boolean found = false;
        for (String rule : rules) {
            if (rule.startsWith("!")) {
                if (matches.test(rule.substring(1).trim())) {
                    return false;
                }
            } else {
                wanted = true;
                found |= matches.test(rule.trim());
            }
        }
        return !wanted || found;
    }
}
