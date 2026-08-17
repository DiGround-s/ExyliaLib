package net.exylia.lib.region;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Immutable configuration for an interactive block selection.
 *
 * @param selectorMaterial material that identifies selection interactions
 * @param cancelInteractions whether handled selection interactions are cancelled
 * @param requireSameWorld whether both selected corners must belong to the same world
 * @since 1.23.0
 */
public record SelectionOptions(@NotNull Material selectorMaterial,
                               boolean cancelInteractions,
                               boolean requireSameWorld) {

    /** Shared default options: wooden axe, cancelled interactions, and same-world corners. */
    public static final SelectionOptions DEFAULT =
            new SelectionOptions(Material.WOODEN_AXE, true, true);

    /**
     * Creates the default selection options.
     */
    public SelectionOptions() {
        this(Material.WOODEN_AXE, true, true);
    }

    /** Validates the immutable options. */
    public SelectionOptions {
        Objects.requireNonNull(selectorMaterial, "selectorMaterial");
        if (selectorMaterial == Material.AIR || selectorMaterial.name().endsWith("_AIR")
                || !isItem(selectorMaterial)) {
            throw new IllegalArgumentException("Selector material must be a non-air item material");
        }
    }

    /**
     * Returns the shared default options.
     *
     * @return default selection options
     */
    public static @NotNull SelectionOptions defaults() {
        return DEFAULT;
    }

    private static boolean isItem(Material material) {
        try {
            return material.isItem();
        } catch (LinkageError unavailableRegistry) {
            // Paper resolves item registries lazily. Unit environments do not install that
            // registry; a live server always returns from the authoritative branch above.
            return !material.isLegacy() && material != Material.WATER && material != Material.LAVA
                    && material != Material.FIRE && material != Material.SOUL_FIRE
                    && material != Material.NETHER_PORTAL && material != Material.END_PORTAL;
        }
    }
}
