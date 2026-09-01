package net.exylia.lib.input;

import net.exylia.lib.input.internal.TransportKind;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * A typed searchable choice.
 *
 * <p>At open time this request derives and stores one normalized search string
 * per element. A transport filtering on each keystroke reads that snapshot
 * rather than rerunning labels or arbitrary caller functions for every keypress.
 *
 * @param <T> option type
 *
 * @since 1.31.0
 */
public final class SearchInput<T> extends InputRequest<T, SearchInput<T>> {

    private final List<T> choices;
    private Function<T, String> label = String::valueOf;
    private Function<T, String> key;
    private Function<T, Material> icon = ignored -> Material.PAPER;
    private BiPredicate<T, String> matcher;
    private Function<T, ItemStack> item;
    private Pages<T> source;
    private int pageSize = 45;
    private volatile SearchIndex<T> index;

    SearchInput(String pluginName, Player player, String prompt, Collection<T> choices) {
        super(pluginName, player, prompt, raw -> InputParser.Parsed.rejected("Choose one of the available options."));
        if (choices == null || choices.isEmpty()) {
            throw new InputException("choices must not be empty");
        }
        List<T> copy = new ArrayList<>(choices.size());
        for (T choice : choices) {
            copy.add(Inputs.require(choice, "choice"));
        }
        this.choices = List.copyOf(copy);
    }

    /**
     * A request with no snapshot, answered by a {@link #source(Pages) source}.
     *
     * <p>The collection is empty on purpose: a catalogue of eighty thousand
     * options is not a list a server holds in memory to filter it.
     */
    SearchInput(String pluginName, Player player, String prompt) {
        super(pluginName, player, prompt, raw -> InputParser.Parsed.rejected("Choose one of the available options."));
        this.choices = List.of();
    }

    /** Sets the text players see and the default string precomputed for search. */
    public @NotNull SearchInput<T> label(@NotNull Function<T, String> label) {
        this.label = Inputs.require(label, "label");
        this.index = null;
        return this;
    }

    /** Sets unique stable keys submitted by transports. */
    public @NotNull SearchInput<T> key(@NotNull Function<T, String> key) {
        this.key = Inputs.require(key, "key");
        this.index = null;
        return this;
    }

    /** Sets the material shown beside each result. */
    public @NotNull SearchInput<T> icon(@NotNull Function<T, Material> icon) {
        this.icon = Inputs.require(icon, "icon");
        return this;
    }

    /**
     * Sets the item shown for each result, when a material is not enough.
     *
     * <p>A catalogue of heads is the case this exists for: every row is a
     * {@code PLAYER_HEAD}, so a material tells the player nothing and the
     * texture tells them everything. The stack is used as the base and the
     * name and lore are written onto a copy of it.
     *
     * @param item builds the stack for an option
     * @return this builder
     */
    public @NotNull SearchInput<T> iconItem(@NotNull Function<T, ItemStack> item) {
        this.item = Inputs.require(item, "item");
        return this;
    }

    /**
     * Answers this request from somewhere else, one page at a time.
     *
     * <p>For collections too large to hold: nothing is loaded when the request
     * opens, and only the page on screen is ever in memory. The query the
     * player typed is passed through untouched, so the source — a database, an
     * HTTP API — does the matching it is better at than a string scan.
     *
     * <p>Pins the request to the anvil search, which is the only transport that
     * can page: a chat prompt has no page to turn.
     *
     * @param source where pages come from
     * @return this builder
     */
    public @NotNull SearchInput<T> source(@NotNull Pages<T> source) {
        this.source = Inputs.require(source, "source");
        return transports(TransportKind.ANVIL_SEARCH);
    }

    /** Sets the positive maximum number of results shown on one page. */
    public @NotNull SearchInput<T> pageSize(int pageSize) {
        if (pageSize <= 0) {
            throw new InputException("pageSize must be positive");
        }
        this.pageSize = pageSize;
        return this;
    }

    /**
     * Overrides matching for domain-specific searches.
     *
     * <p>The query supplied to the predicate is normalized once by the transport;
     * the predicate is responsible for any element-side strategy it needs.
     */
    public @NotNull SearchInput<T> matcher(@NotNull BiPredicate<T, String> matcher) {
        this.matcher = Inputs.require(matcher, "matcher");
        return this;
    }

    /** Resolves the selected key to its actual element. */
    @Override
    public @NotNull InputParser.Parsed<T> parseRaw(@NotNull String raw) {
        if (raw == null) {
            return InputParser.Parsed.rejected("Choose one of the available options.");
        }
        final String key;
        try {
            String transformed = transform().apply(raw.trim());
            if (transformed == null) {
                return InputParser.Parsed.rejected("Choose one of the available options.");
            }
            key = ChoiceInput.normalizeKey(transformed);
        } catch (RuntimeException invalid) {
            return InputParser.Parsed.rejected("Choose one of the available options.");
        }
        T value = ensureIndex().byKey().get(key);
        if (value == null) {
            return InputParser.Parsed.rejected("Choose one of the available options.");
        }
        Validation validation = validate(value);
        return validation.valid() ? InputParser.Parsed.of(value)
                : InputParser.Parsed.rejected(validation.messages().getFirst());
    }

    @Override
    protected void beforeOpen() {
        if (source == null) {
            index = buildIndex();
        }
    }

    /** Filters the precomputed open-time snapshot in collection order. */
    @ApiStatus.Internal
    public @NotNull List<T> search(@NotNull String query) {
        String normalized = Inputs.require(query, "query").trim().toLowerCase(Locale.ROOT);
        SearchIndex<T> snapshot = ensureIndex();
        List<T> matches = new ArrayList<>();
        for (SearchEntry<T> entry : snapshot.entries()) {
            boolean accepted = matcher != null
                    ? matcher.test(entry.value(), normalized)
                    : entry.normalizedSearch().contains(normalized);
            if (accepted) {
                matches.add(entry.value());
            }
        }
        return List.copyOf(matches);
    }

    /** Immutable options in display order. */
    @ApiStatus.Internal
    public @NotNull List<T> choices() {
        return choices;
    }

    /** Where pages come from, or {@code null} when this request holds its own. */
    @ApiStatus.Internal
    public @Nullable Pages<T> source() {
        return source;
    }

    /** Accepts an option a paged source supplied, which no key can resolve. */
    @ApiStatus.Internal
    public @NotNull InputParser.Parsed<T> parseValue(@NotNull T value) {
        Validation validation = validate(Inputs.require(value, "value"));
        return validation.valid() ? InputParser.Parsed.of(value)
                : InputParser.Parsed.rejected(validation.messages().getFirst());
    }

    /** Returns an option's own stack, or {@code null} to draw its material. */
    @ApiStatus.Internal
    public @Nullable ItemStack itemOf(@NotNull T choice) {
        return item == null ? null : item.apply(Inputs.require(choice, "choice"));
    }

    /** Returns an option's display label. */
    @ApiStatus.Internal
    public @NotNull String labelOf(@NotNull T choice) {
        return ChoiceInput.requireDerived(label.apply(Inputs.require(choice, "choice")), "choice label");
    }

    /** Returns an option's normalized stable key. */
    @ApiStatus.Internal
    public @NotNull String keyOf(@NotNull T choice) {
        String raw = key == null ? labelOf(choice)
                : ChoiceInput.requireDerived(key.apply(choice), "choice key");
        return ChoiceInput.normalizeKey(raw);
    }

    /** Returns an option's icon. */
    @ApiStatus.Internal
    public @NotNull Material iconOf(@NotNull T choice) {
        Material material = icon.apply(Inputs.require(choice, "choice"));
        if (material == null) {
            throw new InputException("choice icon must not be null");
        }
        return material;
    }

    /** Number of matches a transport should place on one page. */
    @ApiStatus.Internal
    public int pageSize() {
        return pageSize;
    }

    /** Precomputed normalized search text by option, in display order. */
    @ApiStatus.Internal
    public @NotNull Map<T, String> normalizedSearchStrings() {
        if (source != null) {
            // Nothing was indexed: ranking belongs to whoever answered the page.
            return Map.of();
        }
        Map<T, String> strings = new LinkedHashMap<>();
        for (SearchEntry<T> entry : ensureIndex().entries()) {
            strings.put(entry.value(), entry.normalizedSearch());
        }
        return Map.copyOf(strings);
    }

    private SearchIndex<T> ensureIndex() {
        SearchIndex<T> current = index;
        if (current == null) {
            current = buildIndex();
            index = current;
        }
        return current;
    }

    private SearchIndex<T> buildIndex() {
        Map<String, T> byKey = new LinkedHashMap<>();
        List<SearchEntry<T>> entries = new ArrayList<>(choices.size());
        for (T choice : choices) {
            String choiceKey = keyOf(choice);
            if (byKey.putIfAbsent(choiceKey, choice) != null) {
                throw new InputException("choice keys must be unique; duplicate: '" + choiceKey + "'");
            }
            String normalized = labelOf(choice).trim().toLowerCase(Locale.ROOT);
            entries.add(new SearchEntry<>(choice, normalized));
        }
        return new SearchIndex<>(Map.copyOf(byKey), List.copyOf(entries));
    }

    /**
     * One page of results, and how many there are in total.
     *
     * @param items the options on this page, in display order
     * @param total how many options the query matches overall
     * @param <T>   option type
     */
    public record Page<T>(@NotNull List<T> items, int total) {

        public Page {
            items = List.copyOf(Inputs.require(items, "items"));
            if (total < 0) {
                throw new InputException("total must not be negative");
            }
        }

        /** An empty page, for a query that matched nothing. */
        public static <T> @NotNull Page<T> empty() {
            return new Page<>(List.of(), 0);
        }
    }

    /** Where a {@link #source(Pages) paged} request gets its results. */
    @FunctionalInterface
    public interface Pages<T> {

        /**
         * Fetches one window of results.
         *
         * <p>Called off the main thread's critical path and never blocking it:
         * whatever this returns is applied when it completes.
         *
         * @param query  what the player typed, trimmed and lowercased; blank
         *               means everything
         * @param offset how many results to skip
         * @param limit  how many to return at most
         * @return the page
         */
        @NotNull CompletionStage<Page<T>> fetch(@NotNull String query, int offset, int limit);
    }

    private record SearchEntry<T>(T value, String normalizedSearch) {
    }

    private record SearchIndex<T>(Map<String, T> byKey, List<SearchEntry<T>> entries) {
    }
}
