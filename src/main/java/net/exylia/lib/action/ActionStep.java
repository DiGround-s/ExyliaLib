package net.exylia.lib.action;

import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * One compiled call in a sequence, optionally delayed and with typed values
 * bound for that step.
 *
 * <p>The bindings are how a structured item config gives each step its own
 * record without a string-keyed map:
 *
 * <pre>{@code
 * sequence.then("specials:damage", 0, ITEM_CONFIG, damageConfig);
 * }</pre>
 *
 * @param call       the already-resolved action
 * @param delayTicks ticks to wait before it, zero for immediate
 * @param bindings   typed values written to the shared scope before execution
 * @since 1.20.0
 */
public record ActionStep(@NotNull ActionCall call, long delayTicks,
                         @NotNull Map<ActionKey<?>, Object> bindings) {
    public ActionStep {
        if (delayTicks < 0) throw new IllegalArgumentException("Action delay cannot be negative");
        bindings = Map.copyOf(bindings);
    }

    public ActionStep(@NotNull ActionCall call, long delayTicks) {
        this(call, delayTicks, Map.of());
    }

    /** Applies this step's typed config without copying the whole context. */
    void bind(ActionScope scope) {
        bindings.forEach(scope::setUnchecked);
    }
}
