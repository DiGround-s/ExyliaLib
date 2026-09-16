package net.exylia.lib.camera;

import net.exylia.lib.util.internal.Ease;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Where the camera is, written as the frames it passes through.
 *
 * <pre>{@code
 * CameraShot orbit = CameraShot.parse(
 *         "0 close"
 *       + " | 3.2 yaw=~360 ease=in_out"
 *       + " | 0.6 distance=2.1 pitch=4 ease=out", problem -> getLogger().warning(problem));
 * }</pre>
 *
 * <h2>A frame</h2>
 * Frames are separated by {@code |}. Each starts with the seconds it takes to
 * reach it from the one before, and lists only what changes: everything it does
 * not mention is carried over, so a push-in is a frame that says nothing but
 * {@code distance=}.
 *
 * <p>Everything is written in the subject's own terms, so one shot reads the
 * same whichever way the player happens to be facing:
 * <table>
 *   <caption>Channels</caption>
 *   <tr><th>Channel</th><th>What it moves</th></tr>
 *   <tr><td>{@code distance=}</td><td>how far the camera sits from what it is
 *       watching, in blocks</td></tr>
 *   <tr><td>{@code yaw=}</td><td>where it sits around them, in degrees:
 *       {@code 0} behind their back, {@code 90} off their left shoulder,
 *       {@code 180} facing them</td></tr>
 *   <tr><td>{@code pitch=}</td><td>how far above the horizon it sits, in
 *       degrees; positive looks down at them</td></tr>
 *   <tr><td>{@code height=}</td><td>what it aims at, in blocks above their
 *       feet</td></tr>
 *   <tr><td>{@code ease=}</td><td>how this frame is reached</td></tr>
 * </table>
 *
 * <p>A number written as {@code ~90} is added to where that channel already is,
 * so {@code yaw=~360} is one more turn around them however many came before it.
 *
 * <p>A bare word is a whole frame: {@code behind}, {@code front}, {@code side},
 * {@code over}, {@code low}, {@code close}, {@code wide} and {@code top}. It
 * sets all four channels, and whatever the frame writes after it is written on
 * top.
 *
 * <h2>The camera always looks at the subject</h2>
 * Nothing here says where it points, because a shot that has to be told both
 * where the camera is and where it aims is a shot nobody gets right by hand.
 * The aim is the subject at {@code height}, every frame, and the whole
 * vocabulary above is about walking the camera around that point.
 *
 * <p>Immutable. Parsed once, when configuration is read, and shared by every
 * play.
 *
 * @since 1.172.0
 */
public final class CameraShot {

    /**
     * How often the camera is moved, in milliseconds.
     *
     * <p>Two ticks rather than one. The client is told how long it has to reach
     * each position and draws its own frames in between, exactly as a display
     * does, so halving the packets costs nothing anybody can see — and a camera
     * is the one entity on the server whose every update is sent while somebody
     * is looking straight down it.
     */
    public static final long SAMPLE_MILLIS = 100L;

    /**
     * The furthest the camera may turn between two positions, in degrees.
     *
     * <p>A rotation travels as a byte and the client takes the short way round
     * to it. Past a half turn in one step the short way round is the wrong way,
     * and an orbit visibly snaps backwards once a revolution.
     */
    private static final double MAX_DEGREES_PER_SAMPLE = 150;

    /** A ceiling, so a mistyped file cannot hold somebody in a camera. */
    private static final long MAX_MILLIS = 60_000L;

    static final int DISTANCE = 0;
    static final int YAW = 1;
    static final int PITCH = 2;
    static final int HEIGHT = 3;
    static final int COUNT = 4;

    /** Over the shoulder, which is what a player expects to see of themselves. */
    private static final double[] START = {3.2, 0, 12, 1.45};

    private static final CameraShot NONE =
            new CameraShot(new long[]{0L}, new double[][]{START.clone()}, new Ease[]{Ease.LINEAR});

    private final long[] times;
    private final double[][] keys;
    private final Ease[] eases;
    private final boolean loop;

    private CameraShot(long[] times, double[][] keys, Ease[] eases) {
        this(times, keys, eases, false);
    }

    private CameraShot(long[] times, double[][] keys, Ease[] eases, boolean loop) {
        this.times = times;
        this.keys = keys;
        this.eases = eases;
        this.loop = loop;
    }

    /**
     * The same shot, played again as soon as it ends.
     *
     * <p>For a body that is still dancing when the camera runs out of path. The
     * path has to come back to where it started &mdash; an orbit of a whole
     * turn does, a swoop from far to near does not &mdash; because a loop
     * whose ends do not meet jumps once a cycle, forever.
     *
     * @return the looping shot
     * @since 1.174.0
     */
    public @NotNull CameraShot looping() {
        return loop ? this : new CameraShot(times, keys, eases, true);
    }

    /**
     * Whether this shot starts again when it ends.
     *
     * @since 1.174.0
     */
    public boolean loops() {
        return loop;
    }

    /** A camera that sits behind the subject and does nothing. */
    public static @NotNull CameraShot none() {
        return NONE;
    }

    /**
     * Reads a shot.
     *
     * <p>A frame that cannot be read is reported and skipped, and so is a word
     * in it: a camera that misses one move is better than an emote that does
     * not play.
     *
     * @param text     the frames, separated by {@code |}
     * @param problems where each thing that could not be read is described
     * @return the shot
     */
    public static @NotNull CameraShot parse(@NotNull String text, @NotNull Consumer<String> problems) {
        List<Long> times = new ArrayList<>();
        List<double[]> keys = new ArrayList<>();
        List<Ease> eases = new ArrayList<>();
        times.add(0L);
        keys.add(START.clone());
        eases.add(Ease.LINEAR);

        long clock = 0;
        int written = 0;
        for (String frame : text.split("\\|")) {
            String[] words = frame.trim().split("\\s+");
            if (words.length == 0 || words[0].isEmpty()) {
                continue;
            }
            written++;
            double seconds;
            try {
                seconds = Double.parseDouble(words[0]);
            } catch (NumberFormatException malformed) {
                problems.accept("frame " + written + " does not start with its seconds: \""
                        + frame.trim() + "\"");
                continue;
            }
            double[] where = keys.get(keys.size() - 1).clone();
            Ease ease = Ease.IN_OUT;
            for (int index = 1; index < words.length; index++) {
                String word = words[index];
                int equals = word.indexOf('=');
                if (equals < 0) {
                    double[] preset = PRESETS.get(word.toLowerCase(Locale.ROOT));
                    if (preset == null) {
                        problems.accept("frame " + written + ": there is no shot called \""
                                + word + "\"");
                        continue;
                    }
                    System.arraycopy(preset, 0, where, 0, COUNT);
                    continue;
                }
                if (word.substring(0, equals).equalsIgnoreCase("ease")) {
                    Ease named = Ease.of(word.substring(equals + 1));
                    if (named == null) {
                        problems.accept("frame " + written + ": there is no ease called \""
                                + word.substring(equals + 1) + "\"");
                    } else {
                        ease = named;
                    }
                    continue;
                }
                write(where, word, written, problems);
            }
            clamp(where);
            long millis = Math.max(0L, Math.round(seconds * 1000));
            double[] previous = keys.get(keys.size() - 1);
            if (ease != Ease.SNAP && millis > 0) {
                double perSample = Math.abs(where[YAW] - previous[YAW])
                        / millis * SAMPLE_MILLIS * ease.peak();
                if (perSample > MAX_DEGREES_PER_SAMPLE) {
                    problems.accept("frame " + written + " goes round the subject faster than the"
                            + " client can follow the right way; give it longer");
                }
            }
            if (clock + millis > MAX_MILLIS) {
                problems.accept("frame " + written + " takes the shot past "
                        + (MAX_MILLIS / 1000) + " seconds, which is as long as a camera may hold"
                        + " somebody; the rest of the shot was dropped");
                break;
            }
            clock += millis;
            times.add(clock);
            keys.add(where);
            eases.add(ease);
        }
        if (keys.size() == 1) {
            return NONE;
        }
        long[] packed = new long[times.size()];
        for (int index = 0; index < packed.length; index++) {
            packed[index] = times.get(index);
        }
        return new CameraShot(packed, keys.toArray(double[][]::new), eases.toArray(Ease[]::new));
    }

    /** How long the whole shot takes, in milliseconds. */
    public long durationMillis() {
        return times[times.length - 1];
    }

    /** Whether it has no frames at all, and so nothing to play. */
    public boolean isEmpty() {
        return keys.length <= 1 || durationMillis() <= 0;
    }

    /** How many frames were read. */
    public int frames() {
        return keys.length - 1;
    }

    /**
     * Every channel at one moment.
     *
     * @param millis how far into the shot
     * @return a fresh array the caller may keep, in
     *         {@code distance, yaw, pitch, height} order
     */
    @ApiStatus.Internal
    public double @NotNull [] at(long millis) {
        int last = times.length - 1;
        if (millis <= 0 || last == 0) {
            int start = 0;
            while (start < last && times[start + 1] <= 0) {
                start++;
            }
            return keys[start].clone();
        }
        if (millis >= times[last]) {
            return keys[last].clone();
        }
        int to = 1;
        while (times[to] <= millis) {
            to++;
        }
        int from = to - 1;
        double span = times[to] - times[from];
        double progress = (millis - times[from]) / span;
        Ease ease = eases[to];
        double[] where = new double[COUNT];
        if (ease == Ease.SMOOTH) {
            // A curve run through the neighbouring frames rather than stopped
            // at every one of them: a camera that visits four waypoints without
            // pausing at each, which is the one move no other ease can give.
            double squared = progress * progress;
            double cubed = squared * progress;
            double h00 = 2 * cubed - 3 * squared + 1;
            double h10 = cubed - 2 * squared + progress;
            double h01 = -2 * cubed + 3 * squared;
            double h11 = cubed - squared;
            for (int channel = 0; channel < COUNT; channel++) {
                where[channel] = h00 * keys[from][channel]
                        + h10 * span * slope(from, channel)
                        + h01 * keys[to][channel]
                        + h11 * span * slope(to, channel);
            }
            clamp(where);
            return where;
        }
        double eased = ease.at(progress);
        for (int channel = 0; channel < COUNT; channel++) {
            where[channel] = keys[from][channel]
                    + (keys[to][channel] - keys[from][channel]) * eased;
        }
        clamp(where);
        return where;
    }

    /**
     * How fast a channel is moving through a frame, per millisecond.
     *
     * <p>Only a frame that is flowed through has a speed. One reached with any
     * other ease is arrived at, which is a stop, and a curve that sailed past it
     * would be a camera that never reaches the position the file wrote.
     */
    private double slope(int key, int channel) {
        if (key <= 0 || key >= times.length - 1) {
            return 0;
        }
        Ease into = eases[key];
        Ease out = eases[key + 1];
        boolean flowsIn = into == Ease.SMOOTH || into == Ease.LINEAR;
        boolean flowsOut = out == Ease.SMOOTH || out == Ease.LINEAR;
        if (!flowsIn || !flowsOut) {
            return 0;
        }
        long before = times[key] - times[key - 1];
        long after = times[key + 1] - times[key];
        if (into == Ease.LINEAR) {
            return before <= 0 ? 0 : (keys[key][channel] - keys[key - 1][channel]) / before;
        }
        if (out == Ease.LINEAR) {
            return after <= 0 ? 0 : (keys[key + 1][channel] - keys[key][channel]) / after;
        }
        long across = times[key + 1] - times[key - 1];
        return across <= 0 ? 0 : (keys[key + 1][channel] - keys[key - 1][channel]) / across;
    }

    // ---------------------------------------------------------------- reading

    /**
     * Keeps a frame inside what a camera can actually be.
     *
     * <p>Distance has a near limit because the client draws nothing closer than
     * its near plane and a camera inside the subject shows the inside of their
     * head, and a far one because the camera is an entity the client only keeps
     * while it is being tracked. Pitch stops short of straight up and straight
     * down, where the aim yaw is undefined and the view rolls as it passes.
     */
    private static void clamp(double[] where) {
        where[DISTANCE] = Math.clamp(where[DISTANCE], 0.6, 24.0);
        where[PITCH] = Math.clamp(where[PITCH], -85.0, 85.0);
        where[HEIGHT] = Math.clamp(where[HEIGHT], -2.0, 6.0);
    }

    /** Writes one {@code name=value} onto a frame. */
    private static void write(double[] where, String word, int frame, Consumer<String> problems) {
        int equals = word.indexOf('=');
        if (equals <= 0) {
            problems.accept("frame " + frame + ": \"" + word + "\" is not name=value");
            return;
        }
        String name = word.substring(0, equals).toLowerCase(Locale.ROOT);
        Integer channel = CHANNELS.get(name);
        if (channel == null) {
            problems.accept("frame " + frame + ": there is nothing called \"" + name
                    + "\" to move the camera by");
            return;
        }
        String part = word.substring(equals + 1).trim();
        boolean relative = part.startsWith("~");
        String number = relative ? part.substring(1) : part;
        double value;
        try {
            value = number.isEmpty() ? 0 : Double.parseDouble(number);
        } catch (NumberFormatException malformed) {
            problems.accept("frame " + frame + ": \"" + part + "\" in " + name + " is not a number");
            return;
        }
        where[channel] = relative ? where[channel] + value : value;
    }

    private static final Map<String, Integer> CHANNELS = Map.of(
            "distance", DISTANCE,
            "away", DISTANCE,
            "yaw", YAW,
            "around", YAW,
            "pitch", PITCH,
            "above", PITCH,
            "height", HEIGHT,
            "aim", HEIGHT);

    /** Whole frames, in {@code distance, yaw, pitch, height} order. */
    private static final Map<String, double[]> PRESETS = new HashMap<>();

    static {
        PRESETS.put("behind", new double[]{3.2, 0, 12, 1.45});
        PRESETS.put("front", new double[]{3.0, 180, 8, 1.55});
        PRESETS.put("side", new double[]{3.0, 90, 10, 1.45});
        PRESETS.put("over", new double[]{2.6, 25, 42, 1.20});
        PRESETS.put("low", new double[]{2.8, 200, -14, 0.90});
        PRESETS.put("close", new double[]{1.8, 160, 6, 1.60});
        PRESETS.put("wide", new double[]{6.0, 30, 18, 1.30});
        PRESETS.put("top", new double[]{5.0, 0, 78, 1.00});
    }
}
