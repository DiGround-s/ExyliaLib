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
 *
 * <p>Inside a tag a token stands for the bare colour, so a gradient can be
 * written against the palette: {@code <gradient:{primary}:{secondary_light}>}
 * becomes {@code <gradient:#8a51c4:#b48fd9>}. Outside a tag it is the whole
 * colour tag, {@code <#8a51c4>}.
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

        boolean inTag = insideTag(text, open);
        int cursor = open;
        while (cursor < length) {
            char current = text.charAt(cursor);
            if (current != '{') {
                if (current == '<') {
                    inTag = true;
                } else if (current == '>') {
                    inTag = false;
                }
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
                result.append(inTag ? bare(replacement) : replacement);
            } else {
                // Not ours. Leave it untouched for whoever owns it.
                result.append(text, cursor, close + 1);
            }
            cursor = close + 1;
        }

        return result.toString();
    }

    /** Whether {@code index} sits between a {@code <} and the {@code >} that closes it. */
    private static boolean insideTag(String text, int index) {
        return text.lastIndexOf('<', index) > text.lastIndexOf('>', index);
    }

    /** {@code <#8a51c4>} as the argument a tag takes, {@code #8a51c4}. */
    private static String bare(String tag) {
        return tag.length() > 2 && tag.charAt(0) == '<' && tag.charAt(tag.length() - 1) == '>'
                ? tag.substring(1, tag.length() - 1) : tag;
    }
}
