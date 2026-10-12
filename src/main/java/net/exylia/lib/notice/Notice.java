package net.exylia.lib.notice;

import net.exylia.lib.text.Text;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * One message a plugin left for a player, as a {@link NoticeSummary} reads it.
 *
 * @param category the category it was sent under
 * @param message  the raw message, Exylia notation, {@code %prefix%} included
 * @param values   what each {@code %key%} in it stands for, by key without the percent signs
 * @param sentAt   when it was sent, in epoch milliseconds
 * @since 1.278.0
 */
public record Notice(@NotNull String category, @NotNull String message, @NotNull Map<String, String> values,
                     long sentAt) {

    public Notice {
        values = Map.copyOf(values);
    }

    /**
     * The message with its values in, ready to send.
     *
     * <p>Values go in as literal text, so a value a player typed cannot inject
     * formatting.
     *
     * @param plugin whose {@code %prefix%}
     * @return the filled text
     */
    public @NotNull Text text(@NotNull Plugin plugin) {
        return fill(Text.from(plugin, message));
    }

    /**
     * Fills these values into another template, such as a list line built
     * around the message.
     *
     * @param template the text to fill
     * @return the filled text
     */
    public @NotNull Text fill(@NotNull Text template) {
        Text filled = template;
        for (Map.Entry<String, String> value : values.entrySet()) {
            filled = filled.with("%" + value.getKey() + "%", value.getValue());
        }
        return filled;
    }

    /**
     * A value read as a number, for a summary that adds them up.
     *
     * @param key the key, without percent signs
     * @return the number, or {@code 0} when it is missing or not a number
     */
    public double number(@NotNull String key) {
        String value = values.get(key);
        if (value == null) return 0;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }
}
