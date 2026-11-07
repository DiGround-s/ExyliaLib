package net.exylia.lib.screen.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Text with {@code %name%} and {@code ${expression}} in it, split once into parts and filled on every render.
 * A {@code %name%} nothing answers stays as written. A value inserted into text that will be drawn is escaped
 * when it is plain ({@link Expr.Lit}), so it can never restyle the screen; inserted anywhere else (an id, a
 * material, an action argument) it goes in as it is.
 *
 * <p>Internal: free to change without notice.
 */
public final class Interp {

    private static final Map<String, Interp> CACHE = new ConcurrentHashMap<>();
    private static final Interp EMPTY = new Interp(List.of(), "");

    private sealed interface Piece permits Fixed, Name, Code {
    }

    private record Fixed(String text) implements Piece {
    }

    private record Name(String name, String written) implements Piece {
    }

    private record Code(Expr expr) implements Piece {
    }

    private final List<Piece> pieces;
    private final String text;

    private Interp(List<Piece> pieces, String text) {
        this.pieces = pieces;
        this.text = text;
    }

    /** Whether the text has anything to fill at all. */
    public static boolean dynamic(String text) {
        return text != null && (text.indexOf('%') >= 0 || text.contains("${"));
    }

    /** The parts of {@code text}, read once and shared; throws {@link Expr.Bad} on a broken {@code ${...}}. */
    public static Interp of(String text) {
        if (text == null || text.isEmpty()) return EMPTY;
        Interp cached = CACHE.get(text);
        if (cached != null) return cached;
        Interp built = parse(text);
        // Texts come from files, so the set is bounded; values are filled in, never cached here.
        if (CACHE.size() < 20_000) CACHE.put(text, built);
        return built;
    }

    private static Interp parse(String text) {
        List<Piece> out = new ArrayList<>();
        StringBuilder fixed = new StringBuilder();
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '$' && i + 1 < n && text.charAt(i + 1) == '{') {
                int end = closing(text, i + 2);
                if (end < 0) throw new Expr.Bad("\"${\" is not closed", i);
                if (!fixed.isEmpty()) { out.add(new Fixed(fixed.toString())); fixed.setLength(0); }
                try {
                    out.add(new Code(Expr.compile(text.substring(i + 2, end))));
                } catch (Expr.Bad bad) {
                    throw new Expr.Bad(bad.getMessage(), i + 2 + bad.at);
                }
                i = end + 1;
                continue;
            }
            if (c == '%') {
                int end = i + 1;
                while (end < n && (Character.isLetterOrDigit(text.charAt(end)) || "_.:-".indexOf(text.charAt(end)) >= 0)) end++;
                if (end < n && end > i + 1 && text.charAt(end) == '%') {
                    if (!fixed.isEmpty()) { out.add(new Fixed(fixed.toString())); fixed.setLength(0); }
                    out.add(new Name(text.substring(i + 1, end), text.substring(i, end + 1)));
                    i = end + 1;
                    continue;
                }
            }
            fixed.append(c);
            i++;
        }
        if (!fixed.isEmpty()) out.add(new Fixed(fixed.toString()));
        return new Interp(List.copyOf(out), text);
    }

    /** Where the {@code }} closing a {@code ${} is, quotes and nested braces skipped. */
    static int closing(String text, int from) {
        int depth = 0;
        char quote = 0;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                if (depth == 0) return i;
                depth--;
            }
        }
        return -1;
    }

    /** The compiled expressions in it, for checking names. */
    public List<Expr> expressions() {
        List<Expr> out = new ArrayList<>();
        for (Piece piece : pieces) if (piece instanceof Code code) out.add(code.expr());
        return out;
    }

    /**
     * The text filled from {@code env}.
     *
     * @param escape whether plain values are escaped for drawing ({@code true} for text on screen)
     */
    public String fill(Expr.Env env, boolean escape) {
        if (pieces.isEmpty()) return "";
        if (pieces.size() == 1 && pieces.get(0) instanceof Fixed fixed) return fixed.text();
        StringBuilder out = new StringBuilder();
        for (Piece piece : pieces) {
            switch (piece) {
                case Fixed fixed -> out.append(fixed.text());
                case Name name -> {
                    Object value = env.placeholder(name.name());
                    if (value == null) out.append(name.written());
                    else append(out, value, escape);
                }
                case Code code -> append(out, code.expr().eval(env), escape);
            }
        }
        return out.toString();
    }

    /**
     * The value of a text that is one {@code %name%} or one {@code ${...}} and nothing else, kept as the value it
     * is (a list, a number, plain text) rather than turned into a string; anything else is {@link #fill}ed.
     */
    public Object value(Expr.Env env) {
        if (pieces.size() == 1) {
            if (pieces.get(0) instanceof Code code) return code.expr().eval(env);
            if (pieces.get(0) instanceof Name name) {
                Object value = env.placeholder(name.name());
                return value == null ? name.written() : value;
            }
        }
        return fill(env, false);
    }

    private static void append(StringBuilder out, Object value, boolean escape) {
        String text = Expr.text(value);
        out.append(escape && value instanceof Expr.Lit ? net.exylia.lib.text.Text.escape(text) : text);
    }

    @Override
    public String toString() {
        return text;
    }
}
