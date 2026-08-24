package net.exylia.lib.panel.internal;

import org.jetbrains.annotations.ApiStatus;
import net.exylia.lib.debug.Debug;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Says once, per type, that a component has no control.
 *
 * <p>The {@code ItemComponents} lesson, applied before it can be re-learned: a
 * settings panel renders every component each time it opens, so reporting per
 * control put eighteen identical lines in the console for one screen. Which
 * types this library has no control for is a fact about the library, not an
 * incident of the panel that happened to open.
 *
 * <p>Keyed by type name rather than by a single flag, because two unsupported
 * types are two different things an owner may want to know about — and a
 * {@code Set<String>} of type names is bounded by the classes that exist, so it
 * cannot grow with players or sessions.
 */
@ApiStatus.Internal
public final class UnsupportedTypes {

    /** Type names already reported. Never keyed by player: see the class doc. */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private UnsupportedTypes() {
        throw new AssertionError("No instances.");
    }

    /**
     * Reports a component this library cannot edit, at most once per type.
     *
     * <p>Never throws and never prevents a panel opening: the component is
     * drawn read-only and passed through untouched, which is strictly better
     * than refusing the whole screen over one field.
     *
     * @param owner the plugin whose config declares it, so the line names the
     *              plugin an owner would go and look at
     * @param field the component name, as declared
     * @param type  the declared type that has no control
     * @return whether this call was the one that reported it
     */
    public static boolean report(Plugin owner, String field, Class<?> type) {
        if (!REPORTED.add(type.getName())) {
            return false;
        }
        Debug.of(owner).warn("No panel control exists for " + type.getName()
                + " (field \"" + field + "\"), so it is shown read-only and saved unchanged.");
        return true;
    }

    /** Whether a type has already been reported. */
    public static boolean wasReported(Class<?> type) {
        return REPORTED.contains(type.getName());
    }

    /**
     * Test seam: forgets what has been said.
     *
     * <p>"Once per server" is only assertable more than once if the memory can
     * be cleared between tests. Precedent: {@code ItemComponents}.
     */
    public static void forgetReportedForTests() {
        REPORTED.clear();
    }
}
