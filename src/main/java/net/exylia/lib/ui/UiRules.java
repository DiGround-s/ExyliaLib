package net.exylia.lib.ui;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What a file says about a slot beyond its look, in the words screens use: when it shows and what must hold before a
 * click runs, as screen expressions ({@code perm('vip') and balance() >= 100}), and what a click does, as interaction
 * lines ({@code right: [MESSAGE] Hello}).
 *
 * @param when  the slot's {@code if}: it is only there while this holds, or {@code null} for always
 * @param click the slot's {@code click} interaction lines
 * @param every the gate on every click, or {@code null}
 * @param gates the gates on one kind of click, after {@code every}
 * @since 1.548.0
 */
public record UiRules(@Nullable String when, @NotNull List<String> click, @Nullable Gate every,
                      @NotNull Map<ClickKind, Gate> gates) {

    /** A slot the file says nothing more about. */
    public static final UiRules NONE = new UiRules(null, List.of(), null, Map.of());

    public UiRules {
        click = List.copyOf(click);
        gates = Map.copyOf(gates);
    }

    /** The gates {@code kind} has to pass, in order. */
    public @NotNull List<Gate> gatesFor(@NotNull ClickKind kind) {
        List<Gate> out = new ArrayList<>(2);
        if (every != null) out.add(every);
        Gate own = gates.get(kind);
        if (own != null) out.add(own);
        return out;
    }

    /**
     * What must hold before a click runs.
     *
     * @param require a screen expression
     * @param deny    interaction lines run when it does not hold
     */
    public record Gate(@NotNull String require, @NotNull List<String> deny) {
        public Gate {
            deny = List.copyOf(deny);
        }
    }
}
