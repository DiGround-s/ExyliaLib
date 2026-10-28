package net.exylia.lib.guide;

import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * How a trail looks and how much of it is drawn.
 *
 * <pre>{@code
 * TrailStyle style = TrailStyle.of(Material.LIGHT_BLUE_CONCRETE.createBlockData(), 0x59A4FF).ahead(24);
 * }</pre>
 *
 * <p>Each marker is a small flat panel of {@code marker} lying on the ground,
 * longer than it is wide and turned along the route, so a line of them reads
 * as a direction rather than as dots.
 *
 * @param marker         the block a marker is cut from
 * @param colour         the markers' outline, {@code 0xRRGGBB}; {@code -1} for none
 * @param ahead          how far ahead of the player the trail is drawn, in blocks
 * @param spacing        the distance between markers, in blocks
 * @param offRoute       how far from the route the player may stray before a line is drawn
 *                       back to it, in blocks
 * @param returnMarker   the block the line back to the route is cut from
 * @param returnColour   that line's outline, {@code 0xRRGGBB}; {@code -1} for none
 * @since 1.391.0
 */
public record TrailStyle(@NotNull BlockData marker, int colour, double ahead, double spacing,
                         double offRoute, @NotNull BlockData returnMarker, int returnColour) {

    /** Keeps the numbers inside what stays cheap. */
    public TrailStyle {
        Objects.requireNonNull(marker, "marker");
        Objects.requireNonNull(returnMarker, "returnMarker");
        spacing = Math.clamp(spacing, 0.5, 16.0);
        // At most 64 markers on screen, however the numbers are written.
        ahead = Math.clamp(ahead, spacing, spacing * 64);
        offRoute = Math.max(1.5, offRoute);
    }

    /**
     * A trail of one block and one colour: 20 blocks ahead, a marker every
     * 1.5, a line back after 6 blocks off the route drawn the same way.
     *
     * @param marker the marker block
     * @param colour the outline colour, {@code -1} for none
     * @return the style
     */
    public static @NotNull TrailStyle of(@NotNull BlockData marker, int colour) {
        return new TrailStyle(marker, colour, 20, 1.5, 6, marker, colour);
    }

    /**
     * @param blocks how far ahead it is drawn
     * @return a copy with it
     */
    public @NotNull TrailStyle ahead(double blocks) {
        return new TrailStyle(marker, colour, blocks, spacing, offRoute, returnMarker, returnColour);
    }

    /**
     * @param blocks the distance between markers
     * @return a copy with it
     */
    public @NotNull TrailStyle spacing(double blocks) {
        return new TrailStyle(marker, colour, ahead, blocks, offRoute, returnMarker, returnColour);
    }

    /**
     * @param blocks how far off the route counts as lost
     * @return a copy with it
     */
    public @NotNull TrailStyle offRoute(double blocks) {
        return new TrailStyle(marker, colour, ahead, spacing, blocks, returnMarker, returnColour);
    }

    /**
     * @param block  the block the way back is drawn with
     * @param rgb    its outline, {@code -1} for none
     * @return a copy with both
     */
    public @NotNull TrailStyle wayBack(@NotNull BlockData block, int rgb) {
        return new TrailStyle(marker, colour, ahead, spacing, offRoute, block, rgb);
    }
}
