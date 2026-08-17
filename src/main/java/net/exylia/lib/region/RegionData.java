package net.exylia.lib.region;

import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Concrete portable persistence value for a region.
 *
 * <p>Shape coordinates are positional and interpreted by {@link ShapeType}: cuboid stores six
 * bounds, unbounded rectangle four bounds, sphere four center/radius values, and horizontal
 * cylinder three center/radius values. Policy values are limited to booleans, strings, and finite
 * numbers so a future database adapter can store this value in one encoded column or split it into
 * scalar columns. This class performs no JSON or database work.
 *
 * @param formatVersion persistence schema version
 * @param id namespaced region identifier
 * @param owner exact owner string
 * @param worldId authoritative world UUID
 * @param fallbackWorldName exact fallback world name
 * @param shapeType concrete shape discriminator
 * @param coordinates positional shape coordinates
 * @param priority region precedence
 * @param policies namespaced policy identifiers mapped to scalar values
 * @since 1.23.0
 */
public record RegionData(int formatVersion, @NotNull String id, @NotNull String owner,
                         @NotNull UUID worldId, @NotNull String fallbackWorldName,
                         @NotNull ShapeType shapeType, @NotNull double[] coordinates,
                         int priority, @NotNull Map<String, Object> policies) {

    /** Current persistence format emitted by {@link RegionCodec}. */
    public static final int CURRENT_FORMAT_VERSION = 1;

    /** Validates and defensively copies the portable data. */
    public RegionData {
        if (formatVersion <= 0) {
            throw new IllegalArgumentException("Format version must be positive");
        }
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(fallbackWorldName, "fallbackWorldName");
        Objects.requireNonNull(shapeType, "shapeType");
        Objects.requireNonNull(coordinates, "coordinates");
        Objects.requireNonNull(policies, "policies");
        if (id.isBlank() || owner.isBlank() || fallbackWorldName.isBlank()) {
            throw new IllegalArgumentException("Region id, owner, and world name cannot be blank");
        }
        coordinates = coordinates.clone();
        for (double coordinate : coordinates) {
            if (!Double.isFinite(coordinate)) {
                throw new IllegalArgumentException("Shape coordinates must be finite");
            }
        }
        if (coordinates.length != shapeType.coordinateCount) {
            throw new IllegalArgumentException(shapeType + " requires "
                    + shapeType.coordinateCount + " coordinates");
        }
        Map<String, Object> checkedPolicies = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : policies.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "policy id");
            Object value = requireScalar(entry.getValue(), key);
            if (key.isBlank()) {
                throw new IllegalArgumentException("Policy id cannot be blank");
            }
            checkedPolicies.put(key, value);
        }
        policies = Map.copyOf(checkedPolicies);
    }

    /** Returns a defensive copy of positional shape coordinates. */
    @Override
    public double @NotNull [] coordinates() {
        return coordinates.clone();
    }

    /** Supported concrete shape discriminators. */
    public enum ShapeType {
        CUBOID(6),
        UNBOUNDED_Y_RECTANGLE(4),
        SPHERE(4),
        HORIZONTAL_CYLINDER(3);

        private final int coordinateCount;

        ShapeType(int coordinateCount) {
            this.coordinateCount = coordinateCount;
        }
    }

    private static Object requireScalar(Object value, String key) {
        Objects.requireNonNull(value, "policy value for " + key);
        if (value instanceof Boolean || value instanceof String) {
            return value;
        }
        if (value instanceof Number number) {
            double converted = number.doubleValue();
            if (!Double.isFinite(converted)) {
                throw new IllegalArgumentException("Policy number must be finite: " + key);
            }
            return value;
        }
        throw new IllegalArgumentException("Policy value must be boolean, string, or number: " + key);
    }
}
