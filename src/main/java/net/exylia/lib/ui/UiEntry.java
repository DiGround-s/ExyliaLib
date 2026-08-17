package net.exylia.lib.ui;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * One row of a paginated list.
 *
 * <p>Three things travel together: the values that fill the template's
 * placeholders, which template to use, and what the row is actually
 * <em>about</em>.
 *
 * <pre>{@code
 * List<UiEntry> rows = new ArrayList<>();
 * for (Kit kit : kits) {
 *     rows.add(UiEntry.of(kit)
 *             .with("kit_name", kit.name())
 *             .with("kit_icon", kit.icon())
 *             .template(kit.equals(selected) ? "selected" : "not_selected"));
 * }
 * session.entries("kits", rows);
 * }</pre>
 *
 * <p>That last part is the one ExyliaCommons lacked. A handler that needed to
 * know which kit was clicked had to work it back out from the item it was drawn
 * as, which is why menus kept static maps keyed by player — and why two menus
 * open at once could hand the wrong answer to the wrong click. Here the value is
 * on the row, and a click reads it through {@link UiKeys#ENTRY}.
 *
 * @param value    what the row is about, or {@code null} when it is only text
 * @param values   what fills the template's placeholders
 * @param template which template to draw it with, or {@code null} for the default
 * @param item     an item to draw as-is, instead of a template
 * @since 1.22.0
 */
public record UiEntry(
        @Nullable Object value,
        @NotNull Map<String, String> values,
        @Nullable String template,
        @Nullable org.bukkit.inventory.ItemStack item) {

    public UiEntry {
        // Ordered rather than Map.copyOf: substitution walks these in turn, so
        // a caller adding "%rank%" before "%rank_name%" should see them applied
        // in that order. An unordered copy makes that depend on hash codes.
        values = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(values));
        // Copied, because an ItemStack is mutable and the caller still holds
        // theirs: a kit room handing out its own stored stacks must not have
        // them renamed by whoever drew the row.
        item = item == null ? null : item.clone();
    }

    /**
     * Returns whether this row brings its own item rather than a template.
     *
     * <p>A kit room lists the stacks it stores; there is no template that could
     * describe an arbitrary saved item, and pretending otherwise would mean
     * writing one out to configuration and reading it back.
     */
    public boolean hasItem() {
        return item != null;
    }

    /**
     * Starts a row about something.
     *
     * @param value what the row is about
     * @return a builder
     */
    public static @NotNull Builder of(@Nullable Object value) {
        return new Builder(value);
    }

    /**
     * Starts a row that is only text.
     *
     * @return a builder
     */
    public static @NotNull Builder row() {
        return new Builder(null);
    }

    /** Builds a row. */
    public static final class Builder {
        private final Object value;
        private final java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
        private String template;
        private org.bukkit.inventory.ItemStack item;

        private Builder(Object value) {
            this.value = value;
        }

        /**
         * Sets a placeholder value for this row.
         *
         * <p>Written without percent signs: {@code with("kit_name", ...)} fills
         * {@code %kit_name%} in the template.
         *
         * @param name  the placeholder name
         * @param value what it resolves to; {@code null} becomes empty
         * @return this builder
         */
        public @NotNull Builder with(@NotNull String name, @Nullable Object value) {
            values.put(strip(name), value == null ? "" : String.valueOf(value));
            return this;
        }

        /**
         * Chooses which template draws this row.
         *
         * @param template the template name, or {@code null} for the default
         * @return this builder
         */
        public @NotNull Builder template(@Nullable String template) {
            this.template = template;
            return this;
        }

        /**
         * Draws this row as a given item, ignoring the section's templates.
         *
         * <p>For a list of items a plugin already holds — a kit room, a
         * preview of somebody's inventory — where no template could describe
         * them.
         *
         * @param item what to draw
         * @return this builder
         */
        public @NotNull Builder item(@Nullable org.bukkit.inventory.ItemStack item) {
            this.item = item;
            return this;
        }

        public @NotNull UiEntry build() {
            return new UiEntry(value, values, template, item);
        }

        /** Accepts a name written either way, since both spellings are natural. */
        private static String strip(String name) {
            String trimmed = name.trim();
            if (trimmed.length() > 2 && trimmed.startsWith("%") && trimmed.endsWith("%")) {
                return trimmed.substring(1, trimmed.length() - 1);
            }
            return trimmed;
        }
    }

    /**
     * Reads the value this row is about.
     *
     * @param type what it should be
     * @param <T>  that type
     * @return the value, or empty when there is none or it is something else
     */
    public <T> @NotNull java.util.Optional<T> value(@NotNull Class<T> type) {
        return type.isInstance(value)
                ? java.util.Optional.of(type.cast(value))
                : java.util.Optional.empty();
    }
}
