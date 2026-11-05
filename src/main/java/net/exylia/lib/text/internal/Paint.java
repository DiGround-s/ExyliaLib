package net.exylia.lib.text.internal;

import net.exylia.lib.text.Gradients;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.Context;
import net.kyori.adventure.text.minimessage.ParsingException;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Gradients that run across substituted values.
 *
 * <p>A value is a single marker character when the text is parsed, so
 * {@code <gradient:{primary}:{secondary_light}>%kit_name%</gradient>} would
 * paint the whole name in the first stop. A gradient whose body holds a marker
 * is therefore rewritten to a tag of ours that only labels its content; once
 * the values are in, {@link #apply} paints the labelled run across the real
 * letters. A gradient with no value in it is left to MiniMessage, which parses
 * it once and caches it.
 *
 * <p>The label is a font key in the {@code exylia} namespace. It never reaches
 * a client: {@link #apply} takes it off as it paints.
 */
public final class Paint {

    /** The tag a gradient over a value is rewritten to. */
    static final String TAG = "exylia_paint";

    private static final String OPEN = "<gradient:";
    private static final String CLOSE = "</gradient>";
    private static final String PREFIX = "paint/";

    /** Resolves {@code <exylia_paint:#a:#b>} into the label {@link #apply} looks for. */
    static final TagResolver RESOLVER = new TagResolver() {
        @Override
        public @Nullable Tag resolve(String name, ArgumentQueue arguments, Context context) throws ParsingException {
            if (!TAG.equals(name)) {
                return null;
            }
            List<String> hexes = new ArrayList<>();
            while (arguments.hasNext()) {
                String argument = arguments.pop().value();
                TextColor colour = colour(argument);
                if (colour != null) {
                    hexes.add(colour.asHexString().substring(1));
                }
                // A phase (a number) or anything unreadable is skipped: the
                // run still paints between the colours that were readable.
            }
            if (hexes.isEmpty()) {
                return null;
            }
            Key key = Key.key("exylia", PREFIX + String.join("-", hexes));
            return Tag.styling(builder -> builder.font(key));
        }

        @Override
        public boolean has(String name) {
            return TAG.equals(name);
        }
    };

    private Paint() {
    }

    /**
     * Rewrites every gradient whose body holds a value marker.
     *
     * @param source the text after markers went in, before the parse
     * @param first  the first marker character
     * @param last   the last marker character
     * @return the text to parse
     */
    public static String mark(String source, char first, char last) {
        int open = source.indexOf(OPEN);
        if (open < 0) {
            return source;
        }
        StringBuilder result = null;
        int copied = 0;
        while (open >= 0) {
            int argsEnd = source.indexOf('>', open + OPEN.length());
            if (argsEnd < 0) {
                break;
            }
            int close = source.indexOf(CLOSE, argsEnd + 1);
            int bodyEnd = close < 0 ? source.length() : close;
            if (holdsMarker(source, argsEnd + 1, bodyEnd, first, last)) {
                if (result == null) {
                    result = new StringBuilder(source.length() + 16);
                }
                result.append(source, copied, open)
                        .append('<').append(TAG).append(':')
                        .append(source, open + OPEN.length(), argsEnd + 1)
                        .append(source, argsEnd + 1, bodyEnd);
                if (close >= 0) {
                    result.append("</").append(TAG).append('>');
                    copied = close + CLOSE.length();
                } else {
                    copied = source.length();
                }
            }
            open = source.indexOf(OPEN, close < 0 ? source.length() : close + CLOSE.length());
        }
        return result == null ? source : result.append(source, copied, source.length()).toString();
    }

    /**
     * Paints every labelled run across the letters it holds now.
     *
     * @param component the line, values substituted
     * @return the line with each run painted and no label left
     */
    public static Component apply(Component component) {
        String key = keyOf(component.style());
        if (key != null) {
            return paint(component, key);
        }
        List<Component> children = component.children();
        if (children.isEmpty()) {
            return component;
        }
        List<Component> painted = new ArrayList<>(children.size());
        boolean changed = false;
        int at = 0;
        while (at < children.size()) {
            Component child = children.get(at);
            String run = keyOf(child.style());
            if (run == null) {
                Component visited = apply(child);
                changed |= visited != child;
                painted.add(visited);
                at++;
                continue;
            }
            // A line flattened for depth carries the label on every piece:
            // consecutive pieces with one label are one run.
            int end = at + 1;
            while (end < children.size() && run.equals(keyOf(children.get(end).style()))) {
                end++;
            }
            Component group = end - at == 1 ? child : Component.text().append(children.subList(at, end)).build();
            painted.add(paint(group, run));
            changed = true;
            at = end;
        }
        return changed ? component.children(painted) : component;
    }

    private static Component paint(Component run, String key) {
        List<TextColor> stops = new ArrayList<>();
        for (String hex : key.substring(PREFIX.length()).split("-")) {
            TextColor colour = TextColor.fromHexString('#' + hex);
            if (colour != null) {
                stops.add(colour);
            }
        }
        Component bare = unlabel(run, key);
        return stops.isEmpty() ? bare : TextEngine.shadowed(Gradients.apply(bare, stops));
    }

    private static Component unlabel(Component component, String key) {
        Component result = key.equals(keyOf(component.style()))
                ? component.style(component.style().font(null)) : component;
        List<Component> children = result.children();
        if (children.isEmpty()) {
            return result;
        }
        List<Component> bare = new ArrayList<>(children.size());
        for (Component child : children) {
            bare.add(unlabel(child, key));
        }
        return result.children(bare);
    }

    private static @Nullable String keyOf(Style style) {
        Key font = style.font();
        return font != null && "exylia".equals(font.namespace()) && font.value().startsWith(PREFIX)
                ? font.value() : null;
    }

    private static boolean holdsMarker(String source, int from, int to, char first, char last) {
        for (int i = from; i < to; i++) {
            char character = source.charAt(i);
            if (character >= first && character <= last) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable TextColor colour(String argument) {
        if (argument.startsWith("#")) {
            return TextColor.fromHexString(argument);
        }
        return NamedTextColor.NAMES.value(argument.toLowerCase(Locale.ROOT));
    }
}
