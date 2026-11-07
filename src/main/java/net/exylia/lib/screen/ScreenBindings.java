package net.exylia.lib.screen;

import net.exylia.lib.screen.internal.MarkupRender;
import net.exylia.lib.ui.UiEntry;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Sources, values and actions every screen of a {@link ScreenSet} reads: registered once for the whole folder
 * rather than on each {@link ScreenMarkup}. A screen's own registration of the same name wins.
 *
 * <pre>{@code
 * screens.bindings()
 *         .value("coins", session -> economy.balance(session.viewer()))
 *         .action("queue", (event, args) -> queues.join(event.viewer(), args.get(0)));
 * }</pre>
 *
 * @since 1.496.0
 */
public final class ScreenBindings {

    private final MarkupRender.Bindings shared = MarkupRender.Bindings.create();
    private final ScreenSet set;

    ScreenBindings(ScreenSet set) {
        this.set = set;
    }

    /** As {@link ScreenMarkup#source}, for every screen of the set. */
    public @NotNull ScreenBindings source(@NotNull String name,
                                         @NotNull Function<ScreenSession, List<? extends Map<String, ?>>> rows) {
        shared.sources().put(name, rows);
        return spread();
    }

    /** As {@link ScreenMarkup#rows}, for every screen of the set. */
    public @NotNull ScreenBindings rows(@NotNull String name, @NotNull Function<ScreenSession, List<UiEntry>> rows) {
        shared.entries().put(name, rows);
        return spread();
    }

    /** As {@link ScreenMarkup#value}, for every screen of the set. */
    public @NotNull ScreenBindings value(@NotNull String name, @NotNull Function<ScreenSession, ?> value) {
        shared.values().put(name, value);
        return spread();
    }

    /** As {@link ScreenMarkup#text}, for every screen of the set. */
    public @NotNull ScreenBindings text(@NotNull String name, @NotNull Function<ScreenSession, ?> value) {
        shared.texts().put(name, value);
        return spread();
    }

    /** As {@link ScreenMarkup#action}, for every screen of the set. */
    public @NotNull ScreenBindings action(@NotNull String name, @NotNull BiConsumer<ScreenEvent, List<String>> handler) {
        shared.actions().put(name, handler);
        return spread();
    }

    private ScreenBindings spread() {
        for (ScreenMarkup markup : set.markups().values()) markup.share(shared);
        return this;
    }

    /** Hands the shared names to a screen read later. */
    void into(ScreenMarkup markup) {
        markup.share(shared);
    }
}
