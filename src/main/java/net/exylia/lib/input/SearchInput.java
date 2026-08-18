package net.exylia.lib.input;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        index = buildIndex();
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

    private record SearchEntry<T>(T value, String normalizedSearch) {
    }

    private record SearchIndex<T>(Map<String, T> byKey, List<SearchEntry<T>> entries) {
    }
}
