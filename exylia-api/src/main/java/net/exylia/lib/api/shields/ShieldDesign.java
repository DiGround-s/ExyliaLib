package net.exylia.lib.api.shields;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

/**
 * A shield somebody designed: a base colour with layers drawn over it, bottom
 * first.
 *
 * <p>A snapshot, not a live view. Every edit inside the plugin produces a new
 * design, so what you hold is the arrangement that was in the slot when you
 * asked; read it again rather than keeping it across ticks.
 *
 * <p>Build one yourself to draw it onto an item with
 * {@link ShieldsService#applyDesign(org.bukkit.inventory.ItemStack, ShieldDesign)};
 * a design that was never published carries a {@code libraryId} of {@code 0}.
 *
 * @param libraryId the row in the shared library this design was published as,
 *                  or {@code 0} when it is nobody's published design. It is
 *                  what {@link ShieldsService#importDesign(org.bukkit.entity.Player, long)}
 *                  takes, and it survives edits because it identifies the row
 *                  rather than this particular arrangement of layers
 * @param baseColor the dye colour underneath, such as {@code WHITE}
 * @param layers    the layers, in the order they are drawn
 * @since 1.0.0
 */
public record ShieldDesign(
        long libraryId,
        @NotNull String baseColor,
        @NotNull @Unmodifiable List<ShieldLayer> layers) {

    /**
     * Copies the layers on the way in, so a caller that keeps editing the list
     * it passed is not editing a design somebody is already drawing.
     */
    public ShieldDesign {
        layers = List.copyOf(layers);
    }
}
