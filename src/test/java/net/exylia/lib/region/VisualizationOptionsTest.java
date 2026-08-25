package net.exylia.lib.region;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an outline is allowed to be told.
 *
 * <p>The two settings that carry weight here are the ones a long-running zone
 * needs: an outline whose end is an act rather than a tick count, and a view
 * distance that keeps it affordable when everybody online is a viewer.
 */
class VisualizationOptionsTest {

    @Test
    @DisplayName("the defaults are the admin-being-shown-a-region case")
    void defaults() {
        VisualizationOptions options = VisualizationOptions.defaults();

        assertEquals(VisualizationOptions.DEFAULT_PARTICLE, options.particleName());
        assertEquals(VisualizationOptions.DEFAULT_SPACING, options.spacing());
        assertEquals(VisualizationOptions.DEFAULT_PERIOD_TICKS, options.periodTicks());
        assertEquals(VisualizationOptions.DEFAULT_DURATION_TICKS, options.durationTicks());
        assertEquals(VisualizationOptions.DEFAULT_VIEW_DISTANCE, options.viewDistance());
        assertFalse(options.isUntilClosed(), "a shown region goes away on its own");
    }

    @Test
    @DisplayName("an outline can run until it is closed")
    void untilClosed() {
        VisualizationOptions options = VisualizationOptions.builder().untilClosed().build();

        assertTrue(options.isUntilClosed());
        assertEquals(VisualizationOptions.UNTIL_CLOSED, options.durationTicks());
    }

    @Test
    @DisplayName("the four-argument form still compiles and gains a view distance")
    void legacyConstructor() {
        // Written against the record this used to be. It must keep working, and
        // it must pick up the setting it never knew about.
        VisualizationOptions options = new VisualizationOptions("FLAME", 2.0, 20L, 100L);

        assertEquals("FLAME", options.particleName());
        assertEquals(2.0, options.spacing());
        assertEquals(20L, options.periodTicks());
        assertEquals(100L, options.durationTicks());
        assertEquals(VisualizationOptions.DEFAULT_VIEW_DISTANCE, options.viewDistance());
    }

    @Test
    @DisplayName("a setting that cannot draw anything is refused where it is written")
    void invalidSettings() {
        VisualizationOptions.Builder builder = VisualizationOptions.builder();

        assertThrows(IllegalArgumentException.class, () -> builder.particleName("  "));
        assertThrows(IllegalArgumentException.class, () -> builder.spacing(0.0));
        assertThrows(IllegalArgumentException.class, () -> builder.periodTicks(0L));
        assertThrows(IllegalArgumentException.class, () -> builder.durationTicks(-1L));
        // Zero is not "unlimited": past the client's own range there is nothing
        // to draw, so a distance nobody can be inside is a mistake, not a mode.
        assertThrows(IllegalArgumentException.class, () -> builder.viewDistance(0.0));
        assertThrows(IllegalArgumentException.class,
                () -> builder.viewDistance(Double.POSITIVE_INFINITY));
    }

    @Test
    @DisplayName("toBuilder carries every setting, including the new ones")
    void roundTrip() {
        VisualizationOptions original = VisualizationOptions.builder()
                .particleName("WAX_ON")
                .spacing(1.5)
                .periodTicks(40L)
                .untilClosed()
                .viewDistance(64.0)
                .build();

        assertEquals(original, original.toBuilder().build());
        assertEquals(original.hashCode(), original.toBuilder().build().hashCode());
    }
}
