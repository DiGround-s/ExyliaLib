package net.exylia.lib.screen.internal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The CSS subset a screen reads: rules of selectors and declarations, matched by specificity then order.
 *
 * <p>Selectors: a tag, {@code #id}, {@code .class}, {@code [attr]}, {@code :hover}, compounds of those, and
 * descendants separated by spaces; several selectors per rule separated by commas. Comments are
 * {@code /* ... * /}.
 *
 * <p>Internal: free to change without notice.
 */
public final class Css {

    /** Every property the screen reads; anything else is reported. */
    public static final Set<String> PROPERTIES = Set.of("flex-direction", "display", "gap", "padding", "width",
            "height", "flex-grow", "align-items", "justify-content", "background", "outline", "border", "color",
            "font-size", "font-weight", "text-align");

    /** One selector step: what one element must have. */
    record Step(String tag, String id, List<String> classes, List<String> attrs, boolean hover) {

        boolean matches(Target target, boolean hovered) {
            if (tag != null && !tag.equals(target.tag())) return false;
            if (id != null && !id.equals(target.id())) return false;
            for (String c : classes) if (!target.classes().contains(c)) return false;
            for (String a : attrs) if (!target.flags().contains(a)) return false;
            return !hover || hovered;
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
     */
    public record Target(String tag, String id, Set<String> classes, Set<String> flags) {
    }

    private final List<Rule> rules = new ArrayList<>();
    /** Computed styles by element path and hover: the same element in every row and every render asks again. */
    private final com.github.benmanes.caffeine.cache.Cache<Object, Object> computed =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(4096).build();

    private record Key(List<Target> path, int hovered) {
    }

    /** Reads {@code source} after any rules already here, so later sheets win ties. */
    public Css add(String source, String name, Consumer<String> problems) {
        // Palette tokens ({primary}) use the same braces as rules: set them aside while splitting.
        String text = source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("\\{([\\w-]+)}", "\uE000$1\uE001");
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
            Map<String, String> declarations = new LinkedHashMap<>();
            for (String declaration : text.substring(open + 1, close).split(";")) {
                if (declaration.isBlank()) continue;
                int colon = declaration.indexOf(':');
                if (colon < 0) {
                    problems.accept(name + ":" + line(text, open) + ": \"" + declaration.trim() + "\" has no value");
                    continue;
                }
                String property = declaration.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT);
                if (!PROPERTIES.contains(property)) {
                    problems.accept(name + ":" + line(text, open) + ": unknown property \"" + property + "\"");
                    continue;
                }
                declarations.put(property, declaration.substring(colon + 1).trim()
                        .replace('\uE000', '{').replace('\uE001', '}'));
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

    private static int line(String text, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) if (text.charAt(i) == '\n') line++;
        return line;
    }

    static Selector selector(String text) {
        if (text.isEmpty()) return null;
        List<Step> steps = new ArrayList<>();
        int ids = 0, classes = 0, tags = 0;
        for (String part : text.split("\\s+")) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("([a-zA-Z][\\w-]*|\\*)?((?:#[\\w-]+|\\.[\\w-]+|\\[[\\w-]+]|:hover)*)").matcher(part);
            if (!m.matches()) return null;
            String tag = m.group(1) == null || m.group(1).equals("*") ? null : m.group(1);
            String id = null;
            List<String> cls = new ArrayList<>();
            List<String> attrs = new ArrayList<>();
            boolean hover = false;
            java.util.regex.Matcher bits = java.util.regex.Pattern.compile("#([\\w-]+)|\\.([\\w-]+)|\\[([\\w-]+)]|(:hover)")
                    .matcher(m.group(2));
            while (bits.find()) {
                if (bits.group(1) != null) id = bits.group(1);
                else if (bits.group(2) != null) cls.add(bits.group(2));
                else if (bits.group(3) != null) attrs.add(bits.group(3));
                else hover = true;
            }
            if (tag != null) tags++;
            if (id != null) ids++;
            classes += cls.size() + attrs.size() + (hover ? 1 : 0);
            steps.add(new Step(tag, id, cls, attrs, hover));
        }
        return new Selector(steps, ids * 10000 + classes * 100 + tags);
    }

    /**
     * The declarations that apply to {@code path}'s last element, the cascade already resolved.
     *
     * @param path    the element and its ancestors, root first
     * @param hovered whether {@code :hover} on the last element matches
     */
    @SuppressWarnings("unchecked")
    public Map<String, String> style(List<Target> path, boolean hovered) {
        return (Map<String, String>) computed.get(new Key(List.copyOf(path), hovered ? 1 : 0),
                key -> java.util.Collections.unmodifiableMap(cascade(path, hovered)));
    }

    private Map<String, String> cascade(List<Target> path, boolean hovered) {
        List<Rule> matched = new ArrayList<>();
        for (Rule rule : rules) if (matches(rule.selector(), path, hovered)) matched.add(rule);
        matched.sort((a, b) -> a.selector().specificity() != b.selector().specificity()
                ? Integer.compare(a.selector().specificity(), b.selector().specificity())
                : Integer.compare(a.order(), b.order()));
        Map<String, String> out = new LinkedHashMap<>();
        for (Rule rule : matched) out.putAll(rule.declarations());
        return out;
    }

    /** Whether any rule mentions {@code :hover} for this element, so it must be pointable. */
    public boolean hoverable(List<Target> path) {
        return (Boolean) computed.get(new Key(List.copyOf(path), 2), key -> anyHover(path));
    }

    private boolean anyHover(List<Target> path) {
        for (Rule rule : rules) {
            List<Step> steps = rule.selector().steps();
            if (steps.get(steps.size() - 1).hover() && matches(rule.selector(), path, true)) return true;
        }
        return false;
    }

    private static boolean matches(Selector selector, List<Target> path, boolean hovered) {
        List<Step> steps = selector.steps();
        int at = path.size() - 1;
        if (!steps.get(steps.size() - 1).matches(path.get(at), hovered)) return false;
        // Descendant steps: each one somewhere further up, in order. :hover only means the element itself.
        for (int s = steps.size() - 2; s >= 0; s--) {
            Step step = steps.get(s);
            do {
                at--;
            } while (at >= 0 && !step.matches(path.get(at), false));
            if (at < 0) return false;
        }
        return true;
    }

    /** Names in a class attribute. */
    public static Set<String> classes(String attribute) {
        Set<String> out = new HashSet<>();
        if (attribute != null) for (String c : attribute.trim().split("\\s+")) if (!c.isEmpty()) out.add(c);
        return out;
    }
}
