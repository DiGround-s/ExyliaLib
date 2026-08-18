package net.exylia.lib.util.sequence.internal;

import net.exylia.lib.util.sequence.Shape;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runs one shape's maths without a server.
 *
 * <p>The geometry is the part of this module worth asserting: a sphere that
 * drifts a block or a star that collapses to a point is a visible bug and an
 * invisible code change. Points are pure arithmetic, so they can be checked
 * exactly, with no world and no players.
 */
@ApiStatus.Internal
public final class ShapePoints {

    private ShapePoints() {
    }

    /**
     * The points a built-in shape draws for a line of arguments.
     *
     * @param name the shape name, as written in configuration
     * @param args the arguments after the particle, as {@code key:value;...}
     * @return the offsets from the anchor
     */
    public static @NotNull List<Vector> of(@NotNull String name, @NotNull String args) {
        Map<String, Shape> shapes = Shapes.builtIn();
        Shape shape = shapes.get(name.toLowerCase(Locale.ROOT));
        if (shape == null) {
            throw new IllegalArgumentException("There is no shape called " + name);
        }
        Args parsed = Args.parse("IGNORED;" + args, (where, problem) -> { });
        return shape.points(new Reader(parsed));
    }

    private record Reader(Args args) implements Shape.ShapeArgs {
        private static final Args.Problems QUIET = (where, problem) -> { };

        @Override
        public double number(@NotNull String key, double fallback) {
            return args.number(key, fallback, QUIET);
        }

        @Override
        public int count(@NotNull String key, int fallback) {
            return args.count(key, fallback, QUIET);
        }

        @Override
        public int atLeastOne(@NotNull String key, int fallback) {
            return args.atLeastOne(key, fallback, QUIET);
        }

        @Override
        public double radians(@NotNull String key, double fallback) {
            return Math.toRadians(args.number(key, fallback, QUIET));
        }

        @Override
        public boolean flag(@NotNull String key, boolean fallback) {
            return args.flag(key, fallback);
        }

        @Override
        public boolean has(@NotNull String key) {
            return args.has(key);
        }
    }
}
