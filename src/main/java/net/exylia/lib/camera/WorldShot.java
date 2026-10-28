package net.exylia.lib.camera;

import net.exylia.lib.util.internal.Ease;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * A shot through fixed places in the world: a fly-over, an establishing shot,
 * a cutscene that walks the camera down a street while somebody talks.
 *
 * <pre>{@code
 * WorldShot intro = WorldShot.builder()
 *         .key(0, gate)                          // a Location: where and which way it looks
 *         .key(4, square, "smooth")
 *         .key(9, castle, "in_out")
 *         .subtitle(0.5, 3.5, guide, Text.of("{letters}Welcome to the city.").build())
 *         .subtitle(4.5, 4, guide, Text.of("{letters}The castle is up the hill.").build())
 *         .letterbox(true)
 *         .fade(0.6, 0.8)
 *         .build();
 *
 * Cameras.of(plugin).play(player, intro);
 * }</pre>
 *
 * <h2>Where {@link CameraShot} films the player, this films the place</h2>
 * A {@link CameraShot} is written in the subject's own terms so one emote
 * reads the same whichever way somebody faces. A cinematic is the opposite: it
 * is authored once, in the world, by somebody flying the path, and every
 * viewer must see exactly that path. So every key is an absolute
 * {@link Location}, yaw and pitch included, and the camera looks where the key
 * looks rather than at a subject.
 *
 * <p>Keys are timed in seconds from the start of the shot, not from the key
 * before, because that is how a recorded path and a subtitle track are both
 * written: "at 4.5 s the camera is over the square and the guide says this".
 * The camera holds the first key until its time and the last key until the
 * shot ends.
 *
 * <h2>Easing</h2>
 * Each key names how it is reached &mdash; the camera module's vocabulary:
 * {@code in_out} (the default), {@code in}, {@code out}, {@code linear},
 * {@code back}, {@code snap}, {@code hold}, the easings.net names, and
 * {@code smooth}, which runs a curve through the neighbouring keys instead of
 * stopping at each one. A recorded path of a dozen points wants {@code smooth}
 * on every key; anything else stops the camera dead at each point.
 *
 * <h2>Subtitles, letterbox, fade</h2>
 * All three are text displays seated on the camera entity, so the client
 * carries them with it: they move with the view on the same frame, at no cost
 * per move, and cannot lag behind a fast pan. Subtitles sit in the lower part
 * of the view; the letterbox is two black bars; the fade is one black panel
 * whose opacity is ramped at the start and the end.
 *
 * <p>The bars and the subtitle are placed for the default 70&deg; field of
 * view. A player with a wider one sees thinner bars and the subtitle a little
 * nearer the middle; the panels are oversized, so nothing ever shows an edge.
 *
 * <h2>What it cannot film</h2>
 * The client draws only the chunks it has, and those are the ones around the
 * viewer's own body, which stays where it was. A path that leaves the viewer's
 * view distance films the edge of the loaded world. Keep a cinematic within
 * about the server's view distance of where it is watched from, or move the
 * player there first (through the teleport module) and back afterwards.
 *
 * <p>Immutable. Built once, when configuration is read, and shared by every
 * play.
 *
 * @since 1.391.0
 */
public final class WorldShot {

    /**
     * The longest a world shot may run, in milliseconds.
     *
     * <p>Three minutes rather than the sixty seconds of a {@link CameraShot}. A
     * camera on the player is a moment, but a narrated cinematic is a scene:
     * eight subtitle lines read at a comfortable pace already need most of a
     * minute. It stays bounded so a mistyped time cannot hold anybody in a
     * camera indefinitely; past this the shot is cut.
     */
    public static final long MAX_MILLIS = 180_000L;

    /** x, y, z, yaw, pitch. */
    static final int CHANNELS = 5;
    private static final int YAW = 3;
    private static final int PITCH = 4;

    private final World world;
    private final long[] times;
    private final double[][] keys;
    private final Ease[] eases;
    private final List<Subtitle> subtitles;
    private final boolean letterbox;
    private final long fadeIn;
    private final long fadeOut;
    private final long duration;

    private WorldShot(Builder builder, long[] times, double[][] keys, Ease[] eases) {
        this.world = builder.world;
        this.times = times;
        this.keys = keys;
        this.eases = eases;
        long lastSubtitle = 0;
        for (Subtitle line : builder.subtitles) {
            lastSubtitle = Math.max(lastSubtitle, line.startMillis() + line.durationMillis());
        }
        this.duration = Math.min(MAX_MILLIS, Math.max(times.length == 0 ? 0 : times[times.length - 1],
                lastSubtitle));
        List<Subtitle> lines = new ArrayList<>(builder.subtitles);
        lines.sort(Comparator.comparingLong(Subtitle::startMillis));
        this.subtitles = List.copyOf(lines);
        this.letterbox = builder.letterbox;
        this.fadeIn = Math.min(builder.fadeIn, duration / 2);
        this.fadeOut = Math.min(builder.fadeOut, duration / 2);
    }

    /**
     * Starts a shot.
     *
     * @return an empty builder
     */
    public static @NotNull Builder builder() {
        return new Builder();
    }

    /**
     * One line of the subtitle track.
     *
     * @param startMillis    when it appears, from the start of the shot
     * @param durationMillis how long it stays
     * @param speaker        who says it, drawn on its own line above; {@code null} for narration
     * @param text           what is said, already formatted
     */
    public record Subtitle(long startMillis, long durationMillis, @Nullable Component speaker,
                           @NotNull Component text) {

        /** Checks the values. */
        public Subtitle {
            Objects.requireNonNull(text, "text");
            startMillis = Math.max(0, startMillis);
            durationMillis = Math.max(0, durationMillis);
        }

        /** Whether this line is on screen at a moment of the shot. */
        public boolean showsAt(long millis) {
            return millis >= startMillis && millis < startMillis + durationMillis;
        }

        /** The speaker above the line, as one component. */
        public @NotNull Component rendered() {
            return speaker == null ? text : speaker.append(Component.newline()).append(text);
        }
    }

    /** How long the whole shot takes, in milliseconds: its last key or its last subtitle. */
    public long durationMillis() {
        return duration;
    }

    /** Whether there is nothing to play: no key, or no time. */
    public boolean isEmpty() {
        return keys.length == 0 || duration <= 0;
    }

    /** The world every key is in, or {@code null} for an empty shot. */
    public @Nullable World world() {
        return world;
    }

    /** How many keys were given. */
    public int keyCount() {
        return keys.length;
    }

    /** The subtitle track, in the order the lines appear. */
    public @NotNull List<Subtitle> subtitles() {
        return subtitles;
    }

    /** Whether two black bars frame the view. */
    public boolean letterbox() {
        return letterbox;
    }

    /** Whether anything at all rides the camera: subtitles, bars or a fade. */
    public boolean hasOverlay() {
        return letterbox || !subtitles.isEmpty() || fadeIn > 0 || fadeOut > 0;
    }

    /**
     * The subtitle on screen at a moment, or {@code null}.
     *
     * <p>Two lines that overlap show the one that started last: a reply cuts
     * in, it does not wait for the line before it to run out.
     *
     * @param millis how far into the shot
     * @return the line, or {@code null} for none
     */
    public @Nullable Subtitle subtitleAt(long millis) {
        Subtitle showing = null;
        for (Subtitle line : subtitles) {
            if (line.startMillis() > millis) {
                break;
            }
            if (line.showsAt(millis)) {
                showing = line;
            }
        }
        return showing;
    }

    /**
     * How black the fade panel is at a moment, {@code 0} clear to {@code 255}
     * black.
     *
     * <p>The fade-in runs from black at the start to clear; the fade-out from
     * clear to black at the end. Both are linear: the eye reads a fade by its
     * ends, and an eased one only looks like it started late.
     *
     * @param millis how far into the shot
     * @return the opacity
     */
    public int fadeAlpha(long millis) {
        double alpha = 0;
        if (fadeIn > 0 && millis < fadeIn) {
            alpha = 1.0 - (double) Math.max(0, millis) / fadeIn;
        }
        if (fadeOut > 0 && millis > duration - fadeOut) {
            alpha = Math.max(alpha, (double) Math.min(fadeOut, millis - (duration - fadeOut)) / fadeOut);
        }
        return (int) Math.round(Math.clamp(alpha, 0.0, 1.0) * 255);
    }

    /** Whether the shot fades at either end. */
    public boolean fades() {
        return fadeIn > 0 || fadeOut > 0;
    }

    /**
     * Where the camera is and where it looks at one moment.
     *
     * @param millis how far into the shot
     * @return a fresh array in {@code x, y, z, yaw, pitch} order; the yaw is
     *         unwrapped (it may run past 360) so a pan never takes the long way
     */
    @ApiStatus.Internal
    public double @NotNull [] at(long millis) {
        int last = times.length - 1;
        if (last < 0) {
            return new double[CHANNELS];
        }
        if (millis <= times[0] || last == 0) {
            return keys[0].clone();
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
        double[] where = new double[CHANNELS];
        if (ease == Ease.SMOOTH) {
            // The same Hermite curve CameraShot runs through its frames: a
            // recorded path flows through every point instead of stopping at
            // each, which is what makes a fly-over read as one movement.
            double squared = progress * progress;
            double cubed = squared * progress;
            double h00 = 2 * cubed - 3 * squared + 1;
            double h10 = cubed - 2 * squared + progress;
            double h01 = -2 * cubed + 3 * squared;
            double h11 = cubed - squared;
            for (int channel = 0; channel < CHANNELS; channel++) {
                where[channel] = h00 * keys[from][channel]
                        + h10 * span * slope(from, channel)
                        + h01 * keys[to][channel]
                        + h11 * span * slope(to, channel);
            }
        } else {
            double eased = ease.at(progress);
            for (int channel = 0; channel < CHANNELS; channel++) {
                where[channel] = keys[from][channel] + (keys[to][channel] - keys[from][channel]) * eased;
            }
        }
        where[PITCH] = Math.clamp(where[PITCH], -89.0, 89.0);
        return where;
    }

    /** As {@link CameraShot}: only a key that is flowed through has a speed. */
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

    /**
     * Collects keys and the overlay, then freezes them into a shot.
     *
     * <p>Not thread-safe; build on one thread and share the result.
     */
    public static final class Builder {

        private record Key(long millis, double[] where, Ease ease) {
        }

        private final List<Key> keys = new ArrayList<>();
        private final List<Subtitle> subtitles = new ArrayList<>();
        private World world;
        private boolean letterbox;
        private long fadeIn;
        private long fadeOut;

        private Builder() {
        }

        /**
         * Adds a key reached with {@code in_out}.
         *
         * @param seconds when the camera is there, from the start of the shot
         * @param at      where it is and which way it looks
         * @return this builder
         */
        public @NotNull Builder key(double seconds, @NotNull Location at) {
            return key(seconds, at, "in_out");
        }

        /**
         * Adds a key.
         *
         * @param seconds when the camera is there, from the start of the shot
         * @param at      where it is and which way it looks; every key must be
         *                in the same world
         * @param ease    how it is reached: {@code smooth}, {@code linear},
         *                {@code in_out}, ...
         * @return this builder
         * @throws IllegalArgumentException for an unknown ease or a key in another world
         */
        public @NotNull Builder key(double seconds, @NotNull Location at, @NotNull String ease) {
            Ease named = Ease.of(ease);
            if (named == null) {
                throw new IllegalArgumentException("there is no ease called \"" + ease + "\"");
            }
            if (at.getWorld() != null) {
                if (world != null && !world.equals(at.getWorld())) {
                    throw new IllegalArgumentException("every key of a shot must be in one world");
                }
                world = at.getWorld();
            }
            keys.add(new Key(Math.max(0L, Math.round(seconds * 1000)),
                    new double[]{at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch()}, named));
            return this;
        }

        /**
         * Adds a line of narration.
         *
         * @param startSeconds when it appears
         * @param seconds      how long it stays
         * @param text         the line
         * @return this builder
         */
        public @NotNull Builder subtitle(double startSeconds, double seconds, @NotNull Component text) {
            return subtitle(startSeconds, seconds, null, text);
        }

        /**
         * Adds a line somebody says.
         *
         * @param startSeconds when it appears
         * @param seconds      how long it stays
         * @param speaker      who says it, on its own line above; {@code null} for narration
         * @param text         the line
         * @return this builder
         */
        public @NotNull Builder subtitle(double startSeconds, double seconds, @Nullable Component speaker,
                                         @NotNull Component text) {
            subtitles.add(new Subtitle(Math.round(startSeconds * 1000), Math.round(seconds * 1000),
                    speaker, text));
            return this;
        }

        /**
         * Frames the view with two black bars.
         *
         * @param bars whether to
         * @return this builder
         */
        public @NotNull Builder letterbox(boolean bars) {
            this.letterbox = bars;
            return this;
        }

        /**
         * Fades in from black at the start and out to black at the end.
         *
         * @param inSeconds  how long the fade-in takes; {@code 0} for none
         * @param outSeconds how long the fade-out takes; {@code 0} for none
         * @return this builder
         */
        public @NotNull Builder fade(double inSeconds, double outSeconds) {
            this.fadeIn = Math.max(0L, Math.round(inSeconds * 1000));
            this.fadeOut = Math.max(0L, Math.round(outSeconds * 1000));
            return this;
        }

        /**
         * Freezes the shot.
         *
         * <p>Keys are put in time order; two at the same moment keep the one
         * given last. Each yaw is unwrapped against the one before so a pan
         * from 350&deg; to 10&deg; turns twenty degrees, not three hundred and
         * forty. Anything past {@link #MAX_MILLIS} is cut.
         *
         * @return the shot
         */
        public @NotNull WorldShot build() {
            List<Key> ordered = new ArrayList<>(keys);
            ordered.sort(Comparator.comparingLong(Key::millis));
            List<Key> kept = new ArrayList<>(ordered.size());
            for (Key key : ordered) {
                if (key.millis() > MAX_MILLIS) {
                    break;
                }
                if (!kept.isEmpty() && kept.get(kept.size() - 1).millis() == key.millis()) {
                    kept.set(kept.size() - 1, key);
                } else {
                    kept.add(key);
                }
            }
            long[] times = new long[kept.size()];
            double[][] where = new double[kept.size()][];
            Ease[] eases = new Ease[kept.size()];
            for (int index = 0; index < kept.size(); index++) {
                Key key = kept.get(index);
                times[index] = key.millis();
                where[index] = key.where().clone();
                eases[index] = key.ease();
                if (index > 0) {
                    where[index][YAW] = unwrap(where[index - 1][YAW], where[index][YAW]);
                }
            }
            return new WorldShot(this, times, where, eases);
        }

        /** The same heading as {@code yaw}, written as close to {@code previous} as it can be. */
        static double unwrap(double previous, double yaw) {
            double delta = ((yaw - previous) % 360 + 540) % 360 - 180;
            return previous + delta;
        }
    }
}
