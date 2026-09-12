package net.exylia.lib.api.aimtrainer;

import org.jetbrains.annotations.NotNull;

/**
 * What a player chose about their own targets and their own screen.
 *
 * @param sizeMultiplier     multiplies every drill's target size
 * @param distanceMultiplier multiplies every drill's distances
 * @param colour             the configured colour id
 * @param style              the configured style id
 * @param sidebar            whether the sidebar is shown
 * @param bossBar            whether the boss bar is shown
 * @param actionBar          whether hit feedback is shown
 * @param hitSounds          whether hits make a sound
 * @param particles          whether hits draw particles
 * @param freeze             whether the player stands still during a drill
 * @since 1.5.0
 */
public record AimPreferences(double sizeMultiplier, double distanceMultiplier, @NotNull String colour,
                             @NotNull String style, boolean sidebar, boolean bossBar, boolean actionBar,
                             boolean hitSounds, boolean particles, boolean freeze) {
}
