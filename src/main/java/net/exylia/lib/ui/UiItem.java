package net.exylia.lib.ui;

import net.exylia.lib.item.Item;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * A slot in a menu: an item, plus what pressing it does.
 *
 * <p>The two halves are deliberately apart. What the item <em>looks like</em>
 * is an {@link Item}, which the item module owns and four plugins use without
 * ever opening a menu; what it <em>does</em> is here, because clicks,
 * conditions and refresh dependencies mean nothing outside a screen.
 *
 * <p>Kept as data rather than an {@code ItemStack} for the same reason
 * {@link Item} is: a definition is shared by every player looking at the menu,
 * and turning it into an item is per-viewer work done at render time.
 *
 * <p>There is deliberately no per-slot animation. The field existed, no file in
 * the ecosystem has ever written one, and an animation nobody can trigger is
 * worse than none: it reads as supported. A menu animates when it opens, which
 * is what {@code animation} at the root means.
 *
 * @param item         what to draw
 * @param bindings     what each kind of click does
 * @param condition    whether this slot is shown at all, or {@code null} for always
 * @param dependencies what this slot is derived from, so it can be redrawn when
 *                     that changes and left alone when it does not
 * @param alternates   what to draw in the same slot instead when this one's
 *                     condition fails, in the order the file wrote them
 * @since 1.22.0
 */
public record UiItem(
        @NotNull Item item,
        @NotNull ClickBindings bindings,
        @Nullable String condition,
        @NotNull List<String> dependencies,
        @NotNull List<UiItem> alternates) {

    public UiItem {
        dependencies = List.copyOf(dependencies);
        alternates = List.copyOf(alternates);
    }

    /** A slot with no alternates, which is what most slots are. */
    public UiItem(@NotNull Item item,
                  @NotNull ClickBindings bindings,
                  @Nullable String condition,
                  @NotNull List<String> dependencies) {
        this(item, bindings, condition, dependencies, List.of());
    }

    /**
     * Returns this slot with another item to fall back on.
     *
     * <p>Two entries claiming one slot are the same button in two states — a
     * "create" that becomes a "renew" — and only one of them passes its
     * condition at a time. They are kept in file order and the first one that
     * passes is drawn, so the file reads the way it renders.
     *
     * @param alternate what to draw instead when this one is hidden
     */
    public @NotNull UiItem withAlternate(@NotNull UiItem alternate) {
        List<UiItem> chain = new ArrayList<>(alternates);
        chain.add(alternate);
        return new UiItem(item, bindings, condition, dependencies, chain);
    }

    /**
     * The first of this slot's states whose condition passes.
     *
     * @param passes how a condition is decided
     * @return the item to draw, or empty when every state is hidden
     */
    public @NotNull Optional<UiItem> visible(@NotNull Predicate<UiItem> passes) {
        if (passes.test(this)) {
            return Optional.of(this);
        }
        for (UiItem alternate : alternates) {
            if (passes.test(alternate)) {
                return Optional.of(alternate);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns whether anything about this slot can change while it is shown.
     *
     * <p>A static slot is rendered once and never looked at again, which is
     * what makes a menu of decorations free. Only the ones that can change are
     * re-rendered, and only when what they depend on says so.
     */
    public boolean isDynamic() {
        return item.isDynamic()
                || !dependencies.isEmpty()
                || condition != null
                || !alternates.isEmpty();
    }

    /** Starts describing a slot. */
    public static @NotNull Builder of(@NotNull Item item) {
        return new Builder(item);
    }

    /** Builds a slot. */
    public static final class Builder {
        private final Item item;
        private ClickBindings bindings = ClickBindings.none();
        private String condition;
        private List<String> dependencies = List.of();

        private Builder(Item item) {
            this.item = item;
        }

        public @NotNull Builder bindings(@NotNull ClickBindings bindings) {
            this.bindings = bindings;
            return this;
        }

        public @NotNull Builder condition(@Nullable String condition) {
            this.condition = condition;
            return this;
        }

        public @NotNull Builder dependsOn(@NotNull List<String> dependencies) {
            this.dependencies = dependencies;
            return this;
        }

        public @NotNull UiItem build() {
            return new UiItem(item, bindings, condition, dependencies);
        }
    }
}
