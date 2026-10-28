package net.exylia.lib.ability.internal;

import net.exylia.lib.ability.Ability;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Stops an ability's own damage from firing ATTACK and HIT_TAKEN again.
 *
 * <p>Per thread, because a proc and the damage events it causes run on the same
 * thread (the region's, on Folia). Shared by every plugin's abilities: one
 * plugin's enchant damage does not set off another's either. Even with
 * {@code chain: true}, nesting stops at {@link #MAX_DEPTH}.
 */
public final class ChainGuard {

    /** How deep chained abilities may nest, {@code chain: true} included. */
    public static final int MAX_DEPTH = 3;

    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    private ChainGuard() {
        throw new AssertionError("No instances.");
    }

    /** Runs something on behalf of an ability. */
    public static void run(@Nullable Ability ability, @NotNull Runnable task) {
        State state = STATE.get();
        Ability previous = state.current;
        state.current = ability;
        state.depth++;
        try {
            task.run();
        } finally {
            state.depth--;
            state.current = previous;
        }
    }

    /** Whether ATTACK and HIT_TAKEN must not fire on this thread right now. */
    public static boolean blocksDamageTriggers() {
        State state = STATE.get();
        if (state.depth == 0) return false;
        return state.current == null || !state.current.chain() || state.depth >= MAX_DEPTH;
    }

    private static final class State {
        private @Nullable Ability current;
        private int depth;
    }
}
