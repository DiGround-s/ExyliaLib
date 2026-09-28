package net.exylia.lib.region;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable set of materials, the value type of a region's block lists.
 *
 * <p>It exists so a block list can be a region policy like any other:
 * {@link CommonRegionPolicies#ALLOWED_BLOCKS} and
 * {@link CommonRegionPolicies#BREAKABLE_BLOCKS} are declared on the region, persisted
 * by {@link RegionCodec} as a list of material names, and read by the enforcement
 * {@link PluginRegions#enforce()} turns on, instead of living in a private field
 * of every consumer.
 *
 * <pre>{@code
 * PolicySet.of(CommonRegionPolicies.BREAKABLE_BLOCKS_ONLY, true)
 *         .with(CommonRegionPolicies.BREAKABLE_BLOCKS, MaterialSet.of(Material.SAND, Material.GRAVEL))
 * }</pre>
 *
 * @since 1.201.0
 */
public final class MaterialSet {
    private static final MaterialSet EMPTY = new MaterialSet(EnumSet.noneOf(Material.class));

    private final Set<Material> materials;

    private MaterialSet(EnumSet<Material> materials) {
        this.materials = Collections.unmodifiableSet(materials);
    }

    /** Returns the shared empty set. */
    public static @NotNull MaterialSet empty() {
        return EMPTY;
    }

    /**
     * Creates a set from individual materials.
     *
     * @param materials materials to contain; duplicates collapse
     * @return an immutable set
     */
    public static @NotNull MaterialSet of(@NotNull Material... materials) {
        Objects.requireNonNull(materials, "materials");
        return of(java.util.Arrays.asList(materials));
    }

    /**
     * Creates a set from a collection, copying it.
     *
     * @param materials materials to contain; duplicates collapse
     * @return an immutable set
     */
    public static @NotNull MaterialSet of(@NotNull Collection<Material> materials) {
        Objects.requireNonNull(materials, "materials");
        if (materials.isEmpty()) return EMPTY;
        EnumSet<Material> copy = EnumSet.noneOf(Material.class);
        for (Material material : materials) {
            copy.add(Objects.requireNonNull(material, "materials contains null"));
        }
        return new MaterialSet(copy);
    }

    /** Whether the set contains a material. */
    public boolean contains(@NotNull Material material) {
        return materials.contains(material);
    }

    /** Returns the materials as an unmodifiable set, in declaration order. */
    public @NotNull Set<Material> materials() {
        return materials;
    }

    /** Whether the set contains no material. */
    public boolean isEmpty() {
        return materials.isEmpty();
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof MaterialSet set && materials.equals(set.materials);
    }

    @Override
    public int hashCode() {
        return materials.hashCode();
    }

    @Override
    public @NotNull String toString() {
        return materials.toString();
    }
}
