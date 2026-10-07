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
 * <p>A value handed over as a lambda is live: a timed redraw reads it again
 * and redraws the row only when it came out different. Anything that moves
 * while the menu is open — a countdown, a stock, a player count — should be
 * written that way, so the menu shows it as it is rather than as it was when
 * it opened:
 *
 * <pre>{@code
 * UiEntry.of(mine)
 *         .with("mine_name", mine.name())                  // read once
 *         .withFormatted("next_reset", () -> resets.left(mine)) // read on every redraw
 * }</pre>
 *
 * <p>That last part is the one ExyliaCommons lacked. A handler that needed to
 * know which kit was clicked had to work it back out from the item it was drawn
 * as, which is why menus kept static maps keyed by player — and why two menus
 * open at once could hand the wrong answer to the wrong click. Here the value is
 * on the row, and a click reads it through {@link UiKeys#ENTRY}.
 *
 * @param value     what the row is about, or {@code null} when it is only text
 * @param values    what fills the template's placeholders
 * @param formatted which of those values carry their own formatting
 * @param verbatim  which of the formatted ones keep their own letters
 * @param template  which template to draw it with, or {@code null} for the default
 * @param item      an item to draw as-is, instead of a template
 * @param live      values read again on every timed redraw, by placeholder name
 * @since 1.22.0
 */
public record UiEntry(
        @Nullable Object value,
        @NotNull Map<String, String> values,
        @NotNull java.util.Set<String> formatted,
        @NotNull java.util.Set<String> verbatim,
        @Nullable String template,
        @Nullable org.bukkit.inventory.ItemStack item,
        @NotNull Map<String, java.util.function.Supplier<?>> live) {

    public UiEntry {
        // Ordered rather than Map.copyOf: substitution walks these in turn, so
        // a caller adding "%rank%" before "%rank_name%" should see them applied
        // in that order. An unordered copy makes that depend on hash codes.
        values = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(values));
        formatted = java.util.Set.copyOf(formatted);
        verbatim = java.util.Set.copyOf(verbatim);
        // Copied, because an ItemStack is mutable and the caller still holds
        // theirs: a kit room handing out its own stored stacks must not have
        // them renamed by whoever drew the row.
        item = item == null ? null : item.clone();
        live = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(live));
    }

    /**
     * A row with nothing read again on a redraw.
     *
     * <p>Kept so code written before {@link Builder#live} still compiles.
     *
     * @param value     what the row is about
     * @param values    what fills the template's placeholders
     * @param formatted which of those carry their own formatting
     * @param verbatim  which of the formatted ones keep their own letters
     * @param template  which template to draw it with
     * @param item      an item to draw as-is
     */
    public UiEntry(@Nullable Object value, @NotNull Map<String, String> values,
                   @NotNull java.util.Set<String> formatted, @NotNull java.util.Set<String> verbatim,
                   @Nullable String template, @Nullable org.bukkit.inventory.ItemStack item) {
        this(value, values, formatted, verbatim, template, item, Map.of());
    }

    /**
     * A row whose values are all literal.
     *
     * <p>Kept so code written before rows could carry formatting still
     * compiles: {@link Builder#withFormatted} is the way to ask for the other
     * kind, and nothing that never asked has to say so.
     *
     * @param value    what the row is about
     * @param values   what fills the template's placeholders
     * @param template which template to draw it with
     * @param item     an item to draw as-is
     */
    public UiEntry(@Nullable Object value, @NotNull Map<String, String> values,
                   @Nullable String template, @Nullable org.bukkit.inventory.ItemStack item) {
        this(value, values, java.util.Set.of(), java.util.Set.of(), template, item);
    }

    /**
     * A row with formatted values but none that keep their own letters.
     *
     * <p>Kept so code written before {@link Builder#withVerbatim} still
     * compiles.
     *
     * @param value     what the row is about
     * @param values    what fills the template's placeholders
     * @param formatted which of those carry their own formatting
     * @param template  which template to draw it with
     * @param item      an item to draw as-is
     */
    public UiEntry(@Nullable Object value, @NotNull Map<String, String> values,
                   @NotNull java.util.Set<String> formatted, @Nullable String template,
                   @Nullable org.bukkit.inventory.ItemStack item) {
        this(value, values, formatted, java.util.Set.of(), template, item);
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

    /** Returns whether this row has values that a timed redraw reads again. */
    public boolean isLive() {
        return !live.isEmpty();
    }

    /**
     * This row with its live values read again.
     *
     * <p>Returns this very row when none of them moved, which is how a timed
     * redraw knows to leave the slot alone: a countdown on one row of forty
     * re-renders one item, not forty.
     *
     * @return the row as it is now
     */
    public @NotNull UiEntry refreshed() {
        Map<String, String> now = null;
        for (Map.Entry<String, java.util.function.Supplier<?>> source : live.entrySet()) {
            String read = Builder.text(source.getValue().get());
            if (read.equals(values.get(source.getKey()))) {
                continue;
            }
            if (now == null) {
                now = new java.util.LinkedHashMap<>(values);
            }
            now.put(source.getKey(), read);
        }
        return now == null ? this : new UiEntry(value, now, formatted, verbatim, template, item, live);
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
        private final java.util.Set<String> formatted = new java.util.LinkedHashSet<>();
        private final java.util.Set<String> verbatim = new java.util.LinkedHashSet<>();
        private String template;
        private org.bukkit.inventory.ItemStack item;
        private final java.util.Map<String, java.util.function.Supplier<?>> live = new java.util.LinkedHashMap<>();

        private Builder(Object value) {
            this.value = value;
        }

        /**
         * Sets a placeholder value for this row.
         *
         * <p>Written without percent signs: {@code with("kit_name", ...)} fills
         * {@code %kit_name%} in the template.
         *
         * <p>Inserted as literal text. A kit somebody named {@code {error}X}
         * shows those characters rather than recolouring the row, which is why
         * this is the default and {@link #withFormatted} has to be asked for.
         *
         * @param name  the placeholder name
         * @param value what it resolves to; {@code null} becomes empty
         * @return this builder
         */
        public @NotNull Builder with(@NotNull String name, @Nullable Object value) {
            if (value instanceof java.util.function.Supplier<?> reader) {
                // A lambda that reached here typed as an object is still a
                // lambda: written out it would read "Lambda$12@3f2a".
                return with(name, reader);
            }
            String key = strip(name);
            values.put(key, text(value));
            formatted.remove(key);
            verbatim.remove(key);
            live.remove(key);
            return this;
        }

        /**
         * Sets a value that carries its own formatting.
         *
         * <p>For values that come from configuration and say what they look
         * like — a rank shown as {@code {highlight}&lMVP}. The value is parsed
         * the same way the template is, so its colours are honoured.
         *
         * <p>Only for values the server owner wrote. Anything a player typed
         * goes through {@link #with}: a formatted value can recolour the rest
         * of the line, and a name is data rather than formatting.
         *
         * @param name  the placeholder name
         * @param value what it resolves to; {@code null} becomes empty
         * @return this builder
         * @since 1.28.0
         */
        public @NotNull Builder withFormatted(@NotNull String name, @Nullable Object value) {
            if (value instanceof java.util.function.Supplier<?> reader) {
                // A lambda that reached here typed as an object is still a
                // lambda: written out it would read "Lambda$12@3f2a".
                return withFormatted(name, reader);
            }
            String key = strip(name);
            values.put(key, text(value));
            formatted.add(key);
            verbatim.remove(key);
            live.remove(key);
            return this;
        }

        /**
         * Sets a formatted value that keeps its own letters.
         *
         * <p>{@link #withFormatted} for a value that is somebody's words
         * rather than the server's: a chat line drawn into a row's lore, a
         * tag a player wrote for themselves. Its colours are honoured and the
         * small-capitals look is not applied to it, while the lore around it
         * — which the server did write — keeps whatever {@code small-text}
         * says.
         *
         * @param name  the placeholder name
         * @param value what it resolves to; {@code null} becomes empty
         * @return this builder
         */
        public @NotNull Builder withVerbatim(@NotNull String name, @Nullable Object value) {
            if (value instanceof java.util.function.Supplier<?> reader) {
                // A lambda that reached here typed as an object is still a
                // lambda: written out it would read "Lambda$12@3f2a".
                return withVerbatim(name, reader);
            }
            String key = strip(name);
            values.put(key, text(value));
            formatted.add(key);
            verbatim.add(key);
            live.remove(key);
            return this;
        }

        /**
         * Sets a value that is read again on every timed redraw.
         *
         * <p>{@link #with(String, Object)} for what moves while the menu is
         * open — a countdown, a stock, a player count. A row whose live values
         * read the same is not redrawn, so a quiet row costs one call to the
         * lambda per redraw and nothing else. A menu holding a live value
         * redraws every second on its own unless its file says otherwise.
         *
         * <p>Inserted as literal text, like {@link #with(String, Object)}.
         * Called on the viewer's thread, so it has to be cheap and must not
         * block: read what is already in memory, never a database.
         *
         * <pre>{@code
         * UiEntry.of(mine).with("next_reset", () -> resets.timeLeft(mine))
         * }</pre>
         *
         * @param name  the placeholder name
         * @param value reads what it resolves to; {@code null} sets an empty value
         * @return this builder
         * @since 1.254.0
         */
        public @NotNull Builder with(@NotNull String name, @Nullable java.util.function.Supplier<?> value) {
            // A null here is somebody's with(name, null), which the overload
            // caught: it meant an empty value, as it always has.
            if (value == null) {
                return with(name, (Object) null);
            }
            with(name, value.get());
            live.put(strip(name), value);
            return this;
        }

        /**
         * {@link #with(String, java.util.function.Supplier)} for a value that
         * carries its own formatting, as {@link #withFormatted(String, Object)}
         * is to {@link #with(String, Object)}.
         *
         * @param name  the placeholder name
         * @param value reads what it resolves to; {@code null} sets an empty value
         * @return this builder
         * @since 1.254.0
         */
        public @NotNull Builder withFormatted(@NotNull String name,
                                              @Nullable java.util.function.Supplier<?> value) {
            if (value == null) {
                return withFormatted(name, (Object) null);
            }
            withFormatted(name, value.get());
            live.put(strip(name), value);
            return this;
        }

        /**
         * {@link #with(String, java.util.function.Supplier)} for a formatted
         * value that keeps its own letters, as
         * {@link #withVerbatim(String, Object)} is to
         * {@link #withFormatted(String, Object)}.
         *
         * @param name  the placeholder name
         * @param value reads what it resolves to; {@code null} sets an empty value
         * @return this builder
         * @since 1.254.0
         */
        public @NotNull Builder withVerbatim(@NotNull String name,
                                             @Nullable java.util.function.Supplier<?> value) {
            if (value == null) {
                return withVerbatim(name, (Object) null);
            }
            withVerbatim(name, value.get());
            live.put(strip(name), value);
            return this;
        }

        /**
         * The same as {@link #with(String, java.util.function.Supplier)}.
         *
         * @param name  the placeholder name
         * @param value reads what it resolves to
         * @return this builder
         * @since 1.242.0
         */
        public @NotNull Builder live(@NotNull String name, @NotNull java.util.function.Supplier<?> value) {
            return with(name, value);
        }

        /**
         * The same as {@link #withFormatted(String, java.util.function.Supplier)}.
         *
         * @param name  the placeholder name
         * @param value reads what it resolves to
         * @return this builder
         * @since 1.242.0
         */
        public @NotNull Builder liveFormatted(@NotNull String name, @NotNull java.util.function.Supplier<?> value) {
            return withFormatted(name, value);
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
            return new UiEntry(value, values, formatted, verbatim, template, item, live);
        }

        private static String text(Object value) {
            return value == null ? "" : String.valueOf(value);
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
