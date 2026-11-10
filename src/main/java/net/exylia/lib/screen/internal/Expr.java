package net.exylia.lib.screen.internal;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The screen's expression language, compiled once into a tree of closures and evaluated on every render.
 *
 * <pre>
 * literals    12  1.5  'text'  "text"  true  false  null
 * names       tab  kit.kit_name  %stats_elo%  (a %placeholder% reads the lib's placeholders too)
 * operators   a ? b : c   or ||   and &amp;&amp;   not !   == != &lt; &lt;= &gt; &gt;=   in   + -   * / %   -x   ( )
 * functions   min max abs round floor ceil len upper lower plural(n,'kill','kills') t('key')
 *             perm('node') balance(['currency']) has_item('diamond', n) level() world()
 *             fmt.number(n) fmt.percent(x) fmt.time(seconds)
 * </pre>
 *
 * <p>{@code ==} compares numbers when both sides are numbers and text otherwise, case-sensitive. {@code +} adds
 * numbers and joins anything else. {@code x in list} looks in a list, or among the words of a text. A quoted
 * literal may hold {@code %name%} and {@code ${...}}, filled as it is read. Nothing here can run code, loop,
 * or reach the server: names and functions are all there is.
 *
 * <p>Plain text a player could have written travels as a {@link Lit} so that it is escaped wherever it is drawn;
 * operators see through it, and joining one onto anything keeps the result plain.
 *
 * <p>Internal: free to change without notice.
 */
public final class Expr {

    /** What an expression reads. */
    public interface Env {
        /** A bare or dotted name, or {@code null} when nothing answers it. */
        Object name(String name);

        /** A {@code %name%}, or {@code null} when nothing answers it. */
        Object placeholder(String name);

        /** A word of the screen's language file, or {@code null}. */
        String word(String key);

        /** One of the library's lines in the screen's language. */
        default String phrase(String english) {
            return net.exylia.lib.text.Phrases.tr(english);
        }

        /** A quoted literal, filled with {@code %name%} and {@code ${...}}. */
        Object literal(String text);

        /** Who the screen is drawn for, for {@code perm()}, {@code balance()} and the like; {@code null} off a server. */
        default org.bukkit.entity.Player viewer() {
            return null;
        }
    }

    /** Text that is shown as written: never parsed for colours or tags. */
    public record Lit(String text) {
        @Override
        public String toString() {
            return text;
        }
    }

    /** An expression that does not read, with where in its text. */
    public static final class Bad extends IllegalArgumentException {
        public final int at;

        Bad(String message, int at) {
            super(message);
            this.at = at;
        }
    }

    @FunctionalInterface
    private interface E {
        Object eval(Env env);
    }

    private final E root;
    private final String text;

    private Expr(String text, E root) {
        this.text = text;
        this.root = root;
    }

    /** Compiles {@code text}; throws {@link Bad} with the reason and the offset when it does not read. */
    public static Expr compile(String text) {
        Parser parser = new Parser(text);
        E root = parser.ternary();
        parser.skip();
        if (parser.at < text.length()) throw new Bad("unexpected \"" + text.substring(parser.at) + "\"", parser.at);
        return new Expr(text, root);
    }

    public Object eval(Env env) {
        return root.eval(env);
    }

    public boolean test(Env env) {
        return truthy(eval(env));
    }

    @Override
    public String toString() {
        return text;
    }

    // ------------------------------------------------------------------
    // Values
    // ------------------------------------------------------------------

    static Object plain(Object value) {
        return value instanceof Lit lit ? lit.text() : value;
    }

    /** Empty, {@code false}, {@code 0}, {@code no}, a placeholder nobody answered and an empty list are false. */
    public static boolean truthy(Object value) {
        value = plain(value);
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.doubleValue() != 0;
        if (value instanceof Collection<?> c) return !c.isEmpty();
        if (value instanceof Map<?, ?> m) return !m.isEmpty();
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return !(text.isEmpty() || text.equals("false") || text.equals("0") || text.equals("no") || text.startsWith("%"));
    }

    /** How a value reads as text: whole numbers without {@code .0}. */
    public static String text(Object value) {
        value = plain(value);
        if (value == null) return "";
        if (value instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
            return Long.toString(d.longValue());
        }
        if (value instanceof Float f && f == Math.rint(f)) return Long.toString(f.longValue());
        return String.valueOf(value);
    }

    static Double number(Object value) {
        value = plain(value);
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof Boolean || value == null) return null;
        String text = String.valueOf(value).trim();
        if (text.isEmpty() || !(Character.isDigit(text.charAt(text.length() - 1)))) return null;
        try {
            double parsed = Double.parseDouble(text);
            return Double.isFinite(parsed) ? parsed : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    static boolean equal(Object a, Object b) {
        Double x = number(a);
        Double y = number(b);
        if (x != null && y != null) return x.doubleValue() == y.doubleValue();
        return text(a).equals(text(b));
    }

    static int compare(Object a, Object b) {
        Double x = number(a);
        Double y = number(b);
        if (x != null && y != null) return Double.compare(x, y);
        return text(a).compareTo(text(b));
    }

    static boolean contains(Object list, Object item) {
        list = plain(list);
        if (list instanceof Collection<?> c) {
            for (Object one : c) if (equal(one, item)) return true;
            return false;
        }
        if (list instanceof Map<?, ?> m) return m.containsKey(text(item));
        for (String word : text(list).split("[\\s,|]+")) if (!word.isEmpty() && equal(word, item)) return true;
        return false;
    }

    private static final ThreadLocal<DecimalFormat> GROUPED = ThreadLocal.withInitial(() ->
            new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.ROOT)));

    private static Object call(String name, List<Object> args, Env env, int at) {
        Object a = args.isEmpty() ? null : args.get(0);
        return switch (name) {
            case "min", "max" -> {
                Double best = null;
                for (Object arg : args) {
                    Double n = number(arg);
                    if (n != null && (best == null || (name.equals("min") ? n < best : n > best))) best = n;
                }
                yield best;
            }
            case "abs" -> num(a, Math::abs);
            case "round" -> num(a, d -> (double) Math.round(d));
            case "floor" -> num(a, Math::floor);
            case "ceil" -> num(a, Math::ceil);
            case "len" -> {
                Object p = plain(a);
                yield p instanceof Collection<?> c ? (double) c.size() : (double) text(p).length();
            }
            case "upper" -> keep(a, text(a).toUpperCase(Locale.ROOT));
            case "lower" -> keep(a, text(a).toLowerCase(Locale.ROOT));
            case "plural" -> {
                Double n = number(a);
                yield args.size() < 3 ? text(a) : (n != null && n == 1 ? args.get(1) : args.get(2));
            }
            case "t" -> {
                String word = env.word(text(a));
                yield word != null ? word : args.size() > 1 ? args.get(1) : text(a);
            }
            case "tr" -> env.phrase(text(a));
            case "fmt.number" -> {
                Double n = number(a);
                yield n == null ? text(a) : GROUPED.get().format(n);
            }
            case "fmt.percent" -> {
                Double n = number(a);
                yield n == null ? text(a) : Math.round(n * (n <= 1 && n >= -1 ? 100 : 1)) + "%";
            }
            case "fmt.time" -> {
                Double n = number(a);
                yield n == null ? text(a) : net.exylia.lib.util.TimeFormats.render(Math.max(0, n));
            }
            case "perm" -> {
                org.bukkit.entity.Player viewer = env.viewer();
                yield viewer != null && viewer.hasPermission(text(a));
            }
            case "balance" -> {
                org.bukkit.entity.Player viewer = env.viewer();
                if (viewer == null) yield 0d;
                yield (args.isEmpty() ? net.exylia.lib.economy.Economy.balance(viewer.getUniqueId())
                        : net.exylia.lib.economy.Economy.of(text(a)).balance(viewer.getUniqueId())).doubleValue();
            }
            case "has_item" -> {
                org.bukkit.entity.Player viewer = env.viewer();
                org.bukkit.Material material = org.bukkit.Material.matchMaterial(text(a));
                Double wanted = args.size() > 1 ? number(args.get(1)) : Double.valueOf(1);
                if (viewer == null || material == null) yield false;
                int have = 0;
                for (org.bukkit.inventory.ItemStack stack : viewer.getInventory().getContents()) {
                    if (stack != null && stack.getType() == material) have += stack.getAmount();
                }
                yield have >= (wanted == null ? 1 : wanted);
            }
            case "level" -> {
                org.bukkit.entity.Player viewer = env.viewer();
                yield viewer == null ? 0d : (double) viewer.getLevel();
            }
            case "world" -> {
                org.bukkit.entity.Player viewer = env.viewer();
                yield viewer == null ? "" : viewer.getWorld().getName();
            }
            case "contains" -> args.size() > 1 && text(a).contains(text(args.get(1)));
            case "matches" -> args.size() > 1 && regex(text(args.get(1))).matcher(text(a)).matches();
            case "chance" -> {
                Double n = number(a);
                yield n != null && java.util.concurrent.ThreadLocalRandom.current().nextDouble(100) < n;
            }
            case "players" -> {
                // Players on a server of the network as the proxy last counted them, or on the whole network (1.565.0).
                if (args.isEmpty()) {
                    int network = net.exylia.lib.proxy.Proxy.players().size();
                    yield (double) (network > 0 ? network : org.bukkit.Bukkit.getOnlinePlayers().size());
                }
                yield net.exylia.lib.proxy.Proxy.server(text(a)).map(server -> (double) server.players()).orElse(0d);
            }
            case "online" -> net.exylia.lib.proxy.Proxy.server(text(a)).map(server -> server.online()).orElse(false);
            case "cooldown" -> {
                // Seconds left on a screen button's cooldown="key 24h", 0 when it is ready (1.565.0).
                org.bukkit.entity.Player viewer = env.viewer();
                yield viewer == null ? 0d : (double) MarkupRender.COOLDOWNS.remainingWholeSeconds(viewer, text(a));
            }
            default -> {
                // A plugin's own (ExpressionFunctions); one released since the file was read answers null.
                net.exylia.lib.screen.ExpressionFunctions.Function own = net.exylia.lib.screen.ExpressionFunctions.get(name);
                yield own == null ? null : own.call(env.viewer(), java.util.Collections.unmodifiableList(args));
            }
        };
    }

    /** Patterns are written by the server owner and compiled once each; a bad one matches nothing. */
    private static final java.util.Map<String, java.util.regex.Pattern> PATTERNS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.regex.Pattern NEVER = java.util.regex.Pattern.compile("(?!)");

    private static java.util.regex.Pattern regex(String pattern) {
        java.util.regex.Pattern cached = PATTERNS.get(pattern);
        if (cached != null) return cached;
        java.util.regex.Pattern compiled;
        try {
            compiled = java.util.regex.Pattern.compile(pattern);
        } catch (java.util.regex.PatternSyntaxException bad) {
            compiled = NEVER;
        }
        // ponytail: unbounded only if a placeholder feeds the pattern; owners write it, so a few hundred at most.
        if (PATTERNS.size() < 1024) PATTERNS.put(pattern, compiled);
        return compiled;
    }

    /** Whether {@code name} is one of the language's own functions, which a plugin cannot replace. */
    public static boolean isBuiltIn(String name) {
        return FUNCTIONS.contains(name);
    }

    private static Object num(Object a, java.util.function.DoubleUnaryOperator op) {
        Double n = number(a);
        return n == null ? null : op.applyAsDouble(n);
    }

    private static Object keep(Object source, String text) {
        return source instanceof Lit ? new Lit(text) : text;
    }

    /** The functions {@link #compile} accepts, so a typo is caught when the file is read. */
    private static final java.util.Set<String> FUNCTIONS = java.util.Set.of("min", "max", "abs", "round", "floor",
            "ceil", "len", "upper", "lower", "plural", "t", "tr", "fmt.number", "fmt.percent", "fmt.time",
            // 1.501.0: about the viewer
            "perm", "balance", "has_item", "level", "world",
            // 1.552.0
            "contains", "matches", "chance",
            // 1.565.0
            "cooldown", "players", "online");

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    private static final class Parser {
        private final String s;
        int at;
        private int depth;

        Parser(String s) {
            this.s = s;
        }

        void skip() {
            while (at < s.length() && Character.isWhitespace(s.charAt(at))) at++;
        }

        boolean eat(String token) {
            skip();
            if (!s.startsWith(token, at)) return false;
            // A word operator needs a word boundary: "order" is not "or".
            if (Character.isLetter(token.charAt(0))) {
                int end = at + token.length();
                if (end < s.length() && (Character.isLetterOrDigit(s.charAt(end)) || s.charAt(end) == '_')) return false;
            }
            at += token.length();
            return true;
        }

        E ternary() {
            if (++depth > 64) throw new Bad("nested too deep", at);
            E test = or();
            if (eat("?")) {
                E yes = ternary();
                if (!eat(":")) throw new Bad("\"?\" needs a \":\"", at);
                E no = ternary();
                depth--;
                return env -> truthy(test.eval(env)) ? yes.eval(env) : no.eval(env);
            }
            depth--;
            return test;
        }

        E or() {
            E left = and();
            while (eat("||") || eat("or")) {
                E l = left, r = and();
                left = env -> truthy(l.eval(env)) || truthy(r.eval(env));
            }
            return left;
        }

        E and() {
            E left = not();
            while (eat("&&") || eat("and")) {
                E l = left, r = not();
                left = env -> truthy(l.eval(env)) && truthy(r.eval(env));
            }
            return left;
        }

        E not() {
            skip();
            if (s.startsWith("!", at) && !s.startsWith("!=", at)) {
                at++;
                E inner = not();
                return env -> !truthy(inner.eval(env));
            }
            if (eat("not")) {
                E inner = not();
                return env -> !truthy(inner.eval(env));
            }
            return comparison();
        }

        E comparison() {
            E left = additive();
            while (true) {
                E l = left;
                if (eat("==")) { E r = additive(); left = env -> equal(l.eval(env), r.eval(env)); }
                else if (eat("!=")) { E r = additive(); left = env -> !equal(l.eval(env), r.eval(env)); }
                else if (eat("<=")) { E r = additive(); left = env -> compare(l.eval(env), r.eval(env)) <= 0; }
                else if (eat(">=")) { E r = additive(); left = env -> compare(l.eval(env), r.eval(env)) >= 0; }
                else if (eat("<")) { E r = additive(); left = env -> compare(l.eval(env), r.eval(env)) < 0; }
                else if (eat(">")) { E r = additive(); left = env -> compare(l.eval(env), r.eval(env)) > 0; }
                else if (eat("in")) { E r = additive(); left = env -> contains(r.eval(env), l.eval(env)); }
                else return left;
            }
        }

        E additive() {
            E left = multiplicative();
            while (true) {
                E l = left;
                if (eat("+")) {
                    E r = multiplicative();
                    left = env -> {
                        Object a = l.eval(env), b = r.eval(env);
                        Double x = number(a), y = number(b);
                        if (x != null && y != null) return x + y;
                        String joined = text(a) + text(b);
                        return a instanceof Lit || b instanceof Lit ? new Lit(joined) : joined;
                    };
                } else if (eat("-")) {
                    E r = multiplicative();
                    left = env -> arithmetic(l.eval(env), r.eval(env), '-');
                } else return left;
            }
        }

        E multiplicative() {
            E left = unary();
            while (true) {
                E l = left;
                skip();
                char op = at < s.length() ? s.charAt(at) : 0;
                if (op == '*' || op == '/' || (op == '%' && !placeholderAt())) {
                    at++;
                    E r = unary();
                    left = env -> arithmetic(l.eval(env), r.eval(env), op);
                } else return left;
            }
        }

        E unary() {
            skip();
            if (s.startsWith("-", at)) {
                at++;
                E inner = unary();
                return env -> {
                    Double n = number(inner.eval(env));
                    return n == null ? null : -n;
                };
            }
            return primary();
        }

        private boolean placeholderAt() {
            int end = s.indexOf('%', at + 1);
            return end > at + 1 && s.substring(at + 1, end).matches("[\\w.:-]+");
        }

        E primary() {
            skip();
            if (at >= s.length()) throw new Bad("an expression ends too early", at);
            char c = s.charAt(at);
            int start = at;
            if (c == '(') {
                at++;
                E inner = ternary();
                if (!eat(")")) throw new Bad("\"(\" is not closed", start);
                return inner;
            }
            if (c == '\'' || c == '"') {
                int end = s.indexOf(c, at + 1);
                if (end < 0) throw new Bad("a quote is not closed", start);
                String literal = s.substring(at + 1, end);
                at = end + 1;
                if (literal.indexOf('%') < 0 && !literal.contains("${")) return env -> literal;
                return env -> env.literal(literal);
            }
            if (c == '%') {
                int end = s.indexOf('%', at + 1);
                String name = end < 0 ? "" : s.substring(at + 1, end);
                if (!name.matches("[\\w.:-]+")) throw new Bad("\"%\" starts no %placeholder%", start);
                at = end + 1;
                return env -> env.placeholder(name);
            }
            if (Character.isDigit(c) || (c == '.' && at + 1 < s.length() && Character.isDigit(s.charAt(at + 1)))) {
                while (at < s.length() && (Character.isDigit(s.charAt(at))
                        || (s.charAt(at) == '.' && at + 1 < s.length() && Character.isDigit(s.charAt(at + 1))))) at++;
                double value = Double.parseDouble(s.substring(start, at));
                return env -> value;
            }
            if (Character.isLetter(c) || c == '_') {
                while (at < s.length() && (Character.isLetterOrDigit(s.charAt(at)) || s.charAt(at) == '_'
                        || (s.charAt(at) == '.' && at + 1 < s.length() && Character.isLetter(s.charAt(at + 1))))) at++;
                String name = s.substring(start, at);
                switch (name) {
                    case "true": return env -> Boolean.TRUE;
                    case "false": return env -> Boolean.FALSE;
                    case "null": return env -> null;
                    default:
                }
                skip();
                if (at < s.length() && s.charAt(at) == '(') {
                    if (!FUNCTIONS.contains(name) && net.exylia.lib.screen.ExpressionFunctions.get(name) == null) {
                        throw new Bad("unknown function " + name + "()", start);
                    }
                    at++;
                    List<E> args = new ArrayList<>();
                    if (!eat(")")) {
                        do args.add(ternary()); while (eat(","));
                        if (!eat(")")) throw new Bad(name + "( is not closed", start);
                    }
                    return env -> {
                        List<Object> values = new ArrayList<>(args.size());
                        for (E arg : args) values.add(arg.eval(env));
                        return call(name, values, env, start);
                    };
                }
                return env -> env.name(name);
            }
            throw new Bad("unexpected \"" + c + "\"", start);
        }
    }

    private static Object arithmetic(Object a, Object b, char op) {
        Double x = number(a), y = number(b);
        if (x == null || y == null) return null;
        return switch (op) {
            case '-' -> x - y;
            case '*' -> x * y;
            case '/' -> y == 0 ? null : x / y;
            default -> y == 0 ? null : x % y;
        };
    }
}
