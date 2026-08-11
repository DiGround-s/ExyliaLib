package net.exylia.lib.text.internal;

import java.util.Map;

/**
 * Replaces <code>{name}</code> tokens with the palette colour they stand for.
 *
 * <p>Runs before MiniMessage so a token can appear anywhere a colour can, and
 * so the rest of the pipeline only has to understand one notation.
 *
 * <p>A brace that is not a known colour is left exactly as it was. That matters:
 * plugins use braces for their own placeholders, and silently eating
 * {@code {player}} because it is not a colour would be a bug that only shows up
 * in production.
 */
final class TokenResolver {

    private TokenResolver() {
    }

    /**
     * Expands palette tokens.
     *
     * @param text    the text, already known to contain a brace
     * @param palette resolved colour tags, keyed by token name
     * @return the text with known tokens replaced
     */
    static String resolve(String text, Map<String, String> palette) {
        int open = text.indexOf('{');
        if (open < 0) {
            return text;
        }

        int length = text.length();
        StringBuilder result = new StringBuilder(length + 16);
        result.append(text, 0, open);

        int cursor = open;
        while (cursor < length) {
            char current = text.charAt(cursor);
            if (current != '{') {
                result.append(current);
                cursor++;
                continue;
            }

            int close = text.indexOf('}', cursor + 1);
            if (close < 0) {
                // No closing brace: the rest is literal.
                result.append(text, cursor, length);
                break;
            }

            String name = text.substring(cursor + 1, close);
            String replacement = palette.get(name);
            if (replacement != null) {
                result.append(replacement);
            } else {
                // Not ours. Leave it untouched for whoever owns it.
                result.append(text, cursor, close + 1);
            }
            cursor = close + 1;
        }

        return result.toString();
    }
}
