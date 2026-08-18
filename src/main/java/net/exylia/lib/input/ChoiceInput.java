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
import java.util.function.Function;

/**
 * A typed choice whose answer is the original element, not a key that callers
 * must look up again.
 *
 * @param <T> option type
 *
 * @since 1.31.0
 */
public class ChoiceInput<T> extends InputRequest<T, ChoiceInput<T>> {

    private final List<T> choices;
    private Function<T, String> label = String::valueOf;
    private Function<T, String> key;
    private Function<T, Material> icon = ignored -> Material.PAPER;
    private volatile ChoiceIndex<T> index;

    ChoiceInput(String pluginName, Player player, String prompt, Collection<T> choices) {
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

    /** Sets the text players see for each option. */
    public @NotNull ChoiceInput<T> label(@NotNull Function<T, String> label) {
        this.label = Inputs.require(label, "label");
        this.index = null;
        return this;
    }

    /**
     * Sets the stable raw key accepted from transports.
     *
     * <p>Keys must be unique; otherwise two buttons would submit the same raw
     * value and resolving the actual object would depend on collection order.
     */
    public @NotNull ChoiceInput<T> key(@NotNull Function<T, String> key) {
        this.key = Inputs.require(key, "key");
        this.index = null;
        return this;
    }

    /** Sets a lightweight Bukkit material icon without coupling choices to item definitions. */
    public @NotNull ChoiceInput<T> icon(@NotNull Function<T, Material> icon) {
        this.icon = Inputs.require(icon, "icon");
        return this;
    }

    /** Resolves raw transport keys to the actual option and then runs validations. */
    @Override
    public @NotNull InputParser.Parsed<T> parseRaw(@NotNull String raw) {
        if (raw == null) {
            return InputParser.Parsed.rejected("Choose one of the available options.");
        }
        final String normalized;
        try {
            String transformed = transform().apply(raw.trim());
            normalized = transformed == null ? null : normalizeKey(transformed);
        } catch (RuntimeException failure) {
            return InputParser.Parsed.rejected("Choose one of the available options.");
        }
        if (normalized == null) {
            return InputParser.Parsed.rejected("Choose one of the available options.");
        }
        T value = ensureIndex().byKey().get(normalized);
        if (value == null) {
            return InputParser.Parsed.rejected("Choose one of the available options.");
        }
        Validation validation = validate(value);
        return validation.valid()
                ? InputParser.Parsed.of(value)
                : InputParser.Parsed.rejected(validation.messages().getFirst());
    }

    @Override
    protected void beforeOpen() {
        ensureIndex();
    }

    /** Immutable options in display order. */
    @ApiStatus.Internal
    public final @NotNull List<T> choices() {
        return choices;
    }

    /** Returns a transport-facing option label. */
    @ApiStatus.Internal
    public final @NotNull String labelOf(@NotNull T choice) {
        return requireDerived(label.apply(Inputs.require(choice, "choice")), "choice label");
    }

    /** Returns the normalized transport key for an option. */
    @ApiStatus.Internal
    public final @NotNull String keyOf(@NotNull T choice) {
        return normalizeKey(rawKey(Inputs.require(choice, "choice")));
    }

    /** Returns the icon material for an option. */
    @ApiStatus.Internal
    public final @NotNull Material iconOf(@NotNull T choice) {
        Material material = icon.apply(Inputs.require(choice, "choice"));
        if (material == null) {
            throw new InputException("choice icon must not be null");
        }
        return material;
    }

    private ChoiceIndex<T> ensureIndex() {
        ChoiceIndex<T> current = index;
        if (current != null) {
            return current;
        }
        Map<String, T> byKey = new LinkedHashMap<>();
        for (T choice : choices) {
            String key = keyOf(choice);
            if (byKey.putIfAbsent(key, choice) != null) {
                throw new InputException("choice keys must be unique; duplicate: '" + key + "'");
            }
        }
        current = new ChoiceIndex<>(Map.copyOf(byKey));
        index = current;
        return current;
    }

    private String rawKey(T choice) {
        return key != null ? requireDerived(key.apply(choice), "choice key") : labelOf(choice);
    }

    static String normalizeKey(String raw) {
        InputParser.Parsed<String> slug = InputParser.slug().parse(raw);
        if (!slug.ok()) {
            throw new InputException("choice key '" + raw + "' cannot be normalized");
        }
        return slug.value().toLowerCase(Locale.ROOT);
    }

    static String requireDerived(String value, String name) {
        return Inputs.requireText(value, name);
    }

    private record ChoiceIndex<T>(Map<String, T> byKey) {
    }
}
