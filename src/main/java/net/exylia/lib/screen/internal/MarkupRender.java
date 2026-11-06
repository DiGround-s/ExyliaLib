package net.exylia.lib.screen.internal;

import net.exylia.lib.action.ActionContext;
import net.exylia.lib.action.Actions;
import net.exylia.lib.screen.Node;
import net.exylia.lib.screen.ScreenEvent;
import net.exylia.lib.screen.ScreenSession;
import net.exylia.lib.screen.internal.Markup.El;
import net.exylia.lib.screen.internal.Markup.Part;
import net.exylia.lib.screen.internal.Markup.Words;
import org.bukkit.SoundCategory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a screen's markup and style into a {@link Node} tree for one session, once per render.
 *
 * <p>Internal: free to change without notice.
 */
public final class MarkupRender {

    /** Every tag a screen knows; anything else is reported when the markup is read. */
    public static final Set<String> TAGS = Set.of("screen", "panel", "row", "column", "box", "text", "h1", "h2",
            "b", "small", "p", "span", "label", "button", "tabs", "tab", "chips", "chip", "grid", "list", "card",
            "empty", "toggle", "checkbox", "pager", "icon", "item", "progress", "modal", "img");

    private static final Pattern PLACEHOLDER = Pattern.compile("%([\\w.-]+)%");
    private static final Pattern NUMBER = Pattern.compile("(-?\\d+(?:\\.\\d+)?)(px)?");

    /** What a render reads: the markup's own parts, handed over by {@code ScreenMarkup}. */
    public record Parts(El root, Css css, Map<String, Function<ScreenSession, List<? extends Map<String, ?>>>> sources,
                        Map<String, Function<ScreenSession, ?>> values,
                        Map<String, BiConsumer<ScreenEvent, List<String>>> actions, Consumer<String> problems,
                        java.nio.file.@org.jetbrains.annotations.Nullable Path images) {
    }

    private final Parts parts;
    private final ScreenSession session;
    private final Map<String, List<? extends Map<String, ?>>> rows = new HashMap<>();
    /** Grids by id or source, for pagers. */
    private final Map<String, El> grids = new HashMap<>();
    /** A {@code state} key's {@code default}, read before the player has picked anything. */
    private final Map<String, String> defaults = new HashMap<>();

    private MarkupRender(Parts parts, ScreenSession session) {
        this.parts = parts;
        this.session = session;
        index(parts.root());
    }

    /** The node tree of {@code parts} for {@code session}, as it stands now. */
    public static Node render(Parts parts, ScreenSession session) {
        MarkupRender render = new MarkupRender(parts, session);
        List<Node> nodes = render.build(parts.root(), new Scope(null, null), new ArrayList<>(), Map.of());
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

    /** Where names are looked up: the row being repeated (innermost first), then values, then state. */
    private record Scope(Map<String, ?> row, Scope parent) {
    }

    private Object lookup(String name, Scope scope) {
        for (Scope s = scope; s != null; s = s.parent()) {
            if (s.row() != null && s.row().containsKey(name)) return s.row().get(name);
        }
        Function<ScreenSession, ?> value = parts.values().get(name);
        if (value != null) return value.apply(session);
        Object state = session.state(name, "");
        if (!"".equals(state)) return state;
        String fallback = defaults.get(name);
        // A default may itself be a placeholder (default="%first_mode%"); one that names itself stays as written.
        return fallback == null || fallback.contains("%" + name + "%") ? fallback : fill(fallback, scope);
    }

    /** {@code %name%} replaced from the scope; an unknown name is left as written. */
    String fill(String text, Scope scope) {
        if (text == null || text.indexOf('%') < 0) return text;
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            Object value = lookup(m.group(1), scope);
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? m.group() : String.valueOf(value)));
        }
        m.appendTail(out);
        return out.toString();
    }

    static boolean truthy(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return !(text.isEmpty() || text.equals("false") || text.equals("0") || text.equals("no") || text.startsWith("%"));
    }

    /** {@code name}, {@code !name}, {@code name == 'x'}, {@code name != 'x'}, joined by {@code &&} and {@code ||}. */
    boolean test(String expression, Scope scope) {
        String text = expression.trim();
        // || binds looser than &&, as in every language the markup's authors know; no brackets.
        if (text.contains("||")) {
            for (String part : text.split("\\|\\|")) if (test(part, scope)) return true;
            return false;
        }
        if (text.contains("&&")) {
            for (String part : text.split("&&")) if (!test(part, scope)) return false;
            return true;
        }
        for (String op : new String[]{"==", "!="}) {
            int at = text.indexOf(op);
            if (at < 0) continue;
            String left = operand(text.substring(0, at), scope);
            String right = operand(text.substring(at + 2), scope);
            return left.equals(right) == op.equals("==");
        }
        if (text.startsWith("!")) return !test(text.substring(1), scope);
        return truthy(text.startsWith("%") ? fill(text, scope) : lookup(text, scope));
    }

    private String operand(String raw, Scope scope) {
        String text = raw.trim();
        if (text.length() >= 2 && (text.startsWith("'") && text.endsWith("'") || text.startsWith("\"") && text.endsWith("\""))) {
            return fill(text.substring(1, text.length() - 1), scope);
        }
        if (text.startsWith("%")) return fill(text, scope);
        Object value = lookup(text, scope);
        return value == null ? "" : String.valueOf(value);
    }

    private List<? extends Map<String, ?>> rows(String source) {
        return rows.computeIfAbsent(source, name -> {
            Function<ScreenSession, List<? extends Map<String, ?>>> supplier = parts.sources().get(name);
            if (supplier == null) {
                parts.problems().accept("no source \"" + name + "\" is registered");
                return List.of();
            }
            List<? extends Map<String, ?>> list = supplier.apply(session);
            return list == null ? List.of() : list;
        });
    }

    // ------------------------------------------------------------------
    // Building
    // ------------------------------------------------------------------

    /**
     * The nodes one element draws: none when hidden, one usually, one per row when it repeats.
     *
     * @param path      the targets of its ancestors, root first
     * @param inherited the text properties it inherits: color, font-size, font-weight
     */
    private List<Node> build(El el, Scope scope, List<Css.Target> path, Map<String, String> inherited) {
        String condition = el.attr("if");
        if (condition != null && !test(condition, scope)) return List.of();

        Set<String> flags = new HashSet<>();
        for (String flag : new String[]{"selected", "disabled", "checked"}) {
            String raw = el.attr(flag);
            if (raw != null && (raw.isEmpty() || truthy(fill(raw, scope)))) flags.add(flag);
        }
        String option = option(el, path, scope);
        if (option != null && option.equals(current(stateOwner(path), scope))) flags.add("selected");
        String toggleKey = el.attr("state") != null && (el.tag().equals("toggle") || el.tag().equals("checkbox"))
                ? el.attr("state") : null;
        if (toggleKey != null && truthy(session.state(toggleKey, false))) flags.add("checked");

        Css.Target target = new Css.Target(el.tag(), el.attr("id"), Css.classes(el.attr("class")), flags);
        List<Css.Target> here = new ArrayList<>(path);
        here.add(target);
        Map<String, String> style = parts.css().style(here, false);
        if ("none".equalsIgnoreCase(style.get("display"))) return List.of();
        Map<String, String> text = new HashMap<>(inherited);
        for (String p : new String[]{"color", "font-size", "font-weight", "text-align"}) if (style.containsKey(p)) text.put(p, style.get(p));

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
            node = words(fill(label, scope), text);
        } else if (el.tag().equals("item")) {
            org.bukkit.inventory.ItemStack stack = item(el, scope);
            node = stack == null ? Node.box().size(Layout.ITEM, Layout.ITEM) : Node.item(stack);
        } else if (el.tag().equals("img")) {
            node = image(el, scope);
        } else if (el.tag().equals("progress")) {
            node = progress(el, scope, style);
        } else if (el.tag().equals("icon")) {
            String material = fill(el.attr("material") != null ? el.attr("material") : el.text(), scope).trim();
            // ponytail: block items whose sprite lives under block/ fall back to the glyph; map them when needed.
            String glyph = attrOr(el, "glyph", "◆");
            node = words("{icon:items:item/" + material.toLowerCase(Locale.ROOT) + "|" + glyph + "}", text);
        } else if (el.elements().isEmpty() && !el.text().isEmpty()) {
            node = words(fill(el.text(), scope), text);
        } else {
            node = container(style);
            boolean own = el.attr("state") != null;
            if (own) owners.add(el);
            try {
                for (Part part : el.children()) {
                    if (part instanceof Words words) node.add(words(fill(words.text(), scope), text));
                    else if (part instanceof El child) node.add(build(child, scope, here, text));
                }
            } finally {
                if (own) owners.remove(owners.size() - 1);
            }
        }
        box(node, style);
        if (el.attr("id") != null && source == null) node.id(fill(el.attr("id"), scope));
        if (el.tag().equals("modal")) {
            String dismiss = el.attr("dismiss");
            if (dismiss == null && condition != null && condition.trim().matches("[\\w.-]+")) dismiss = "clear " + condition.trim();
            node.overlay(true);
            if (dismiss != null && !dismiss.isBlank()) {
                List<String> actions = actions(dismiss, scope);
                node.onDismiss(session -> {
                    ScreenEvent event = new ScreenEvent(session, ScreenEvent.Kind.LEFT, 0, null);
                    for (String one : actions) run(one, event);
                });
            }
        }

        if (parts.css().hoverable(here)) {
            Map<String, String> hover = parts.css().style(here, true);
            String background = hover.get("background");
            node.hover(background != null ? background : style.get("background"));
            String[] line = outline(hover.containsKey("outline") ? hover.get("outline") : hover.get("border"));
            if (line != null) node.hoverOutline(line[0]);
        }
        click(el, node, scope, flags, option, path, toggleKey);
        return List.of(node);
    }

    /** The value an option sets, when this element sits inside an element that keeps a {@code state}. */
    private String option(El el, List<Css.Target> path, Scope scope) {
        String value = el.attr("value");
        if (value == null || stateOwner(path) == null) return null;
        return fill(value, scope);
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
        return fallback == null ? null : fill(fallback, scope);
    }

    private Node container(Map<String, String> style) {
        return "row".equalsIgnoreCase(style.get("flex-direction")) ? Node.row() : Node.column();
    }

    private Node repeat(El el, String source, Scope scope, List<Css.Target> here, Map<String, String> text,
                        Map<String, String> style) {
        List<? extends Map<String, ?>> all = rows(source);
        List<El> template = new ArrayList<>();
        // Several empties may each carry an if, so the message can match why the list is empty.
        List<El> empties = new ArrayList<>();
        for (El child : el.elements()) {
            if (child.tag().equals("empty")) empties.add(child);
            else template.add(child);
        }
        Node node = container(style);
        node.id(el.attr("id") != null ? el.attr("id") : source);
        if (all.isEmpty()) {
            for (El empty : empties) for (Node built : build(empty, scope, here, text)) node.add(built.id(source + ".empty"));
            return node;
        }
        boolean windowed = grids.get(source) == el;
        int columns = windowed ? Math.max(1, intAttr(el, "columns", 1)) : 1;
        int window = windowed && el.attr("rows") != null ? columns * Math.max(1, intAttr(el, "rows", 1)) : all.size();
        String key = "scroll." + source;
        int offset = offset(key, all.size(), window, columns);
        List<? extends Map<String, ?>> visible = all.subList(offset, Math.min(all.size(), offset + window));
        boolean own = el.attr("state") != null;
        if (own) owners.add(el);
        try {
            if (columns == 1) {
                for (int i = 0; i < visible.size(); i++) {
                    Scope row = new Scope(visible.get(i), scope);
                    for (El child : template) for (Node built : build(child, row, here, text)) {
                        node.add(built.id(source + "." + (offset + i) + "." + template.indexOf(child)));
                    }
                }
            } else {
                double gap = px(style.get("gap"), 0);
                for (int start = 0; start < visible.size(); start += columns) {
                    // A line fills its container: stretched across a column, grown along a row.
                    Node line = Node.row().gap(gap).grow(node.kind() == Node.Kind.ROW ? 1 : 0);
                    for (int c = 0; c < columns; c++) {
                        int i = start + c;
                        if (i >= visible.size()) {
                            line.add(Node.box().width(0).grow(1));
                            continue;
                        }
                        Scope row = new Scope(visible.get(i), scope);
                        for (El child : template) for (Node built : build(child, row, here, text)) {
                            // Every column the same width, whatever its words need, as CSS grid columns are.
                            if (built.width() < 0) built.width(0);
                            line.add(built.grow(1).id(source + "." + (offset + i)));
                        }
                    }
                    node.add(line);
                }
            }
        } finally {
            if (own) owners.remove(owners.size() - 1);
        }
        if (windowed && window < all.size()) {
            node.onScroll(event -> event.session().set(key, offset(key, all.size(), window, columns)
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
            parts.problems().accept("line " + el.line() + ": pager for \"" + el.attr("for") + "\" finds no grid");
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
        Node previous = words(attrOr(el, "previous", "‹"), text).id(source + ".previous")
                .onClick(event -> event.session().set(key, Math.max(0, offset - window)));
        Node next = words(attrOr(el, "next", "›"), text).id(source + ".next")
                .onClick(event -> event.session().set(key, offset + window < size ? offset + window : offset));
        Node label = words(page + " / " + pages, text).id(source + ".page");
        return Node.row(previous, label, next).align(Node.Align.CENTER);
    }

    private void click(El el, Node node, Scope scope, Set<String> flags, String option, List<Css.Target> path,
                       String toggleKey) {
        List<String> action = el.attr("action") == null ? null : actions(el.attr("action"), scope);
        El owner = option == null ? null : stateOwner(path);
        if (action == null && owner == null && toggleKey == null) return;
        boolean disabled = flags.contains("disabled");
        String stateKey = owner == null ? null : owner.attr("state");
        node.onClick(event -> {
            if (disabled) {
                event.viewer().playSound(event.viewer().getLocation(), "entity.villager.no", SoundCategory.MASTER, 0.4f, 1f);
                return;
            }
            if (stateKey != null) event.session().set(stateKey, option);
            if (toggleKey != null) event.session().set(toggleKey, !truthy(event.session().state(toggleKey, false)));
            if (action != null) for (String one : action) run(one, event);
        });
    }

    /** Split on {@code ;} before filling, so a {@code ;} inside a placeholder's value never starts an action. */
    private List<String> actions(String raw, Scope scope) {
        List<String> out = new ArrayList<>();
        for (String one : raw.split(";")) if (!one.isBlank()) out.add(fill(one, scope));
        return out;
    }

    /**
     * Built-ins first ({@code close}, {@code set k v}, {@code clear k}, {@code toggle k}, {@code toast text}), then
     * the screen's, then a global id. Several run in order when separated by {@code ;}.
     */
    private void run(String action, ScreenEvent event) {
        List<String> words = new ArrayList<>(List.of(action.trim().split("\\s+")));
        String name = words.remove(0);
        switch (name) {
            case "close" -> event.session().close();
            case "set" -> {
                if (words.size() >= 2) event.session().set(words.get(0), String.join(" ", words.subList(1, words.size())));
            }
            case "clear" -> {
                for (String key : words) event.session().set(key, null);
            }
            case "toast" -> event.session().toast(String.join(" ", words));
            case "toggle" -> {
                if (!words.isEmpty()) event.session().set(words.get(0), !truthy(event.session().state(words.get(0), false)));
            }
            default -> {
                BiConsumer<ScreenEvent, List<String>> handler = parts.actions().get(name);
                if (handler != null) {
                    handler.accept(event, List.copyOf(words));
                } else if (name.contains(":")) {
                    Actions.compile(action.trim()).execute(ActionContext.forPlayer(event.viewer()).origin("screen").build());
                } else {
                    parts.problems().accept("no action \"" + name + "\" is registered");
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

    private static void box(Node node, Map<String, String> style) {
        String padding = style.get("padding");
        if (padding != null) {
            String[] p = padding.trim().split("\\s+");
            double top = px(p[0], 0);
            double right = p.length > 1 ? px(p[1], 0) : top;
            double bottom = p.length > 2 ? px(p[2], 0) : top;
            double left = p.length > 3 ? px(p[3], 0) : right;
            node.padding(top, right, bottom, left);
        }
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

    /** {@code material}, or a head by {@code head} (a player), {@code texture} (base64) or {@code url}. */
    /**
     * {@code <img src="badge.png">} from the folder the markup was read from, or {@code <img skin="name">}: a player's
     * body. A picture that cannot be had is an empty box of its size, so the layout never jumps.
     */
    private Node image(El el, Scope scope) {
        if (el.attr("skin") != null) {
            String name = fill(el.attr("skin"), scope).trim();
            Pictures.Picture body = name.isEmpty() ? null : Pictures.body(name);
            return body == null ? Node.box().size(16, 32) : body.node();
        }
        String src = el.attr("src") == null ? "" : fill(el.attr("src"), scope).trim();
        if (src.isEmpty()) {
            parts.problems().accept("<img> needs src or skin");
            return Node.box();
        }
        if (parts.images() == null) {
            parts.problems().accept("<img src> needs markup read with ScreenMarkup.load or given images(dir)");
            return Node.box();
        }
        try {
            return Pictures.file(parts.images(), src).node();
        } catch (java.io.IOException failure) {
            parts.problems().accept("<img src=\"" + src + "\">: " + failure.getMessage());
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

    private org.bukkit.inventory.ItemStack itemOf(El el, Scope scope) {
        String head = el.attr("head");
        if (head != null && !(head = fill(head, scope).trim()).isEmpty() && !head.startsWith("%")) {
            return net.exylia.lib.skull.Skulls.player(head).item();
        }
        String texture = el.attr("texture");
        if (texture != null && !(texture = fill(texture, scope).trim()).isEmpty() && !texture.startsWith("%")) {
            return net.exylia.lib.skull.Skulls.texture(texture).item();
        }
        String url = el.attr("url");
        if (url != null && !(url = fill(url, scope).trim()).isEmpty() && !url.startsWith("%")) {
            return net.exylia.lib.skull.Skulls.url(url).item();
        }
        String name = fill(el.attr("material") != null ? el.attr("material") : el.text(), scope).trim();
        org.bukkit.Material material = name.isEmpty() ? null : org.bukkit.Material.matchMaterial(name);
        if (material == null || !material.isItem() || material.isAir()) material = org.bukkit.Material.PAPER;
        return new org.bukkit.inventory.ItemStack(material);
    }

    /** A bar: {@code value} of {@code max} filled in the text colour over the background. */
    private Node progress(El el, Scope scope, Map<String, String> style) {
        double max = Math.max(0, px(fill(attrOr(el, "max", "100"), scope), 100));
        double value = Math.max(0, Math.min(max, px(fill(attrOr(el, "value", "0"), scope), 0)));
        String colour = style.getOrDefault("color", "{primary}");
        Node bar = Node.row().height(4);
        if (value > 0) bar.add(Node.box().grow(value).background(colour));
        if (max - value > 0) bar.add(Node.box().grow(max - value));
        return bar;
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
