package net.exylia.lib.ui;

import net.exylia.lib.skull.SkullSource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * What to draw in a slot, before it is drawn for anybody.
 *
 * <p>Deliberately data rather than an {@code ItemStack}: a definition is
 * shared by every player looking at the menu, and turning it into an item is
 * per-viewer work done at render time. Keeping them apart is also what makes
 * the interesting parts — bindings, dependencies, conditions — testable
 * without a running server.
 *
 * @since 1.22.0
 */
public record UiItem(
        @NotNull String material,
        @Nullable String name,
        @NotNull List<String> lore,
        @NotNull String amount,
        boolean glow,
        boolean hideTooltip,
        int customModelData,
        @Nullable SkullSource head,
        @Nullable String headTemplate,
        @NotNull Map<String, Integer> enchantments,
        @NotNull List<String> itemFlags,
        @NotNull ClickBindings bindings,
        @Nullable String condition,
        @NotNull List<String> dependencies,
        @Nullable UiAnimationSpec animation) {

    public UiItem {
        lore = List.copyOf(lore);
        enchantments = Map.copyOf(enchantments);
        itemFlags = List.copyOf(itemFlags);
        dependencies = List.copyOf(dependencies);
    }

    /**
     * Returns whether anything about this item can change while it is shown.
     *
     * <p>A static item is rendered once and never looked at again, which is
     * what makes a menu of decorations free. Only the ones that can change are
     * re-rendered, and only when what they depend on says so.
     */
    public boolean isDynamic() {
        return !dependencies.isEmpty()
                || animation != null
                || headTemplate != null
                || condition != null
                || containsPlaceholder();
    }

    private boolean containsPlaceholder() {
        if (hasPlaceholder(material) || hasPlaceholder(name) || hasPlaceholder(amount)) {
            return true;
        }
        for (String line : lore) {
            if (hasPlaceholder(line)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPlaceholder(String text) {
        return text != null && text.indexOf('%') >= 0;
    }

    /** Starts a definition. */
    public static @NotNull Builder of(@NotNull String material) {
        return new Builder(material);
    }

    /** Builds an item definition. */
    public static final class Builder {
        private final String material;
        private String name;
        private List<String> lore = List.of();
        private String amount = "1";
        private boolean glow;
        private boolean hideTooltip;
        private int customModelData = -1;
        private SkullSource head;
        private String headTemplate;
        private Map<String, Integer> enchantments = Map.of();
        private List<String> itemFlags = List.of();
        private ClickBindings bindings = ClickBindings.none();
        private String condition;
        private List<String> dependencies = List.of();
        private UiAnimationSpec animation;

        private Builder(String material) {
            this.material = material;
        }

        public @NotNull Builder name(@Nullable String name) {
            this.name = name;
            return this;
        }

        public @NotNull Builder lore(@NotNull List<String> lore) {
            this.lore = lore;
            return this;
        }

        public @NotNull Builder amount(@NotNull String amount) {
            this.amount = amount;
            return this;
        }

        public @NotNull Builder glow(boolean glow) {
            this.glow = glow;
            return this;
        }

        public @NotNull Builder hideTooltip(boolean hideTooltip) {
            this.hideTooltip = hideTooltip;
            return this;
        }

        public @NotNull Builder customModelData(int customModelData) {
            this.customModelData = customModelData;
            return this;
        }

        public @NotNull Builder head(@Nullable SkullSource head) {
            this.head = head;
            return this;
        }

        /** A head whose owner is only known when the row is drawn. */
        public @NotNull Builder headTemplate(@Nullable String headTemplate) {
            this.headTemplate = headTemplate;
            return this;
        }

        public @NotNull Builder enchantments(@NotNull Map<String, Integer> enchantments) {
            this.enchantments = enchantments;
            return this;
        }

        public @NotNull Builder itemFlags(@NotNull List<String> itemFlags) {
            this.itemFlags = itemFlags;
            return this;
        }

        public @NotNull Builder bindings(@NotNull ClickBindings bindings) {
            this.bindings = bindings;
            return this;
        }

        public @NotNull Builder condition(@Nullable String condition) {
            this.condition = condition;
            return this;
        }

        /**
         * What this item is derived from, so it can be re-rendered when that
         * changes and left alone when it does not.
         */
        public @NotNull Builder dependsOn(@NotNull List<String> dependencies) {
            this.dependencies = dependencies;
            return this;
        }

        public @NotNull Builder animation(@Nullable UiAnimationSpec animation) {
            this.animation = animation;
            return this;
        }

        public @NotNull UiItem build() {
            return new UiItem(material, name, lore, amount, glow, hideTooltip, customModelData,
                    head, headTemplate, enchantments, itemFlags, bindings, condition,
                    dependencies, animation);
        }
    }
}
