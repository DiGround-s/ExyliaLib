package net.exylia.lib.util.sequence;

import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * The points a geometric effect draws, as offsets from its anchor.
 *
 * <pre>{@code
 * Sequences.shape("heart", args -> {
 *     double size = args.number("size", 1.0);
 *     List<Vector> points = new ArrayList<>();
 *     for (int i = 0; i < 60; i++) {
 *         double t = i / 60.0 * Math.PI * 2;
 *         points.add(new Vector(
 *                 size * 16 * Math.pow(Math.sin(t), 3) / 16,
 *                 size * (13 * Math.cos(t) - 5 * Math.cos(2 * t)) / 16,
 *                 0));
 *     }
 *     return points;
 * });
 * }</pre>
 *
 * <h2>Points, not particles</h2>
 * A shape says <em>where</em>, never <em>what</em>. The particle, its colour,
 * its animation and who sees it are the same for every shape and are handled
 * once, which is why a new shape is a loop that returns vectors rather than
 * another copy of the drawing code.
 *
 * <p>That is also what lets a custom shape inherit everything the built-in ones
 * have: {@code ticks:}, {@code interval:}, {@code color:}, {@code rotate:},
 * {@code scale:} and the rest work on it without the author writing any of them.
 *
 * <h2>The offsets are relative</h2>
 * {@code (0,0,0)} is the sequence's anchor. Positive Y is up. A shape that
 * should sit at chest height returns points around {@code y=1}, and a shape
 * that draws on the floor returns points around {@code y=0}.
 *
 * @since 1.30.0
 */
@FunctionalInterface
public interface Shape {

    /**
     * The points this shape draws.
     *
     * <p>Called once, when the sequence is compiled &mdash; not per play and
     * not per player. An expensive shape therefore costs nothing at runtime, so
     * favour clarity over cleverness here.
     *
     * @param args the parameters written in the configuration line
     * @return the offsets from the anchor, in draw order
     */
    @NotNull List<Vector> points(@NotNull ShapeArgs args);

    /**
     * The parameters of one shape line.
     *
     * <p>A reader over what was written after the particle name, with defaults
     * for what was not. Every unreadable value is reported against the file it
     * came from rather than silently replaced.
     *
     * @since 1.30.0
     */
    interface ShapeArgs {

        /**
         * A number, or {@code fallback} when it was not given.
         *
         * @param key      the parameter name, as written in configuration
         * @param fallback what to use when absent
         * @return the value
         */
        double number(@NotNull String key, double fallback);

        /**
         * A whole number, or {@code fallback} when it was not given.
         *
         * @param key      the parameter name
         * @param fallback what to use when absent
         * @return the value
         */
        int count(@NotNull String key, int fallback);

        /**
         * A whole number of at least one.
         *
         * <p>For point and segment counts, where zero draws nothing and a
         * negative number would fail inside an allocation.
         *
         * @param key      the parameter name
         * @param fallback what to use when absent
         * @return the value, never below one
         */
        int atLeastOne(@NotNull String key, int fallback);

        /**
         * An angle written in degrees, handed back in radians.
         *
         * <p>Configuration is written in degrees because that is what a server
         * owner thinks in; the maths wants radians. Converting here keeps every
         * shape from remembering to.
         *
         * @param key      the parameter name
         * @param fallback the default, in degrees
         * @return the angle in radians
         */
        double radians(@NotNull String key, double fallback);

        /**
         * A flag.
         *
         * @param key      the parameter name
         * @param fallback what to use when absent
         * @return the value
         */
        boolean flag(@NotNull String key, boolean fallback);

        /**
         * Whether the configuration mentioned this parameter at all.
         *
         * @param key the parameter name
         * @return whether it was written
         */
        boolean has(@NotNull String key);
    }
}
