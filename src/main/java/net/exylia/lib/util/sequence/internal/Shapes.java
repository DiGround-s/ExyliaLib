package net.exylia.lib.util.sequence.internal;

import net.exylia.lib.util.sequence.Shape;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The shapes that come with the library.
 *
 * <p>The twenty ExyliaCommons drew, with the same names, parameters and
 * defaults, so an existing {@code effects.yml} produces the same picture.
 *
 * <h2>Three deliberate differences</h2>
 * All three are bugs in the original, each fixed on purpose:
 *
 * <ul>
 *   <li><b>{@code SPHERE} ignored {@code y:}</b> and sat one block up
 *       regardless. Nineteen of the twenty shapes honoured it, so a file that
 *       wrote {@code y:} on a sphere had no way to know why nothing moved.
 *       It now honours {@code y:} like the rest, and the default keeps it
 *       centred at chest height.</li>
 *   <li><b>{@code TORUS} added a block</b> on top of whatever {@code y:} said,
 *       for no reason the other shapes shared.</li>
 *   <li><b>{@code STAR} divided by {@code points}</b> without a floor, so
 *       {@code points:0} produced infinities and drew a shape at the world
 *       origin.</li>
 * </ul>
 *
 * <p>The first two move an existing sphere and torus down by one block. Both
 * are documented, and both are what the file always asked for.
 */
public final class Shapes {

    private Shapes() {
    }

    /** Built under their configuration names, in the order a reader meets them. */
    public static Map<String, Shape> builtIn() {
        Map<String, Shape> shapes = new LinkedHashMap<>();
        shapes.put("circle", Shapes::circle);
        shapes.put("sphere", Shapes::sphere);
        shapes.put("beam", Shapes::beam);
        shapes.put("spiral", Shapes::spiral);
        shapes.put("double_helix", Shapes::doubleHelix);
        shapes.put("tornado", Shapes::tornado);
        shapes.put("star", Shapes::star);
        shapes.put("cage", Shapes::cage);
        shapes.put("disc", Shapes::disc);
        shapes.put("vortex", Shapes::vortex);
        shapes.put("wave", Shapes::wave);
        shapes.put("cross", Shapes::cross);
        shapes.put("galaxy", Shapes::galaxy);
        shapes.put("torus", Shapes::torus);
        shapes.put("burst", Shapes::burst);
        shapes.put("pyramid", Shapes::pyramid);
        shapes.put("ring_pulse", Shapes::ringPulse);
        shapes.put("wings", Shapes::wings);
        shapes.put("arch", Shapes::arch);
        shapes.put("claw", Shapes::claw);
        // Five that the display work asked for. Particles never needed them:
        // a shape drawn out of light is read as a glow, and one drawn out of
        // objects is read as a thing, so where the objects are matters in a way
        // it never did before.
        shapes.put("dome", Shapes::dome);
        shapes.put("cube", Shapes::cube);
        shapes.put("line", Shapes::line);
        shapes.put("ribbon", Shapes::ribbon);
        shapes.put("scatter", Shapes::scatter);
        // One point at the anchor. Not geometry so much as the absence of it:
        // it exists so [DISPLAY] is a shape like any other and inherits y:,
        // ticks:, rotate: and the rest rather than being a second code path
        // that has to grow each of them again.
        shapes.put("display", args -> List.of(new Vector(0, 0, 0)));
        return shapes;
    }

    /** The parameters each built-in reads, so a typo in one can be named. */
    public static String[] parametersOf(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "circle" -> new String[]{"radius", "points"};
            case "sphere" -> new String[]{"radius", "points"};
            case "beam" -> new String[]{"height", "points"};
            case "spiral" -> new String[]{"height", "radius", "turns", "points"};
            case "double_helix" -> new String[]{"height", "radius", "turns", "points", "strands"};
            case "tornado" -> new String[]{"height", "radius", "top_radius", "turns", "points"};
            case "star" -> new String[]{"radius", "spikes", "inner", "points"};
            case "cage" -> new String[]{"radius", "height", "columns", "points"};
            case "disc" -> new String[]{"radius", "rings", "points"};
            case "vortex" -> new String[]{"radius", "turns", "points"};
            case "wave" -> new String[]{"length", "amplitude", "frequency", "arms", "angle", "points"};
            case "cross" -> new String[]{"radius", "arms", "angle", "points"};
            case "galaxy" -> new String[]{"radius", "turns", "arms", "points"};
            case "torus" -> new String[]{"radius", "tube", "segments", "tube_segments"};
            case "burst" -> new String[]{"radius", "beams", "angle", "points"};
            case "pyramid" -> new String[]{"base", "height", "points"};
            case "ring_pulse" -> new String[]{"radius", "rings", "spacing", "points"};
            case "wings" -> new String[]{"span", "arch", "depth", "dir", "points"};
            case "arch" -> new String[]{"radius", "arc", "dir", "points"};
            case "claw" -> new String[]{"radius", "claws", "spread", "curve", "dir", "drop", "points"};
            case "dome" -> new String[]{"radius", "points"};
            case "cube" -> new String[]{"size", "points", "edges"};
            case "line" -> new String[]{"length", "dir", "rise", "points"};
            case "ribbon" -> new String[]{"radius", "points", "waves", "amplitude"};
            case "scatter" -> new String[]{"radius", "height", "points", "seed", "floor"};
            case "display" -> new String[0];
            default -> new String[0];
        };
    }

    // ------------------------------------------------------------------ flat

    private static List<Vector> circle(Shape.ShapeArgs args) {
        double radius = args.number("radius", 1.0);
        int points = args.atLeastOne("points", 16);

        double step = 2 * Math.PI / points;
        List<Vector> out = new ArrayList<>(points);
        for (int i = 0; i < points; i++) {
            double angle = i * step;
            out.add(new Vector(radius * Math.cos(angle), 0, radius * Math.sin(angle)));
        }
        return out;
    }

    private static List<Vector> disc(Shape.ShapeArgs args) {
        double radius = args.number("radius", 2.0);
        int rings = args.atLeastOne("rings", 5);
        // New: commons derived this from the radius and gave no way to change
        // it, so a wide disc was always expensive and a small one always coarse.
        int perRing = args.count("points", 0);

        List<Vector> out = new ArrayList<>();
        out.add(new Vector(0, 0, 0));
        for (int ring = 1; ring <= rings; ring++) {
            double r = radius * ((double) ring / rings);
            int count = perRing > 0 ? perRing : Math.max(8, (int) (r * 16));
            double step = 2.0 * Math.PI / count;
            for (int i = 0; i < count; i++) {
                double angle = i * step;
                out.add(new Vector(r * Math.cos(angle), 0, r * Math.sin(angle)));
            }
        }
        return out;
    }

    /**
     * The upper half of a sphere.
     *
     * <p>A sphere wide enough to be worth drawing buries its lower half, which
     * is invisible and paid for anyway. A dome is the half anybody was looking
     * at: a shield, a blast front, a canopy.
     */
    private static List<Vector> dome(Shape.ShapeArgs args) {
        double radius = args.number("radius", 2.0);
        int points = args.atLeastOne("points", 24);

        double goldenAngle = Math.PI * (3.0 - Math.sqrt(5.0));
        List<Vector> out = new ArrayList<>(points);
        for (int i = 0; i < points; i++) {
            // Only the top half of the range the sphere walks, so the spread
            // stays even instead of crowding the rim.
            double y = points == 1 ? 1.0 : 1.0 - (i / (double) points);
            double r = Math.sqrt(Math.max(0.0, 1.0 - y * y));
            double theta = goldenAngle * i;
            out.add(new Vector(radius * r * Math.cos(theta), radius * y,
                    radius * r * Math.sin(theta)));
        }
        return out;
    }

    /**
     * The twelve edges of a cube.
     *
     * <p>Straight lines and right angles, which nothing else here produces. A
     * shape made of them reads as something built rather than something
     * summoned, and that is a whole register of effect the round shapes cannot
     * reach.
     */
    private static List<Vector> cube(Shape.ShapeArgs args) {
        double size = args.number("size", 2.0);
        int perEdge = args.atLeastOne("points", 4);
        boolean edges = args.flag("edges", true);
        double half = size / 2.0;

        List<Vector> out = new ArrayList<>(perEdge * 12);
        double[][] corners = {
                {-half, -half, -half}, {half, -half, -half}, {half, -half, half}, {-half, -half, half},
                {-half, half, -half}, {half, half, -half}, {half, half, half}, {-half, half, half}};
        int[][] lines = {
                {0, 1}, {1, 2}, {2, 3}, {3, 0},
                {4, 5}, {5, 6}, {6, 7}, {7, 4},
                {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        if (!edges) {
            // The eight corners alone: a frame implied rather than drawn, which
            // is what a cage of eight large objects wants.
            for (double[] corner : corners) {
                out.add(new Vector(corner[0], corner[1] + half, corner[2]));
            }
            return out;
        }
        for (int[] line : lines) {
            double[] from = corners[line[0]];
            double[] to = corners[line[1]];
            for (int i = 0; i < perEdge; i++) {
                double t = perEdge == 1 ? 0.0 : i / (double) perEdge;
                // Centred sideways and standing on the anchor, like a block a
                // player placed rather than one buried to its middle.
                out.add(new Vector(
                        from[0] + (to[0] - from[0]) * t,
                        from[1] + (to[1] - from[1]) * t + half,
                        from[2] + (to[2] - from[2]) * t));
            }
        }
        return out;
    }

    /**
     * A straight run of points in one direction.
     *
     * <p>Everything else here closes on itself. A line does not, which is what
     * a wall, a bridge, a lance or a sweep is made of, and it is the only shape
     * whose points have a beginning and an end for {@code ticks:} to draw along.
     */
    private static List<Vector> line(Shape.ShapeArgs args) {
        double length = args.number("length", 4.0);
        double direction = args.radians("dir", 0.0);
        double rise = args.number("rise", 0.0);
        int points = args.atLeastOne("points", 12);

        double stepX = Math.sin(direction) * length;
        double stepZ = Math.cos(direction) * length;
        List<Vector> out = new ArrayList<>(points);
        for (int i = 0; i < points; i++) {
            double t = points == 1 ? 0.0 : i / (double) (points - 1);
            out.add(new Vector(stepX * t, rise * t, stepZ * t));
        }
        return out;
    }

    /**
     * A ring that rises and falls as it goes round.
     *
     * <p>A flat ring is a circle and reads as a diagram. The same ring with a
     * wave in it reads as cloth, water or something alive, and it is one
     * sine away.
     */
    private static List<Vector> ribbon(Shape.ShapeArgs args) {
        double radius = args.number("radius", 2.0);
        int points = args.atLeastOne("points", 28);
        double waves = args.number("waves", 3.0);
        double amplitude = args.number("amplitude", 0.6);

        List<Vector> out = new ArrayList<>(points);
        for (int i = 0; i < points; i++) {
            double angle = i / (double) points * Math.PI * 2;
            out.add(new Vector(radius * Math.cos(angle),
                    amplitude * Math.sin(angle * waves),
                    radius * Math.sin(angle)));
        }
        return out;
    }

    /**
     * Points scattered through a volume, the same way every time.
     *
     * <p>The one thing every other shape here cannot do: look unplanned. Twenty
     * fragments on a perfect circle read as a diagram of an explosion, and the
     * same twenty scattered read as one.
     *
     * <p>Deterministic, from {@code seed:}, and that matters more than it
     * sounds. A sequence is compiled once and played by every kill on the
     * server; points drawn from a live generator would make one effect a
     * different effect every time, and there would be no way to test any of it
     * or to tell a server owner what their file does.
     */
    private static List<Vector> scatter(Shape.ShapeArgs args) {
        double radius = args.number("radius", 2.0);
        double height = args.number("height", 2.0);
        int points = args.atLeastOne("points", 20);
        long seed = (long) args.number("seed", 1.0);
        boolean floor = args.flag("floor", false);

        // A small linear congruential generator, written out rather than taken
        // from Random: this has to produce the same points on every server and
        // in every version, and Random is free to change how it seeds.
        long state = seed * 6364136223846793005L + 1442695040888963407L;
        List<Vector> out = new ArrayList<>(points);
        for (int i = 0; i < points; i++) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            double a = ((state >>> 11) / (double) (1L << 53)) * Math.PI * 2;
            state = state * 6364136223846793005L + 1442695040888963407L;
            // Square-rooted so the points spread evenly over the area rather
            // than crowding the middle, which is what a raw radius does.
            double r = radius * Math.sqrt((state >>> 11) / (double) (1L << 53));
            state = state * 6364136223846793005L + 1442695040888963407L;
            double h = ((state >>> 11) / (double) (1L << 53)) * height;
            out.add(new Vector(r * Math.cos(a), floor ? 0.0 : h, r * Math.sin(a)));
        }
        return out;
    }

    private static List<Vector> vortex(Shape.ShapeArgs args) {
        double radius = args.number("radius", 2.0);
        int turns = args.count("turns", 3);
        int points = args.atLeastOne("points", 60);

        double totalAngle = turns * 2.0 * Math.PI;
        List<Vector> out = new ArrayList<>(points);
        for (int i = 0; i < points; i++) {
            double t = (double) i / points;
            double r = radius * (1.0 - t);
            double angle = t * totalAngle;
            out.add(new Vector(r * Math.cos(angle), 0, r * Math.sin(angle)));
        }
        return out;
    }

    private static List<Vector> cross(Shape.ShapeArgs args) {
        double radius = args.number("radius", 2.0);
        int arms = args.atLeastOne("arms", 4);
        double angle = args.radians("angle", 0);
        int points = args.atLeastOne("points", 16);

        double step = 2.0 * Math.PI / arms;
        double[] dx = new double[arms];
        double[] dz = new double[arms];
        for (int a = 0; a < arms; a++) {
            dx[a] = Math.cos(angle + a * step);
            dz[a] = Math.sin(angle + a * step);
        }

        List<Vector> out = new ArrayList<>(arms * (points + 1));
        for (int j = 0; j <= points; j++) {
            double r = (double) j / points * radius;
            for (int a = 0; a < arms; a++) {
                out.add(new Vector(r * dx[a], 0, r * dz[a]));
            }
        }
        return out;
    }

    private static List<Vector> burst(Shape.ShapeArgs args) {
        double radius = args.number("radius", 2.0);
        int beams = args.atLeastOne("beams", 8);
        double angle = args.radians("angle", 0);
        int points = args.atLeastOne("points", 10);

        double step = 2.0 * Math.PI / beams;
        double[] dx = new double[beams];
        double[] dz = new double[beams];
        for (int b = 0; b < beams; b++) {
            dx[b] = Math.cos(angle + b * step);
            dz[b] = Math.sin(angle + b * step);
        }

        List<Vector> out = new ArrayList<>(beams * (points + 1));
        for (int j = 0; j <= points; j++) {
            double r = (double) j / points * radius;
            for (int b = 0; b < beams; b++) {
                out.add(new Vector(r * dx[b], 0, r * dz[b]));
            }
        }
        return out;
    }

    private static List<Vector> galaxy(Shape.ShapeArgs args) {
        double radius = args.number("radius", 2.5);
        int turns = args.count("turns", 2);
        int arms = args.atLeastOne("arms", 2);
        int points = args.atLeastOne("points", 50);

        double armOffset = 2.0 * Math.PI / arms;
        double totalAngle = turns * 2.0 * Math.PI;

        List<Vector> out = new ArrayList<>(arms * points);
        for (int i = 0; i < points; i++) {
            double t = (double) i / points;
            double r = t * radius;
            for (int a = 0; a < arms; a++) {
                double angle = a * armOffset + t * totalAngle;
                out.add(new Vector(r * Math.cos(angle), 0, r * Math.sin(angle)));
            }
        }
        return out;
    }

    private static List<Vector> star(Shape.ShapeArgs args) {
        double radius = args.number("radius", 1.5);
        int spikes = args.atLeastOne("spikes", 5);
        double inner = args.number("inner", 0.5);
        // Fixed: commons divided by this without a floor, so points:0 produced
        // infinities and drew the star at the world origin.
        int points = args.atLeastOne("points", 8);

        double innerRadius = radius * inner;
        int vertices = spikes * 2;
        double angleStep = 2.0 * Math.PI / vertices;
        double start = -Math.PI / 2;

        List<Vector> out = new ArrayList<>(vertices * (points + 1));
        for (int i = 0; i < vertices; i++) {
            double rA = (i % 2 == 0) ? radius : innerRadius;
            double rB = (i % 2 == 0) ? innerRadius : radius;
            double aA = start + i * angleStep;
            double aB = aA + angleStep;
            for (int j = 0; j <= points; j++) {
                double t = (double) j / points;
                out.add(new Vector(
                        rA * Math.cos(aA) * (1 - t) + rB * Math.cos(aB) * t,
                        0,
                        rA * Math.sin(aA) * (1 - t) + rB * Math.sin(aB) * t));
            }
        }
        return out;
    }

    private static List<Vector> ringPulse(Shape.ShapeArgs args) {
        double radius = args.number("radius", 2.0);
        int rings = args.atLeastOne("rings", 6);
        double spacing = args.number("spacing", 0.4);
        int points = args.atLeastOne("points", 24);

        double step = 2.0 * Math.PI / points;
        List<Vector> out = new ArrayList<>(rings * points);
        for (int ring = 0; ring < rings; ring++) {
            double y = ring * spacing;
            for (int i = 0; i < points; i++) {
                double angle = i * step;
                out.add(new Vector(radius * Math.cos(angle), y, radius * Math.sin(angle)));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- upright

    private static List<Vector> beam(Shape.ShapeArgs args) {
        double height = args.number("height", 3.0);
        int points = args.atLeastOne("points", 20);

        double step = height / points;
        List<Vector> out = new ArrayList<>(points + 1);
        for (int i = 0; i <= points; i++) {
            out.add(new Vector(0, i * step, 0));
        }
        return out;
    }

    private static List<Vector> spiral(Shape.ShapeArgs args) {
        double height = args.number("height", 3.0);
        double radius = args.number("radius", 1.0);
        int turns = args.count("turns", 2);
        int points = args.atLeastOne("points", 40);

        double angleStep = turns * 2.0 * Math.PI / points;
        double heightStep = height / points;

        List<Vector> out = new ArrayList<>(points);
        for (int i = 0; i < points; i++) {
            double angle = i * angleStep;
            out.add(new Vector(radius * Math.cos(angle), i * heightStep, radius * Math.sin(angle)));
        }
        return out;
    }

    private static List<Vector> doubleHelix(Shape.ShapeArgs args) {
        double height = args.number("height", 3.0);
        double radius = args.number("radius", 1.0);
        int turns = args.count("turns", 2);
        int points = args.atLeastOne("points", 40);
        // New: commons hardcoded two strands half a turn apart. A triple helix
        // is the same maths with a different divisor.
        int strands = args.atLeastOne("strands", 2);

        double angleStep = turns * 2.0 * Math.PI / points;
        double heightStep = height / points;
        double strandOffset = 2.0 * Math.PI / strands;

        List<Vector> out = new ArrayList<>(points * strands);
        for (int i = 0; i < points; i++) {
            double angle = i * angleStep;
            double y = i * heightStep;
            for (int s = 0; s < strands; s++) {
                double a = angle + s * strandOffset;
                out.add(new Vector(radius * Math.cos(a), y, radius * Math.sin(a)));
            }
        }
        return out;
    }

    private static List<Vector> tornado(Shape.ShapeArgs args) {
        double height = args.number("height", 4.0);
        double baseRadius = args.number("radius", 1.5);
        double topRadius = args.number("top_radius", 0.2);
        int turns = args.count("turns", 3);
        int points = args.atLeastOne("points", 60);

        double angleStep = turns * 2.0 * Math.PI / points;
        double heightStep = height / points;

        List<Vector> out = new ArrayList<>(points);
        for (int i = 0; i < points; i++) {
            double t = (double) i / points;
            double r = baseRadius + (topRadius - baseRadius) * t;
            double angle = i * angleStep;
            out.add(new Vector(r * Math.cos(angle), i * heightStep, r * Math.sin(angle)));
        }
        return out;
    }

    private static List<Vector> cage(Shape.ShapeArgs args) {
        double radius = args.number("radius", 1.5);
        double height = args.number("height", 3.0);
        int columns = args.atLeastOne("columns", 8);
        int points = args.atLeastOne("points", 16);

        double angleStep = 2.0 * Math.PI / columns;
        double heightStep = height / points;
        double[] cos = new double[columns];
        double[] sin = new double[columns];
        for (int col = 0; col < columns; col++) {
            cos[col] = Math.cos(col * angleStep);
            sin[col] = Math.sin(col * angleStep);
        }

        List<Vector> out = new ArrayList<>(columns * (points + 1));
        for (int pt = 0; pt <= points; pt++) {
            double y = pt * heightStep;
            for (int col = 0; col < columns; col++) {
                out.add(new Vector(radius * cos[col], y, radius * sin[col]));
            }
        }
        return out;
    }

    private static List<Vector> pyramid(Shape.ShapeArgs args) {
        double base = args.number("base", 2.0);
        double height = args.number("height", 3.0);
        int points = args.atLeastOne("points", 16);

        double[] cx = {base, base, -base, -base};
        double[] cz = {base, -base, -base, base};

        List<Vector> out = new ArrayList<>(8 * (points + 1));
        for (int j = 0; j <= points; j++) {
            double t = (double) j / points;
            for (int i = 0; i < 4; i++) {
                int next = (i + 1) % 4;
                out.add(new Vector(cx[i] * (1 - t) + cx[next] * t, 0, cz[i] * (1 - t) + cz[next] * t));
            }
            for (int i = 0; i < 4; i++) {
                out.add(new Vector(cx[i] * (1 - t), height * t, cz[i] * (1 - t)));
            }
        }
        return out;
    }

    private static List<Vector> wave(Shape.ShapeArgs args) {
        double length = args.number("length", 5.0);
        double amplitude = args.number("amplitude", 1.0);
        int frequency = args.count("frequency", 2);
        int arms = args.atLeastOne("arms", 1);
        double angle = args.radians("angle", 0);
        int points = args.atLeastOne("points", 40);

        double armOffset = 2.0 * Math.PI / arms;
        double[] dx = new double[arms];
        double[] dz = new double[arms];
        for (int a = 0; a < arms; a++) {
            dx[a] = Math.cos(angle + a * armOffset);
            dz[a] = Math.sin(angle + a * armOffset);
        }

        List<Vector> out = new ArrayList<>(arms * (points + 1));
        for (int i = 0; i <= points; i++) {
            double t = (double) i / points;
            double dist = -length / 2.0 + t * length;
            double y = amplitude * Math.sin(frequency * 2.0 * Math.PI * t);
            for (int a = 0; a < arms; a++) {
                out.add(new Vector(dist * dx[a], y, dist * dz[a]));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------- 3-D

    private static List<Vector> sphere(Shape.ShapeArgs args) {
        double radius = args.number("radius", 1.0);
        int points = args.atLeastOne("points", 32);

        // Fibonacci sphere: evenly spread without the clustering at the poles a
        // naive lat/long loop produces.
        double goldenAngle = Math.PI * (3.0 - Math.sqrt(5.0));
        List<Vector> out = new ArrayList<>(points);
        for (int i = 0; i < points; i++) {
            // Guarded: with one point commons divided by zero here.
            double y = points == 1 ? 0.0 : 1.0 - (i / (double) (points - 1)) * 2.0;
            double r = Math.sqrt(Math.max(0.0, 1.0 - y * y));
            double theta = goldenAngle * i;
            // Fixed: commons added a hardcoded +1.0 here and ignored y:
            // entirely, alone among the twenty shapes. The centre now sits at
            // the anchor and y: raises it like everywhere else; the default in
            // the step keeps a sphere at chest height.
            out.add(new Vector(radius * r * Math.cos(theta), radius * y, radius * r * Math.sin(theta)));
        }
        return out;
    }

    private static List<Vector> torus(Shape.ShapeArgs args) {
        double radius = args.number("radius", 1.5);
        double tube = args.number("tube", 0.5);
        int segments = args.atLeastOne("segments", 20);
        int tubeSegments = args.atLeastOne("tube_segments", 10);

        List<Vector> out = new ArrayList<>(segments * tubeSegments);
        for (int i = 0; i < segments; i++) {
            double phi = 2.0 * Math.PI * i / segments;
            for (int j = 0; j < tubeSegments; j++) {
                double theta = 2.0 * Math.PI * j / tubeSegments;
                double ring = radius + tube * Math.cos(theta);
                // Fixed: commons added +1.0 on top of y:, which no other shape
                // did. y: alone now decides the height.
                out.add(new Vector(ring * Math.cos(phi), tube * Math.sin(theta), ring * Math.sin(phi)));
            }
        }
        return out;
    }

    private static List<Vector> wings(Shape.ShapeArgs args) {
        double span = args.number("span", 2.5);
        double arch = args.number("arch", 1.0);
        double depth = args.number("depth", 0.4);
        double dir = args.radians("dir", 0.0);
        int points = args.atLeastOne("points", 30);

        double cos = Math.cos(dir);
        double sin = Math.sin(dir);

        List<Vector> out = new ArrayList<>((points + 1) * 2);
        for (int i = 0; i <= points; i++) {
            double t = (double) i / points;
            double curve = Math.sin(t * Math.PI);
            for (int w = 0; w < 2; w++) {
                double lx = (w == 0 ? 1.0 : -1.0) * t * span;
                double lz = depth * curve;
                out.add(new Vector(lx * cos - lz * sin, arch * curve, lx * sin + lz * cos));
            }
        }
        return out;
    }

    private static List<Vector> arch(Shape.ShapeArgs args) {
        double radius = args.number("radius", 1.5);
        double half = args.radians("arc", 180.0) / 2.0;
        double dir = args.radians("dir", 0.0);
        int points = args.atLeastOne("points", 20);

        double cos = Math.cos(dir);
        double sin = Math.sin(dir);

        List<Vector> out = new ArrayList<>(points + 1);
        for (int i = 0; i <= points; i++) {
            double t = (double) i / points;
            double theta = Math.PI / 2.0 - half + t * 2.0 * half;
            double u = radius * Math.cos(theta);
            out.add(new Vector(u * cos, radius * Math.sin(theta), u * sin));
        }
        return out;
    }

    private static List<Vector> claw(Shape.ShapeArgs args) {
        double radius = args.number("radius", 2.0);
        int claws = args.atLeastOne("claws", 3);
        double spread = args.radians("spread", 90.0);
        double curve = args.radians("curve", 20.0);
        double dir = args.radians("dir", 0.0);
        double drop = args.number("drop", 0.3);
        int points = args.atLeastOne("points", 14);

        double start = dir - spread / 2.0;
        double angleStep = claws > 1 ? spread / (claws - 1) : 0.0;
        double[] base = new double[claws];
        for (int c = 0; c < claws; c++) {
            base[c] = start + c * angleStep;
        }

        List<Vector> out = new ArrayList<>(claws * (points + 1));
        for (int i = 0; i <= points; i++) {
            double t = (double) i / points;
            double r = t * radius;
            double y = -drop * t * t * radius;
            for (int c = 0; c < claws; c++) {
                double angle = base[c] + curve * t;
                out.add(new Vector(r * Math.cos(angle), y, r * Math.sin(angle)));
            }
        }
        return out;
    }
}
