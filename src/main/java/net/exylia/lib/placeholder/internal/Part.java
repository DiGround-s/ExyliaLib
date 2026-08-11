package net.exylia.lib.placeholder.internal;

import java.util.List;

/**
 * One piece of a compiled template: either fixed text or a placeholder.
 *
 * <p>Everything a placeholder needs is worked out at compile time, so rendering
 * never parses anything: the name is already split from its arguments, the
 * format and the fallback are already extracted, and the owning resolver has
 * already been located by name.
 *
 * @param literal  fixed text, or {@code null} when this part is a placeholder
 * @param name     the registered placeholder name, without arguments
 * @param args     arguments parsed from the placeholder text
 * @param format   the format requested after a colon, or {@code null}
 * @param fallback the text to use when the value is missing, or {@code null} to
 *                 leave the placeholder visible
 * @param original the placeholder exactly as written, used when nothing resolves
 */
public record Part(String literal,
                   String name,
                   List<String> args,
                   String format,
                   String fallback,
                   String original) {

    /** Builds a fixed-text part. */
    static Part literal(String text) {
        return new Part(text, null, List.of(), null, null, null);
    }

    /** Returns whether this part is fixed text. */
    public boolean isLiteral() {
        return literal != null;
    }
}
