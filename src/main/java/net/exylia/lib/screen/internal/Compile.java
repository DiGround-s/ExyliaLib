package net.exylia.lib.screen.internal;

import net.exylia.lib.screen.internal.Markup.El;
import net.exylia.lib.screen.internal.Markup.Part;
import net.exylia.lib.screen.internal.Markup.Pos;
import net.exylia.lib.screen.internal.Markup.Words;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a screen file into the tree a render walks, once per load: {@code <include>} files inlined,
 * {@code <template>}s collected and every use of one expanded (props filled, {@code <slot/>} replaced by the
 * caller's children), {@code t="key"} words put in, and everything checked, each problem with its file, line and
 * column. Nothing here reads a session: what depends on the viewer is left for the render.
 *
 * <p>Internal: free to change without notice.
 */
public final class Compile {

    /** Tags the render draws, beyond the screen's templates. */
    public static final Set<String> TAGS = Set.of("screen", "panel", "row", "column", "box", "text", "h1", "h2",
            "b", "small", "p", "span", "label", "button", "tabs", "tab", "chips", "chip", "grid", "list", "card",
            "empty", "toggle", "checkbox", "pager", "icon", "item", "item3d", "window", "canvas", "progress", "modal", "img", "search",
            // Structure (1.494.0): drawn as their children, never as a box of their own.
            "fragment", "for", "switch", "case", "default", "else");

    /** Attributes any tag may carry; a template's props are allowed on its uses too. */
    public static final Set<String> ATTRIBUTES = Set.of("preview", "id", "class", "if", "else", "track", "t", "key", "keybind",
            "action", "action-right", "selected", "disabled", "checked", "state", "default", "value", "source",
            "columns", "rows", "for", "keys", "material", "glyph", "head", "texture", "url", "src", "skin", "face",
            "turn", "max", "on", "off", "dismiss", "size", "backdrop", "scene", "parallax", "wheel", "each", "version", "previous", "next", "routes", "tooltip", "tooltip-item",
            // 1.496.0
            "action-shift", "assist", "priority", "refresh", "prompt", "hint",
            // 1.503.0
            "persist",
            // 1.498.0
            "vanish", "action-hover", "wearing", "spin",
            // 1.501.0
            "require", "deny", "require-right", "deny-right", "require-shift", "deny-shift",
            // 1.502.0: on <screen>, read by whichever plugin opens screens by command (ExyliaScreens)
            "command", "aliases", "permission",
            // 1.506.0: <item row/>, the row's own item
            "row",
            // 1.548.0: on <screen>, read by ExyliaScreens
            "fallback", "min-client", "args", "on-open",
            // 1.565.0: the element's own declarations, and a wait between clicks
            "style", "cooldown", "deny-cooldown");

    private static final Pattern PROP = Pattern.compile("\\$\\{\\s*([A-Za-z_][\\w-]*)\\s*}");
    private static final Pattern EACH = Pattern.compile("\\s*([A-Za-z_]\\w*)\\s+in\\s+(.+?)\\s*");
    private static final int DEPTH = 16;

    /** A file next to the screen, read on request: {@code null} when there is none. */
    @FunctionalInterface
    public interface Files {
        String read(String relative);
    }

    /** A template: its props with their defaults ({@code null} when the caller must give one) and its body. */
    record Template(String name, Map<String, String> props, El body) {
    }

    /** What a screen refers to by name, for checking against what the plugin registered. */
    public record Reference(String kind, String name, Pos pos) {
    }

    /** The compiled screen. */
    public record Result(El root, List<String> problems, List<Reference> references) {
    }

    private final Files files;
    private final Function<String, String> words;
    private final List<String> problems = new ArrayList<>();
    private final List<Reference> references = new ArrayList<>();
    private final Map<String, Template> templates = new LinkedHashMap<>();

    private Compile(Files files, Function<String, String> words) {
        this.files = files;
        this.words = words;
    }

    /**
     * Compiles a screen.
     *
     * @param root       the screen file's root
     * @param components fragments of shared {@code <template>}s, read before the screen's own
     * @param files      reads {@code <include src>} files, relative to the screen's folder
     * @param words      the screen's words by key, {@code null} for a key it lacks
     */
    public static Result run(El root, List<El> components, Files files, Function<String, String> words) {
        Compile compile = new Compile(files, words);
        for (El fragment : components) compile.collect(compile.include(fragment, 0));
        El inlined = compile.include(root, 0);
        El collected = compile.collect(inlined);
        El expanded = compile.expand(collected, 0);
        compile.check(expanded, null);
        return new Result(expanded, List.copyOf(compile.problems), List.copyOf(compile.references));
    }

    private void problem(Pos pos, String message) {
        problems.add(pos + ": " + message);
    }

    // ------------------------------------------------------------------
    // Includes
    // ------------------------------------------------------------------

    private El include(El el, int depth) {
        List<Part> children = new ArrayList<>(el.children().size());
        for (Part part : el.children()) {
            if (!(part instanceof El child)) {
                children.add(part);
            } else if (child.tag().equals("include")) {
                children.add(included(child, depth));
            } else {
                children.add(include(child, depth));
            }
        }
        return el.with(children);
    }

    private El included(El at, int depth) {
        String src = at.attr("src");
        El empty = new El("fragment", Map.of(), List.of(), at.pos());
        if (src == null || src.isBlank()) {
            problem(at.pos(), "<include> needs src=\"file.html\"");
            return empty;
        }
        if (depth >= DEPTH) {
            problem(at.pos(), "<include src=\"" + src + "\"> includes itself");
            return empty;
        }
        if (src.startsWith("/") || src.contains("..") || src.contains("\\") || src.contains(":")) {
            problem(at.pos(), "<include src=\"" + src + "\"> must name a file inside the screens folder");
            return empty;
        }
        String text = files.read(src);
        if (text == null) {
            problem(at.pos(), "<include src=\"" + src + "\"> finds no such file");
            return empty;
        }
        try {
            El fragment = Markup.fragment(text, src);
            Map<String, String> attrs = new LinkedHashMap<>();
            // An include may carry if/else like any element.
            for (String keep : new String[]{"if", "else"}) if (at.attr(keep) != null) attrs.put(keep, at.attr(keep));
            return new El("fragment", attrs, include(fragment, depth + 1).children(), at.pos());
        } catch (Markup.Malformed malformed) {
            problems.add(src + ":" + malformed.line + ": " + malformed.getMessage());
            return empty;
        }
    }

    // ------------------------------------------------------------------
    // Templates
    // ------------------------------------------------------------------

    /** Takes every {@code <template>} out of the tree into {@link #templates}. */
    private El collect(El el) {
        List<Part> children = new ArrayList<>(el.children().size());
        for (Part part : el.children()) {
            if (part instanceof El child && child.tag().equals("template")) {
                define(child);
            } else if (part instanceof El child) {
                children.add(collect(child));
            } else {
                children.add(part);
            }
        }
        return el.with(children);
    }

    private void define(El template) {
        String name = template.attr("name");
        if (name == null || !name.matches("[a-z][\\w-]*")) {
            problem(template.pos(), "<template> needs name=\"lower-case-name\"");
            return;
        }
        if (TAGS.contains(name)) {
            problem(template.pos(), "<template name=\"" + name + "\"> is already a tag");
            return;
        }
        Map<String, String> props = new LinkedHashMap<>();
        String declared = template.attr("props");
        if (declared != null) {
            for (String prop : declared.trim().split("\\s+")) {
                if (prop.isEmpty()) continue;
                int eq = prop.indexOf('=');
                if (eq < 0) props.put(prop, null);
                else props.put(prop.substring(0, eq), prop.substring(eq + 1));
            }
        }
        templates.put(name, new Template(name, props, collect(template)));
    }

    /** Every use of a template replaced by its body. */
    private El expand(El el, int depth) {
        List<Part> children = new ArrayList<>(el.children().size());
        for (Part part : el.children()) {
            if (part instanceof El child) children.add(use(child, depth));
            else children.add(part);
        }
        return el.with(children);
    }

    private Part use(El el, int depth) {
        Template template = templates.get(el.tag());
        if (template == null) return expand(el, depth);
        if (depth >= DEPTH) {
            problem(el.pos(), "<" + el.tag() + "> uses itself");
            return new El("fragment", Map.of(), List.of(), el.pos());
        }
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, String> passed = new LinkedHashMap<>();
        for (Map.Entry<String, String> prop : template.props().entrySet()) {
            String given = el.attr(prop.getKey());
            if (given == null && prop.getValue() == null) {
                problem(el.pos(), "<" + el.tag() + "> needs " + prop.getKey() + "=\"...\"");
            }
            values.put(prop.getKey(), given != null ? given : prop.getValue() != null ? prop.getValue() : "");
        }
        Map<String, String> outer = new LinkedHashMap<>();
        for (Map.Entry<String, String> attr : el.attrs().entrySet()) {
            String name = attr.getKey();
            if (template.props().containsKey(name)) continue;
            if (name.equals("if") || name.equals("else")) outer.put(name, attr.getValue());
            else passed.put(name, attr.getValue());
        }
        // The caller's own children, already expanded where the caller stands.
        List<Part> slot = new ArrayList<>();
        for (Part part : el.children()) slot.add(part instanceof El child ? use(child, depth) : part);
        List<Part> body = new ArrayList<>();
        for (Part part : template.body().children()) body.addAll(fill(part, values, slot));
        if (!passed.isEmpty()) {
            List<El> roots = new ArrayList<>();
            for (Part part : body) if (part instanceof El root) roots.add(root);
            if (roots.size() != 1) {
                problem(el.pos(), "<" + el.tag() + "> passes " + String.join(", ", passed.keySet())
                        + ", which needs a template with one root element");
            } else {
                El root = roots.get(0);
                Map<String, String> merged = new LinkedHashMap<>(root.attrs());
                for (Map.Entry<String, String> attr : passed.entrySet()) {
                    if (attr.getKey().equals("class") && merged.containsKey("class")) {
                        merged.put("class", merged.get("class") + " " + attr.getValue());
                    } else {
                        merged.put(attr.getKey(), attr.getValue());
                    }
                }
                body.set(body.indexOf(root), new El(root.tag(), merged, root.children(), root.pos()));
            }
        }
        El fragment = new El("fragment", outer, body, el.pos());
        return expand(fragment, depth + 1);
    }

    /** One part of a template body with its props written in and its {@code <slot/>} replaced. */
    private List<Part> fill(Part part, Map<String, String> values, List<Part> slot) {
        if (part instanceof Words words) return List.of(new Words(props(words.text(), values)));
        El el = (El) part;
        if (el.tag().equals("slot")) return slot;
        Map<String, String> attrs = new LinkedHashMap<>();
        for (Map.Entry<String, String> attr : el.attrs().entrySet()) attrs.put(attr.getKey(), props(attr.getValue(), values));
        List<Part> children = new ArrayList<>();
        for (Part child : el.children()) children.addAll(fill(child, values, slot));
        return List.of(new El(el.tag(), attrs, children, el.pos()));
    }

    private static String props(String text, Map<String, String> values) {
        if (text.indexOf("${") < 0) return text;
        Matcher m = PROP.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = values.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    // ------------------------------------------------------------------
    // Words and checks
    // ------------------------------------------------------------------

    /**
     * Puts the words in and reports what the render could not draw. Mutates the tree in place: it is this
     * compile's own copy.
     */
    private void check(El el, El previous) {
        String tag = el.tag();
        if (!TAGS.contains(tag)) problem(el.pos(), "unknown tag <" + tag + ">");
        for (Map.Entry<String, String> attr : List.copyOf(el.attrs().entrySet())) {
            String name = attr.getKey();
            if (name.startsWith("t-")) {
                String word = words.apply(attr.getValue());
                if (word != null) el.attrs().put(name.substring(2), word);
                continue;
            }
            if (!ATTRIBUTES.contains(name) && !name.startsWith("data-")) {
                problem(el.pos(), "unknown attribute " + name + " on <" + tag + ">");
            }
        }
        if (el.attr("style") != null) Css.inline(el.attr("style"), bad -> problem(el.pos(), "style: " + bad));
        String key = el.attr("t");
        if (key != null) {
            if (!el.elements().isEmpty()) {
                problem(el.pos(), "t=\"" + key + "\" needs an element with only text in it");
            } else {
                String word = words.apply(key);
                if (word != null) {
                    el.children().clear();
                    String collapsed = word.replaceAll("\\s+", " ").strip();
                    if (!collapsed.isEmpty()) el.children().add(new Words(collapsed));
                }
            }
        }
        for (Map.Entry<String, String> attr : el.attrs().entrySet()) {
            String name = attr.getKey();
            String value = attr.getValue();
            try {
                if (name.equals("if") || name.startsWith("require") || (name.equals("else") && !value.isEmpty())
                        || (name.equals("on") && tag.equals("switch"))) {
                    Expr.compile(value);
                } else if (!name.startsWith("action")) {
                    Interp.of(value);
                }
            } catch (Expr.Bad bad) {
                problem(el.pos(), name + "=\"" + value + "\": " + bad.getMessage() + " (at " + (bad.at + 1) + ")");
            }
        }
        if (tag.equals("else") || el.attr("else") != null) {
            if (previous == null || previous.attr("if") == null) {
                problem(el.pos(), "<" + tag + (tag.equals("else") ? "" : " else") + "> must follow an element with if=\"...\"");
            }
        }
        if (tag.equals("pager") && el.attr("for") == null) problem(el.pos(), "<pager> needs for=\"<source>\"");
        if (tag.equals("for")) {
            String each = el.attr("each");
            Matcher m = each == null ? null : EACH.matcher(each);
            if (m == null || !m.matches()) {
                problem(el.pos(), "<for> needs each=\"name in source\" (or \"i in 1..10\")");
            } else if (!m.group(2).contains("..") && m.group(2).matches("[A-Za-z_][\\w-]*")) {
                references.add(new Reference("source", m.group(2), el.pos()));
            }
        }
        if (tag.equals("switch")) {
            if (el.attr("on") == null) problem(el.pos(), "<switch> needs on=\"expression\"");
            for (El child : el.elements()) {
                if (!child.tag().equals("case") && !child.tag().equals("default")) {
                    problem(child.pos(), "<switch> holds only <case value=\"...\"> and <default>");
                }
            }
        }
        if (tag.equals("case") && el.attr("value") == null) problem(el.pos(), "<case> needs value=\"...\"");
        if (el.attr("source") != null) references.add(new Reference("source", el.attr("source"), el.pos()));
        for (String attr : new String[]{"action", "action-right", "action-shift", "action-hover", "dismiss", "deny",
                "deny-right", "deny-shift"}) {
            String action = el.attr(attr);
            if (action == null) continue;
            for (List<String> step : Actions.steps(action)) {
                if (!step.isEmpty()) references.add(new Reference("action", step.get(0), el.pos()));
                // confirm ... then <action>: the action it holds is checked too.
                int then = step.indexOf("then");
                if (then >= 0 && then + 1 < step.size()) references.add(new Reference("action", step.get(then + 1), el.pos()));
            }
        }
        El before = null;
        for (Part part : el.children()) {
            if (part instanceof El child) {
                check(child, before);
                before = child;
            } else if (part instanceof Words words) {
                try {
                    Interp.of(words.text());
                } catch (Expr.Bad bad) {
                    problem(el.pos(), "\"" + words.text() + "\": " + bad.getMessage());
                }
            }
        }
    }

    /** How actions read: steps split by {@code ;}, each step into words, quotes kept together. */
    public static final class Actions {
        private Actions() {
        }

        public static List<List<String>> steps(String raw) {
            List<List<String>> out = new ArrayList<>();
            List<String> step = new ArrayList<>();
            StringBuilder word = new StringBuilder();
            boolean any = false;
            char quote = 0;
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (quote != 0) {
                    if (c == quote) quote = 0;
                    else word.append(c);
                } else if (c == '$' && i + 1 < raw.length() && raw.charAt(i + 1) == '{') {
                    // An expression is one word, spaces and all.
                    int end = Interp.closing(raw, i + 2);
                    end = end < 0 ? raw.length() - 1 : end;
                    word.append(raw, i, end + 1);
                    i = end;
                } else if (c == '\'' || c == '"') {
                    quote = c;
                    any = true;
                } else if (c == ';') {
                    if (any || !word.isEmpty()) step.add(word.toString());
                    word.setLength(0);
                    any = false;
                    if (!step.isEmpty()) out.add(step);
                    step = new ArrayList<>();
                } else if (Character.isWhitespace(c)) {
                    if (any || !word.isEmpty()) step.add(word.toString());
                    word.setLength(0);
                    any = false;
                } else {
                    word.append(c);
                }
            }
            if (any || !word.isEmpty()) step.add(word.toString());
            if (!step.isEmpty()) out.add(step);
            return out;
        }
    }
}
