package net.exylia.lib.region;

import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

/**
 * Complete immutable public state of one region revision.
 *
 * <p>The natural query order is descending priority followed by ascending identifier.
 *
 * @since 1.23.0
 */
public final class RegionSnapshot implements Comparable<RegionSnapshot> {

    /** Canonical comparator used by region queries. */
    public static final Comparator<RegionSnapshot> ORDER = Comparator
            .comparingInt(RegionSnapshot::priority).reversed()
            .thenComparing(RegionSnapshot::id);

    private final RegionId id;
    private final String owner;
    private final WorldIdentity world;
    private final RegionShape shape;
    private final int priority;
    private final PolicySet policySet;

    /**
     * Creates an immutable region snapshot.
     *
     * @param id stable region identifier
     * @param owner exact owner string, compared without normalization
     * @param world portable world identity
     * @param shape immutable region geometry
     * @param priority precedence value; larger values take precedence
     * @param policies immutable explicit policy declarations
     */
    public RegionSnapshot(@NotNull RegionId id, @NotNull String owner,
                          @NotNull WorldIdentity world, @NotNull RegionShape shape,
                          int priority, @NotNull PolicySet policies) {
        this.id = Objects.requireNonNull(id, "id");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.world = Objects.requireNonNull(world, "world");
        this.shape = Objects.requireNonNull(shape, "shape");
        this.priority = priority;
        this.policySet = Objects.requireNonNull(policies, "policies");
        if (owner.isBlank()) {
            throw new IllegalArgumentException("Region owner cannot be blank");
        }
    }

    /** Returns the stable region identifier. */
    public @NotNull RegionId id() {
        return id;
    }

    /** Returns the exact owner string. */
    public @NotNull String owner() {
        return owner;
    }

    /** Returns the portable world identity. */
    public @NotNull WorldIdentity world() {
        return world;
    }

    /** Returns the immutable region geometry. */
    public @NotNull RegionShape shape() {
        return shape;
    }

    /** Returns this region's precedence value. */
    public int priority() {
        return priority;
    }

    /** Returns the immutable typed policy set. */
    public @NotNull PolicySet policySet() {
        return policySet;
    }

    /** Returns the authoritative world UUID for spatial indexing. */
    public @NotNull UUID worldId() {
        return world.id();
    }

    /** Orders snapshots by descending priority and then ascending identifier. */
    @Override
    public int compareTo(@NotNull RegionSnapshot other) {
        return ORDER.compare(this, Objects.requireNonNull(other, "other"));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof RegionSnapshot snapshot)) return false;
        return priority == snapshot.priority && id.equals(snapshot.id) && owner.equals(snapshot.owner)
                && world.equals(snapshot.world) && shape.equals(snapshot.shape)
                && policySet.equals(snapshot.policySet);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, owner, world, shape, priority, policySet);
    }

    @Override
    public @NotNull String toString() {
        return "RegionSnapshot[id=" + id + ", owner=" + owner + ", world=" + world
                + ", shape=" + shape + ", priority=" + priority + ", policies=" + policySet + ']';
    }
}
