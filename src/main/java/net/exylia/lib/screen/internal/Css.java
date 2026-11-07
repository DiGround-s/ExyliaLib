package net.exylia.lib.screen.internal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The CSS subset a screen reads: rules of selectors and declarations, matched by specificity then order.
 *
 * <p>Selectors: a tag, {@code *}, {@code #id}, {@code .class}, {@code [attr]}, {@code :hover}, {@code :root},
 * {@code :first-child}, {@code :last-child}, {@code :only-child}, {@code :nth-child(odd|even|n)},
 * {@code :not(simple)}, {@code :disabled}/{@code :checked}/{@code :selected} (aliases of the attributes), compounds
 * of those, descendants separated by spaces and children by {@code >}; several selectors per rule separated by
 * commas. Custom properties ({@code --accent: {primary}}) are inherited and read with {@code var(--accent)} or
 * {@code var(--accent, fallback)}. Comments are {@code /* ... * /}.
 *
 * <p>Internal: free to change without notice.
 */
public final class Css {

    /** Every property the screen reads; anything else is reported. Custom properties ({@code --x}) always are. */
    public static final Set<String> PROPERTIES = Set.of("flex-direction", "display", "gap", "padding", "width",
            "height", "flex-grow", "align-items", "justify-content", "background", "outline", "border", "color",
            "font-size", "font-weight", "text-align",
            // 1.496.0: motion, placement and text
            "transform", "transition", "animation", "enter", "leave", "margin", "position", "top", "right", "bottom",
            "left", "z-index", "max-width", "white-space", "text-shadow",
            // 1.497.0
            "background-image");

    /** Properties a child takes from its parent when it sets none. */
    static final Set<String> INHERITED = Set.of("color", "font-size", "font-weight", "text-align", "white-space",
            "text-shadow");

    /** Pointer states a style is asked for: the cursor or the keyboard focus is on it, it was just clicked. */
    public static final int HOVER = 1, ACTIVE = 2;

    private static final Pattern COMPOUND = Pattern.compile(
            "([a-zA-Z][\\w-]*|\\*)?((?:#[\\w-]+|\\.[\\w-]+|\\[[\\w-]+]|:[\\w-]+(?:\\([^)]*\\))?)*)");
    private static final Pattern BIT = Pattern.compile("#([\\w-]+)|\\.([\\w-]+)|\\[([\\w-]+)]|:([\\w-]+)(?:\\(([^)]*)\\))?");
    private static final Pattern VAR = Pattern.compile("var\\(\\s*(--[\\w-]+)\\s*(?:,\\s*([^()]*))?\\)");

    /**
     * One selector step: what one element must have.
     *
     * @param child whether the step before it must be its parent ({@code >}) rather than any ancestor
     * @param nth   {@code first}, {@code last}, {@code only}, {@code odd}, {@code even}, a number, or {@code null}
     */
    record Step(String tag, String id, List<String> classes, List<String> attrs, boolean hover, boolean root,
                String nth, List<Step> not, boolean child, boolean active) {

        boolean matches(Target target, int states) {
            if (active && (states & ACTIVE) == 0) return false;
            boolean hovered = (states & HOVER) != 0;
            if (tag != null && !tag.equals(target.tag())) return false;
            if (root && !target.tag().equals("screen")) return false;
            if (id != null && !id.equals(target.id())) return false;
            for (String c : classes) if (!target.classes().contains(c)) return false;
            for (String a : attrs) if (!target.flags().contains(a)) return false;
            if (nth != null && !nth(target)) return false;
            for (Step negated : not) if (negated.matches(target, states)) return false;
            return !hover || hovered;
        }

        private boolean nth(Target target) {
            int i = target.index();
            if (i <= 0) return false;
            return switch (nth) {
                case "first" -> i == 1;
                case "last" -> i == target.count();
                case "only" -> target.count() == 1;
                case "odd" -> i % 2 == 1;
                case "even" -> i % 2 == 0;
                default -> nth.equals(Integer.toString(i));
            };
        }
    }

    /** A selector, rightmost step last, with its specificity packed as ids, classes, tags. */
    record Selector(List<Step> steps, int specificity) {
    }

    record Rule(Selector selector, Map<String, String> declarations, int order) {
    }

    /**
     * What a selector sees of one element.
     *
     * @param flags attribute names present (and true) on it, such as {@code selected} or {@code disabled}
     * @param index its place among its parent's elements, from 1; 0 when unknown
     * @param count how many elements its parent has
     */
    public record Target(String tag, String id, Set<String> classes, Set<String> flags, int index, int count) {

        public Target(String tag, String id, Set<String> classes, Set<String> flags) {
            this(tag, id, classes, flags, 0, 0);
        }
    }

    private final List<Rule> rules = new ArrayList<>();
    /** {@code @keyframes} by name: each frame's place (0 to 1) and its declarations. */
    private final Map<String, List<Map.Entry<Double, Map<String, String>>>> keyframes = new LinkedHashMap<>();
    /** Computed styles by element path and hover: the same element in every row and every render asks again. */
    private final com.github.benmanes.caffeine.cache.Cache<Object, Object> computed =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(4096).build();
    private boolean variables;

    private record Key(List<Target> path, int hovered) {
    }

    /** Reads {@code source} after any rules already here, so later sheets win ties. */
    public Css add(String source, String name, Consumer<String> problems) {
        // Palette tokens ({primary}) use the same braces as rules: set them aside while splitting.
        String text = source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("\\{([\\w-]+)}", "$1");
        int at = 0;
        while (true) {
            int open = text.indexOf('{', at);
            if (open < 0) break;
            int close = text.indexOf('}', open);
            if (close < 0) {
                problems.accept(name + ":" + line(text, open) + ": a rule is not closed");
                break;
            }
            String selectors = text.substring(at, open).trim();
            if (selectors.startsWith("@keyframes")) {
                at = keyframes(text, selectors.substring("@keyframes".length()).trim(), open, name, problems);
                continue;
            }
            Map<String, String> declarations = new LinkedHashMap<>();
            for (String declaration : text.substring(open + 1, close).split(";")) {
                if (declaration.isBlank()) continue;
                int colon = declaration.indexOf(':');
                if (colon < 0) {
                    problems.accept(name + ":" + line(text, open) + ": \"" + declaration.trim() + "\" has no value");
                    continue;
                }
                String property = declaration.substring(0, colon).trim();
                if (!property.startsWith("--")) property = property.toLowerCase(java.util.Locale.ROOT);
                if (!property.startsWith("--") && !PROPERTIES.contains(property)) {
                    problems.accept(name + ":" + line(text, open) + ": unknown property \"" + property + "\"");
                    continue;
                }
                String value = declaration.substring(colon + 1).trim().replace('', '{').replace('', '}');
                if (property.startsWith("--") || value.contains("var(")) variables = true;
                declarations.put(property, value);
            }
            for (String selector : selectors.split(",")) {
                Selector parsed = selector(selector.trim());
                if (parsed == null) problems.accept(name + ":" + line(text, open) + ": cannot read selector \"" + selector.trim() + "\"");
                else rules.add(new Rule(parsed, declarations, rules.size()));
            }
            at = close + 1;
        }
        computed.invalidateAll();
        return this;
    }

    /** Reads {@code @keyframes name { 0% {...} 50% {...} }} from its opening brace; returns where reading goes on. */
    private int keyframes(String text, String label, int open, String name, Consumer<String> problems) {
        int depth = 0;
        int end = -1;
        for (int i = open; i < text.length(); i++) {
            if (text.charAt(i) == '{') depth++;
            else if (text.charAt(i) == '}' && --depth == 0) {
                end = i;
                break;
            }
        }
        if (end < 0) {
            problems.accept(name + ":" + line(text, open) + ": @keyframes " + label + " is not closed");
            return text.length();
        }
        List<Map.Entry<Double, Map<String, String>>> frames = new ArrayList<>();
        String body = text.substring(open + 1, end);
        int at = 0;
        while (true) {
            int o = body.indexOf('{', at);
            if (o < 0) break;
            int c = body.indexOf('}', o);
            if (c < 0) break;
            Map<String, String> declarations = new LinkedHashMap<>();
            for (String declaration : body.substring(o + 1, c).split(";")) {
                int colon = declaration.indexOf(':');
                if (colon > 0) {
                    declarations.put(declaration.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT),
                            declaration.substring(colon + 1).trim().replace('\uE000', '{').replace('\uE001', '}'));
                }
            }
            for (String stop : body.substring(at, o).split(",")) {
                String s = stop.trim().toLowerCase(java.util.Locale.ROOT);
                Double f = s.equals("from") ? Double.valueOf(0) : s.equals("to") ? Double.valueOf(1)
                        : s.endsWith("%") ? parsePercent(s) : null;
                if (f == null) problems.accept(name + ":" + line(text, open) + ": @keyframes " + label + ": cannot read \"" + stop.trim() + "\"");
                else frames.add(Map.entry(f, declarations));
            }
            at = c + 1;
        }
        frames.sort(Map.Entry.comparingByKey());
        keyframes.put(label, frames);
        computed.invalidateAll();
        return end + 1;
    }

    private static Double parsePercent(String s) {
        try {
            return Math.max(0, Math.min(1, Double.parseDouble(s.substring(0, s.length() - 1)) / 100));
        } catch (NumberFormatException bad) {
            return null;
        }
    }

    /** The frames of {@code @keyframes name}, in order, or {@code null} when no sheet declares it. */
    public List<Map.Entry<Double, Map<String, String>>> keyframes(String name) {
        return keyframes.get(name);
    }

    private static int line(String text, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) if (text.charAt(i) == '\n') line++;
        return line;
    }

    static Selector selector(String text) {
        if (text.isEmpty()) return null;
        List<Step> steps = new ArrayList<>();
        int[] weight = new int[3];
        boolean child = false;
        // "a > b" and "a>b" both name a child.
        for (String part : text.replace(">", " > ").trim().split("\\s+")) {
            if (part.equals(">")) {
                if (steps.isEmpty() || child) return null;
                child = true;
                continue;
            }
            Step step = step(part, child, weight);
            if (step == null) return null;
            steps.add(step);
            child = false;
        }
        if (child || steps.isEmpty()) return null;
        return new Selector(steps, weight[0] * 10000 + weight[1] * 100 + weight[2]);
    }

    private static Step step(String part, boolean child, int[] weight) {
        Matcher m = COMPOUND.matcher(part);
        if (!m.matches()) return null;
        String tag = m.group(1) == null || m.group(1).equals("*") ? null : m.group(1);
        String id = null;
        List<String> cls = new ArrayList<>();
        List<String> attrs = new ArrayList<>();
        List<Step> not = new ArrayList<>();
        boolean hover = false;
        boolean active = false;
        boolean root = false;
        String nth = null;
        Matcher bits = BIT.matcher(m.group(2));
        while (bits.find()) {
            if (bits.group(1) != null) {
                id = bits.group(1);
                weight[0]++;
            } else if (bits.group(2) != null) {
                cls.add(bits.group(2));
                weight[1]++;
            } else if (bits.group(3) != null) {
                attrs.add(bits.group(3));
                weight[1]++;
            } else {
                String pseudo = bits.group(4);
                String arg = bits.group(5) == null ? null : bits.group(5).trim();
                switch (pseudo) {
                    // The keyboard focus is where the cursor is put, so :focus is the hover.
                    case "hover", "focus" -> hover = true;
                    case "active" -> active = true;
                    case "root" -> root = true;
                    case "disabled", "checked", "selected" -> attrs.add(pseudo);
                    case "first-child" -> nth = "first";
                    case "last-child" -> nth = "last";
                    case "only-child" -> nth = "only";
                    case "nth-child" -> {
                        if (arg == null || !arg.matches("odd|even|\\d+")) return null;
                        nth = arg;
                    }
                    case "not" -> {
                        if (arg == null || arg.contains(" ")) return null;
                        Step negated = step(arg, false, weight);
                        if (negated == null) return null;
                        not.add(negated);
                    }
                    default -> {
                        return null;
                    }
                }
                if (!pseudo.equals("not")) weight[1]++;
            }
        }
        if (tag != null) weight[2]++;
        return new Step(tag, id, cls, attrs, hover, root, nth, not, child, active);
    }

    /**
     * The declarations that apply to {@code path}'s last element, the cascade already resolved and every
     * {@code var()} filled.
     *
     * @param path    the element and its ancestors, root first
     * @param hovered whether {@code :hover} on the last element matches
     */
    public Map<String, String> style(List<Target> path, boolean hovered) {
        return style(path, hovered ? HOVER : 0);
    }

    /** The same for pointer states: {@link #HOVER}, {@link #ACTIVE}, both or none. */
    @SuppressWarnings("unchecked")
    public Map<String, String> style(List<Target> path, int states) {
        Key key = new Key(List.copyOf(path), states);
        Object known = computed.getIfPresent(key);
        if (known != null) return (Map<String, String>) known;
        // Not computed inside the cache: a var() reads the parent's style, which is another entry.
        Map<String, String> style = java.util.Collections.unmodifiableMap(resolve(path, states));
        computed.put(key, style);
        return style;
    }

    private Map<String, String> resolve(List<Target> path, int states) {
        Map<String, String> own = cascade(path, states);
        if (!variables) return own;
        // Custom properties inherit: the parent's, then this element's own.
        Map<String, String> vars = new LinkedHashMap<>();
        if (path.size() > 1) {
            for (Map.Entry<String, String> e : style(path.subList(0, path.size() - 1), false).entrySet()) {
                if (e.getKey().startsWith("--")) vars.put(e.getKey(), e.getValue());
            }
        }
        for (Map.Entry<String, String> e : own.entrySet()) {
            if (e.getKey().startsWith("--")) vars.put(e.getKey(), fill(e.getValue(), vars, 0));
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : own.entrySet()) {
            if (!e.getKey().startsWith("--")) out.put(e.getKey(), fill(e.getValue(), vars, 0));
        }
        out.putAll(vars);
        return out;
    }

    private static String fill(String value, Map<String, String> vars, int depth) {
        if (!value.contains("var(") || depth > 8) return value;
        Matcher m = VAR.matcher(value);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String found = vars.get(m.group(1));
            String replacement = found != null ? found : m.group(2) != null ? m.group(2).trim() : "";
            m.appendReplacement(out, Matcher.quoteReplacement(fill(replacement, vars, depth + 1)));
        }
        m.appendTail(out);
        return out.toString();
    }

    private Map<String, String> cascade(List<Target> path, int states) {
        List<Rule> matched = new ArrayList<>();
        for (Rule rule : rules) if (matches(rule.selector(), path, states)) matched.add(rule);
        matched.sort((a, b) -> a.selector().specificity() != b.selector().specificity()
                ? Integer.compare(a.selector().specificity(), b.selector().specificity())
                : Integer.compare(a.order(), b.order()));
        Map<String, String> out = new LinkedHashMap<>();
        for (Rule rule : matched) out.putAll(rule.declarations());
        return out;
    }

    /** Whether any rule mentions {@code :hover} for this element, so it must be pointable. */
    public boolean hoverable(List<Target> path) {
        return (Boolean) computed.get(new Key(List.copyOf(path), -1), key -> anyState(path, true));
    }

    /** Whether any rule mentions {@code :active} for this element. */
    public boolean activable(List<Target> path) {
        return (Boolean) computed.get(new Key(List.copyOf(path), -2), key -> anyState(path, false));
    }

    private boolean anyState(List<Target> path, boolean hover) {
        for (Rule rule : rules) {
            List<Step> steps = rule.selector().steps();
            Step last = steps.get(steps.size() - 1);
            if ((hover ? last.hover() : last.active()) && matches(rule.selector(), path, HOVER | ACTIVE)) return true;
        }
        return false;
    }

    private static boolean matches(Selector selector, List<Target> path, int states) {
        List<Step> steps = selector.steps();
        int last = path.size() - 1;
        if (!steps.get(steps.size() - 1).matches(path.get(last), states)) return false;
        return ancestors(steps, steps.size() - 2, path, last - 1, steps.get(steps.size() - 1).child());
    }

    /**
     * Whether steps {@code 0..s} match the ancestors from {@code at} up, the step after {@code s} being a child
     * of the one at {@code s} when {@code child}. Backtracks, so {@code .a .b > c} finds the right {@code .b}.
     */
    private static boolean ancestors(List<Step> steps, int s, List<Target> path, int at, boolean child) {
        if (s < 0) return true;
        Step step = steps.get(s);
        for (int i = at; i >= 0; i--) {
            // :hover only means the element itself.
            if (step.matches(path.get(i), 0) && ancestors(steps, s - 1, path, i - 1, step.child())) return true;
            if (child) return false;
        }
        return false;
    }

    /** Names in a class attribute. */
    public static Set<String> classes(String attribute) {
        Set<String> out = new HashSet<>();
        if (attribute != null) for (String c : attribute.trim().split("\\s+")) if (!c.isEmpty()) out.add(c);
        return out;
    }
}
