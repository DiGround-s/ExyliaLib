package net.exylia.lib.ui;

import net.exylia.lib.item.Item;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

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
 * @since 1.22.0
 */
public record UiItem(
        @NotNull Item item,
        @NotNull ClickBindings bindings,
        @Nullable String condition,
        @NotNull List<String> dependencies) {

    public UiItem {
        dependencies = List.copyOf(dependencies);
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
                || condition != null;
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
