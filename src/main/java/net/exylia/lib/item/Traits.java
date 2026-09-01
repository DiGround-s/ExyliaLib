package net.exylia.lib.item;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * The parts of an item only some materials have.
 *
 * <p>Separate from {@link Item} on purpose. A potion's contents mean nothing on
 * a sword, a trim means nothing on anything that is not armour, and across the
 * whole ecosystem fewer than twenty configured items use any of this at all.
 * Keeping them here means the other few thousand carry one shared reference —
 * {@link #NONE} — instead of six null fields each.
 *
 * <p>It is also where growth goes: a trait added later widens this record, not
 * the one every menu icon is built from.
 *
 * @param potion     what is in the bottle, or {@code null}
 * @param trim       the armour trim, or {@code null}
 * @param banner     the banner design, or {@code null}
 * @param dye        the colour leather is dyed, as {@code #rrggbb} or a
 *                   colour name, or {@code null}
 * @param consumable what makes it edible, or {@code null}
 * @param modifiers  attribute modifiers, empty when there are none
 * @param data       persistent values written onto the item, empty when there are none
 * @since 1.22.0
 */
public record Traits(
        @Nullable Potion potion,
        @Nullable Trim trim,
        @Nullable Banner banner,
        @Nullable String dye,
        @Nullable Consumable consumable,
        @NotNull List<Modifier> modifiers,
        @NotNull Map<String, String> data) {

    /** An item with nothing unusual about it, which is nearly all of them. */
    public static final Traits NONE =
            new Traits(null, null, null, null, null, List.of(), Map.of());

    public Traits {
        modifiers = List.copyOf(modifiers);
        data = Map.copyOf(data);
    }

    /** Returns whether there is anything here to apply. */
    public boolean isEmpty() {
        return potion == null && trim == null && banner == null && dye == null
                && consumable == null && modifiers.isEmpty() && data.isEmpty();
    }

    /**
     * Returns whether any trait has to be resolved per viewer.
     *
     * <p>Trims and banners can: a potion's amplifier may hold a placeholder,
     * but the effect list is resolved at build time either way.
     *
     * <p>A banner that answers yes here and is not asked would be worse than
     * one that is not drawn at all: {@code ItemCache} would keep the first
     * viewer's design and hand it to everyone, so a menu of twenty different
     * shields would show the same one twenty times.
     */
    public boolean isDynamic() {
        return (trim != null && trim.isDynamic())
                || (banner != null && banner.isDynamic())
                || (dye != null && dye.indexOf('%') >= 0);
    }

    /** Starts describing traits. */
    public static @NotNull Builder builder() {
        return new Builder();
    }

    /** Builds a set of traits. */
    public static final class Builder {
        private Potion potion;
        private Trim trim;
        private Banner banner;
        private String dye;
        private Consumable consumable;
        private List<Modifier> modifiers = List.of();
        private Map<String, String> data = Map.of();

        private Builder() {
        }

        public @NotNull Builder potion(@Nullable Potion potion) {
            this.potion = potion;
            return this;
        }

        public @NotNull Builder trim(@Nullable Trim trim) {
            this.trim = trim;
            return this;
        }

        public @NotNull Builder banner(@Nullable Banner banner) {
            this.banner = banner;
            return this;
        }

        /**
         * The colour leather is dyed.
         *
         * @param dye {@code #rrggbb}, a colour name, or a placeholder for
         *            either; {@code null} leaves the leather its own brown
         * @return this builder
         */
        public @NotNull Builder dye(@Nullable String dye) {
            this.dye = dye;
            return this;
        }

        public @NotNull Builder consumable(@Nullable Consumable consumable) {
            this.consumable = consumable;
            return this;
        }

        public @NotNull Builder modifiers(@NotNull List<Modifier> modifiers) {
            this.modifiers = modifiers;
            return this;
        }

        /**
         * Values stored on the item and readable later.
         *
         * <p>Written under the owning plugin's namespace, which is why
         * {@link Items} is obtained per plugin: two plugins writing
         * {@code id} onto an item must not collide.
         *
         * @param data the values
         * @return this builder
         */
        public @NotNull Builder data(@NotNull Map<String, String> data) {
            this.data = data;
            return this;
        }

        /** Builds them, returning the shared {@link #NONE} when there is nothing. */
        public @NotNull Traits build() {
            if (potion == null && trim == null && banner == null && dye == null
                    && consumable == null && modifiers.isEmpty() && data.isEmpty()) {
                return NONE;
            }
            return new Traits(potion, trim, banner, dye, consumable, modifiers, data);
        }
    }
}
