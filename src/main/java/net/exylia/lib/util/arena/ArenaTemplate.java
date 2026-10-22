package net.exylia.lib.util.arena;

import net.exylia.lib.region.Cuboid;
import net.exylia.lib.region.PolicySet;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An arena as a plugin configured it, which an {@link ArenaPool} hands out and copies.
 *
 * <pre>{@code
 * ArenaTemplate forest = ArenaTemplate.of("forest", world,
 *         Cuboid.blocks(100, 60, 100, 160, 90, 160), "duels_forest",
 *         Map.of("a", spawnA, "b", spawnB, "spectator", spectator), 8);
 * }</pre>
 *
 * <p>{@code schematic} must have been saved from exactly {@code bounds}
 * ({@code PluginSchematics.save(name, bounds, world)}): a copy is pasted with the
 * schematic's origin on the plot's corner at {@code bounds}' lowest Y, and the
 * points and boxes follow by the same offset. The same schematic regenerates the
 * arena on every release.
 *
 * @param id        the template's id, unique within its pool
 * @param world     the world the original arena stands in
 * @param bounds    the box the schematic was saved from; the whole arena
 * @param schematic the plugin's schematic of {@code bounds}
 * @param points    named positions inside the arena — spawns, a spectator spot —
 *                  their world is ignored, {@code world} is assumed
 * @param boxes     named boxes inside the arena — a capture zone, a goal
 * @param policies  what the region registered over each copy declares
 * @param maxCopies how many copies may exist besides the original; {@code 0} never copies
 * @since 1.346.0
 */
public record ArenaTemplate(@NotNull String id, @NotNull World world, @NotNull Cuboid bounds,
                            @NotNull String schematic, @NotNull Map<String, Location> points,
                            @NotNull Map<String, Cuboid> boxes, @NotNull PolicySet policies,
                            int maxCopies) {

    /** Validates the template and freezes its maps, keeping their order. */
    public ArenaTemplate {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(schematic, "schematic");
        Objects.requireNonNull(policies, "policies");
        if (maxCopies < 0) {
            throw new IllegalArgumentException("maxCopies cannot be negative, got " + maxCopies);
        }
        points = freeze(points, "points");
        boxes = freeze(boxes, "boxes");
    }

    /**
     * A template with no named boxes and a region that declares nothing.
     *
     * @return the template
     */
    public static @NotNull ArenaTemplate of(@NotNull String id, @NotNull World world,
                                            @NotNull Cuboid bounds, @NotNull String schematic,
                                            @NotNull Map<String, Location> points, int maxCopies) {
        return new ArenaTemplate(id, world, bounds, schematic, points, Map.of(),
                PolicySet.empty(), maxCopies);
    }

    private static <V> Map<String, V> freeze(Map<String, V> map, String name) {
        Objects.requireNonNull(map, name);
        Map<String, V> copy = new LinkedHashMap<>();
        map.forEach((key, value) -> copy.put(Objects.requireNonNull(key, name),
                Objects.requireNonNull(value, name)));
        return Collections.unmodifiableMap(copy);
    }
}
