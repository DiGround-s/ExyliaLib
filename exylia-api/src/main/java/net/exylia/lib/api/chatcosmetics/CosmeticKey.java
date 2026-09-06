package net.exylia.lib.api.chatcosmetics;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Optional;

/**
 * What names one cosmetic everywhere: {@code tag:mvp}.
 *
 * <p>Both halves are normalised on the way in — lower case, spaces to
 * underscores, anything else that is not a letter, a digit, an underscore or a
 * dash dropped. That happens here rather than only inside the plugin so that a
 * key you build and a key the service hands back compare equal, whatever case
 * the caller wrote.
 *
 * @param type the cosmetic type: {@code tag}, {@code font}, {@code chatcolor}
 * @param id   the entry within that type
 * @since 1.0.0
 */
public record CosmeticKey(@NotNull String type, @NotNull String id) {

    public CosmeticKey {
        type = normalise(type);
        id = normalise(id);
    }

    /**
     * Reads a {@code type:id} string.
     *
     * @param raw the text, as a command argument or a config value has it
     * @return the key, or empty when either half is missing
     */
    @NotNull
    public static Optional<CosmeticKey> parse(@NotNull String raw) {
        int colon = raw.indexOf(':');
        if (colon <= 0 || colon == raw.length() - 1) return Optional.empty();
        CosmeticKey key = new CosmeticKey(raw.substring(0, colon), raw.substring(colon + 1));
        return key.type.isEmpty() || key.id.isEmpty() ? Optional.empty() : Optional.of(key);
    }

    /**
     * Applies the plugin's own normalisation to one half of a key.
     *
     * @param raw the text to clean up
     * @return the normalised form
     */
    @NotNull
    public static String normalise(@NotNull String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (char c : raw.trim().toLowerCase(Locale.ROOT).toCharArray()) {
            if (c == ' ') out.append('_');
            else if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-') out.append(c);
        }
        return out.toString();
    }

    /**
     * The {@code type:id} form, which is what the plugin stores and what
     * placeholders and commands accept.
     *
     * @return the key as one string
     */
    @Override
    @NotNull
    public String toString() {
        return type + ":" + id;
    }
}
