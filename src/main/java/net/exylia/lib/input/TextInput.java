package net.exylia.lib.input;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * A request for free-form text.
 *
 * @since 1.31.0
 */
public final class TextInput extends InputRequest<String, TextInput> {

    TextInput(String pluginName, Player player, String prompt, InputParser<String> parser) {
        super(pluginName, player, prompt, parser);
    }

    /** Rejects text longer than the given number of Unicode code points. */
    public @NotNull TextInput maxLength(int maximum) {
        if (maximum < 0) {
            throw new InputException("maximum length must not be negative");
        }
        return validate(value -> value.codePointCount(0, value.length()) <= maximum,
                "Use at most " + maximum + " characters.");
    }

    /**
     * Draws the box tall enough to read, where the transport can.
     *
     * <p>The default is one line, which is what a name or an id wants. A value
     * that is long or carries markup — a display name full of colour tokens, a
     * lore line, a command with placeholders — is edited blind in a box that
     * shows twenty characters at a time.
     *
     * <pre>{@code
     * inputs.text(player, "{primary}New display name")
     *       .defaultValue(item.displayName())   // prefilled, ready to edit
     *       .lines(4)
     *       .open(name -> item.displayName(name));
     * }</pre>
     *
     * <p>A hint rather than a rule: chat has no notion of height and ignores it,
     * and a one-line box never rejected a long answer to begin with.
     *
     * @param lines how many lines tall, at least one
     * @return this request
     * @since 1.56.0
     */
    public @NotNull TextInput lines(int lines) {
        setLines(lines);
        return this;
    }

    /** Rejects text shorter than the given number of Unicode code points. */
    public @NotNull TextInput minLength(int minimum) {
        if (minimum < 0) {
            throw new InputException("minimum length must not be negative");
        }
        return validate(value -> value.codePointCount(0, value.length()) >= minimum,
                "Use at least " + minimum + " characters.");
    }
}
