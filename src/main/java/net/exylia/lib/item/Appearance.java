package net.exylia.lib.item;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * How an item looks, beyond its name and lore.
 *
 * <p>Grouped together because these travel as a set and none of them depend on
 * who is looking: an appearance is decided when the file is read and applied
 * unchanged to every copy of the item.
 *
 * @param glow           adds the enchantment shimmer without an enchantment in the tooltip
 * @param hideTooltip    hides the tooltip entirely, for decorative slots
 * @param hideAttributes hides everything vanilla adds to the tooltip by itself:
 *                       the damage and speed lines on a tool, and the extra
 *                       block a smithing template, a potion or a firework
 *                       carries
 * @param unbreakable    marks the item unbreakable
 * @param modelData      the custom model data, or {@code -1} for none
 * @param maxStackSize   the stack limit, or {@code -1} to leave the vanilla one
 * @param flags          {@link org.bukkit.inventory.ItemFlag} names to hide
 * @param model          an item model key, {@code namespace:key}, or {@code null}
 * @param tooltipStyle   a tooltip style key, {@code namespace:key}, or {@code null}
 * @since 1.22.0
 */
public record Appearance(
        boolean glow,
        boolean hideTooltip,
        boolean hideAttributes,
        boolean unbreakable,
        int modelData,
        int maxStackSize,
        @NotNull List<String> flags,
        @Nullable String model,
        @Nullable String tooltipStyle) {

    /**
     * An item with nothing special about it.
     *
     * <p>Shared rather than allocated: most items in a menu are this, and a
     * record with nine components is not free to build a few hundred times.
     */
    public static final Appearance PLAIN =
            new Appearance(false, false, false, false, -1, -1, List.of(), null, null);

    public Appearance {
        flags = List.copyOf(flags);
    }

    /** Returns whether this is the default appearance. */
    public boolean isPlain() {
        return equals(PLAIN);
    }

    /** Starts describing an appearance. */
    public static @NotNull Builder builder() {
        return new Builder();
    }

    /** Builds an appearance. */
    public static final class Builder {
        private boolean glow;
        private boolean hideTooltip;
        private boolean hideAttributes;
        private boolean unbreakable;
        private int modelData = -1;
        private int maxStackSize = -1;
        private List<String> flags = List.of();
        private String model;
        private String tooltipStyle;

        private Builder() {
        }

        public @NotNull Builder glow(boolean glow) {
            this.glow = glow;
            return this;
        }

        public @NotNull Builder hideTooltip(boolean hideTooltip) {
            this.hideTooltip = hideTooltip;
            return this;
        }

        public @NotNull Builder hideAttributes(boolean hideAttributes) {
            this.hideAttributes = hideAttributes;
            return this;
        }

        public @NotNull Builder unbreakable(boolean unbreakable) {
            this.unbreakable = unbreakable;
            return this;
        }

        public @NotNull Builder modelData(int modelData) {
            this.modelData = modelData;
            return this;
        }

        public @NotNull Builder maxStackSize(int maxStackSize) {
            this.maxStackSize = maxStackSize;
            return this;
        }

        public @NotNull Builder flags(@NotNull List<String> flags) {
            this.flags = flags;
            return this;
        }

        public @NotNull Builder model(@Nullable String model) {
            this.model = model;
            return this;
        }

        public @NotNull Builder tooltipStyle(@Nullable String tooltipStyle) {
            this.tooltipStyle = tooltipStyle;
            return this;
        }

        /** Builds it, returning the shared {@link #PLAIN} when nothing was set. */
        public @NotNull Appearance build() {
            Appearance built = new Appearance(glow, hideTooltip, hideAttributes, unbreakable,
                    modelData, maxStackSize, flags, model, tooltipStyle);
            return built.equals(PLAIN) ? PLAIN : built;
        }
    }
}
