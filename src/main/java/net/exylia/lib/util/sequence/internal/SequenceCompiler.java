package net.exylia.lib.util.sequence.internal;

import net.exylia.lib.util.sequence.Shape;
import net.exylia.lib.util.sequence.SequenceStep;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns configuration lines into steps, once.
 *
 * <p>Everything expensive happens here: names become enums, numbers become
 * doubles, and a shape's trigonometry is run and kept as an array of points.
 * What is left for runtime is arithmetic and packets.
 *
 * <h2>A bad line is reported and skipped</h2>
 * The rest of the sequence still plays. A misspelled particle should cost the
 * server one line of its effect and one line of console, not the whole
 * choreography &mdash; and never the event that triggered it.
 */
public final class SequenceCompiler {

    /** How many milliseconds a tick is, for the file's seconds. */
    private static final long TICK_MS = 50L;

    private final Map<String, Shape> shapes;
    private final Problems problems;

    /**
     * Whose displays these are.
     *
     * <p>Only display lines need it, and they need it at compile rather than at
     * play: a display outlives the tick that drew it, so something has to be
     * able to take it off a client when that plugin is disabled.
     */
    private final String owner;

    public SequenceCompiler(@NotNull Map<String, Shape> shapes, @NotNull Problems problems) {
        this(shapes, problems, "ExyliaLib");
    }

    public SequenceCompiler(@NotNull Map<String, Shape> shapes, @NotNull Problems problems,
                            @NotNull String owner) {
        this.shapes = shapes;
        this.problems = problems;
        this.owner = owner;
    }

    /** Compiles every line, skipping the ones that cannot be understood. */
    public @NotNull List<SequenceStep> compile(@NotNull List<String> lines) {
        List<SequenceStep> steps = new ArrayList<>(lines.size());
        for (String line : lines) {
            SequenceStep step = compileLine(line);
            if (step != null) {
                steps.add(step);
            }
        }
        return steps;
    }

    private @Nullable SequenceStep compileLine(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String line = raw.trim();
        int close = line.indexOf(']');
        if (line.charAt(0) != '[' || close < 2) {
            problems.found(line, "does not start with a [TOKEN]");
            return null;
        }
        String token = line.substring(1, close).trim().toUpperCase(Locale.ROOT);
        String rest = close + 1 < line.length() ? line.substring(close + 1).trim() : "";

        Args.Problems onArg = (where, problem) ->
                problems.found(line, where + " " + problem);
        Args args = Args.parse(rest, onArg);

        Shape shape = shapes.get(token.toLowerCase(Locale.ROOT));
        if (shape != null) {
            return repeated(shapeStep(token, shape, args, line, onArg), args, onArg);
        }

        return repeated(switch (token) {
            case "DELAY" -> delay(rest, line);
            case "PARTICLE" -> particles(args, line, onArg);
            case "SOUND" -> sound(args, line, onArg);
            // The three that name nothing positionally: their first segment is
            // a parameter, not a head, and reading it as one is what made every
            // firework in every existing file come out red.
            case "LIGHTNING" -> lightning(args.asHeadless(), onArg);
            case "EXPLOSION" -> explosion(args.asHeadless(), line, onArg);
            case "FIREWORK" -> firework(args.asHeadless(), onArg);
            case "COMMAND" -> rest.isEmpty() ? null : new Steps.Command(rest);
            case "POTION" -> potion(args, line, onArg);
            case "BLOCK_BREAK" -> blockBreak(args, line, onArg);
            case "TITLE" -> title(args, rest, onArg);
            case "ACTION_BAR" -> rest.isEmpty() ? null : new Steps.ActionBarStep(rest);
            case "MESSAGE" -> rest.isEmpty() ? null : new Steps.Message(rest);
            default -> {
                problems.found(line, "there is no effect called \"" + token + "\"");
                yield null;
            }
        }, args, onArg);
    }

    /**
     * Wraps a step that asked to be played more than once.
     *
     * <p>Here rather than inside each token, because a beat is a property of
     * the line and not of what the line draws: a sound, a shape and a ring of
     * blades all repeat the same way.
     */
    private @Nullable SequenceStep repeated(@Nullable SequenceStep step, Args args,
                                            Args.Problems onArg) {
        if (step == null || !args.has("repeat")) {
            return step;
        }
        return RepeatStep.of(step,
                args.count("repeat", 1, onArg),
                (long) (args.number("every", 0.15, onArg) * 1000),
                Math.toRadians(args.number("turn_each", 0.0, onArg)));
    }

    // ------------------------------------------------------------------ shapes

    private @Nullable SequenceStep shapeStep(String token, Shape shape, Args args, String line,
                                             Args.Problems onArg) {
        // What the head names depends on one parameter: with as: it is an item,
        // a block, a head or a line of text, and without it a particle. The
        // geometry, the animation, the rotation and who sees it are the same
        // either way, which is the whole reason a ring of swords needed no new
        // shape and no new token.
        boolean displays = DisplayReader.wanted(args, token);
        if (args.headless()) {
            problems.found(line, displays
                    ? "needs something to draw, as in [" + token + "] NETHERITE_SWORD;as:item"
                    : "needs a particle, as in [" + token + "] FLAME");
            return null;
        }
        Particle particle = null;
        if (!displays) {
            particle = ParticlePaint.particle(args.head());
            if (particle == null) {
                problems.found(line, "there is no particle called \"" + args.head() + "\"");
                return null;
            }
        }

        ShapeReader reader = new ShapeReader(args, onArg);
        List<Vector> points;
        try {
            points = shape.points(reader);
        } catch (RuntimeException broken) {
            // A shape is often written by a plugin author. One that throws
            // takes out its own line, not the sequence and not the event.
            problems.found(line, "the shape failed to build: " + broken);
            return null;
        }
        if (points.isEmpty()) {
            problems.found(line, "the shape produced no points");
            return null;
        }

        double yShift = args.number("y", defaultHeight(token), onArg);
        double scale = args.number("scale", 1.0, onArg);
        if (yShift != 0.0 || scale != 1.0) {
            List<Vector> moved = new ArrayList<>(points.size());
            for (Vector point : points) {
                moved.add(new Vector(point.getX() * scale,
                        point.getY() * scale + yShift,
                        point.getZ() * scale));
            }
            points = moved;
        }

        Color colour = args.colour("color", null, onArg);
        float size = (float) args.number("size", 1.0, onArg);
        int count = args.count("count", 1, onArg);
        int ticks = args.count("ticks", 1, onArg);
        double interval = args.number("interval", 0.05, onArg);
        boolean faceSource = args.flag("face", false);
        double yaw = args.number("rotate", 0.0, onArg);

        if (displays) {
            Paint paint = DisplayReader.read(owner, args, token, onArg);
            if (paint == null) {
                return null;
            }
            args.reportUnknown(onArg, merge(merge(Shapes.parametersOf(token), SHARED),
                    DisplayReader.PARAMETERS));
            return ShapeStep.of(paint, points, ticks, (long) (interval * 1000), yaw, faceSource);
        }

        args.reportUnknown(onArg, merge(Shapes.parametersOf(token), SHARED));

        Object data = ParticlePaint.dataFor(particle, colour, size, null);
        return ShapeStep.of(ParticlePaint.point(particle, data, count), points, ticks,
                (long) (interval * 1000), yaw, faceSource);
    }

    /**
     * Where a shape sits when the file does not say.
     *
     * <p>Only the sphere and the torus have one. Both used to be nailed a block
     * above the anchor with no way to move them; keeping that as the default
     * means an existing file draws them exactly where it always did, while
     * {@code y:} now works on them like on every other shape.
     */
    private static double defaultHeight(String token) {
        return switch (token) {
            case "SPHERE", "TORUS" -> 1.0;
            default -> 0.0;
        };
    }

    /** What every shape line understands, whatever it is drawn with. */
    private static final String[] SHARED = {
            "y", "scale", "color", "size", "count", "ticks", "interval", "face", "rotate"
    };

    private static String[] merge(String[] first, String... second) {
        String[] all = new String[first.length + second.length];
        System.arraycopy(first, 0, all, 0, first.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }

    // ------------------------------------------------------------------ tokens

    private @Nullable SequenceStep delay(String rest, String line) {
        double seconds;
        try {
            seconds = Double.parseDouble(rest.trim());
        } catch (NumberFormatException malformed) {
            problems.found(line, "\"" + rest + "\" is not a number of seconds");
            return null;
        }
        if (seconds <= 0) {
            return null;
        }
        return new Steps.Delay((long) (seconds * 1000));
    }

    private @Nullable SequenceStep particles(Args args, String line, Args.Problems onArg) {
        if (args.headless()) {
            problems.found(line, "needs a particle, as in [PARTICLE] FLAME");
            return null;
        }
        Particle particle = ParticlePaint.particle(args.head());
        if (particle == null) {
            problems.found(line, "there is no particle called \"" + args.head() + "\"");
            return null;
        }
        int count = args.count("count", 1, onArg);
        double speed = args.number("speed", 0, onArg);
        double yShift = args.number("y", 0, onArg);
        Color colour = args.colour("color", null, onArg);
        float size = (float) args.number("size", 1.0, onArg);

        double x = 0;
        double y = 0;
        double z = 0;
        if (args.has("offset")) {
            String[] parts = args.text("offset", "").split(",");
            if (parts.length >= 3) {
                x = number(parts[0], 0, "offset", onArg);
                y = number(parts[1], 0, "offset", onArg);
                z = number(parts[2], 0, "offset", onArg);
            } else if (parts.length == 1) {
                // One number is the same spread on all three axes, which is what
                // "offset:0" plainly means. Refusing it dropped the whole
                // particle line over a shorthand nobody could misread.
                x = y = z = number(parts[0], 0, "offset", onArg);
            } else {
                onArg.found("offset", "needs three numbers, as in offset:0.1,0.1,0.1,"
                        + " or one for all three");
            }
        }
        Material material = material(args, onArg);
        args.reportUnknown(onArg, "count", "speed", "y", "color", "size", "offset", "block");

        Object data = ParticlePaint.dataFor(particle, colour, size, material);
        return new Steps.Particles(new ParticlePaint(particle, data, count, x, y, z, speed), yShift);
    }

    private @Nullable SequenceStep sound(Args args, String line, Args.Problems onArg) {
        if (args.headless()) {
            problems.found(line, "needs a sound, as in [SOUND] ENTITY_BLAZE_DEATH");
            return null;
        }
        String key = soundKey(args.head());
        // Both spellings: the positional form every existing file uses, and the
        // named form that says what the numbers mean.
        float volume = (float) namedOrPositional(args, "volume", 1, 1.0, onArg);
        float pitch = (float) namedOrPositional(args, "pitch", 2, 1.0, onArg);
        args.reportUnknown(onArg, "volume", "pitch");
        return new Steps.Noise(key, volume, pitch);
    }

    private SequenceStep lightning(Args args, Args.Problems onArg) {
        float volume = (float) args.number("volume", 2.0, onArg);
        float pitch = (float) args.number("pitch", 1.0, onArg);
        args.reportUnknown(onArg, "volume", "pitch");
        return new Steps.Lightning(volume, pitch);
    }

    private @Nullable SequenceStep explosion(Args args, String line, Args.Problems onArg) {
        if (ParticlePaint.EXPLOSION == null) {
            problems.found(line, "this server has no explosion particle");
            return null;
        }
        int count = args.count("count", 1, onArg);
        double yShift = args.number("y", 0, onArg);
        args.reportUnknown(onArg, "count", "y");
        return new Steps.Particles(
                ParticlePaint.point(ParticlePaint.EXPLOSION, null, count), yShift);
    }

    private SequenceStep firework(Args args, Args.Problems onArg) {
        Color colour = args.colour("color", Color.RED, onArg);
        Color fade = args.colour("fade", Color.ORANGE, onArg);
        FireworkEffect.Type type = FireworkEffect.Type.BALL_LARGE;
        if (args.has("type")) {
            try {
                type = FireworkEffect.Type.valueOf(
                        args.text("type", "").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                onArg.found("type", "\"" + args.text("type", "") + "\" is not a firework shape");
            }
        }
        boolean trail = args.flag("trail", true);
        boolean flicker = args.flag("flicker", false);
        int power = args.count("power", 0, onArg);
        args.reportUnknown(onArg, "color", "fade", "type", "trail", "flicker", "power");
        return new Steps.Fireworks(colour, fade, type, trail, flicker, power);
    }

    private @Nullable SequenceStep potion(Args args, String line, Args.Problems onArg) {
        if (args.headless()) {
            problems.found(line, "needs an effect, as in [POTION] speed;100;1");
            return null;
        }
        var type = Steps.potion(args.head());
        if (type == null) {
            problems.found(line, "there is no potion effect called \"" + args.head() + "\"");
            return null;
        }
        int duration = (int) namedOrPositional(args, "duration", 1, 100, onArg);
        int amplifier = (int) namedOrPositional(args, "amplifier", 2, 0, onArg);
        args.reportUnknown(onArg, "duration", "amplifier");
        return new Steps.Potion(type, duration, amplifier);
    }

    private @Nullable SequenceStep blockBreak(Args args, String line, Args.Problems onArg) {
        if (args.headless()) {
            problems.found(line, "needs a block, as in [BLOCK_BREAK] STONE");
            return null;
        }
        Material material;
        try {
            material = Material.valueOf(args.head().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            problems.found(line, "there is no block called \"" + args.head() + "\"");
            return null;
        }
        if (ParticlePaint.BLOCK == null) {
            problems.found(line, "this server has no block particle");
            return null;
        }
        int count = args.count("count", 20, onArg);
        double yShift = args.number("y", 0, onArg);
        double x = 0.3;
        double y = 0.3;
        double z = 0.3;
        if (args.has("offset")) {
            String[] parts = args.text("offset", "").split(",");
            if (parts.length >= 3) {
                x = number(parts[0], 0.3, "offset", onArg);
                y = number(parts[1], 0.3, "offset", onArg);
                z = number(parts[2], 0.3, "offset", onArg);
            }
        }
        args.reportUnknown(onArg, "count", "y", "offset");
        Object data = material.createBlockData();
        return new Steps.Particles(
                new ParticlePaint(ParticlePaint.BLOCK, data, count, x, y, z, 0.1), yShift);
    }

    private @Nullable SequenceStep title(Args args, String rest, Args.Problems onArg) {
        if (rest.isEmpty()) {
            return null;
        }
        String[] parts = rest.split(";");
        String title = parts.length > 0 ? parts[0].trim() : "";
        String subtitle = parts.length > 1 ? parts[1].trim() : "";
        // Seconds, like everywhere else in the library. Commons took ticks here
        // and seconds elsewhere; a file that says 0.5 means half a second.
        long fadeIn = millis(parts, 2, 0.5);
        long stay = millis(parts, 3, 3.5);
        long fadeOut = millis(parts, 4, 1.0);
        return new Steps.TitleStep(title, subtitle, fadeIn, stay, fadeOut);
    }

    // ------------------------------------------------------------------ helpers

    private static long millis(String[] parts, int index, double fallbackSeconds) {
        if (parts.length <= index) {
            return (long) (fallbackSeconds * 1000);
        }
        try {
            return (long) (Double.parseDouble(parts[index].trim()) * 1000);
        } catch (NumberFormatException malformed) {
            return (long) (fallbackSeconds * 1000);
        }
    }

    private double namedOrPositional(Args args, String name, int index, double fallback,
                                     Args.Problems onArg) {
        if (args.has(name)) {
            return args.number(name, fallback, onArg);
        }
        String positional = args.positional(index);
        if (positional == null) {
            return fallback;
        }
        return number(positional, fallback, name, onArg);
    }

    private static double number(String raw, double fallback, String where, Args.Problems onArg) {
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException malformed) {
            onArg.found(where, "\"" + raw.trim() + "\" is not a number, using " + fallback);
            return fallback;
        }
    }

    private static @Nullable Material material(Args args, Args.Problems onArg) {
        if (!args.has("block")) {
            return null;
        }
        try {
            return Material.valueOf(args.text("block", "").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            onArg.found("block", "there is no block called \"" + args.text("block", "") + "\"");
            return null;
        }
    }

    /**
     * Resolves a sound by its Bukkit name or its namespaced key.
     *
     * <p>Both, because the enum constant is what every existing file writes and
     * the key is what a resource pack adds. The enum cannot be derived from the
     * key by string rules &mdash; {@code BLOCK_NOTE_BLOCK_PLING} keeps an
     * underscore inside {@code block.note_block.pling} &mdash; so it is asked
     * of the registry rather than guessed.
     */
    private static @NotNull String soundKey(@NotNull String name) {
        String trimmed = name.trim();
        try {
            Sound sound = Sound.valueOf(trimmed.toUpperCase(Locale.ROOT));
            org.bukkit.NamespacedKey key = org.bukkit.Registry.SOUNDS.getKey(sound);
            if (key != null) {
                return key.toString();
            }
        } catch (Throwable notAnEnumName) {
            // Already a key, or a server with no registry behind the enum.
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    /** Reads shape parameters, reporting rather than silently defaulting. */
    private record ShapeReader(Args args, Args.Problems problems) implements Shape.ShapeArgs {
        @Override
        public double number(@NotNull String key, double fallback) {
            return args.number(key, fallback, problems);
        }

        @Override
        public int count(@NotNull String key, int fallback) {
            return args.count(key, fallback, problems);
        }

        @Override
        public int atLeastOne(@NotNull String key, int fallback) {
            return args.atLeastOne(key, fallback, problems);
        }

        @Override
        public double radians(@NotNull String key, double fallback) {
            return Math.toRadians(args.number(key, fallback, problems));
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

    /** Where a line that cannot be compiled is reported. */
    @FunctionalInterface
    public interface Problems {
        /**
         * One line that could not be understood.
         *
         * @param line    the line as written
         * @param problem what is wrong with it
         */
        void found(@NotNull String line, @NotNull String problem);
    }
}
