package net.exylia.lib.guide;

import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * How a beacon looks.
 *
 * <pre>{@code
 * BeaconStyle style = BeaconStyle.of(Material.PURPLE_STAINED_GLASS.createBlockData(), 0x8A51C4).height(64);
 * }</pre>
 *
 * <p>The beam is two block displays at full brightness: a thin white core and
 * a wider shell of {@code beam}, outlined in {@code colour} so the pillar reads
 * through terrain from across the map. Stained glass makes the best shell,
 * because the core shows through it.
 *
 * @param beam           the shell's block
 * @param colour         the outline colour, {@code 0xRRGGBB}; {@code -1} for no outline
 * @param height         how tall the beam stands above the target, in blocks
 * @param width          how wide the shell is, in blocks
 * @param labelHeight    how far above the target the label floats, in blocks
 * @param markerDistance how far from the player the stand-in marker is drawn when the target
 *                       is too far, in blocks
 * @param farDistance    from how far away the stand-in replaces the beam, in blocks;
 *                       {@code 0} works it out from the player's view distance
 * @since 1.391.0
 */
public record BeaconStyle(@NotNull BlockData beam, int colour, double height, double width,
                          double labelHeight, double markerDistance, double farDistance) {

    /** Keeps the numbers inside what reads as a beacon. */
    public BeaconStyle {
        Objects.requireNonNull(beam, "beam");
        height = Math.clamp(height, 2.0, 320.0);
        width = Math.clamp(width, 0.05, 4.0);
        markerDistance = Math.clamp(markerDistance, 8.0, 96.0);
        farDistance = Math.max(0, farDistance);
    }

    /**
     * A beacon in one block and one colour, everything else at its default: a
     * 48-block beam, 0.35 wide, label 2.8 above the target, stand-in at 30
     * blocks, cut-over worked out from the view distance.
     *
     * @param beam   the shell's block
     * @param colour the outline colour, {@code 0xRRGGBB}; {@code -1} for none
     * @return the style
     */
    public static @NotNull BeaconStyle of(@NotNull BlockData beam, int colour) {
        return new BeaconStyle(beam, colour, 48, 0.35, 2.8, 30, 0);
    }

    /**
     * @param blocks the beam's height
     * @return a copy with it
     */
    public @NotNull BeaconStyle height(double blocks) {
        return new BeaconStyle(beam, colour, blocks, width, labelHeight, markerDistance, farDistance);
    }

    /**
     * @param blocks the shell's width
     * @return a copy with it
     */
    public @NotNull BeaconStyle width(double blocks) {
        return new BeaconStyle(beam, colour, height, blocks, labelHeight, markerDistance, farDistance);
    }

    /**
     * @param blocks how far above the target the label floats
     * @return a copy with it
     */
    public @NotNull BeaconStyle labelHeight(double blocks) {
        return new BeaconStyle(beam, colour, height, width, blocks, markerDistance, farDistance);
    }

    /**
     * @param blocks how far away the stand-in marker stands
     * @return a copy with it
     */
    public @NotNull BeaconStyle markerDistance(double blocks) {
        return new BeaconStyle(beam, colour, height, width, labelHeight, blocks, farDistance);
    }

    /**
     * @param blocks from how far the stand-in takes over, {@code 0} automatic
     * @return a copy with it
     */
    public @NotNull BeaconStyle farDistance(double blocks) {
        return new BeaconStyle(beam, colour, height, width, labelHeight, markerDistance, blocks);
    }
}
