package net.exylia.lib.region;

import org.jetbrains.annotations.NotNull;

/**
 * Immutable settings for a region outline visualization.
 *
 * @param particleName Bukkit particle name or namespaced particle key; must not be blank
 * @param spacing distance in blocks between neighboring outline samples; must be finite and positive
 * @param periodTicks ticks between rendered frames; must be at least one
 * @param durationTicks total visualization lifetime in ticks; must be at least one
 * @since 1.23.0
 */
public record VisualizationOptions(@NotNull String particleName, double spacing,
                                   long periodTicks, long durationTicks) {

    /** Default visible particle used by region outlines. */
    public static final String DEFAULT_PARTICLE = "END_ROD";
    /** Default distance between neighboring outline points. */
    public static final double DEFAULT_SPACING = 1.0;
    /** Default interval between frames. */
    public static final long DEFAULT_PERIOD_TICKS = 10L;
    /** Default visualization lifetime. */
    public static final long DEFAULT_DURATION_TICKS = 200L;

    /** Validates and normalizes immutable visualization settings. */
    public VisualizationOptions {
        if (particleName == null) {
            throw new NullPointerException("particleName");
        }
        particleName = particleName.trim();
        if (particleName.isEmpty()) {
            throw new IllegalArgumentException("particleName must not be blank");
        }
        if (!Double.isFinite(spacing) || spacing <= 0.0) {
            throw new IllegalArgumentException("spacing must be finite and positive");
        }
        if (periodTicks < 1L) {
            throw new IllegalArgumentException("periodTicks must be at least one");
        }
        if (durationTicks < 1L) {
            throw new IllegalArgumentException("durationTicks must be at least one");
        }
    }

    /**
     * Returns the standard settings: {@code END_ROD}, one-block spacing, a ten-tick period,
     * and a two-hundred-tick lifetime.
     *
     * @return immutable default options
     */
    public static @NotNull VisualizationOptions defaults() {
        return new VisualizationOptions(DEFAULT_PARTICLE, DEFAULT_SPACING,
                DEFAULT_PERIOD_TICKS, DEFAULT_DURATION_TICKS);
    }
}
