package net.exylia.lib.item;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * An item as configuration describes it, compiled once.
 *
 * <p>Deliberately not an {@code ItemStack}. A definition is shared by every
 * player who sees it, holds its placeholders unresolved, and can be compared,
 * cached and tested without a running server. Turning one into an item is
 * per-viewer work, and {@link PluginItems#render} does it.
 *
 * <p>That split is the whole point of the module: reading a file is expensive
 * and happens once, building an item is cheap and happens constantly, and
 * ExyliaCommons did both together on every render.
 *
 * <pre>{@code
 * // when the config loads
 * Item icon = items.parse(section);
 *
 * // when somebody looks at it
 * ItemStack stack = items.render(icon, player);
 * }</pre>
 *
 * @param source      what object it is
 * @param name        the name shown, in Exylia text notation, or {@code null}
 * @param displayName the name in plain form, for messages and logs, or {@code null}
 * @param lore        the tooltip lines, in Exylia text notation
 * @param amount      the stack size, as text so a placeholder can decide it
 * @param appearance  glow, flags, model and the rest
 * @param enchantments enchantment names to levels
 * @param traits      the parts only some materials have
 * @since 1.22.0
 */
public record Item(
        @NotNull Source source,
        @Nullable String name,
        @Nullable String displayName,
        @NotNull List<String> lore,
        @NotNull String amount,
        @NotNull Appearance appearance,
        @NotNull Map<String, Integer> enchantments,
        @NotNull Traits traits) {

    public Item {
        lore = List.copyOf(lore);
        enchantments = Map.copyOf(enchantments);
    }

    /**
     * Returns whether this item can look different for different players.
     *
     * <p>A static item is rendered once and shared; only the ones that can
     * change are re-rendered, and only when what they depend on says so. This
     * is what makes a menu full of decorations cost nothing to keep open.
     *
     * @return {@code true} when a placeholder, a head template or a dynamic
     *         trait means the result depends on the viewer
     */
    public boolean isDynamic() {
        return source.isDynamic()
                || traits.isDynamic()
                || hasPlaceholder(name)
                || hasPlaceholder(amount)
                || hasPlaceholder(appearance.glow())
                || loreHasPlaceholder();
    }

    /**
     * The name to use where formatting cannot go, such as a chat message about
     * the item.
     *
     * <p>{@code display-name} when the file gives one, and the ordinary name
     * otherwise. They are separate keys because they are separate jobs:
     * {@code name} is what is painted on the item, {@code display-name} is what
     * a plugin says about it, and ExyliaCommons treating the second as a
     * fallback for the first meant a plugin quoting an item back to a player
     * quoted its bold, gradient-filled tooltip name.
     *
     * @return the plain name, or {@code null} when the item has neither
     */
    public @Nullable String label() {
        return displayName != null ? displayName : name;
    }

    private boolean loreHasPlaceholder() {
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

    /**
     * Starts describing an item.
     *
     * @param material what it is, in the {@code material} notation
     * @return a builder
     */
    public static @NotNull Builder of(@NotNull String material) {
        return new Builder(Source.of(material));
    }

    /**
     * Starts describing an item from an already-read source.
     *
     * @param source what it is
     * @return a builder
     */
    public static @NotNull Builder of(@NotNull Source source) {
        return new Builder(source);
    }

    /** Builds an item definition. */
    public static final class Builder {
        private final Source source;
        private String name;
        private String displayName;
        private List<String> lore = List.of();
        private String amount = "1";
        private Appearance appearance = Appearance.PLAIN;
        private Map<String, Integer> enchantments = Map.of();
        private Traits traits = Traits.NONE;

        private Builder(Source source) {
            this.source = source;
        }

        public @NotNull Builder name(@Nullable String name) {
            this.name = name;
            return this;
        }

        public @NotNull Builder displayName(@Nullable String displayName) {
            this.displayName = displayName;
            return this;
        }

        public @NotNull Builder lore(@NotNull List<String> lore) {
            this.lore = lore;
            return this;
        }

        /**
         * Sets the stack size.
         *
         * <p>Text rather than a number so it can be a placeholder: an item
         * showing how many of something a player owns is written
         * {@code amount: "%tokens%"}.
         *
         * @param amount the size, or a placeholder for it
         * @return this builder
         */
        public @NotNull Builder amount(@NotNull String amount) {
            this.amount = amount;
            return this;
        }

        public @NotNull Builder amount(int amount) {
            return amount(String.valueOf(amount));
        }

        public @NotNull Builder appearance(@NotNull Appearance appearance) {
            this.appearance = appearance;
            return this;
        }

        public @NotNull Builder enchantments(@NotNull Map<String, Integer> enchantments) {
            this.enchantments = enchantments;
            return this;
        }

        public @NotNull Builder traits(@NotNull Traits traits) {
            this.traits = traits;
            return this;
        }

        public @NotNull Item build() {
            return new Item(source, name, displayName, lore, amount, appearance,
                    enchantments, traits);
        }
    }
}
