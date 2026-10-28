package net.exylia.lib.text;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads a configuration key that holds text meant to be drawn on several lines.
 *
 * <p>Two things the ecosystem's files do, that every plugin was solving again:
 *
 * <p>First, line breaks. A description belongs next to the thing it describes,
 * so a server owner writes it on the one YAML line that already says what the
 * thing is. Drawn as written it is a tooltip running off the screen, so
 * <b>{@code <nl>}</b>, a real line break, or the literal {@code \n} sequence
 * marks where it should break. That is the only way a config-owned description
 * reaches a lore block as more than one line, because nothing downstream knows
 * where the sentence ends.
 *
 * <p>Second, <b>a String or a list</b>. One sentence reads as a String and five
 * bullets read as a list, so the file is accepted either way rather than making
 * the owner learn which shape this particular key wanted. The two shapes are the
 * same thing once read.
 *
 * <pre>{@code
 * // description: "Hits nearby players<nl>and knocks them back."
 * List<String> lore = Lines.read(section, "description", "lore");
 *
 * // Or as one value for a row, which the renderer expands back into lines.
 * entry.withFormatted("description", Lines.value(section, "description"));
 * }</pre>
 *
 * @since 1.49.0
 */
public final class Lines {

    /**
     * What marks a line break inside one written entry.
     *
     * <p>The single spelling for the whole library: the item reader splits files
     * on it and the item renderer expands row values on it, so a value a plugin
     * passes in and a line written in a file mean the same thing by it.
     */
    public static final String NEWLINE = "<nl>";

    /**
     * A row value that hides every lore line mentioning it.
     *
     * <p>For lines that only matter sometimes: a discount when there is one, a
     * stock when it is limited. Pass it as the value and the line is not drawn,
     * instead of drawing a label beside "none". A blank line can carry a value
     * on its own ({@code "%has_stats%"}) to vanish with the section it separates.
     */
    public static final String OMIT = "<omit>";

    private static final String CARRIAGE_RETURN = "\r";
    private static final String WINDOWS_NEWLINE = "\r\n";
    private static final String LINE_FEED = "\n";
    private static final String LITERAL_NEWLINE = "\\n";

    private Lines() {
        throw new AssertionError("No instances.");
    }

    /**
     * Reads a key as the lines it describes.
     *
     * <p>Keys are tried in order and the first one the section carries answers,
     * which is how a key that was renamed keeps reading files written before the
     * rename without the owner touching them.
     *
     * @param section where to read from
     * @param keys    the spellings to accept, most preferred first
     * @return the lines, never {@code null}, empty when no key holds anything
     */
    public static @NotNull List<String> read(@NotNull ConfigurationSection section,
                                             @NotNull String... keys) {
        for (String key : keys) {
            if (!section.contains(key)) {
                continue;
            }
            if (section.isString(key)) {
                String single = section.getString(key, "");
                return single.isEmpty() ? List.of() : split(single);
            }
            List<String> written = section.getStringList(key);
            if (!written.isEmpty()) {
                List<String> lines = new ArrayList<>(written.size());
                for (String entry : written) {
                    // An empty YAML list entry is an intentional blank lore line.
                    lines.addAll(entry.isEmpty() ? List.of("") : split(entry));
                }
                return List.copyOf(lines);
            }
        }
        return List.of();
    }

    /**
     * Reads a key as one value that still carries its breaks.
     *
     * <p>For a row value rather than a lore block: the renderer expands the
     * canonical {@code <nl>} token as the row is drawn, so a description read
     * this way spans as many lore lines as it needs while the template stays one
     * written line.
     *
     * @param section where to read from
     * @param keys    the spellings to accept, most preferred first
     * @return the value, or {@code ""} when no key holds anything
     */
    public static @NotNull String value(@NotNull ConfigurationSection section,
                                        @NotNull String... keys) {
        return join(read(section, keys));
    }

    /**
     * Splits a value that has already been resolved.
     *
     * <p>Real CRLF, LF, and CR breaks plus a literal {@code \n} sequence are
     * normalized to {@link #NEWLINE} before splitting. The empty trailing entries
     * are kept: a lore block ends on a blank line on purpose, and dropping it
     * closes the gap the owner asked for.
     *
     * @param value the text, or {@code null}
     * @return the lines, never {@code null}
     */
    public static @NotNull List<String> split(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return List.of();
        }
        String normalized = value
                .replace(WINDOWS_NEWLINE, NEWLINE)
                .replace(CARRIAGE_RETURN, NEWLINE)
                .replace(LINE_FEED, NEWLINE)
                .replace(LITERAL_NEWLINE, NEWLINE);
        if (!normalized.contains(NEWLINE)) {
            return List.of(normalized);
        }
        return List.of(normalized.split(NEWLINE, -1));
    }

    /**
     * Joins lines back into one value.
     *
     * @param lines the lines, or {@code null}
     * @return the value, or {@code ""} when there is nothing to join
     */
    public static @NotNull String join(@Nullable List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        return String.join(NEWLINE, lines);
    }

    /**
     * Wraps prose into lore lines of about {@code width} visible characters,
     * joined with {@link #NEWLINE}, so a row value expands into several lines.
     *
     * <pre>{@code
     * entry.withFormatted("description", Lines.wrap(description, 34));
     * }</pre>
     *
     * <p>Greedy, word by word; a word longer than the width gets a line of its
     * own. Width counts what the player sees: palette tokens
     * ({@code {highlight}}), legacy codes ({@code &l}) and MiniMessage tags
     * ({@code <bold>}) count for nothing. Breaks already in the text are kept.
     *
     * @param text  the prose, or {@code null}
     * @param width visible characters per line, 1 at least
     * @return the wrapped value, or {@link #OMIT} when the text is blank, so
     *         the line it fills is not drawn
     * @since 1.387.0
     */
    public static @NotNull String wrap(@Nullable String text, int width) {
        if (text == null || text.isBlank()) {
            return OMIT;
        }
        int max = Math.max(1, width);
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (String paragraph : split(text.trim())) {
            if (!out.isEmpty()) {
                out.append(NEWLINE);
            }
            int line = 0;
            for (String word : paragraph.trim().split("\\s+")) {
                if (word.isEmpty()) {
                    continue;
                }
                int visible = visibleLength(word);
                if (line > 0 && line + 1 + visible > max) {
                    out.append(NEWLINE);
                    line = 0;
                } else if (line > 0) {
                    out.append(' ');
                    line++;
                }
                out.append(word);
                line += visible;
            }
        }
        return out.toString();
    }

    /** Characters a player sees: palette tokens, {@code &x} codes and {@code <tags>} left out. */
    static int visibleLength(String word) {
        int visible = 0;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            int close = c == '{' ? word.indexOf('}', i) : c == '<' ? word.indexOf('>', i) : -1;
            if (close > i && word.substring(i + 1, close).matches("[#/!a-zA-Z0-9_:.\\-]+")) {
                i = close;
                continue;
            }
            if (c == '&' && i + 1 < word.length() && "0123456789abcdefklmnorABCDEFKLMNOR".indexOf(word.charAt(i + 1)) >= 0) {
                i++;
                continue;
            }
            visible++;
        }
        return visible;
    }

}
