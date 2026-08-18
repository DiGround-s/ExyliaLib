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

    /** Rejects text shorter than the given number of Unicode code points. */
    public @NotNull TextInput minLength(int minimum) {
        if (minimum < 0) {
            throw new InputException("minimum length must not be negative");
        }
        return validate(value -> value.codePointCount(0, value.length()) >= minimum,
                "Use at least " + minimum + " characters.");
    }
}
