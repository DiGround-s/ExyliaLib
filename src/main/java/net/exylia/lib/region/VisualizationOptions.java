package net.exylia.lib.region;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * How a region outline is drawn to one player.
 *
 * <pre>{@code
 * VisualizationOptions zone = VisualizationOptions.builder()
 *         .particleName("WAX_ON")
 *         .periodTicks(40L)
 *         .untilClosed()
 *         .build();
 * }</pre>
 *
 * <h2>Two of these decide whether it can run for a whole match</h2>
 * The defaults describe an admin being shown where a region is: two hundred
 * ticks and gone. A zone that is part of a game is the other case — it is drawn
 * for as long as the game lasts, to everybody, and the cost is paid every frame
 * by every viewer.
 *
 * <p>{@link Builder#untilClosed()} is what makes the first possible without
 * naming a duration nobody can know in advance, and {@link #viewDistance()} is
 * what keeps the second affordable: a viewer on the other side of the world is
 * skipped before the outline is worked out, not after.
 *
 * @since 1.23.0
 */
public final class VisualizationOptions {

    /** Default visible particle used by region outlines. */
    public static final String DEFAULT_PARTICLE = "END_ROD";

    /** Default distance between neighboring outline points. */
    public static final double DEFAULT_SPACING = 1.0;

    /** Default interval between frames. */
    public static final long DEFAULT_PERIOD_TICKS = 10L;

    /** Default visualization lifetime. */
    public static final long DEFAULT_DURATION_TICKS = 200L;

    /**
     * How far a viewer can be and still be drawn to, in blocks.
     *
     * <p>The same distance a hologram uses, and for the same reason: past it
     * the client has nothing to draw anyway, so the packets were only ever
     * bandwidth.
     */
    public static final double DEFAULT_VIEW_DISTANCE = 48.0;

    /**
     * A lifetime that ends only when the handle is closed.
     *
     * <p>The visualization still stops on its own when the viewer leaves, the
     * region goes away or the owning plugin is disabled — so this is "for as
     * long as there is something to draw", not "forever".
     */
    public static final long UNTIL_CLOSED = 0L;

    /** Shared default options. */
    public static final VisualizationOptions DEFAULT = builder().build();

    private final String particleName;
    private final double spacing;
    private final long periodTicks;
    private final long durationTicks;
    private final double viewDistance;

    private VisualizationOptions(Builder builder) {
        this.particleName = builder.particleName;
        this.spacing = builder.spacing;
        this.periodTicks = builder.periodTicks;
        this.durationTicks = builder.durationTicks;
        this.viewDistance = builder.viewDistance;
    }

    /**
     * The four settings this class had before it grew the rest.
     *
     * <p>Kept so a caller written against the record still compiles, and still
     * gets the view distance it never asked for.
     *
     * @param particleName  Bukkit particle name or namespaced key; must not be blank
     * @param spacing       blocks between neighbouring outline samples; positive
     * @param periodTicks   ticks between frames; at least one
     * @param durationTicks lifetime in ticks, or {@link #UNTIL_CLOSED}
     */
    public VisualizationOptions(@NotNull String particleName, double spacing,
                                long periodTicks, long durationTicks) {
        this(builder()
                .particleName(particleName)
                .spacing(spacing)
                .periodTicks(periodTicks)
                .durationTicks(durationTicks));
    }

    /**
     * Returns the standard settings: {@code END_ROD}, one-block spacing, a
     * ten-tick period, a two-hundred-tick lifetime and a 48-block view
     * distance.
     *
     * @return the shared default options
     */
    public static @NotNull VisualizationOptions defaults() {
        return DEFAULT;
    }

    /**
     * A builder holding the defaults.
     *
     * @return a new builder
     */
    public static @NotNull Builder builder() {
        return new Builder();
    }

    /**
     * A builder holding these options' values.
     *
     * @return a new builder, prefilled
     */
    public @NotNull Builder toBuilder() {
        Builder builder = new Builder();
        builder.particleName = particleName;
        builder.spacing = spacing;
        builder.periodTicks = periodTicks;
        builder.durationTicks = durationTicks;
        builder.viewDistance = viewDistance;
        return builder;
    }

    /**
     * The particle each outline point is drawn with.
     *
     * @return a Bukkit particle name or a namespaced key
     */
    public @NotNull String particleName() {
        return particleName;
    }

    /**
     * How far apart neighbouring outline points are, in blocks.
     *
     * @return the spacing
     */
    public double spacing() {
        return spacing;
    }

    /**
     * How many ticks pass between frames.
     *
     * @return the period, at least one
     */
    public long periodTicks() {
        return periodTicks;
    }

    /**
     * How long the visualization runs, in ticks.
     *
     * @return the lifetime, or {@link #UNTIL_CLOSED}
     */
    public long durationTicks() {
        return durationTicks;
    }

    /**
     * Whether this visualization ends only when it is closed.
     *
     * @return {@code true} when no lifetime was set
     */
    public boolean isUntilClosed() {
        return durationTicks == UNTIL_CLOSED;
    }

    /**
     * How far a viewer may be from the region and still be drawn to, in blocks.
     *
     * <p>Measured to the nearest point of the region's bounds, so standing just
     * outside a large region is near it whatever its centre is doing.
     *
     * @return the view distance, always positive
     */
    public double viewDistance() {
        return viewDistance;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof VisualizationOptions options)) {
            return false;
        }
        return Double.compare(spacing, options.spacing) == 0
                && periodTicks == options.periodTicks
                && durationTicks == options.durationTicks
                && Double.compare(viewDistance, options.viewDistance) == 0
                && particleName.equals(options.particleName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(particleName, spacing, periodTicks, durationTicks, viewDistance);
    }

    @Override
    public String toString() {
        return "VisualizationOptions[particle=" + particleName
                + ", spacing=" + spacing
                + ", period=" + periodTicks
                + ", duration=" + (isUntilClosed() ? "until closed" : durationTicks)
                + ", viewDistance=" + viewDistance + ']';
    }

    /** Builds {@link VisualizationOptions}. */
    public static final class Builder {

        private String particleName = DEFAULT_PARTICLE;
        private double spacing = DEFAULT_SPACING;
        private long periodTicks = DEFAULT_PERIOD_TICKS;
        private long durationTicks = DEFAULT_DURATION_TICKS;
        private double viewDistance = DEFAULT_VIEW_DISTANCE;

        private Builder() {
        }

        /**
         * The particle each outline point is drawn with.
         *
         * @param particleName a Bukkit particle name or a namespaced key
         * @return this builder
         */
        public @NotNull Builder particleName(@NotNull String particleName) {
            Objects.requireNonNull(particleName, "particleName");
            String trimmed = particleName.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("particleName must not be blank");
            }
            this.particleName = trimmed;
            return this;
        }

        /**
         * How far apart neighbouring outline points are, in blocks.
         *
         * @param spacing the spacing; must be finite and positive
         * @return this builder
         */
        public @NotNull Builder spacing(double spacing) {
            if (!Double.isFinite(spacing) || spacing <= 0.0) {
                throw new IllegalArgumentException("spacing must be finite and positive");
            }
            this.spacing = spacing;
            return this;
        }

        /**
         * How many ticks pass between frames.
         *
         * @param periodTicks the period; must be at least one
         * @return this builder
         */
        public @NotNull Builder periodTicks(long periodTicks) {
            if (periodTicks < 1L) {
                throw new IllegalArgumentException("periodTicks must be at least one");
            }
            this.periodTicks = periodTicks;
            return this;
        }

        /**
         * How long the visualization runs.
         *
         * @param durationTicks the lifetime in ticks, or {@link #UNTIL_CLOSED}
         * @return this builder
         */
        public @NotNull Builder durationTicks(long durationTicks) {
            if (durationTicks < 0L) {
                throw new IllegalArgumentException("durationTicks must not be negative");
            }
            this.durationTicks = durationTicks;
            return this;
        }

        /**
         * Runs until the handle is closed instead of for a fixed time.
         *
         * <p>For an outline that belongs to something with its own lifetime —
         * a match, a round, an event — where the end is an act rather than a
         * number of ticks known when it starts.
         *
         * @return this builder
         */
        public @NotNull Builder untilClosed() {
            this.durationTicks = UNTIL_CLOSED;
            return this;
        }

        /**
         * How far a viewer may be from the region and still be drawn to.
         *
         * @param viewDistance the distance in blocks; must be finite and positive
         * @return this builder
         */
        public @NotNull Builder viewDistance(double viewDistance) {
            if (!Double.isFinite(viewDistance) || viewDistance <= 0.0) {
                throw new IllegalArgumentException("viewDistance must be finite and positive");
            }
            this.viewDistance = viewDistance;
            return this;
        }

        /**
         * Builds the options.
         *
         * @return immutable visualization settings
         */
        public @NotNull VisualizationOptions build() {
            return new VisualizationOptions(this);
        }
    }
}
