package net.exylia.lib.action;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Arguments already tokenised when an action string is compiled.
 *
 * <p>Quoted strings and backslash escapes are handled once, while loading a
 * menu or item config, rather than on every click. Negative numbers remain
 * ordinary values; there is no flag syntax that mistakes {@code -0.5} for an
 * option.
 *
 * @since 1.20.0
 */
public final class ActionArguments {

    private static final ActionArguments EMPTY = new ActionArguments(List.of());
    private final List<String> values;

    private ActionArguments(List<String> values) {
        this.values = values;
    }

    /** An empty argument list. */
    public static @NotNull ActionArguments empty() { return EMPTY; }

    /** Tokenises a raw argument tail. */
    public static @NotNull ActionArguments parse(@NotNull String raw) {
        if (raw.isBlank()) return EMPTY;
        List<String> out = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        boolean quoted = false;
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (escaped) {
                token.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (quoted) {
                if (c == quote) quoted = false;
                else token.append(c);
            } else if (c == '\'' || c == '"') {
                quoted = true;
                quote = c;
            } else if (Character.isWhitespace(c)) {
                if (!token.isEmpty()) {
                    out.add(token.toString());
                    token.setLength(0);
                }
            } else token.append(c);
        }
        if (escaped) token.append('\\');
        if (quoted) throw new IllegalArgumentException("Unclosed quote in action arguments: " + raw);
        if (!token.isEmpty()) out.add(token.toString());
        return out.isEmpty() ? EMPTY : new ActionArguments(List.copyOf(out));
    }

    public int size() { return values.size(); }
    public boolean isEmpty() { return values.isEmpty(); }
    public @NotNull List<String> values() { return values; }
    public @NotNull String string(int index) { return require(index); }
    public @NotNull String string(int index, @NotNull String fallback) {
        return index < values.size() ? values.get(index) : fallback;
    }
    public int integer(int index) { return Integer.parseInt(require(index)); }
    public int integer(int index, int fallback) {
        try { return Integer.parseInt(require(index)); }
        catch (IndexOutOfBoundsException | NumberFormatException ignored) { return fallback; }
    }
    public double decimal(int index) { return Double.parseDouble(require(index)); }
    public double decimal(int index, double fallback) {
        try { return Double.parseDouble(require(index)); }
        catch (IndexOutOfBoundsException | NumberFormatException ignored) { return fallback; }
    }
    public boolean bool(int index, boolean fallback) {
        if (index >= values.size()) return fallback;
        String value = values.get(index);
        if (value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        return fallback;
    }

    private String require(int index) {
        if (index < 0 || index >= values.size()) {
            throw new IndexOutOfBoundsException("Missing action argument " + index);
        }
        return values.get(index);
    }
}
