package net.exylia.lib.screen.internal;

import net.exylia.lib.action.ActionContext;
import net.exylia.lib.action.Actions;
import net.exylia.lib.screen.Node;
import net.exylia.lib.screen.ScreenEvent;
import net.exylia.lib.screen.ScreenSession;
import net.exylia.lib.screen.internal.Markup.El;
import net.exylia.lib.screen.internal.Markup.Part;
import net.exylia.lib.screen.internal.Markup.Words;
import net.exylia.lib.ui.UiEntry;
import org.bukkit.SoundCategory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a screen's compiled markup and style into a {@link Node} tree for one session, once per render.
 *
 * <p>Internal: free to change without notice.
 */
public final class MarkupRender {

    /** Every tag a screen knows; anything else is reported when the markup is read. */
    public static final Set<String> TAGS = Compile.TAGS;

    private static final Pattern NUMBER = Pattern.compile("(-?\\d+(?:\\.\\d+)?)(px)?");
    private static final Pattern RANGE = Pattern.compile("(.+?)\\.\\.(.+)");
    /** The tones {@code toast <tone> <text>} takes. */
    static final Set<String> TONES = Set.of("success", "error", "info", "warning");

    /** The plugin's names for a screen, shared by every compile of its files. */
    public record Bindings(Map<String, Function<ScreenSession, List<? extends Map<String, ?>>>> sources,
                           Map<String, Function<ScreenSession, List<UiEntry>>> entries,
                           Map<String, Function<ScreenSession, ?>> values,
                           Map<String, Function<ScreenSession, ?>> texts,
                           Map<String, BiConsumer<ScreenEvent, List<String>>> actions) {
        public static Bindings create() {
            return new Bindings(new java.util.concurrent.ConcurrentHashMap<>(), new java.util.concurrent.ConcurrentHashMap<>(),
                    new java.util.concurrent.ConcurrentHashMap<>(), new java.util.concurrent.ConcurrentHashMap<>(),
                    new java.util.concurrent.ConcurrentHashMap<>());
        }

        public boolean hasSource(String name) {
            return sources.containsKey(name) || entries.containsKey(name);
        }
    }

    /** What a render reads: the compiled markup, its style and words, and the plugin's bindings. */
    public record Parts(El root, Css css, Bindings bindings, Consumer<String> problems,
                        java.nio.file.@org.jetbrains.annotations.Nullable Path images, Function<String, String> words) {

        /** The shape before 1.494.0: no words, bindings given one map at a time. */
        public Parts(El root, Css css, Map<String, Function<ScreenSession, List<? extends Map<String, ?>>>> sources,
                     Map<String, Function<ScreenSession, ?>> values,
                     Map<String, BiConsumer<ScreenEvent, List<String>>> actions, Consumer<String> problems,
                     java.nio.file.@org.jetbrains.annotations.Nullable Path images) {
            this(root, css, new Bindings(sources, Map.of(), values, Map.of(), actions), problems, images, key -> null);
        }
    }

    /** One source's rows for this render: their fields, which are formatted, and what each row is about. */
    private record Rows(List<Map<String, ?>> maps, List<Set<String>> formatted, List<Object> objects) {
        static final Rows NONE = new Rows(List.of(), List.of(), List.of());

        int size() {
            return maps.size();
        }

        Scope scope(int i, String alias, Map<String, Object> loop, Scope parent) {
            return new Scope(maps.get(i), formatted.get(i), objects.get(i), alias, alias == null ? null : maps.get(i),
                    loop, parent);
        }
    }

    private final Parts parts;
    private final ScreenSession session;
    private final Map<String, Rows> rows = new HashMap<>();
    /** Grids by id or source, for pagers. */
    private final Map<String, El> grids = new HashMap<>();
    /** A {@code state} key's {@code default}, read before the player has picked anything. */
    private final Map<String, String> defaults = new HashMap<>();
    /** Binding values read so far in this render. */
    private final Map<String, Object> memo = new HashMap<>();

    private MarkupRender(Parts parts, ScreenSession session) {
        this.parts = parts;
        this.session = session;
        index(parts.root());
    }

    /** The node tree of {@code parts} for {@code session}, as it stands now. */
    public static Node render(Parts parts, ScreenSession session) {
        MarkupRender render = new MarkupRender(parts, session);
        Scope top = new Scope(null, null, null, null, null, null, null);
        List<Node> nodes = render.build(new Inst(parts.root(), top, null), new ArrayList<>(), Map.of(), 1, 1);
        return nodes.isEmpty() ? Node.box() : nodes.get(0);
    }

    private void index(El el) {
        if (el.attr("state") != null && el.attr("default") != null) defaults.putIfAbsent(el.attr("state"), el.attr("default"));
        if (el.attr("source") != null && (el.attr("columns") != null || el.attr("rows") != null
                || el.tag().equals("grid") || el.tag().equals("list"))) {
            grids.put(el.attr("source"), el);
            if (el.attr("id") != null) grids.put(el.attr("id"), el);
        }
        for (El child : el.elements()) index(child);
    }

    // ------------------------------------------------------------------
    // Values
    // ------------------------------------------------------------------

    /**
     * Where names are looked up: the row being repeated (innermost first), then values, then state.
     *
     * @param formatted the row's fields that carry their own formatting; {@code null} when all of them do
     * @param object    what the row is about, handed to a click as {@link ScreenEvent#row()}
     * @param alias     the name a {@code <for each="kit in kits">} gives the row, or {@code null}
     * @param aliased   what that name reads
     * @param loop      {@code loop.index}, {@code loop.count}... inside a {@code <for>}
     */
    private record Scope(Map<String, ?> row, Set<String> formatted, Object object, String alias, Object aliased,
                         Map<String, Object> loop, Scope parent) {
    }

    private Object lookup(String name, Scope scope) {
        for (Scope s = scope; s != null; s = s.parent()) {
            if (s.alias() != null) {
                if (name.equals(s.alias())) return s.aliased();
                if (name.startsWith(s.alias() + ".")) return field(s.aliased(), name.substring(s.alias().length() + 1), s);
            }
            if (s.loop() != null && name.startsWith("loop.")) return s.loop().get(name.substring(5));
            // A value that is there but empty is empty text, never the placeholder's own name on screen.
            if (s.row() != null && s.row().containsKey(name)) return mark(s.row().get(name), s, name);
        }
        Function<ScreenSession, ?> value = parts.bindings().values().get(name);
        // Once a render: a value read by every row of a grid is asked for once.
        if (value != null) return memo.computeIfAbsent(name, ignored -> Objects.requireNonNullElse(value.apply(session), ""));
        Function<ScreenSession, ?> text = parts.bindings().texts().get(name);
        if (text != null) return literal(Objects.requireNonNullElse(text.apply(session), ""));
        Object state = session.state(name, "");
        // State is the player's doing (a picked name, a search), so it is drawn as plain text.
        if (!"".equals(state)) return literal(state);
        net.exylia.lib.ui.UiSession menu = menu();
        if (menu != null) {
            // A screen opened for a chest menu reads that menu's values, as its YAML does.
            Object context = menu.context().get(name);
            if (context instanceof java.util.function.Supplier<?> reader) context = reader.get();
            if (context != null) return context;
        }
        String fallback = defaults.get(name);
        if (fallback != null) {
            // A default may itself be a placeholder (default="%first_mode%"); one that names itself stays as written.
            return fallback.contains("%" + name + "%") ? fallback : fill(fallback, scope, false);
        }
        int dot = name.indexOf('.');
        if (dot > 0) {
            Object head = lookup(name.substring(0, dot), scope);
            if (head != null && !"".equals(head)) return field(head, name.substring(dot + 1), null);
        }
        return null;
    }

    /** A field of a row map read by a dotted name: {@code kit.kit_name}. */
    private static Object field(Object value, String path, Scope scope) {
        Object at = Expr.plain(value);
        for (String part : path.split("\\.")) {
            if (at instanceof UiEntry entry) at = entry.values();
            if (!(at instanceof Map<?, ?> map)) return null;
            Object next = map.get(part);
            if (next == null) return map.containsKey(part) ? "" : null;
            at = scope != null && scope.formatted() != null && !scope.formatted().contains(part) ? literal(next) : next;
        }
        return at;
    }

    private static Object mark(Object value, Scope scope, String name) {
        if (value == null) return "";
        return scope.formatted() == null || scope.formatted().contains(name) ? value : literal(value);
    }

    private static Object literal(Object value) {
        return value instanceof String text ? new Expr.Lit(text) : value;
    }

    /** What expressions read in a scope. */
    private Expr.Env env(Scope scope) {
        return new Expr.Env() {
            @Override
            public Object name(String name) {
                return lookup(name, scope);
            }

            @Override
            public Object placeholder(String name) {
                Object known = lookup(name, scope);
                return known != null ? known : library(name);
            }

            @Override
            public String word(String key) {
                return parts.words().apply(key);
            }

            @Override
            public Object literal(String text) {
                return fill(text, scope, false);
            }

            @Override
            public org.bukkit.entity.Player viewer() {
                try {
                    return session.viewer();
                } catch (RuntimeException noViewer) {
                    return null;
                }
            }
        };
    }

    /** A {@code %name%} none of the screen's names answer, from the lib's placeholders (and PlaceholderAPI). */
    private Object library(String name) {
        try {
            String written = "%" + name + "%";
            String resolved = net.exylia.lib.placeholder.Placeholders.apply(written, session.viewer());
            return resolved.equals(written) ? null : resolved;
        } catch (RuntimeException | LinkageError noServer) {
            // Only without a server (a unit test) or a viewer.
            return null;
        }
    }

    /**
     * {@code %name%} and {@code ${...}} filled from the scope; an unknown name is left as written.
     *
     * @param draw whether the text is drawn, so plain values are escaped
     */
    String fill(String text, Scope scope, boolean draw) {
        if (!Interp.dynamic(text)) return text;
        try {
            return Interp.of(text).fill(env(scope), draw);
        } catch (Expr.Bad bad) {
            // Reported when the file was read; drawn as nothing rather than as broken code.
            return "";
        }
    }

    private Object value(String text, Scope scope) {
        if (!Interp.dynamic(text)) return text;
        try {
            return Interp.of(text).value(env(scope));
        } catch (Expr.Bad bad) {
            return "";
        }
    }

    static boolean truthy(Object value) {
        return Expr.truthy(value);
    }

    /** An {@code if}: the whole expression language, which reads the conditions written before 1.494.0 the same. */
    boolean test(String expression, Scope scope) {
        try {
            return Expr.compile(expression.trim()).test(env(scope));
        } catch (Expr.Bad bad) {
            return false;
        }
    }

    /** The chest menu this screen was opened for in SCREEN style, or {@code null}. */
    private net.exylia.lib.ui.UiSession menu() {
        Object menu = session.state(ScreenRoutes.MENU, "");
        return menu instanceof net.exylia.lib.ui.UiSession open ? open : null;
    }

    private Rows rows(String source) {
        return rows.computeIfAbsent(source, name -> {
            Function<ScreenSession, List<UiEntry>> entries = parts.bindings().entries().get(name);
            net.exylia.lib.ui.UiSession menu = entries == null && !parts.bindings().sources().containsKey(name) ? menu() : null;
            if (menu != null && menu.definition().section(name) != null) {
                // The chest menu's own list, as the plugin filled it.
                entries = ignored -> menu.entries(name);
            }
            if (entries != null) {
                List<UiEntry> list = entries.apply(session);
                if (list == null) return Rows.NONE;
                List<Map<String, ?>> maps = new ArrayList<>(list.size());
                List<Set<String>> formatted = new ArrayList<>(list.size());
                List<Object> objects = new ArrayList<>(list.size());
                for (UiEntry entry : list) {
                    UiEntry now = entry.isLive() ? entry.refreshed() : entry;
                    maps.add(now.values());
                    formatted.add(now.formatted());
                    objects.add(now.value() != null ? now.value() : now);
                }
                return new Rows(maps, formatted, objects);
            }
            Function<ScreenSession, List<? extends Map<String, ?>>> supplier = parts.bindings().sources().get(name);
            if (supplier == null) {
                parts.problems().accept("no source \"" + name + "\" is registered");
                return Rows.NONE;
            }
            List<? extends Map<String, ?>> list = supplier.apply(session);
            if (list == null) return Rows.NONE;
            List<Map<String, ?>> maps = new ArrayList<>(list);
            List<Set<String>> formatted = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) formatted.add(null);
            return new Rows(maps, formatted, new ArrayList<>(list));
        });
    }

    // ------------------------------------------------------------------
    // Structure: if, else, for, switch, fragments
    // ------------------------------------------------------------------

    /**
     * One element to draw, in the scope it is drawn in.
     *
     * @param id the id it takes, when a {@code track} gave its row one
     */
    private record Inst(El el, Scope scope, String id) {
    }

    /** The parts of {@code children} that are drawn, structure resolved: words stay words. */
    private List<Object> expand(List<Part> children, Scope scope) {
        List<Object> out = new ArrayList<>();
        boolean chain = false;
        boolean satisfied = false;
        for (Part part : children) {
            if (part instanceof Words) {
                out.add(part);
                continue;
            }
            El el = (El) part;
            boolean otherwise = el.tag().equals("else") || el.attr("else") != null;
            String condition = el.attr("if");
            if (otherwise) {
                if (!chain || satisfied) continue;
                if (condition != null && !test(condition, scope)) continue;
                satisfied = true;
            } else if (condition != null) {
                chain = true;
                satisfied = test(condition, scope);
                if (!satisfied) continue;
            } else {
                chain = false;
            }
            switch (el.tag()) {
                case "else", "fragment", "case", "default" -> out.addAll(expand(el.children(), scope));
                case "switch" -> out.addAll(expand(cases(el, scope), scope));
                case "for" -> out.addAll(loop(el, scope));
                default -> out.add(new Inst(el, scope, null));
            }
        }
        return out;
    }

    private List<Part> cases(El el, Scope scope) {
        String on = Expr.text(value("${" + el.attr("on") + "}", scope));
        El fallback = null;
        for (El option : el.elements()) {
            if (option.tag().equals("default")) {
                fallback = option;
                continue;
            }
            String value = option.attr("value");
            if (value == null) continue;
            for (String one : fill(value, scope, false).split("\\|")) {
                if (Expr.equal(one.trim(), on)) return option.children();
            }
        }
        return fallback == null ? List.of() : fallback.children();
    }

    /** {@code <for each="kit in kits">}: its children once per row, a range or a list. */
    private List<Object> loop(El el, Scope scope) {
        String each = el.attr("each");
        Matcher m = each == null ? null : Pattern.compile("\\s*([A-Za-z_]\\w*)\\s+in\\s+(.+?)\\s*").matcher(each);
        if (m == null || !m.matches()) return List.of();
        String alias = m.group(1);
        String over = m.group(2);
        Rows rows;
        Matcher range = RANGE.matcher(over);
        if (parts.bindings().hasSource(over)) {
            rows = rows(over);
        } else if (range.matches()) {
            Double from = Expr.number(value("${" + range.group(1) + "}", scope));
            Double to = Expr.number(value("${" + range.group(2) + "}", scope));
            List<Map<String, ?>> maps = new ArrayList<>();
            List<Object> objects = new ArrayList<>();
            if (from != null && to != null) {
                int a = (int) Math.round(from), b = (int) Math.round(to);
                // A range is a counter, not a list to page: kept short so a typo cannot draw a thousand boxes.
                for (int i = a; (a <= b ? i <= b : i >= b) && maps.size() < 256; i += a <= b ? 1 : -1) {
                    maps.add(null);
                    objects.add((double) i);
                }
            }
            List<Set<String>> formatted = new ArrayList<>();
            for (int i = 0; i < maps.size(); i++) formatted.add(null);
            rows = new Rows(maps, formatted, objects);
        } else {
            Object list = Expr.plain(value("${" + over + "}", scope));
            List<Map<String, ?>> maps = new ArrayList<>();
            List<Object> objects = new ArrayList<>();
            if (list instanceof java.util.Collection<?> collection) {
                for (Object one : collection) {
                    maps.add(one instanceof Map<?, ?> map ? cast(map) : null);
                    objects.add(one);
                }
            } else if (list != null && !"".equals(list)) {
                parts.problems().accept(el.pos() + ": <for each=\"" + each + "\"> reads no source or list");
            }
            List<Set<String>> formatted = new ArrayList<>();
            for (int i = 0; i < maps.size(); i++) formatted.add(null);
            rows = new Rows(maps, formatted, objects);
        }
        String prefix = el.attr("id") != null ? el.attr("id") : "for" + el.pos().line() + "_" + el.pos().col();
        Set<String> seen = new HashSet<>();
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> meta = Map.of("index", (double) i, "number", (double) (i + 1), "count",
                    (double) rows.size(), "first", i == 0, "last", i == rows.size() - 1);
            Scope row = rows.maps().get(i) == null
                    ? new Scope(null, null, rows.objects().get(i), alias, rows.objects().get(i), meta, scope)
                    : rows.scope(i, alias, meta, scope);
            String track = track(el, row, prefix, seen);
            List<Object> drawn = expand(el.children(), row);
            int n = 0;
            for (Object one : drawn) {
                if (one instanceof Inst inst && track != null) {
                    out.add(new Inst(inst.el(), inst.scope(), track + (n++ == 0 ? "" : "." + n)));
                } else {
                    out.add(one);
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> cast(Map<?, ?> map) {
        return (Map<String, ?>) map;
    }

    /** A row's identity from {@code track="%kit_id%"}, unique within its list, or {@code null} without one. */
    private String track(El el, Scope row, String prefix, Set<String> seen) {
        String track = el.attr("track");
        if (track == null) return null;
        String key = fill(track, row, false);
        String id = prefix + "@" + key;
        for (int n = 2; !seen.add(id); n++) id = prefix + "@" + key + "~" + n;
        return id;
    }

    // ------------------------------------------------------------------
    // Building
    // ------------------------------------------------------------------

    /**
     * The nodes one element draws: none when hidden, one usually, one per row when it repeats.
     *
     * @param path      the targets of its ancestors, root first
     * @param inherited the text properties it inherits: color, font-size, font-weight
     * @param index     its place among its parent's drawn elements, from 1
     * @param count     how many its parent draws
     */
    private List<Node> build(Inst inst, List<Css.Target> path, Map<String, String> inherited, int index, int count) {
        El el = inst.el();
        Scope scope = inst.scope();

        Set<String> flags = new HashSet<>();
        for (String flag : new String[]{"selected", "disabled", "checked"}) {
            String raw = el.attr(flag);
            if (raw != null && (raw.isEmpty() || truthy(value(raw, scope)))) flags.add(flag);
        }
        String option = option(el, path, scope);
        if (option != null && option.equals(current(stateOwner(path), scope))) flags.add("selected");
        String toggleKey = el.attr("state") != null && (el.tag().equals("toggle") || el.tag().equals("checkbox"))
                ? el.attr("state") : null;
        if (toggleKey != null && truthy(session.state(toggleKey, false))) flags.add("checked");

        Css.Target target = new Css.Target(el.tag(), el.attr("id"), Css.classes(el.attr("class")), flags, index, count);
        List<Css.Target> here = new ArrayList<>(path);
        here.add(target);
        Map<String, String> style = parts.css().style(here, false);
        if ("none".equalsIgnoreCase(style.get("display"))) return List.of();
        Map<String, String> text = new HashMap<>(inherited);
        for (String p : Css.INHERITED) if (style.containsKey(p)) text.put(p, style.get(p));

        Node node;
        String source = el.attr("source");
        if (source != null) {
            node = repeat(el, source, scope, here, text, style);
        } else if (el.tag().equals("pager")) {
            node = pager(el, scope, text);
        } else if (el.tag().equals("toggle") || el.tag().equals("checkbox")) {
            boolean on = flags.contains("checked");
            String label = el.tag().equals("checkbox") ? (on ? "✔" : " ")
                    : (on ? attrOr(el, "on", "On") : attrOr(el, "off", "Off"));
            node = words(fill(label, scope, true), text);
        } else if (el.tag().equals("item")) {
            org.bukkit.inventory.ItemStack stack = item(el, scope);
            node = stack == null ? Node.box().size(Layout.ITEM, Layout.ITEM) : Node.item(stack);
        } else if (el.tag().equals("canvas")) {
            // <canvas id="table">: the session's scene of that id, sized by CSS (1.500.0).
            String id = el.attr("id") == null ? "canvas" : el.attr("id");
            node = session.scene(id).node(px(style.get("width"), 160), px(style.get("height"), 90));
        } else if (el.tag().equals("item3d")) {
            // <item3d material="..." spin="36">: the real model, turning; a bare spin turns 36 degrees a second.
            org.bukkit.inventory.ItemStack stack = item(el, scope);
            String spin = el.attr("spin") == null ? null : fill(el.attr("spin"), scope, false).trim();
            double speed = spin == null ? 0 : spin.isEmpty() ? 36 : px(spin, 36);
            node = stack == null ? Node.box().size(Layout.ITEM, Layout.ITEM) : Node.item3d(stack, speed);
        } else if (el.tag().equals("img")) {
            node = image(el, scope);
        } else if (el.tag().equals("search")) {
            node = search(el, scope, text);
        } else if (el.tag().equals("progress")) {
            node = progress(el, scope, style);
        } else if (el.tag().equals("icon")) {
            String material = fill(el.attr("material") != null ? el.attr("material") : el.text(), scope, false).trim();
            // ponytail: block items whose sprite lives under block/ fall back to the glyph; map them when needed.
            String glyph = attrOr(el, "glyph", "◆");
            node = words("{icon:items:item/" + material.toLowerCase(Locale.ROOT) + "|" + glyph + "}", text);
        } else if (el.elements().isEmpty() && !el.text().isEmpty()) {
            node = words(fill(el.text(), scope, true), text);
        } else {
            node = container(style);
            boolean own = el.attr("state") != null;
            if (own) owners.add(el);
            try {
                List<Object> drawn = expand(el.children(), scope);
                int elements = 0;
                for (Object one : drawn) if (one instanceof Inst) elements++;
                int at = 0;
                for (Object one : drawn) {
                    if (one instanceof Words words) node.add(words(fill(words.text(), scope, true), text));
                    else if (one instanceof Inst child) {
                        for (Node built : build(child, here, text, ++at, elements)) {
                            node.add(child.id() != null ? built.id(child.id()) : built);
                        }
                    }
                }
            } finally {
                if (own) owners.remove(owners.size() - 1);
            }
        }
        box(node, style, parts.css());
        if (style.containsKey("background-image")) backgroundImage(node, el, style.get("background-image"));
        if (el.attr("id") != null && source == null) node.id(fill(el.attr("id"), scope, false));
        if ("low".equalsIgnoreCase(el.attr("priority"))) node.decoration(true);
        if (el.attr("refresh") != null) node.refresh(Math.max(0, MotionCss.ticks(el.attr("refresh"))));
        String key = el.attr("key") != null ? el.attr("key") : el.attr("keybind");
        if (key != null) node.key(key);
        if (el.attr("tooltip") != null) {
            String tip = fill(el.attr("tooltip"), scope, true);
            if (!tip.isBlank()) node.tooltip(tip.split("<nl>|<br>|\\n"));
        }
        if (el.tag().equals("modal")) {
            String dismiss = el.attr("dismiss");
            String condition = el.attr("if");
            if (dismiss == null && condition != null && condition.trim().matches("[\\w.-]+")) dismiss = "clear " + condition.trim();
            node.overlay(true);
            if (dismiss != null && !dismiss.isBlank()) {
                List<List<String>> steps = actions(dismiss, scope);
                Object row = rowOf(scope);
                node.onDismiss(open -> runAll(steps, new ScreenEvent(open, ScreenEvent.Kind.LEFT, 0, null, row), el));
            }
        }

        if (parts.css().hoverable(here)) {
            Map<String, String> hover = parts.css().style(here, true);
            String background = hover.get("background");
            node.hover(background != null ? background : style.get("background"));
            String[] line = outline(hover.containsKey("outline") ? hover.get("outline") : hover.get("border"));
            if (line != null) node.hoverOutline(line[0]);
            String moved = hover.get("transform");
            if (moved != null && !moved.equals(style.get("transform"))) node.hoverTransform(MotionCss.transform(moved));
        }
        if (parts.css().activable(here)) {
            Map<String, String> pressed = parts.css().style(here, Css.HOVER | Css.ACTIVE);
            String moved = pressed.get("transform");
            String fill = pressed.get("background");
            node.active(fill != null && !fill.equals(node.hover()) ? fill : null,
                    moved != null && !moved.equals(style.get("transform")) ? MotionCss.transform(moved) : null);
        }
        click(el, node, scope, flags, option, path, toggleKey);
        return List.of(node);
    }

    /** The value an option sets, when this element sits inside an element that keeps a {@code state}. */
    private String option(El el, List<Css.Target> path, Scope scope) {
        String value = el.attr("value");
        if (value == null || stateOwner(path) == null) return null;
        return fill(value, scope, false);
    }

    /** Owners of state, innermost last; set while building so options find theirs. */
    private final List<El> owners = new ArrayList<>();

    private El stateOwner(List<Css.Target> path) {
        return owners.isEmpty() ? null : owners.get(owners.size() - 1);
    }

    private String current(El owner, Scope scope) {
        if (owner == null) return null;
        Object value = session.state(owner.attr("state"), "");
        if (!"".equals(value)) return String.valueOf(value);
        String fallback = owner.attr("default");
        return fallback == null ? null : fill(fallback, scope, false);
    }

    private Node container(Map<String, String> style) {
        return "row".equalsIgnoreCase(style.get("flex-direction")) ? Node.row() : Node.column();
    }

    /** An {@code <empty>}, or a template use ({@code <empty-state>}) that comes out as only empties. */
    private static boolean emptyNote(El e) {
        if (e.tag().equals("empty")) return true;
        if (!e.tag().equals("fragment")) return false;
        boolean any = false;
        for (Part part : e.children()) {
            if (!(part instanceof El child)) continue;
            if (!emptyNote(child)) return false;
            any = true;
        }
        return any;
    }

    private Node repeat(El el, String source, Scope scope, List<Css.Target> here, Map<String, String> text,
                        Map<String, String> style) {
        Rows all = rows(source);
        List<Part> template = new ArrayList<>();
        // Several empties may each carry an if, so the message can match why the list is empty.
        List<Part> empties = new ArrayList<>();
        for (Part child : el.children()) {
            if (child instanceof El e && emptyNote(e)) empties.add(e);
            else if (child instanceof El) template.add(child);
        }
        Node node = container(style);
        node.id(el.attr("id") != null ? el.attr("id") : source);
        if (all.size() == 0) {
            for (Object one : expand(empties, scope)) {
                if (one instanceof Inst empty) for (Node built : build(empty, here, text, 1, 1)) node.add(built.id(source + ".empty"));
            }
            return node;
        }
        boolean windowed = grids.get(source) == el;
        int columns = windowed ? Math.max(1, intAttr(el, "columns", 1)) : 1;
        int window = windowed && el.attr("rows") != null ? columns * Math.max(1, intAttr(el, "rows", 1)) : all.size();
        String key = "scroll." + source;
        int offset = offset(key, all.size(), window, columns);
        int end = Math.min(all.size(), offset + window);
        int visible = end - offset;
        boolean own = el.attr("state") != null;
        Set<String> seen = new HashSet<>();
        if (own) owners.add(el);
        try {
            Node line = null;
            for (int i = offset; i < end; i++) {
                Scope row = all.scope(i, null, null, scope);
                String track = track(el, row, source, seen);
                String base = track != null ? track : source + "." + i;
                List<Object> drawn = expand(template, row);
                List<Node> built = new ArrayList<>();
                List<Integer> slots = new ArrayList<>();
                for (Object one : drawn) {
                    if (!(one instanceof Inst inst)) continue;
                    // Which child of the template it is, so a hidden sibling never renames the others.
                    int slot = template.indexOf(inst.el());
                    for (Node b : build(inst, here, text, i - offset + 1, visible)) {
                        built.add(b);
                        slots.add(slot >= 0 ? slot : slots.size());
                    }
                }
                if (columns == 1) {
                    for (int c = 0; c < built.size(); c++) {
                        // Untracked rows keep the ids they had before 1.494.0: source.row.child.
                        String id = track != null ? (c == 0 ? base : base + "." + slots.get(c)) : base + "." + slots.get(c);
                        node.add(built.get(c).id(id));
                    }
                    continue;
                }
                int column = (i - offset) % columns;
                if (column == 0) {
                    // A line fills its container: stretched across a column, grown along a row.
                    line = Node.row().gap(px(style.get("gap"), 0)).grow(node.kind() == Node.Kind.ROW ? 1 : 0);
                    node.add(line);
                }
                for (int c = 0; c < built.size(); c++) {
                    Node cell = built.get(c);
                    // Every column the same width, whatever its words need, as CSS grid columns are.
                    if (cell.width() < 0) cell.width(0);
                    line.add(cell.grow(1).id(c == 0 ? base : base + "." + c));
                }
                if (i == end - 1) for (int pad = column + 1; pad < columns; pad++) line.add(Node.box().width(0).grow(1));
            }
        } finally {
            if (own) owners.remove(owners.size() - 1);
        }
        if (windowed && window < all.size()) {
            int size = all.size();
            node.onScroll(event -> event.session().set(key, offset(key, size, window, columns)
                    + event.steps() * columns));
        }
        return node;
    }

    /** Where a window starts, clamped to the rows there are, on a row boundary. */
    private int offset(String key, int size, int window, int columns) {
        int raw = toInt(session.state(key, 0));
        int last = Math.max(0, size - window);
        last = (last + columns - 1) / columns * columns;
        return Math.max(0, Math.min(last, raw / columns * columns));
    }

    private Node pager(El el, Scope scope, Map<String, String> text) {
        El grid = grids.get(el.attr("for"));
        if (grid == null) {
            parts.problems().accept(el.pos() + ": pager for \"" + el.attr("for") + "\" finds no grid");
            return Node.row();
        }
        String source = grid.attr("source");
        int size = rows(source).size();
        int columns = Math.max(1, intAttr(grid, "columns", 1));
        int window = columns * Math.max(1, intAttr(grid, "rows", 1));
        String key = "scroll." + source;
        int offset = offset(key, size, window, columns);
        int pages = Math.max(1, (size + window - 1) / window);
        // One page needs no pager.
        if (pages == 1) return Node.row();
        int page = Math.min(pages, (offset + window - 1) / window + 1);
        Node previous = words(fill(attrOr(el, "previous", "‹"), scope, true), text).id(source + ".previous")
                .onClick(event -> event.session().set(key, Math.max(0, offset - window)));
        Node next = words(fill(attrOr(el, "next", "›"), scope, true), text).id(source + ".next")
                .onClick(event -> event.session().set(key, offset + window < size ? offset + window : offset));
        Node label = words(page + " / " + pages, text).id(source + ".page");
        // keys="a d": the movement keys that turn the page back and on.
        String[] keys = attrOr(el, "keys", "").trim().split("\\s+");
        if (keys.length == 2) {
            previous.key(keys[0]);
            next.key(keys[1]);
        }
        return Node.row(previous, label, next).align(Node.Align.CENTER);
    }

    /** What the innermost row is about, for {@link ScreenEvent#row()}. */
    private static Object rowOf(Scope scope) {
        for (Scope s = scope; s != null; s = s.parent()) if (s.object() != null) return s.object();
        return null;
    }

    private void click(El el, Node node, Scope scope, Set<String> flags, String option, List<Css.Target> path,
                       String toggleKey) {
        List<List<String>> action = gated(el, "", el.attr("action") == null ? null : actions(el.attr("action"), scope), scope);
        List<List<String>> right = gated(el, "-right",
                el.attr("action-right") == null ? null : actions(el.attr("action-right"), scope), scope);
        List<List<String>> shift = gated(el, "-shift",
                el.attr("action-shift") == null ? null : actions(el.attr("action-shift"), scope), scope);
        List<List<String>> hover = el.attr("action-hover") == null ? null : actions(el.attr("action-hover"), scope);
        if (hover != null) {
            Object hoverRow = rowOf(scope);
            node.onHover(event -> runAll(hover, new ScreenEvent(event.session(), event.kind(), 0, event.node(), hoverRow,
                    false), el));
        }
        El owner = option == null ? null : stateOwner(path);
        if (shift != null) {
            boolean off = flags.contains("disabled");
            Object shiftRow = rowOf(scope);
            node.onShiftClick(event -> {
                if (off) {
                    event.viewer().playSound(event.viewer().getLocation(), "entity.villager.no", SoundCategory.MASTER, 0.4f, 1f);
                    return;
                }
                runAll(shift, new ScreenEvent(event.session(), event.kind(), event.steps(), event.node(), shiftRow, true), el);
            });
        }
        if (action == null && right == null && owner == null && toggleKey == null) return;
        boolean disabled = flags.contains("disabled");
        String stateKey = owner == null ? null : owner.attr("state");
        Object row = rowOf(scope);
        node.onClick(event -> {
            if (disabled) {
                event.viewer().playSound(event.viewer().getLocation(), "entity.villager.no", SoundCategory.MASTER, 0.4f, 1f);
                return;
            }
            ScreenEvent here = new ScreenEvent(event.session(), event.kind(), event.steps(), event.node(), row, event.shift());
            // A right click runs action-right when there is one, and everything else otherwise.
            if (right != null && event.kind() == ScreenEvent.Kind.RIGHT) {
                runAll(right, here, el);
                return;
            }
            if (stateKey != null) event.session().set(stateKey, option);
            if (toggleKey != null) event.session().set(toggleKey, !truthy(event.session().state(toggleKey, false)));
            if (action != null) runAll(action, here, el);
        });
    }

    /**
     * {@code require="expr"} (every click) and {@code require-right}/{@code require-shift} (that click): when the
     * expression is false the click runs {@code deny} (or {@code deny-right}/{@code deny-shift}) instead, or a quiet
     * refusal sound when there is none (1.501.0).
     */
    private List<List<String>> gated(El el, String which, List<List<String>> steps, Scope scope) {
        if (steps == null) return null;
        for (String suffix : which.isEmpty() ? List.of("") : List.of(which, "")) {
            String require = el.attr("require" + suffix);
            if (require == null || test(require, scope)) continue;
            String deny = el.attr("deny" + suffix);
            if (deny == null && !suffix.isEmpty()) deny = el.attr("deny");
            return deny != null ? actions(deny, scope) : List.of(List.of("sound", "entity.villager.no", "0.4"));
        }
        return steps;
    }

    /**
     * Whether a step is an {@link net.exylia.lib.interaction.Interactions} line ({@code [console] ...},
     * {@code [message] ...}, {@code [MONEY] take 100}, {@code console: ...}) rather than a screen action (1.501.0).
     */
    public static boolean isLine(String first) {
        if (first.length() > 2 && first.charAt(0) == '[' && first.indexOf(']') > 1) return true;
        String lower = first.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("console:") || lower.equals("player:") || lower.equals("console-proxy:")
                || lower.equals("player-proxy:");
    }

    // ponytail: cleared whole when full, since filled lines hold player names; an LRU if it ever matters.
    private static final Map<String, net.exylia.lib.interaction.Interaction> LINES = new java.util.concurrent.ConcurrentHashMap<>();

    /** Runs consecutive interaction steps as one list, then the steps after them once it got to its end. */
    private void runLines(List<List<String>> steps, int from, ScreenEvent event, El el) {
        int end = from;
        List<String> lines = new ArrayList<>();
        while (end < steps.size() && !steps.get(end).isEmpty() && isLine(steps.get(end).get(0))) {
            lines.add(String.join(" ", steps.get(end)));
            end++;
        }
        List<List<String>> rest = List.copyOf(steps.subList(end, steps.size()));
        org.bukkit.plugin.Plugin lib = org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(MarkupRender.class);
        String key = String.join("\n", lines);
        if (LINES.size() > 512) LINES.clear();
        net.exylia.lib.interaction.Interaction compiled = LINES.computeIfAbsent(key, ignored ->
                net.exylia.lib.interaction.Interactions.compile(lib, lines, "screen " + el.pos()));
        org.bukkit.entity.Player viewer = event.viewer();
        net.exylia.lib.interaction.Click click = net.exylia.lib.interaction.Click.of(
                event.kind() != ScreenEvent.Kind.RIGHT, event.shift());
        compiled.runToEnd(viewer, click, Map.of()).thenAccept(finished -> {
            if (!finished || rest.isEmpty() || !viewer.isOnline()) return;
            net.exylia.lib.task.Tasks.of(lib).runAtEntity(viewer, () -> runAll(rest, event, el), () -> { });
        });
    }

    /**
     * Steps split by {@code ;} and words split by spaces as written, then each word filled: a value with a space
     * or a {@code ;} in it stays one argument of one step, whatever it holds.
     */
    private List<List<String>> actions(String raw, Scope scope) {
        List<List<String>> out = new ArrayList<>();
        for (List<String> step : Compile.Actions.steps(raw)) {
            List<String> filled = new ArrayList<>(step.size());
            for (String word : step) filled.add(Expr.text(value(word, scope)));
            out.add(filled);
        }
        return out;
    }

    /**
     * Runs steps in order. A {@code confirm} or {@code input} step holds the rest: the words after its {@code then}
     * and every later step run once the player answers, and not at all when they cancel.
     */
    private void runAll(List<List<String>> steps, ScreenEvent event, El el) {
        for (int i = 0; i < steps.size(); i++) {
            List<String> step = steps.get(i);
            if (step.isEmpty()) continue;
            String name = step.get(0);
            if (isLine(name)) {
                runLines(steps, i, event, el);
                return;
            }
            if (!name.equals("confirm") && !name.equals("input")) {
                run(step, event, el);
                continue;
            }
            int then = step.indexOf("then");
            List<String> head = step.subList(1, then < 0 ? step.size() : then);
            List<List<String>> rest = new ArrayList<>();
            if (then >= 0 && then + 1 < step.size()) rest.add(List.copyOf(step.subList(then + 1, step.size())));
            rest.addAll(steps.subList(i + 1, steps.size()));
            if (name.equals("confirm")) confirm(head, rest, event, el);
            else input(head, rest, event, el);
            return;
        }
    }

    /** {@code confirm '<question>' [dangerous] [yes='<label>'] [no='<label>'] then <steps>}: a dialog in the screen. */
    private void confirm(List<String> head, List<List<String>> rest, ScreenEvent event, El el) {
        String question = head.isEmpty() ? "" : head.get(0);
        boolean dangerous = false;
        String yes = null;
        String no = null;
        for (String word : head.subList(Math.min(1, head.size()), head.size())) {
            if (word.equalsIgnoreCase("dangerous")) dangerous = true;
            else if (word.startsWith("yes=")) yes = word.substring(4);
            else if (word.startsWith("no=")) no = word.substring(3);
        }
        event.session().confirm(question, dangerous, yes, no, open -> runAll(rest, event, el));
    }

    /** {@code input text <key> '<prompt>' [hint='<hint>'] [max=<n>] then <steps>}: asks, stores the answer, goes on. */
    private void input(List<String> head, List<List<String>> rest, ScreenEvent event, El el) {
        if (head.size() < 2 || !head.get(0).equals("text")) {
            parts.problems().accept(el.pos() + ": input needs \"input text <key> '<prompt>'\"");
            return;
        }
        String key = head.get(1);
        String prompt = head.size() > 2 ? head.get(2) : key;
        String hint = null;
        int max = 32;
        for (String word : head.subList(Math.min(3, head.size()), head.size())) {
            if (word.startsWith("hint=")) hint = word.substring(5);
            else if (word.startsWith("max=")) max = Math.max(1, toInt(word.substring(4)));
        }
        event.session().ask(prompt, hint, max, String.valueOf(event.session().state(key, ""))).thenAccept(answer -> {
            if (answer == null) return;
            event.session().set(key, answer.isBlank() ? null : answer.trim());
            runAll(rest, event, el);
        });
    }

    /**
     * {@code <search state="q" prompt="Search a player">}: a box showing what was searched (or its own words),
     * that asks for a new search when clicked, with a clear button beside it while something is searched.
     */
    private Node search(El el, Scope scope, Map<String, String> text) {
        String key = el.attr("state") != null ? el.attr("state") : "search";
        String prompt = fill(attrOr(el, "prompt", "Search"), scope, false);
        String hint = el.attr("hint") == null ? null : fill(el.attr("hint"), scope, false);
        int max = el.attr("max") == null ? 32 : Math.max(1, toInt(el.attr("max")));
        Object current = session.state(key, "");
        String label = "".equals(current) ? fill(el.text().isEmpty() ? "⌕ " + prompt : el.text(), scope, true)
                : "⌕ " + net.exylia.lib.text.Text.escape(String.valueOf(current));
        Node field = words(label, text).id(key + ".search").onClick(event -> event.session()
                .ask(prompt, hint, max, String.valueOf(event.session().state(key, "")))
                .thenAccept(answer -> {
                    if (answer != null) event.session().set(key, answer.isBlank() ? null : answer.trim());
                }));
        if ("".equals(current)) return field;
        Node clear = words("{error_light}✘", text).id(key + ".clear").padding(0, 3).hover("{error}30")
                .onClick(event -> event.session().set(key, null));
        return Node.row(field.grow(1), clear).gap(2).align(Node.Align.CENTER);
    }

    /**
     * Built-ins first ({@code close}, {@code back}, {@code set k v}, {@code clear k}, {@code toggle k},
     * {@code toast [tone] text}, {@code sound key [volume] [pitch]}), then the screen's, then a global id. Several
     * run in order when separated by {@code ;}.
     */
    private void run(List<String> step, ScreenEvent event, El el) {
        if (step.isEmpty()) return;
        List<String> words = step.subList(1, step.size());
        String name = step.get(0);
        switch (name) {
            case "close" -> event.session().close();
            case "back" -> {
                if (!event.session().back()) event.session().close();
            }
            case "sound" -> {
                if (!words.isEmpty()) {
                    float volume = words.size() > 1 ? (float) px(words.get(1), 0.5) : 0.5f;
                    float pitch = words.size() > 2 ? (float) px(words.get(2), 1) : 1f;
                    event.viewer().playSound(event.viewer().getLocation(), words.get(0), SoundCategory.MASTER, volume, pitch);
                }
            }
            case "set" -> {
                if (words.size() >= 2) event.session().set(words.get(0), String.join(" ", words.subList(1, words.size())));
            }
            case "clear" -> {
                for (String key : words) event.session().set(key, null);
            }
            case "toast" -> {
                if (words.size() > 1 && TONES.contains(words.get(0))) {
                    event.session().toast(words.get(0), String.join(" ", words.subList(1, words.size())));
                } else {
                    event.session().toast(String.join(" ", words));
                }
            }
            case "toggle" -> {
                if (!words.isEmpty()) event.session().set(words.get(0), !truthy(event.session().state(words.get(0), false)));
            }
            default -> {
                BiConsumer<ScreenEvent, List<String>> handler = parts.bindings().actions().get(name);
                if (handler != null) {
                    handler.accept(event, List.copyOf(words));
                } else if (name.contains(":")) {
                    StringBuilder call = new StringBuilder(name);
                    // Each argument escaped, so the action reads exactly the words the screen meant.
                    for (String word : words) call.append(' ').append(word.isEmpty() ? "''" : word.replaceAll("([\\s'\"\\\\])", "\\\\$1"));
                    ActionContext.Builder context = ActionContext.forPlayer(event.viewer()).origin("screen");
                    // As from a chest: the clicked row's object, and the chest menu the screen stands for.
                    if (event.row() != null && !(event.row() instanceof Map<?, ?>) && !(event.row() instanceof UiEntry)) {
                        context.put(net.exylia.lib.ui.UiKeys.ENTRY, event.row());
                    }
                    net.exylia.lib.ui.UiSession menu = menu();
                    if (menu != null) {
                        context.put(net.exylia.lib.ui.UiKeys.SESSION, menu).put(net.exylia.lib.ui.UiKeys.MENU, menu.menuId());
                    }
                    Actions.compile(call.toString()).execute(context.build());
                } else {
                    parts.problems().accept(el.pos() + ": no action \"" + name + "\" is registered");
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Style
    // ------------------------------------------------------------------

    /** A line of words in the inherited text style: colour, weight, size and alignment. */
    static Node words(String words, Map<String, String> text) {
        Node node = Node.text(textOf(words, text));
        if (text.containsKey("font-size")) node.scale(scale(text.get("font-size")));
        if ("normal".equalsIgnoreCase(text.get("white-space"))) node.wrap(Double.MAX_VALUE);
        String shadow = text.get("text-shadow");
        if (shadow != null && !shadow.isBlank() && !shadow.trim().equalsIgnoreCase("none")) node.shadow(true);
        String align = text.get("text-align");
        if (align != null) node.textAlign(switch (align.trim()) {
            case "center" -> Node.Align.CENTER;
            case "right", "end" -> Node.Align.END;
            default -> Node.Align.START;
        });
        return node;
    }

    /** Text as {@code Text} reads it: colour and weight first, then the words. */
    static String textOf(String words, Map<String, String> style) {
        StringBuilder out = new StringBuilder();
        String color = style.get("color");
        if (color != null) out.append(color.startsWith("#") ? "<" + color + ">" : color);
        String weight = style.get("font-weight");
        if (weight != null && (weight.equalsIgnoreCase("bold") || weight.equals("700"))) out.append("&l");
        return out.append(words).toString();
    }

    /** {@code background-image: url(img/x.png)} (or {@code none}), read from the markup's folder like {@code <img src>}. */
    private void backgroundImage(Node node, El el, String value) {
        String v = value.trim();
        if (v.equalsIgnoreCase("none")) return;
        if (!v.startsWith("url(") || !v.endsWith(")")) {
            parts.problems().accept(el.pos() + ": background-image wants url(path), not " + v);
            return;
        }
        String path = v.substring(4, v.length() - 1).trim().replaceAll("^['\"]|['\"]$", "");
        if (parts.images() == null) {
            parts.problems().accept(el.pos() + ": background-image needs markup read with ScreenMarkup.load or given images(dir)");
            return;
        }
        try {
            node.backgroundImage(Pictures.file(parts.images(), path).node());
        } catch (java.io.IOException failure) {
            parts.problems().accept(el.pos() + ": background-image " + path + ": " + failure.getMessage());
        }
    }

    private static void box(Node node, Map<String, String> style, Css css) {
        double[] padding = sides(style.get("padding"));
        if (padding != null) node.padding(padding[0], padding[1], padding[2], padding[3]);
        double[] margin = sides(style.get("margin"));
        if (margin != null) node.margin(margin[0], margin[1], margin[2], margin[3]);
        if (style.containsKey("transform")) node.transform(MotionCss.transform(style.get("transform")));
        if (style.containsKey("transition")) node.transition(MotionCss.transition(style.get("transition")));
        if (style.containsKey("enter")) node.enter(MotionCss.tween(style.get("enter")));
        if (style.containsKey("leave")) node.leave(MotionCss.tween(style.get("leave")));
        if (style.containsKey("animation")) node.animation(MotionCss.animation(style.get("animation"), css));
        if ("absolute".equalsIgnoreCase(style.getOrDefault("position", "").trim())) {
            node.absolute(px(style.get("top"), Double.NaN), px(style.get("right"), Double.NaN),
                    px(style.get("bottom"), Double.NaN), px(style.get("left"), Double.NaN));
        }
        if (style.containsKey("z-index")) node.layer((int) px(style.get("z-index"), 0));
        if (node.kind() == Node.Kind.TEXT && style.containsKey("max-width")) node.wrap(px(style.get("max-width"), 0));
        if (style.containsKey("gap")) node.gap(px(style.get("gap"), 0));
        if (style.containsKey("width")) node.width(px(style.get("width"), -1));
        if (style.containsKey("height")) node.height(px(style.get("height"), -1));
        if (style.containsKey("flex-grow")) node.grow(px(style.get("flex-grow"), 0));
        if (style.containsKey("background")) node.background(style.get("background"));
        if (style.containsKey("font-size") && node.kind() == Node.Kind.TEXT) node.scale(scale(style.get("font-size")));
        String[] line = outline(style.containsKey("outline") ? style.get("outline") : style.get("border"));
        if (line != null) node.outline(line[0], Double.parseDouble(line[1]));
        String align = style.get("align-items");
        if (align != null) node.align(switch (align.trim()) {
            case "center" -> Node.Align.CENTER;
            case "end", "flex-end" -> Node.Align.END;
            case "start", "flex-start" -> Node.Align.START;
            default -> Node.Align.STRETCH;
        });
        String justify = style.get("justify-content");
        if (justify != null) node.justify(switch (justify.trim()) {
            case "center" -> Node.Justify.CENTER;
            case "end", "flex-end" -> Node.Justify.END;
            case "space-between" -> Node.Justify.SPACE_BETWEEN;
            default -> Node.Justify.START;
        });
    }

    /**
     * {@code <img src="badge.png">} from the folder the markup was read from, or {@code <img skin="name">}: a player's
     * body. A picture that cannot be had is an empty box of its size, so the layout never jumps.
     */
    private Node image(El el, Scope scope) {
        if (el.attr("skin") != null) {
            String name = fill(el.attr("skin"), scope, false).trim();
            // wearing="...": armour and a held item over the skin (1.498.0).
            String wearing = el.attr("wearing") == null ? null : fill(el.attr("wearing"), scope, false).trim();
            Pictures.Picture body = name.isEmpty() ? null : Pictures.body(name, wearing);
            Node node = (body == null ? Pictures.stranger(true) : body).node().wearing(wearing);
            // <img skin="..." turn>: the body in the round, turning.
            if (el.attr("turn") != null && !name.isEmpty()) node.turntable(name);
            return node;
        }
        if (el.attr("face") != null) {
            String name = fill(el.attr("face"), scope, false).trim();
            Pictures.Picture face = name.isEmpty() ? null : Pictures.face(name);
            return (face == null ? Pictures.stranger(false) : face).node();
        }
        String src = el.attr("src") == null ? "" : fill(el.attr("src"), scope, false).trim();
        if (src.isEmpty()) {
            parts.problems().accept(el.pos() + ": <img> needs src, skin or face");
            return Node.box();
        }
        if (parts.images() == null) {
            parts.problems().accept(el.pos() + ": <img src> needs markup read with ScreenMarkup.load or given images(dir)");
            return Node.box();
        }
        try {
            return Pictures.file(parts.images(), src).node();
        } catch (java.io.IOException failure) {
            parts.problems().accept(el.pos() + ": <img src=\"" + src + "\">: " + failure.getMessage());
            return Node.box();
        }
    }

    private org.bukkit.inventory.ItemStack item(El el, Scope scope) {
        try {
            return itemOf(el, scope);
        } catch (RuntimeException | LinkageError noServer) {
            // Only without a server (a unit test): no registry to make items from, so a blank slot.
            return null;
        }
    }

    /** {@code material}, or a head by {@code head} (a player), {@code texture} (base64) or {@code url}. */
    private org.bukkit.inventory.ItemStack itemOf(El el, Scope scope) {
        String head = el.attr("head");
        if (head != null && !(head = fill(head, scope, false).trim()).isEmpty() && !head.startsWith("%")) {
            return net.exylia.lib.skull.Skulls.player(head).item();
        }
        String texture = el.attr("texture");
        if (texture != null && !(texture = fill(texture, scope, false).trim()).isEmpty() && !texture.startsWith("%")) {
            return net.exylia.lib.skull.Skulls.texture(texture).item();
        }
        String url = el.attr("url");
        if (url != null && !(url = fill(url, scope, false).trim()).isEmpty() && !url.startsWith("%")) {
            return net.exylia.lib.skull.Skulls.url(url).item();
        }
        String name = fill(el.attr("material") != null ? el.attr("material") : el.text(), scope, false).trim();
        org.bukkit.Material material = name.isEmpty() ? null : org.bukkit.Material.matchMaterial(name);
        if (material == null || !material.isItem() || material.isAir()) material = org.bukkit.Material.PAPER;
        return new org.bukkit.inventory.ItemStack(material);
    }

    /** A bar: {@code value} of {@code max} filled in the text colour over the background. */
    private Node progress(El el, Scope scope, Map<String, String> style) {
        double max = Math.max(0, px(fill(attrOr(el, "max", "100"), scope, false), 100));
        double value = Math.max(0, Math.min(max, px(fill(attrOr(el, "value", "0"), scope, false), 0)));
        String colour = style.getOrDefault("color", "{primary}");
        Node bar = Node.row().height(4);
        if (value > 0) bar.add(Node.box().grow(value).background(colour));
        if (max - value > 0) bar.add(Node.box().grow(max - value));
        return bar;
    }

    /** One to four pixel values, as CSS padding and margin take them: {top, right, bottom, left}. */
    private static double[] sides(String value) {
        if (value == null || value.isBlank()) return null;
        String[] p = value.trim().split("\\s+");
        double top = px(p[0], 0);
        double right = p.length > 1 ? px(p[1], 0) : top;
        double bottom = p.length > 2 ? px(p[2], 0) : top;
        double left = p.length > 3 ? px(p[3], 0) : right;
        return new double[]{top, right, bottom, left};
    }

    /** {@code 1px {primary}} as {colour, width}, or {@code null} for none. */
    static String[] outline(String value) {
        if (value == null || value.isBlank() || value.trim().equalsIgnoreCase("none")) return null;
        String width = "1";
        String colour = null;
        for (String part : value.trim().split("\\s+")) {
            Matcher m = NUMBER.matcher(part);
            if (m.matches()) width = m.group(1);
            else if (!part.equalsIgnoreCase("solid")) colour = part;
        }
        return colour == null ? null : new String[]{colour, width};
    }

    /** {@code 12px}, {@code 12} or {@code auto} ({@code fallback}). */
    static double px(String value, double fallback) {
        if (value == null) return fallback;
        Matcher m = NUMBER.matcher(value.trim());
        return m.matches() ? Double.parseDouble(m.group(1)) : fallback;
    }

    /** A font size: {@code 14px} is 1.4 lines of the font's ten pixels, a bare {@code 1.4} the same. */
    static double scale(String value) {
        Matcher m = NUMBER.matcher(value.trim());
        if (!m.matches()) return 1;
        double number = Double.parseDouble(m.group(1));
        return m.group(2) != null ? number / Layout.LINE : number;
    }

    private static String attrOr(El el, String name, String fallback) {
        String value = el.attr(name);
        return value == null ? fallback : value;
    }

    private static int intAttr(El el, String name, int fallback) {
        try {
            return el.attr(name) == null ? fallback : Integer.parseInt(el.attr(name).trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int toInt(Object value) {
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
