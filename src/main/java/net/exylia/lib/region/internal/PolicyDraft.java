package net.exylia.lib.region.internal;

import net.exylia.lib.region.PolicyKey;
import net.exylia.lib.region.PolicySet;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The working copy behind a policy editor, with no window attached.
 *
 * <p>Everything a click can do to a set of policies is decided here, so it is
 * tested without a server: the cycle, what a locked row refuses, what
 * "Default" resolves to and the edit of a value row. The window only draws what
 * this says and forwards clicks.
 *
 * <p>Keys the editor does not show pass through untouched. An editor opened on
 * the boolean rows must not drop a block list somebody declared elsewhere.
 */
@ApiStatus.Internal
public final class PolicyDraft {

    /** Where a boolean row stands. */
    public enum State {
        /** Not declared: whatever the defaults say decides. */
        DEFAULT,
        /** Declared {@code true}. */
        ALLOW,
        /** Declared {@code false}. */
        DENY
    }

    private final PolicySet original;
    private final @Nullable PolicySet defaults;
    private final Map<PolicyKey<?>, String> locked;
    private PolicySet working;

    /**
     * @param policies    what is being edited
     * @param defaults    what an undeclared row resolves to, or {@code null} when the plugin decides
     * @param locked      rows that never change, with the reason shown for each
     */
    public PolicyDraft(@NotNull PolicySet policies, @Nullable PolicySet defaults,
                       @NotNull Map<PolicyKey<?>, String> locked) {
        this.original = Objects.requireNonNull(policies, "policies");
        this.working = policies;
        this.defaults = defaults;
        this.locked = Map.copyOf(locked);
    }

    /** Where a boolean row stands now. */
    public @NotNull State state(@NotNull PolicyKey<Boolean> key) {
        return working.explicit(key).map(value -> value ? State.ALLOW : State.DENY).orElse(State.DEFAULT);
    }

    /**
     * Moves a boolean row one step: Default, Allow, Deny, and back to Default.
     *
     * @return whether anything changed; a locked row never does
     */
    public boolean cycle(@NotNull PolicyKey<Boolean> key) {
        if (isLocked(key)) {
            return false;
        }
        working = switch (state(key)) {
            case DEFAULT -> working.with(key, true);
            case ALLOW -> working.with(key, false);
            case DENY -> working.without(key);
        };
        return true;
    }

    /**
     * Declares a value row.
     *
     * @return whether it was accepted; a locked row refuses
     */
    public <T> boolean set(@NotNull PolicyKey<T> key, @NotNull T value) {
        if (isLocked(key)) {
            return false;
        }
        working = working.with(key, value);
        return true;
    }

    /**
     * Takes a row back to undeclared.
     *
     * @return whether it was accepted; a locked row refuses
     */
    public boolean reset(@NotNull PolicyKey<?> key) {
        if (isLocked(key)) {
            return false;
        }
        working = working.without(key);
        return true;
    }

    /** The value declared here, if any. */
    public <T> @NotNull Optional<T> explicit(@NotNull PolicyKey<T> key) {
        return working.explicit(key);
    }

    /**
     * What an undeclared row resolves to.
     *
     * @return the defaults' declaration, else the key's own default; empty when
     *         no defaults were given, because then the plugin decides
     */
    public <T> @NotNull Optional<T> fallback(@NotNull PolicyKey<T> key) {
        if (defaults == null) {
            return Optional.empty();
        }
        return Optional.of(defaults.explicit(key).orElse(key.defaultValue()));
    }

    /** Why a row is locked, or {@code null} when it is not. */
    public @Nullable String lockReason(@NotNull PolicyKey<?> key) {
        return locked.get(key);
    }

    public boolean isLocked(@NotNull PolicyKey<?> key) {
        return locked.containsKey(key);
    }

    /** How many of the rows shown are declared here. */
    public int declared(@NotNull List<PolicyKey<?>> shown) {
        int count = 0;
        for (PolicyKey<?> key : shown) {
            if (working.declares(key)) {
                count++;
            }
        }
        return count;
    }

    /** Whether anything differs from what the editor was opened on. */
    public boolean changed() {
        return !working.equals(original);
    }

    /** The edited set, rows not shown included. */
    public @NotNull PolicySet result() {
        return working;
    }
}
