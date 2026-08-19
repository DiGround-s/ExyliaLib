package net.exylia.lib.util.teleport;

import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Where a random teleport is allowed to put somebody.
 *
 * <pre>{@code
 * RandomArea wild = RandomArea.around(world.getSpawnLocation(), 500, 5_000);
 *
 * teleports.random(player, wild)
 *         .warmup(5.0)
 *         .cooldown("rtp", 300.0)
 *         .start();
 * }</pre>
 *
 * <h2>Why there is a minimum radius</h2>
 * Without one, every random teleport is a lottery weighted towards spawn, which
 * is the one part of the map that is already built on, already claimed and
 * already crowded. The minimum is what makes the feature mean "somewhere else"
 * rather than "somewhere".
 *
 * <h2>Biomes are named, not typed</h2>
 * Strings compared without case, rather than the {@code Biome} type, because
 * several registries stopped being enums in 1.21 and a config that names a
 * biome the server does not have must be a line in the console rather than an
 * exception at startup. A name nobody recognises simply never matches.
 *
 * @param world         which world
 * @param centreX       the x the radius is measured from
 * @param centreZ       the z the radius is measured from
 * @param minRadius     how far out the area starts, in blocks
 * @param maxRadius     how far out it ends, in blocks
 * @param blockedBiomes biomes a player must not be dropped into, by name
 * @since 1.34.0
 */
public record RandomArea(@NotNull World world, int centreX, int centreZ,
                         int minRadius, int maxRadius,
                         @NotNull Set<String> blockedBiomes) {

    /**
     * As far out as the world can go.
     *
     * <p>The vanilla world border, which is where coordinates stop behaving.
     * Clamping here rather than letting a typo through is what keeps a
     * misplaced zero from sending somebody past the edge of the world.
     */
    public static final int MAX_RADIUS = 30_000_000;

    public RandomArea {
        Objects.requireNonNull(world, "world");
        // A negative minimum is not a distance, and a maximum that is not past
        // the minimum leaves the picker with an empty ring to choose from.
        minRadius = Math.clamp(Math.max(0, minRadius), 0, MAX_RADIUS - 1);
        maxRadius = Math.clamp(maxRadius, minRadius + 1, MAX_RADIUS);
        // Held lowercase so the comparison at pick time is a set lookup rather
        // than a scan doing equalsIgnoreCase against every entry.
        Set<String> blocked = new LinkedHashSet<>();
        if (blockedBiomes != null) {
            for (String biome : blockedBiomes) {
                if (biome != null && !biome.isBlank()) {
                    blocked.add(biome.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        blockedBiomes = Set.copyOf(blocked);
    }

    /**
     * A ring around a place, blocking nothing.
     *
     * @param centre    where to measure from
     * @param minRadius how far out the area starts, in blocks
     * @param maxRadius how far out it ends, in blocks
     * @return the area
     */
    public static @NotNull RandomArea around(@NotNull Location centre, int minRadius, int maxRadius) {
        Objects.requireNonNull(centre, "centre");
        World world = centre.getWorld();
        if (world == null) {
            throw new IllegalArgumentException("The centre has no world, so there is no area");
        }
        return new RandomArea(world, centre.getBlockX(), centre.getBlockZ(),
                minRadius, maxRadius, Set.of());
    }

    /**
     * Whether a biome is one this area refuses.
     *
     * @param biome the biome's name, in any case
     * @return whether a player must not be dropped there
     */
    public boolean blocks(@NotNull String biome) {
        return blockedBiomes.contains(biome.toLowerCase(Locale.ROOT));
    }
}
