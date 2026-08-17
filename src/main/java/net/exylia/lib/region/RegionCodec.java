package net.exylia.lib.region;

import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Converts immutable region snapshots to and from portable persistence values.
 *
 * <p>The caller supplies the complete policy key collection for each operation. Unknown policy
 * identifiers, duplicate identifiers with incompatible types, unsupported policy types, and scalar
 * values that cannot be converted without loss are rejected rather than silently dropped.
 *
 * @since 1.23.0
 */
public final class RegionCodec {

    private RegionCodec() { }

    /**
     * Encodes a snapshot using the supplied policy key definitions.
     *
     * @param region snapshot to encode
     * @param policyKeys complete known policy key collection
     * @return portable persistence data
     */
    public static @NotNull RegionData encode(@NotNull RegionSnapshot region,
                                              @NotNull Collection<PolicyKey<?>> policyKeys) {
        Objects.requireNonNull(region, "region");
        Map<RegionId, PolicyKey<?>> known = index(policyKeys);
        ShapeEncoding shape = encodeShape(region.shape());
        Map<String, Object> policies = new LinkedHashMap<>();
        for (PolicyKey<?> key : region.policySet().keys()) {
            PolicyKey<?> registered = known.get(key.id());
            if (registered == null) {
                throw new IllegalArgumentException("Unknown policy key: " + key.id());
            }
            requireCompatible(key, registered);
            Object value = region.policySet().values().get(key);
            policies.put(key.id().toString(), encodeScalar(registered, value));
        }
        return new RegionData(RegionData.CURRENT_FORMAT_VERSION, region.id().toString(),
                region.owner(), region.world().id(), region.world().fallbackName(), shape.type,
                shape.coordinates, region.priority(), policies);
    }

    /**
     * Decodes portable data using the supplied policy key definitions.
     *
     * @param data persistence data to decode
     * @param policyKeys complete known policy key collection
     * @return immutable region snapshot
     */
    public static @NotNull RegionSnapshot decode(@NotNull RegionData data,
                                                  @NotNull Collection<PolicyKey<?>> policyKeys) {
        Objects.requireNonNull(data, "data");
        if (data.formatVersion() != RegionData.CURRENT_FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported region format version: "
                    + data.formatVersion());
        }
        Map<RegionId, PolicyKey<?>> known = index(policyKeys);
        PolicySet policies = PolicySet.empty();
        for (Map.Entry<String, Object> entry : data.policies().entrySet()) {
            RegionId id = RegionId.parse(entry.getKey());
            PolicyKey<?> key = known.get(id);
            if (key == null) {
                throw new IllegalArgumentException("Unknown policy key: " + id);
            }
            policies = addDecoded(policies, key, entry.getValue());
        }
        return new RegionSnapshot(RegionId.parse(data.id()), data.owner(),
                new WorldIdentity(data.worldId(), data.fallbackWorldName()), decodeShape(data),
                data.priority(), policies);
    }

    private static Map<RegionId, PolicyKey<?>> index(Collection<PolicyKey<?>> policyKeys) {
        Objects.requireNonNull(policyKeys, "policyKeys");
        Map<RegionId, PolicyKey<?>> known = new HashMap<>();
        for (PolicyKey<?> key : policyKeys) {
            Objects.requireNonNull(key, "policyKeys contains null");
            PolicyKey<?> existing = known.putIfAbsent(key.id(), key);
            if (existing != null && !existing.type().equals(key.type())) {
                throw new IllegalArgumentException("Incompatible duplicate policy key " + key.id()
                        + ": " + existing.type().getName() + " and " + key.type().getName());
            }
        }
        return known;
    }

    private static void requireCompatible(PolicyKey<?> actual, PolicyKey<?> registered) {
        if (!actual.type().equals(registered.type())) {
            throw new IllegalArgumentException("Policy key " + actual.id() + " has type "
                    + actual.type().getName() + ", expected " + registered.type().getName());
        }
    }

    private static Object encodeScalar(PolicyKey<?> key, Object value) {
        key.cast(value);
        Class<?> type = key.type();
        if (type == Boolean.class || type == String.class || isSupportedNumber(type)) {
            if (value instanceof Number number && !Double.isFinite(number.doubleValue())) {
                throw new IllegalArgumentException("Policy number must be finite: " + key.id());
            }
            return value;
        }
        throw new IllegalArgumentException("Policy type is not persistable as a scalar: "
                + type.getName());
    }

    private static <T> PolicySet addDecoded(PolicySet set, PolicyKey<T> key, Object scalar) {
        return set.with(key, decodeScalar(key, scalar));
    }

    private static <T> T decodeScalar(PolicyKey<T> key, Object scalar) {
        Objects.requireNonNull(scalar, "policy value for " + key.id());
        Class<T> type = key.type();
        Object decoded;
        if (type == Boolean.class || type == String.class) {
            if (!type.isInstance(scalar)) {
                throw incompatibleScalar(key, scalar);
            }
            decoded = scalar;
        } else if (isSupportedNumber(type)) {
            if (!(scalar instanceof Number number)) {
                throw incompatibleScalar(key, scalar);
            }
            decoded = convertNumber(type, number, key.id());
        } else {
            throw new IllegalArgumentException("Policy type is not persistable as a scalar: "
                    + type.getName());
        }
        return type.cast(decoded);
    }

    private static Object convertNumber(Class<?> type, Number number, RegionId id) {
        double value = number.doubleValue();
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Policy number must be finite: " + id);
        }
        if (type == Double.class) return value;
        if (type == Float.class) {
            float converted = number.floatValue();
            if (!Float.isFinite(converted) || Double.compare((double) converted, value) != 0) {
                throw new IllegalArgumentException("Policy number cannot be represented as float: " + id);
            }
            return converted;
        }
        long integral = number.longValue();
        if (value != integral) {
            throw new IllegalArgumentException("Policy number must be integral: " + id);
        }
        if (type == Long.class) return integral;
        if (type == Integer.class && integral >= Integer.MIN_VALUE && integral <= Integer.MAX_VALUE) {
            return (int) integral;
        }
        if (type == Short.class && integral >= Short.MIN_VALUE && integral <= Short.MAX_VALUE) {
            return (short) integral;
        }
        if (type == Byte.class && integral >= Byte.MIN_VALUE && integral <= Byte.MAX_VALUE) {
            return (byte) integral;
        }
        throw new IllegalArgumentException("Policy number is outside the range of "
                + type.getSimpleName() + ": " + id);
    }

    private static boolean isSupportedNumber(Class<?> type) {
        return type == Byte.class || type == Short.class || type == Integer.class
                || type == Long.class || type == Float.class || type == Double.class;
    }

    private static IllegalArgumentException incompatibleScalar(PolicyKey<?> key, Object value) {
        return new IllegalArgumentException("Policy " + key.id() + " requires "
                + key.type().getName() + ", got " + value.getClass().getName());
    }

    private static ShapeEncoding encodeShape(RegionShape shape) {
        return switch (shape) {
            case Cuboid cuboid -> new ShapeEncoding(RegionData.ShapeType.CUBOID,
                    new double[] {cuboid.minX(), cuboid.minY(), cuboid.minZ(),
                            cuboid.maxX(), cuboid.maxY(), cuboid.maxZ()});
            case UnboundedYRectangle rectangle -> new ShapeEncoding(
                    RegionData.ShapeType.UNBOUNDED_Y_RECTANGLE,
                    new double[] {rectangle.minX(), rectangle.minZ(),
                            rectangle.maxX(), rectangle.maxZ()});
            case Sphere sphere -> new ShapeEncoding(RegionData.ShapeType.SPHERE,
                    new double[] {sphere.centerX(), sphere.centerY(), sphere.centerZ(),
                            sphere.radius()});
            case HorizontalCylinder cylinder -> new ShapeEncoding(
                    RegionData.ShapeType.HORIZONTAL_CYLINDER,
                    new double[] {cylinder.centerX(), cylinder.centerZ(), cylinder.radius()});
        };
    }

    private static RegionShape decodeShape(RegionData data) {
        double[] coordinates = data.coordinates();
        return switch (data.shapeType()) {
            case CUBOID -> new Cuboid(coordinates[0], coordinates[1], coordinates[2],
                    coordinates[3], coordinates[4], coordinates[5]);
            case UNBOUNDED_Y_RECTANGLE -> new UnboundedYRectangle(coordinates[0], coordinates[1],
                    coordinates[2], coordinates[3]);
            case SPHERE -> new Sphere(coordinates[0], coordinates[1], coordinates[2], coordinates[3]);
            case HORIZONTAL_CYLINDER -> new HorizontalCylinder(coordinates[0], coordinates[1],
                    coordinates[2]);
        };
    }

    private record ShapeEncoding(RegionData.ShapeType type, double[] coordinates) { }
}
