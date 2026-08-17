package net.exylia.lib.region;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RegionCodecTest {

    private static final WorldIdentity WORLD = new WorldIdentity(UUID.randomUUID(), "Exact World Name");

    @Test
    @DisplayName("Codec round-trips every supported region shape")
    void roundTripsEveryShape() {
        List<RegionShape> shapes = List.of(
                new Cuboid(-12.5, -64, 3.25, 17, 320, 19),
                new UnboundedYRectangle(-100, -50, 200, 300),
                new Sphere(-3, 7, 11, 5.5),
                new HorizontalCylinder(40, -20, 9.25));

        for (int index = 0; index < shapes.size(); index++) {
            RegionSnapshot original = new RegionSnapshot(new RegionId("test", "shape-" + index),
                    "Owner", WORLD, shapes.get(index), 17 - index, PolicySet.empty());
            assertEquals(original, RegionCodec.decode(RegionCodec.encode(original, List.of()), List.of()));
        }
    }

    @Test
    @DisplayName("Codec round-trips booleans, strings, and every numeric wrapper type")
    void roundTripsEveryScalarType() {
        PolicyKey<Boolean> bool = key("boolean", Boolean.class, false);
        PolicyKey<String> string = key("string", String.class, "default");
        PolicyKey<Byte> bytes = key("byte", Byte.class, (byte) 0);
        PolicyKey<Short> shorts = key("short", Short.class, (short) 0);
        PolicyKey<Integer> integers = key("integer", Integer.class, 0);
        PolicyKey<Long> longs = key("long", Long.class, 0L);
        PolicyKey<Float> floats = key("float", Float.class, 0F);
        PolicyKey<Double> doubles = key("double", Double.class, 0D);
        List<PolicyKey<?>> keys = List.of(bool, string, bytes, shorts, integers, longs, floats, doubles);

        PolicySet policies = PolicySet.empty()
                .with(bool, true).with(string, "literal")
                .with(bytes, (byte) -7).with(shorts, (short) 1234)
                .with(integers, -123_456).with(longs, 123_456_789_012L)
                .with(floats, 12.5F).with(doubles, -0.125D);
        RegionSnapshot original = region(new Sphere(1, 2, 3, 4), policies);
        RegionSnapshot decoded = RegionCodec.decode(RegionCodec.encode(original, keys), keys);

        assertEquals(original, decoded);
        assertEquals((byte) -7, decoded.policySet().explicit(bytes).orElseThrow());
        assertEquals((short) 1234, decoded.policySet().explicit(shorts).orElseThrow());
        assertEquals(123_456_789_012L, decoded.policySet().explicit(longs).orElseThrow());
    }

    @Test
    @DisplayName("Portable data makes defensive array and map copies")
    void regionDataIsDefensivelyCopied() {
        double[] coordinates = {0, 0, 0, 1, 1, 1};
        Map<String, Object> policies = new LinkedHashMap<>();
        policies.put("test:value", true);
        RegionData data = new RegionData(1, "test:data", "owner", WORLD.id(), WORLD.fallbackName(),
                RegionData.ShapeType.CUBOID, coordinates, 0, policies);

        coordinates[0] = 99;
        policies.put("test:late", false);
        assertEquals(0, data.coordinates()[0]);
        assertEquals(Map.of("test:value", true), data.policies());

        double[] returned = data.coordinates();
        returned[1] = 99;
        assertEquals(0, data.coordinates()[1]);
        assertThrows(UnsupportedOperationException.class, () -> data.policies().clear());
    }

    @Test
    @DisplayName("Unknown keys and incompatible declarations fail closed without silent dropping")
    void unknownAndIncompatiblePoliciesFailClosed() {
        PolicyKey<Boolean> known = key("known", Boolean.class, true);
        PolicyKey<Boolean> unknown = key("unknown", Boolean.class, true);
        RegionSnapshot withUnknown = region(new Cuboid(0, 0, 0, 1, 1, 1), PolicySet.of(unknown, false));
        assertThrows(IllegalArgumentException.class, () -> RegionCodec.encode(withUnknown, List.of(known)));

        RegionData unknownData = data(Map.of("test:unknown", false));
        assertThrows(IllegalArgumentException.class, () -> RegionCodec.decode(unknownData, List.of(known)));

        RegionData wrongScalar = data(Map.of("test:known", "false"));
        assertThrows(IllegalArgumentException.class, () -> RegionCodec.decode(wrongScalar, List.of(known)));

        PolicyKey<String> incompatible = key("known", String.class, "yes");
        assertThrows(IllegalArgumentException.class,
                () -> RegionCodec.decode(data(Map.of()), List.of(known, incompatible)));
        assertThrows(IllegalArgumentException.class,
                () -> RegionCodec.encode(region(new Cuboid(0, 0, 0, 1, 1, 1), PolicySet.of(known, false)),
                        List.of(incompatible)));
    }

    @Test
    @DisplayName("Unsupported scalar types, invalid numbers, and unsupported formats fail")
    void unsupportedPersistenceValuesFail() {
        PolicyKey<Character> unsupported = key("character", Character.class, 'x');
        RegionSnapshot region = region(new Cuboid(0, 0, 0, 1, 1, 1), PolicySet.of(unsupported, 'y'));
        assertThrows(IllegalArgumentException.class, () -> RegionCodec.encode(region, List.of(unsupported)));

        PolicyKey<Integer> integer = key("integer", Integer.class, 0);
        assertThrows(IllegalArgumentException.class,
                () -> RegionCodec.decode(data(Map.of("test:integer", 1.5D)), List.of(integer)));
        assertThrows(IllegalArgumentException.class,
                () -> new RegionData(1, "test:data", "owner", WORLD.id(), WORLD.fallbackName(),
                        RegionData.ShapeType.CUBOID, new double[]{0, 0, 0, 1, 1, 1}, 0,
                        Map.of("test:value", Double.NaN)));

        RegionData future = new RegionData(2, "test:data", "owner", WORLD.id(), WORLD.fallbackName(),
                RegionData.ShapeType.CUBOID, new double[]{0, 0, 0, 1, 1, 1}, 0, Map.of());
        assertThrows(IllegalArgumentException.class, () -> RegionCodec.decode(future, List.of()));
    }

    @Test
    @DisplayName("Every explicit declaration is encoded")
    void declarationsAreNeverSilentlyDropped() {
        List<PolicyKey<Integer>> keys = new ArrayList<>();
        PolicySet policies = PolicySet.empty();
        for (int index = 0; index < 20; index++) {
            PolicyKey<Integer> key = key("number-" + index, Integer.class, -1);
            keys.add(key);
            policies = policies.with(key, index);
        }
        RegionData encoded = RegionCodec.encode(region(new HorizontalCylinder(0, 0, 2), policies),
                new ArrayList<>(keys));
        assertEquals(20, encoded.policies().size());
        assertEquals(policies, RegionCodec.decode(encoded, new ArrayList<>(keys)).policySet());
    }

    private static RegionData data(Map<String, Object> policies) {
        return new RegionData(1, "test:data", "owner", WORLD.id(), WORLD.fallbackName(),
                RegionData.ShapeType.CUBOID, new double[]{0, 0, 0, 1, 1, 1}, 0, policies);
    }

    private static RegionSnapshot region(RegionShape shape, PolicySet policies) {
        return new RegionSnapshot(RegionId.parse("test:region"), "owner", WORLD, shape, 9, policies);
    }

    private static <T> PolicyKey<T> key(String value, Class<T> type, T defaultValue) {
        return PolicyKey.of(new RegionId("test", value), type, defaultValue);
    }
}
