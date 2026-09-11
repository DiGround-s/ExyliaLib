package net.exylia.lib.ragdoll;

import net.exylia.lib.ragdoll.internal.RagdollRig;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A body's choreography, written as the poses it passes through.
 *
 * <pre>{@code
 * RagdollAnimation hop = RagdollAnimation.parse(
 *         "0.25 crouch ease=anticipate"
 *       + " | 0.35 stand up=1.6 flip=~-360 arms=0,0,150 ease=out"
 *       + " | 0.3 up=0 ease=bounce", problem -> getLogger().warning(problem));
 * }</pre>
 *
 * <h2>A frame</h2>
 * Frames are separated by {@code |}. Each starts with the seconds it takes to
 * reach it from the one before, and lists only what changes: everything it does
 * not mention is carried over, so a hop is a frame that says {@code up=1.6} and a
 * landing is one that says {@code up=0}. The body starts standing where it died.
 *
 * <p>What a frame can say, all in the body's own terms &mdash; forward is towards
 * whoever it faces, right is its own right, angles are degrees:
 * <table>
 *   <caption>Channels</caption>
 *   <tr><th>Channel</th><th>What it moves</th></tr>
 *   <tr><td>{@code at=right,up,forward}</td><td>the hips, in blocks; also
 *       {@code right=} {@code up=} {@code forward=} one at a time</td></tr>
 *   <tr><td>{@code flip=} {@code turn=} {@code lean=}</td><td>the whole body about
 *       its hips: forwards, to its left, to its right</td></tr>
 *   <tr><td>{@code head=} {@code body=}</td><td>{@code pitch,yaw,roll}: nods and
 *       bows forward, turns to its left, tilts to its right</td></tr>
 *   <tr><td>{@code arm_r=} {@code arm_l=} {@code leg_r=} {@code leg_l=}</td>
 *       <td>{@code pitch,yaw,roll}: swings forward and up, swings a raised limb
 *       outwards, raises it away from the body. {@code arms=} and {@code legs=}
 *       set both sides, mirrored</td></tr>
 *   <tr><td>{@code <joint>_at=out,up,forward}</td><td>pulls a part away from its
 *       joint, in blocks: a head popping off, arms reaching out of their
 *       sleeves</td></tr>
 *   <tr><td>{@code size=} {@code <joint>_size=}</td><td>how big the body, or one
 *       part of it, is</td></tr>
 *   <tr><td>{@code shake=}</td><td>how hard the whole body trembles, in blocks</td></tr>
 *   <tr><td>{@code ease=}</td><td>how this frame is reached; see below</td></tr>
 * </table>
 *
 * <p>A number written as {@code ~90} is added to where that channel already is,
 * so {@code turn=~360} is one more turn however many came before it. A rotation
 * given one number sets only its pitch, two set pitch and yaw.
 *
 * <p>A bare word is a whole pose: {@code stand}, {@code tpose}, {@code star},
 * {@code cheer}, {@code reach}, {@code zombie}, {@code hug}, {@code crouch},
 * {@code sit}, {@code kneel}, {@code lie}, {@code prone}, {@code bow},
 * {@code pray}, {@code dab}, {@code superman}, {@code splits}, {@code float},
 * {@code limp}, {@code fetal}, {@code swoon}, {@code heart} and
 * {@code arabesque}. It sets every joint and how the hips sit, and whatever the
 * frame writes after it is written on top.
 *
 * <h2>Easing is where the life is</h2>
 * {@code in_out}, the default, arrives and leaves gently. {@code in} winds up
 * and strikes, {@code out} lands, {@code linear} keeps a spin at one speed.
 * {@code back} overshoots and settles, {@code anticipate} pulls back before it
 * goes, {@code elastic} springs, {@code bounce} lands twice, {@code snap} cuts
 * straight to the pose. {@code smooth} runs a curve through its neighbours
 * instead of stopping at every frame, which is what a body swaying from side to
 * side needs and what nothing else can give it.
 *
 * <p>Immutable. Parsed once, when configuration is read, and shared by every
 * death.
 *
 * @since 1.132.0
 */
public final class RagdollAnimation {

    /** The fastest a joint may be asked to turn between two frames the client draws. */
    private static final double MAX_DEGREES_PER_TICK = 160;

    private static final RagdollAnimation NONE = new RagdollAnimation(
            new long[]{0L}, new double[][]{RagdollRig.standing()}, new Ease[]{Ease.LINEAR});

    private final long[] times;
    private final double[][] keys;
    private final Ease[] eases;

    private RagdollAnimation(long[] times, double[][] keys, Ease[] eases) {
        this.times = times;
        this.keys = keys;
        this.eases = eases;
    }

    /** A body that stands where it died and does nothing. */
    public static @NotNull RagdollAnimation none() {
        return NONE;
    }

    /**
     * Reads a choreography.
     *
     * <p>A frame that cannot be read is reported and skipped, and so is a word
     * in it: a body that misses one gesture is better than a kill effect that
     * does not play.
     *
     * @param text     the frames, separated by {@code |}
     * @param problems where each thing that could not be read is described
     * @return the choreography
     */
    public static @NotNull RagdollAnimation parse(@NotNull String text, @NotNull Consumer<String> problems) {
        List<Long> times = new ArrayList<>();
        List<double[]> keys = new ArrayList<>();
        List<Ease> eases = new ArrayList<>();
        times.add(0L);
        keys.add(RagdollRig.standing());
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
            double[] pose = keys.get(keys.size() - 1).clone();
            Ease ease = Ease.IN_OUT;
            for (int index = 1; index < words.length; index++) {
                String word = words[index];
                int equals = word.indexOf('=');
                if (equals < 0) {
                    String preset = PRESETS.get(word.toLowerCase(Locale.ROOT));
                    if (preset == null) {
                        problems.accept("frame " + written + ": there is no pose called \"" + word + "\"");
                        continue;
                    }
                    strike(pose);
                    for (String part : preset.split("\\s+")) {
                        if (!part.isEmpty()) {
                            write(pose, part, written, problems, true);
                        }
                    }
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
                write(pose, word, written, problems, false);
            }
            long millis = Math.max(0L, Math.round(seconds * 1000));
            double[] previous = keys.get(keys.size() - 1);
            if (ease != Ease.SNAP && millis > 0) {
                for (int channel = 0; channel < RagdollRig.COUNT; channel++) {
                    if (!RagdollRig.isAngle(channel)) {
                        continue;
                    }
                    double perTick = Math.abs(pose[channel] - previous[channel]) / millis * 50 * ease.peak;
                    if (perTick > MAX_DEGREES_PER_TICK) {
                        problems.accept("frame " + written + " turns too fast for the client to"
                                + " follow the right way round; give it longer");
                        break;
                    }
                }
            }
            clock += millis;
            times.add(clock);
            keys.add(pose);
            eases.add(ease);
        }
        if (keys.size() == 1) {
            return NONE;
        }
        long[] packed = new long[times.size()];
        for (int index = 0; index < packed.length; index++) {
            packed[index] = times.get(index);
        }
        return new RagdollAnimation(packed, keys.toArray(double[][]::new), eases.toArray(Ease[]::new));
    }

    /** How long the whole choreography takes, in milliseconds. */
    public long durationMillis() {
        return times[times.length - 1];
    }

    /** Whether it has no frames at all. */
    public boolean isEmpty() {
        return keys.length <= 1;
    }

    /** How many frames were read. */
    public int frames() {
        return keys.length - 1;
    }

    /**
     * Every channel at one moment.
     *
     * @param millis how far into the choreography
     * @return a fresh array the caller may keep
     */
    @ApiStatus.Internal
    public double @NotNull [] at(long millis) {
        int last = times.length - 1;
        if (millis <= 0 || last == 0) {
            // Frames of no length are where the body starts: a clone that
            // stands across the room is there from the first tick.
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
        double[] pose = new double[RagdollRig.COUNT];
        if (ease == Ease.SMOOTH) {
            double squared = progress * progress;
            double cubed = squared * progress;
            double h00 = 2 * cubed - 3 * squared + 1;
            double h10 = cubed - 2 * squared + progress;
            double h01 = -2 * cubed + 3 * squared;
            double h11 = cubed - squared;
            for (int channel = 0; channel < pose.length; channel++) {
                pose[channel] = h00 * keys[from][channel]
                        + h10 * span * slope(from, channel)
                        + h01 * keys[to][channel]
                        + h11 * span * slope(to, channel);
            }
            return pose;
        }
        double eased = ease.at(progress);
        for (int channel = 0; channel < pose.length; channel++) {
            pose[channel] = keys[from][channel] + (keys[to][channel] - keys[from][channel]) * eased;
        }
        return pose;
    }

    /**
     * How fast a channel is moving through a frame, per millisecond.
     *
     * <p>Only a frame that is flowed through has a speed. One reached with any
     * other ease is arrived at, which is a stop, and a curve that sails past it
     * would be a body that never reaches the pose the file wrote. A linear
     * neighbour lends its own speed, so a spin that eases into a sway does not
     * hitch where the two meet.
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

    /** What a written channel name sets: one row of channels per side it applies to. */
    private record Target(int[][] rows, int least, int most) {
    }

    private static final Map<String, Target> CHANNELS = new HashMap<>();

    static {
        root("at", 3, RagdollRig.RIGHT, RagdollRig.UP, RagdollRig.FORWARD);
        root("right", 1, RagdollRig.RIGHT);
        root("up", 1, RagdollRig.UP);
        root("rise", 1, RagdollRig.UP);
        root("forward", 1, RagdollRig.FORWARD);
        root("flip", 1, RagdollRig.FLIP);
        root("turn", 1, RagdollRig.TURN);
        root("lean", 1, RagdollRig.LEAN);
        root("size", 1, RagdollRig.SIZE);
        root("shake", 1, RagdollRig.SHAKE);
        joint("head", RagdollRig.HEAD);
        joint("body", RagdollRig.BODY);
        joint("arm_r", RagdollRig.ARM_RIGHT);
        joint("arm_l", RagdollRig.ARM_LEFT);
        joint("leg_r", RagdollRig.LEG_RIGHT);
        joint("leg_l", RagdollRig.LEG_LEFT);
        joint("arms", RagdollRig.ARM_RIGHT, RagdollRig.ARM_LEFT);
        joint("legs", RagdollRig.LEG_RIGHT, RagdollRig.LEG_LEFT);
    }

    private static void root(String name, int least, int... channels) {
        CHANNELS.put(name, new Target(new int[][]{channels}, least, channels.length));
    }

    private static void joint(String name, int... joints) {
        CHANNELS.put(name, new Target(rows(joints,
                RagdollRig.PITCH, RagdollRig.YAW, RagdollRig.ROLL), 1, 3));
        CHANNELS.put(name + "_at", new Target(rows(joints,
                RagdollRig.OUT, RagdollRig.RAISE, RagdollRig.AHEAD), 3, 3));
        CHANNELS.put(name + "_size", new Target(rows(joints, RagdollRig.SCALE), 1, 1));
    }

    private static int[][] rows(int[] joints, int... fields) {
        int[][] rows = new int[joints.length][fields.length];
        for (int row = 0; row < joints.length; row++) {
            for (int field = 0; field < fields.length; field++) {
                rows[row][field] = RagdollRig.of(joints[row], fields[field]);
            }
        }
        return rows;
    }

    /**
     * Writes one {@code name=value} onto a pose.
     *
     * @param additive whether every number is added to what is there, as a
     *                 named pose's are to the turn it was struck on
     */
    private static void write(double[] pose, String word, int frame, Consumer<String> problems,
                              boolean additive) {
        int equals = word.indexOf('=');
        if (equals <= 0) {
            problems.accept("frame " + frame + ": \"" + word + "\" is not name=value");
            return;
        }
        String name = word.substring(0, equals).toLowerCase(Locale.ROOT);
        Target target = CHANNELS.get(name);
        if (target == null) {
            problems.accept("frame " + frame + ": there is nothing called \"" + name + "\" to move");
            return;
        }
        String[] parts = word.substring(equals + 1).split(",");
        if (parts.length < target.least() || parts.length > target.most()) {
            problems.accept("frame " + frame + ": " + name + " takes "
                    + (target.least() == target.most() ? target.least() : target.least() + " to " + target.most())
                    + " numbers, not " + parts.length);
            return;
        }
        for (int[] row : target.rows()) {
            for (int index = 0; index < parts.length; index++) {
                String part = parts[index].trim();
                boolean relative = part.startsWith("~");
                String number = relative ? part.substring(1) : part;
                double value;
                try {
                    value = number.isEmpty() ? 0 : Double.parseDouble(number);
                } catch (NumberFormatException malformed) {
                    problems.accept("frame " + frame + ": \"" + part + "\" in " + name + " is not a number");
                    return;
                }
                pose[row[index]] = relative || additive ? pose[row[index]] + value : value;
            }
        }
    }

    /**
     * Takes a pose back to standing before a named pose is written on it.
     *
     * <p>Every joint and how the hips sit, and nothing else: where the body has
     * walked to, which way it has turned, how big it is and anything pulled off
     * it all belong to the choreography rather than to the pose.
     *
     * <p>An angle goes back to the nearest whole turn rather than to zero. A
     * body that has flipped twice and is then told to {@code stand} is already
     * upright; taking it back to zero would spin it backwards through both
     * flips to get there.
     */
    private static void strike(double[] pose) {
        pose[RagdollRig.UP] = 0;
        pose[RagdollRig.FLIP] = wholeTurn(pose[RagdollRig.FLIP]);
        pose[RagdollRig.LEAN] = wholeTurn(pose[RagdollRig.LEAN]);
        for (int joint = RagdollRig.HEAD; joint <= RagdollRig.LEG_LEFT; joint++) {
            for (int field : new int[]{RagdollRig.PITCH, RagdollRig.YAW, RagdollRig.ROLL}) {
                int channel = RagdollRig.of(joint, field);
                pose[channel] = wholeTurn(pose[channel]);
            }
        }
    }

    private static double wholeTurn(double degrees) {
        return 360.0 * Math.round(degrees / 360.0);
    }

    /**
     * The poses a frame can name.
     *
     * <p>Written in the same words a file uses, so any of them can be copied
     * out and adjusted. The heights that put a body on the floor are measured
     * rather than guessed: a limb is twelve pixels long and four thick, and a
     * body lying down rests its hips a little over one of those off the ground
     * so the head, which is twice as thick, does not go through it.
     */
    private static final Map<String, String> PRESETS = Map.ofEntries(
            Map.entry("stand", ""),
            Map.entry("tpose", "arms=0,0,90"),
            Map.entry("star", "arms=0,0,125 legs=0,0,26"),
            Map.entry("cheer", "arms=0,0,150 head=-15"),
            Map.entry("reach", "arms=180 head=-35"),
            Map.entry("zombie", "arms=90"),
            Map.entry("hug", "arms=80,-40"),
            Map.entry("crouch", "up=-0.1 legs=34 body=36 arms=36 head=-28"),
            Map.entry("sit", "up=-0.61 legs=90 body=-8 arms=22"),
            Map.entry("kneel", "up=-0.61 legs=-90 arms=8 head=12"),
            Map.entry("lie", "up=-0.56 flip=-90 arms=0,0,8"),
            Map.entry("prone", "up=-0.56 flip=90 arms=0,0,8"),
            Map.entry("bow", "body=62 head=18 arms=14"),
            Map.entry("pray", "arms=72,-32 head=20"),
            // Both arms on one diagonal: the far one out, the near one across the
            // face, a little forward so it passes in front of it and not through.
            Map.entry("dab", "arm_l=0,0,125 arm_r=40,0,-125 head=38,-28,-12"),
            Map.entry("superman", "flip=90 arm_r=178 arm_l=0,0,12 head=-62"),
            Map.entry("splits", "up=-0.62 legs=0,0,88 arms=0,0,90"),
            Map.entry("float", "arms=0,0,38 legs=0,0,12 head=-12 body=-8"),
            Map.entry("limp", "arms=6,0,12 legs=0,0,5 head=38 body=14"),
            Map.entry("fetal", "legs=112 body=42 head=36 arms=96,-28"),
            Map.entry("swoon", "flip=-18 body=-14 head=-32 arm_r=0,-20,158 arm_l=62,-38"),
            // Raised past straight up so the hands meet over the head, and a
            // little forward so the arms pass in front of it.
            Map.entry("heart", "arms=22,0,202 head=-10"),
            Map.entry("arabesque", "leg_l=-78 arm_r=0,0,170 arm_l=0,0,95 body=18 head=-12"));

    /** How a frame is arrived at. */
    private enum Ease {

        LINEAR(1),
        IN(3),
        OUT(3),
        IN_OUT(3),
        BACK(4.7),
        ANTICIPATE(4.7),
        ELASTIC(7),
        BOUNCE(5.5),
        SNAP(1),
        SMOOTH(1.6);

        /** How much faster than the average it runs at its fastest. */
        private final double peak;

        Ease(double peak) {
            this.peak = peak;
        }

        static Ease of(String name) {
            return switch (name.trim().toLowerCase(Locale.ROOT)) {
                case "linear" -> LINEAR;
                case "in" -> IN;
                case "out" -> OUT;
                case "in_out", "inout", "both" -> IN_OUT;
                case "back", "overshoot" -> BACK;
                case "anticipate", "windup" -> ANTICIPATE;
                case "elastic", "spring" -> ELASTIC;
                case "bounce" -> BOUNCE;
                case "snap", "cut", "step" -> SNAP;
                case "smooth", "spline", "flow" -> SMOOTH;
                default -> null;
            };
        }

        double at(double progress) {
            double u = Math.clamp(progress, 0, 1);
            final double c1 = 1.70158;
            final double c3 = c1 + 1;
            return switch (this) {
                case LINEAR, SMOOTH -> u;
                case IN -> u * u * u;
                case OUT -> 1 - Math.pow(1 - u, 3);
                case IN_OUT -> u < 0.5 ? 4 * u * u * u : 1 - Math.pow(-2 * u + 2, 3) / 2;
                case BACK -> 1 + c3 * Math.pow(u - 1, 3) + c1 * Math.pow(u - 1, 2);
                case ANTICIPATE -> c3 * u * u * u - c1 * u * u;
                case ELASTIC -> u == 0 || u == 1 ? u
                        : Math.pow(2, -10 * u) * Math.sin((u * 10 - 0.75) * (2 * Math.PI / 3)) + 1;
                case BOUNCE -> bounce(u);
                case SNAP -> u > 0 ? 1 : 0;
            };
        }

        private static double bounce(double u) {
            final double n1 = 7.5625;
            final double d1 = 2.75;
            if (u < 1 / d1) {
                return n1 * u * u;
            }
            if (u < 2 / d1) {
                double v = u - 1.5 / d1;
                return n1 * v * v + 0.75;
            }
            if (u < 2.5 / d1) {
                double v = u - 2.25 / d1;
                return n1 * v * v + 0.9375;
            }
            double v = u - 2.625 / d1;
            return n1 * v * v + 0.984375;
        }
    }
}
