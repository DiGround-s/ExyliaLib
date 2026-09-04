package net.exylia.lib.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * Colours that run across a piece of text, one character at a time.
 *
 * <pre>{@code
 * // a plain string, painted end to end
 * Component name = Gradients.paint("DiGround", List.of(gold, orange));
 *
 * // a component that already carries bold, hover and click: the colour
 * // changes per character, everything else stays where it was
 * Component painted = Gradients.apply(component, List.of(a, b, c));
 *
 * // an animation: the caller decides what colour each position gets
 * int frame = clock.frame();
 * Component shifted = Gradients.apply(component,
 *         index -> Gradients.wrap(stops, (index + frame) / 12.0));
 * }</pre>
 *
 * <h2>Why this exists next to MiniMessage</h2>
 * {@code <gradient:#a:#b>} is the right tool for text an owner writes in a
 * file: it is parsed once and cached. It is the wrong tool for a colour that
 * changes after the parse — an animation frame, a colour a player chose, a
 * name that is painted after its placeholders were substituted. Those need
 * the colour applied to a {@link Component} that already exists, and
 * MiniMessage only ever produces one from a string.
 *
 * <h2>What a position is</h2>
 * Every method that takes a {@code double} position reads it as a fraction
 * of the way along the text, {@code 0} at the first character and {@code 1}
 * at the last. {@link #at} clamps outside that range; {@link #wrap} loops,
 * which is what a shifting gradient wants: the colour that scrolls off one
 * end comes back at the other.
 *
 * <h2>Threads</h2>
 * Stateless; safe from any thread. Components are immutable, so painting one
 * returns a new one and never touches the original.
 *
 * @since 1.102.0
 */
public final class Gradients {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private Gradients() {
        throw new AssertionError("No instances.");
    }

    /**
     * A colour {@code position} of the way from {@code from} to {@code to}.
     *
     * @param from     the colour at {@code 0}
     * @param to       the colour at {@code 1}
     * @param position where between them, clamped to {@code [0, 1]}
     * @return the blended colour
     */
    public static @NotNull TextColor blend(@NotNull TextColor from, @NotNull TextColor to, double position) {
        double clamped = Math.max(0, Math.min(1, position));
        return TextColor.color(
                (int) Math.round(from.red() + (to.red() - from.red()) * clamped),
                (int) Math.round(from.green() + (to.green() - from.green()) * clamped),
                (int) Math.round(from.blue() + (to.blue() - from.blue()) * clamped));
    }

    /**
     * The colour {@code position} of the way along a run of stops.
     *
     * <p>Stops are spaced evenly: with three, the second sits at {@code 0.5}.
     * A single stop is a solid colour. Outside {@code [0, 1]} the nearest end
     * is returned.
     *
     * @param stops    at least one colour
     * @param position where along the run
     * @return the colour there
     * @throws IllegalArgumentException with no stops
     */
    public static @NotNull TextColor at(@NotNull List<TextColor> stops, double position) {
        requireStops(stops);
        int segments = stops.size() - 1;
        if (segments == 0) {
            return stops.get(0);
        }
        double scaled = Math.max(0, Math.min(1, position)) * segments;
        int index = Math.min((int) scaled, segments - 1);
        return blend(stops.get(index), stops.get(index + 1), scaled - index);
    }

    /**
     * The colour {@code position} of the way around a loop of stops.
     *
     * <p>Like {@link #at}, except the last stop blends back into the first
     * and the position wraps: {@code 1.25} is {@code 0.25}, and {@code -0.25}
     * is {@code 0.75}. A shifting gradient calls this with an offset that
     * grows every frame and never has a seam.
     *
     * @param stops    at least one colour
     * @param position where around the loop, any value
     * @return the colour there
     * @throws IllegalArgumentException with no stops
     */
    public static @NotNull TextColor wrap(@NotNull List<TextColor> stops, double position) {
        requireStops(stops);
        int segments = stops.size();
        if (segments == 1) {
            return stops.get(0);
        }
        double looped = position - Math.floor(position);
        double scaled = looped * segments;
        int index = Math.min((int) scaled, segments - 1);
        return blend(stops.get(index), stops.get((index + 1) % segments), scaled - index);
    }

    /**
     * Paints a plain string across the stops, first character at the first
     * stop and last character at the last.
     *
     * @param text  the text; formatting in it is not parsed
     * @param stops at least one colour
     * @return one component per character, coloured
     */
    public static @NotNull Component paint(@NotNull String text, @NotNull List<TextColor> stops) {
        return apply(Component.text(text), stops);
    }

    /**
     * Repaints every character of a component across the stops.
     *
     * <p>The first visible character gets the first stop and the last gets
     * the last, counting through children in reading order. Decorations,
     * hover and click events, insertion and font are kept exactly where they
     * were; only the colour changes.
     *
     * @param component the component to repaint
     * @param stops     at least one colour
     * @return the repainted copy
     */
    public static @NotNull Component apply(@NotNull Component component, @NotNull List<TextColor> stops) {
        requireStops(stops);
        int last = length(component) - 1;
        if (last <= 0) {
            return apply(component, index -> stops.get(0));
        }
        return apply(component, index -> at(stops, (double) index / last));
    }

    /**
     * Repaints every character of a component with a colour of the caller's
     * choosing.
     *
     * <p>{@code colourAt} is asked once per visible character, with its index
     * counted from {@code 0} through the whole tree in reading order. That is
     * what an animation needs: the index plus a frame offset picks the colour.
     *
     * @param component the component to repaint
     * @param colourAt  the colour for each character index
     * @return the repainted copy
     */
    public static @NotNull Component apply(@NotNull Component component, @NotNull IntFunction<TextColor> colourAt) {
        return repaint(component, new int[1], colourAt);
    }

    /**
     * How many characters {@link #apply} will colour: the visible length of
     * the component, children included.
     *
     * @param component the component
     * @return its visible length in code points
     */
    public static int length(@NotNull Component component) {
        String plain = PLAIN.serialize(component);
        return plain.codePointCount(0, plain.length());
    }

    private static Component repaint(Component component, int[] cursor, IntFunction<TextColor> colourAt) {
        Component painted;
        if (component instanceof TextComponent text && !text.content().isEmpty()) {
            // The parent keeps everything but its colour, which each character
            // now carries itself. Leaving it would be harmless — a child's
            // colour wins — but a reader of the tree should not see two.
            TextComponent.Builder builder = Component.text().style(text.style().color(null));
            text.content().codePoints().forEach(codePoint -> builder.append(
                    Component.text(new String(Character.toChars(codePoint)), colourAt.apply(cursor[0]++))));
            painted = builder.build();
        } else {
            painted = component.children(List.of());
        }
        List<Component> children = new ArrayList<>(component.children().size());
        for (Component child : component.children()) {
            children.add(repaint(child, cursor, colourAt));
        }
        return children.isEmpty() ? painted : painted.children(mergedChildren(painted, children));
    }

    private static List<Component> mergedChildren(Component painted, List<Component> children) {
        List<Component> merged = new ArrayList<>(painted.children().size() + children.size());
        merged.addAll(painted.children());
        merged.addAll(children);
        return merged;
    }

    private static void requireStops(List<TextColor> stops) {
        if (stops.isEmpty()) {
            throw new IllegalArgumentException("A gradient needs at least one colour.");
        }
    }
}
