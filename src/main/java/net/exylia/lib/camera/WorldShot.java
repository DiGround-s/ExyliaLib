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

    /**
     * The most subtitle lines on screen at once. Lines past it wait their turn:
     * the newest four show, the oldest of the overlap is the one left out.
     *
     * @since 1.397.0
     */
    public static final int MAX_LINES = 4;

    /**
     * How much of the screen's height each letterbox bar covers by default, at
     * the default 70&deg; field of view.
     *
     * @since 1.397.0
     */
    public static final double DEFAULT_BAR_SIZE = 0.093;

    private final World world;
    private final long[] times;
    private final double[][] keys;
    private final Ease[] eases;
    private final boolean[] cuts;
    private final List<Subtitle> subtitles;
    private final List<Title> titles;
    private final List<long[]> dips;
    private final List<double[]> looks;
    private final boolean letterbox;
    private final double barSize;
    private final TextStyle subtitleStyle;
    private final TextStyle titleStyle;
    private final long fadeIn;
    private final long fadeOut;
    private final long duration;

    private WorldShot(Builder builder, long[] times, double[][] keys, Ease[] eases, boolean[] cuts) {
        this.world = builder.world;
        this.times = times;
        this.keys = keys;
        this.eases = eases;
        this.cuts = cuts;
        long last = times.length == 0 ? 0 : times[times.length - 1];
        for (Subtitle line : builder.subtitles) {
            last = Math.max(last, line.startMillis() + line.durationMillis());
        }
        for (Title card : builder.titles) {
            last = Math.max(last, card.startMillis() + card.durationMillis());
        }
        this.duration = Math.min(MAX_MILLIS, last);
        List<Subtitle> lines = new ArrayList<>(builder.subtitles);
        lines.sort(Comparator.comparingLong(Subtitle::startMillis));
        this.subtitles = List.copyOf(lines);
        List<Title> cards = new ArrayList<>(builder.titles);
        cards.sort(Comparator.comparingLong(Title::startMillis));
        this.titles = List.copyOf(cards);
        this.dips = List.copyOf(builder.dips);
        this.looks = List.copyOf(builder.looks);
        this.letterbox = builder.letterbox;
        this.barSize = builder.barSize;
        this.subtitleStyle = builder.subtitleStyle;
        this.titleStyle = builder.titleStyle;
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
     * Where on the screen a subtitle sits.
     *
     * @since 1.397.0
     */
    public enum Position {
        /** Above the bottom bar, where films put them; lines that overlap stack upwards. */
        BOTTOM,
        /** In the middle of the view; lines that overlap stack downwards. */
        CENTER,
        /** Under the top bar; lines that overlap stack downwards. */
        TOP
    }

    /**
     * Which side of the screen a subtitle sits on, and how its text is aligned.
     * Two people talking at once read best as one on the left and one on the
     * right.
     *
     * @since 1.397.0
     */
    public enum Align {
        LEFT,
        CENTER,
        RIGHT
    }

    /**
     * One line of the subtitle track.
     *
     * <p>Lines that overlap in time are all shown (up to {@link #MAX_LINES}),
     * each on its own panel: lines with the same position and alignment stack
     * without covering each other, ordered by {@code line} and then by when
     * they started.
     *
     * @param startMillis    when it appears, from the start of the shot
     * @param durationMillis how long it stays
     * @param speaker        who says it, drawn on its own line above; {@code null} for narration
     * @param text           what is said, already formatted
     * @param position       where on the screen (since 1.397.0)
     * @param align          which side, and how its text is aligned (since 1.397.0)
     * @param offset         how far it is moved up (negative: down), in fractions of the screen's
     *                       height (since 1.397.0)
     * @param line           the slot it takes in its stack, {@code 0} nearest the base; {@code -1}
     *                       for the next free one by start time (since 1.397.0)
     * @param style          how this line is drawn; {@code null} for the shot's subtitle style (since 1.397.0)
     */
    public record Subtitle(long startMillis, long durationMillis, @Nullable Component speaker,
                           @NotNull Component text, @NotNull Position position, @NotNull Align align,
                           double offset, int line, @Nullable TextStyle style) {

        /** Checks the values. */
        public Subtitle {
            Objects.requireNonNull(text, "text");
            startMillis = Math.max(0, startMillis);
            durationMillis = Math.max(0, durationMillis);
            position = position == null ? Position.BOTTOM : position;
            align = align == null ? Align.CENTER : align;
            offset = Math.clamp(offset, -1.0, 1.0);
            line = Math.max(-1, line);
        }

        /**
         * A centred line at the bottom of the view, as before 1.397.0.
         *
         * @param startMillis    when it appears
         * @param durationMillis how long it stays
         * @param speaker        who says it; {@code null} for narration
         * @param text           what is said
         */
        public Subtitle(long startMillis, long durationMillis, @Nullable Component speaker,
                        @NotNull Component text) {
            this(startMillis, durationMillis, speaker, text, Position.BOTTOM, Align.CENTER, 0, -1, null);
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

    /**
     * How a text panel riding the camera is drawn: subtitles and title cards.
     *
     * @param background ARGB behind the text; alpha {@code 0} for no panel
     * @param shadow     whether the text has a drop shadow
     * @param lineWidth  where text wraps, in pixels (a side subtitle wraps at half)
     * @param scale      how large, {@code 1} the default size (clamped to 0.3 - 4)
     * @param seeThrough whether the text shows through blocks
     * @param gap        space between two stacked lines, in fractions of the screen's height
     * @since 1.397.0
     */
    public record TextStyle(int background, boolean shadow, int lineWidth, double scale, boolean seeThrough,
                            double gap) {

        /** How subtitles were always drawn: a translucent black panel, a shadow, 360 pixels wide. */
        public static final TextStyle SUBTITLE = new TextStyle(0x70000000, true, 360, 1, false, 0.011);
        /** Title cards: no panel, a shadow. */
        public static final TextStyle TITLE = new TextStyle(0, true, 160, 1, false, 0.011);

        /** Checks the values. */
        public TextStyle {
            lineWidth = Math.clamp(lineWidth, 40, 1000);
            scale = Math.clamp(scale, 0.3, 4.0);
            gap = Math.clamp(gap, 0.0, 0.2);
        }
    }

    /**
     * A title card: big text in the middle of the view, drawn in front of the
     * fade so it reads over black.
     *
     * @param startMillis    when it appears, from the start of the shot
     * @param durationMillis how long it stays
     * @param text           what it says, already formatted; a new line for a subtitle under it
     * @since 1.397.0
     */
    public record Title(long startMillis, long durationMillis, @NotNull Component text) {

        /** Checks the values. */
        public Title {
            Objects.requireNonNull(text, "text");
            startMillis = Math.max(0, startMillis);
            durationMillis = Math.max(0, durationMillis);
        }

        /** Whether this card is on screen at a moment of the shot. */
        public boolean showsAt(long millis) {
            return millis >= startMillis && millis < startMillis + durationMillis;
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

    /** Whether anything at all rides the camera: subtitles, title cards, bars or a fade. */
    public boolean hasOverlay() {
        return letterbox || !subtitles.isEmpty() || !titles.isEmpty() || fades();
    }

    /**
     * How much of the screen's height each letterbox bar covers, at the
     * default field of view.
     *
     * @since 1.397.0
     */
    public double barSize() {
        return barSize;
    }

    /**
     * How subtitles are drawn unless a line says otherwise.
     *
     * @since 1.397.0
     */
    public @NotNull TextStyle subtitleStyle() {
        return subtitleStyle;
    }

    /**
     * How title cards are drawn.
     *
     * @since 1.397.0
     */
    public @NotNull TextStyle titleStyle() {
        return titleStyle;
    }

    /**
     * The title cards, in the order they appear.
     *
     * @since 1.397.0
     */
    public @NotNull List<Title> titles() {
        return titles;
    }

    /**
     * The title card on screen at a moment, or {@code null}; the one that
     * started last wins.
     *
     * @param millis how far into the shot
     * @return the card, or {@code null}
     * @since 1.397.0
     */
    public @Nullable Title titleAt(long millis) {
        Title showing = null;
        for (Title card : titles) {
            if (card.startMillis() > millis) {
                break;
            }
            if (card.showsAt(millis)) {
                showing = card;
            }
        }
        return showing;
    }

    /**
     * Every subtitle on screen at a moment, in the order they started: at most
     * {@link #MAX_LINES}, the newest kept when more overlap.
     *
     * @param millis how far into the shot
     * @return the lines, possibly empty
     * @since 1.397.0
     */
    public @NotNull List<Subtitle> subtitlesAt(long millis) {
        List<Subtitle> showing = new ArrayList<>(MAX_LINES);
        for (Subtitle line : subtitles) {
            if (line.startMillis() > millis) {
                break;
            }
            if (line.showsAt(millis)) {
                showing.add(line);
            }
        }
        return showing.size() <= MAX_LINES ? showing : List.copyOf(showing.subList(showing.size() - MAX_LINES, showing.size()));
    }

    /**
     * The most subtitles ever on screen at once, capped at {@link #MAX_LINES}:
     * how many panels the overlay needs.
     */
    @ApiStatus.Internal
    public int maxConcurrentSubtitles() {
        int most = 0;
        for (Subtitle line : subtitles) {
            most = Math.max(most, subtitlesAt(line.startMillis()).size());
        }
        return most;
    }

    /**
     * Whether a cut falls after one moment and no later than another: the
     * frame that reaches it jumps instead of being travelled to.
     */
    @ApiStatus.Internal
    public boolean cutBetween(long after, long upTo) {
        for (int index = 0; index < times.length; index++) {
            if (cuts[index] && times[index] > after && times[index] <= upTo) {
                return true;
            }
        }
        return false;
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
        for (long[] dip : dips) {
            double half = dip[1] / 2.0;
            double away = Math.abs(millis - dip[0]);
            if (half > 0 && away < half) {
                alpha = Math.max(alpha, 1.0 - away / half);
            }
        }
        return (int) Math.round(Math.clamp(alpha, 0.0, 1.0) * 255);
    }

    /** Whether the shot fades at either end, or dips to black anywhere in it. */
    public boolean fades() {
        return fadeIn > 0 || fadeOut > 0 || !dips.isEmpty();
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
        double[] where = travelled(millis);
        for (double[] look : looks) {
            if (millis >= look[0] && millis <= look[1]) {
                aim(where, look[2], look[3], look[4]);
            }
        }
        return where;
    }

    /** Turns a position's view onto a point. Minecraft's yaw runs clockwise from south. */
    static void aim(double[] where, double x, double y, double z) {
        double dx = x - where[0];
        double dy = y - where[1];
        double dz = z - where[2];
        double flat = Math.sqrt(dx * dx + dz * dz);
        if (flat < 1.0E-6 && Math.abs(dy) < 1.0E-6) {
            return;
        }
        where[YAW] = Math.toDegrees(Math.atan2(-dx, dz));
        where[PITCH] = Math.clamp(-Math.toDegrees(Math.atan2(dy, flat)), -89.0, 89.0);
    }

    private double[] travelled(long millis) {
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
        if (cuts[to]) {
            // A cut is not travelled to: the camera stays put, then is there.
            return keys[from].clone();
        }
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
        // Nothing flows across a cut: the camera does not travel through one.
        boolean flowsIn = !cuts[key] && (into == Ease.SMOOTH || into == Ease.LINEAR);
        boolean flowsOut = !cuts[key + 1] && (out == Ease.SMOOTH || out == Ease.LINEAR);
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

        private record Key(long millis, double[] where, Ease ease, boolean cut) {
        }

        private final List<Key> keys = new ArrayList<>();
        private final List<Subtitle> subtitles = new ArrayList<>();
        private final List<Title> titles = new ArrayList<>();
        private final List<long[]> dips = new ArrayList<>();
        private final List<double[]> looks = new ArrayList<>();
        private World world;
        private boolean letterbox;
        private double barSize = DEFAULT_BAR_SIZE;
        private TextStyle subtitleStyle = TextStyle.SUBTITLE;
        private TextStyle titleStyle = TextStyle.TITLE;
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
            return add(seconds, at, named, false);
        }

        /**
         * Adds a cut: the camera holds the key before until this moment, then
         * is here at once, with nothing travelled in between. Pair it with
         * {@link #dip} to cut through black.
         *
         * <p>A cut stays inside the chunks the viewer's client has. To show
         * something far away or in another world, end the shot there, move the
         * viewer (teleport module) and play the next one.
         *
         * @param seconds when the camera jumps, from the start of the shot
         * @param at      where it lands and which way it looks; same world as every key
         * @return this builder
         * @since 1.397.0
         */
        public @NotNull Builder cut(double seconds, @NotNull Location at) {
            return add(seconds, at, Ease.LINEAR, true);
        }

        private Builder add(double seconds, Location at, Ease named, boolean cut) {
            if (at.getWorld() != null) {
                if (world != null && !world.equals(at.getWorld())) {
                    throw new IllegalArgumentException("every key of a shot must be in one world");
                }
                world = at.getWorld();
            }
            keys.add(new Key(Math.max(0L, Math.round(seconds * 1000)),
                    new double[]{at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch()}, named, cut));
            return this;
        }

        /**
         * Dips the view to black around a moment: it darkens over the first
         * half, is black at {@code seconds} and clears over the second half.
         * What a cut wants so the jump is never seen.
         *
         * @param seconds when it is black, from the start of the shot
         * @param length  how long the whole dip takes
         * @return this builder
         * @since 1.397.0
         */
        public @NotNull Builder dip(double seconds, double length) {
            if (length > 0) {
                dips.add(new long[]{Math.round(seconds * 1000), Math.round(length * 1000)});
            }
            return this;
        }

        /**
         * Keeps the camera aimed at a point between two moments, wherever the
         * keys move it: the keys' own yaw and pitch are ignored in between.
         *
         * @param fromSeconds when it starts aiming
         * @param toSeconds   when it stops
         * @param target      what it looks at; only the coordinates are read
         * @return this builder
         * @since 1.397.0
         */
        public @NotNull Builder lookAt(double fromSeconds, double toSeconds, @NotNull Location target) {
            if (toSeconds > fromSeconds) {
                looks.add(new double[]{Math.round(fromSeconds * 1000), Math.round(toSeconds * 1000),
                        target.getX(), target.getY(), target.getZ()});
            }
            return this;
        }

        /**
         * Adds a title card: big centred text, drawn over the fade.
         *
         * @param startSeconds when it appears
         * @param seconds      how long it stays
         * @param text         what it says
         * @return this builder
         * @since 1.397.0
         */
        public @NotNull Builder title(double startSeconds, double seconds, @NotNull Component text) {
            titles.add(new Title(Math.round(startSeconds * 1000), Math.round(seconds * 1000), text));
            return this;
        }

        /**
         * Adds a fully described subtitle line: position, side, offset, slot.
         *
         * @param line the line
         * @return this builder
         * @since 1.397.0
         */
        public @NotNull Builder subtitle(@NotNull Subtitle line) {
            subtitles.add(Objects.requireNonNull(line, "line"));
            return this;
        }

        /**
         * How subtitles are drawn unless a line carries its own style.
         *
         * @param style the style; {@link TextStyle#SUBTITLE} is the default
         * @return this builder
         * @since 1.397.0
         */
        public @NotNull Builder subtitleStyle(@NotNull TextStyle style) {
            this.subtitleStyle = Objects.requireNonNull(style, "style");
            return this;
        }

        /**
         * How title cards are drawn.
         *
         * @param style the style; {@link TextStyle#TITLE} is the default
         * @return this builder
         * @since 1.397.0
         */
        public @NotNull Builder titleStyle(@NotNull TextStyle style) {
            this.titleStyle = Objects.requireNonNull(style, "style");
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
         * Frames the view with two black bars of a given size.
         *
         * @param size how much of the screen's height each bar covers at the
         *             default 70&deg; field of view ({@code 0.1} is a tenth);
         *             clamped to 0.4, and {@code 0} or less draws none
         * @return this builder
         * @since 1.397.0
         */
        public @NotNull Builder letterbox(double size) {
            this.letterbox = size > 0;
            this.barSize = Math.clamp(size, 0.0, 0.4);
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
            boolean[] cuts = new boolean[kept.size()];
            for (int index = 0; index < kept.size(); index++) {
                Key key = kept.get(index);
                times[index] = key.millis();
                where[index] = key.where().clone();
                eases[index] = key.ease();
                cuts[index] = key.cut() && index > 0;
                if (index > 0) {
                    where[index][YAW] = unwrap(where[index - 1][YAW], where[index][YAW]);
                }
            }
            return new WorldShot(this, times, where, eases, cuts);
        }

        /** The same heading as {@code yaw}, written as close to {@code previous} as it can be. */
        static double unwrap(double previous, double yaw) {
            double delta = ((yaw - previous) % 360 + 540) % 360 - 180;
            return previous + delta;
        }
    }
}
