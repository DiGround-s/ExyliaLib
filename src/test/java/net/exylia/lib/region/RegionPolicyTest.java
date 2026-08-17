package net.exylia.lib.region;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RegionPolicyTest {

    private static final WorldIdentity WORLD = new WorldIdentity(UUID.randomUUID(), "world");

    @Test
    @DisplayName("Policy sets are immutable, typed, and distinguish absence from a default declaration")
    void policySetsAreImmutableAndTyped() {
        PolicyKey<Boolean> key = key("build", Boolean.class, true);
        PolicySet empty = PolicySet.empty();
        PolicySet declared = empty.with(key, true);

        assertFalse(empty.declares(key));
        assertTrue(empty.explicit(key).isEmpty());
        assertTrue(declared.declares(key));
        assertEquals(true, declared.explicit(key).orElseThrow());
        assertFalse(empty.declares(key), "with must not mutate the original set");
        assertThrows(UnsupportedOperationException.class, () -> declared.keys().clear());
        assertSame(PolicySet.empty(), declared.without(key));

        @SuppressWarnings({"rawtypes", "unchecked"})
        PolicyKey raw = key;
        assertThrows(IllegalArgumentException.class, () -> empty.with(raw, "not a boolean"));
    }

    @Test
    @DisplayName("The same policy identifier cannot be declared with incompatible types")
    void incompatibleSameIdIsRejected() {
        RegionId id = RegionId.parse("test:shared");
        PolicyKey<Boolean> booleanKey = PolicyKey.of(id, Boolean.class, true);
        PolicyKey<String> stringKey = PolicyKey.of(id, String.class, "default");
        PolicySet set = PolicySet.of(booleanKey, false);
        assertThrows(IllegalArgumentException.class, () -> set.with(stringKey, "value"));
    }

    @Test
    @DisplayName("Common keys retain exact Commons identifiers and defaults")
    void commonKeysAndDefaultsAreExact() {
        record Expected(PolicyKey<Boolean> key, String value, boolean defaultValue) { }
        List<Expected> expected = List.of(
                new Expected(CommonRegionPolicies.PVP, "pvp", true),
                new Expected(CommonRegionPolicies.BUILD, "build", true),
                new Expected(CommonRegionPolicies.BREAK, "break", true),
                new Expected(CommonRegionPolicies.INTERACT, "interact", true),
                new Expected(CommonRegionPolicies.PLAYER_BUILD_ONLY, "player_build_only", false),
                new Expected(CommonRegionPolicies.ALLOWED_BLOCKS_ONLY, "allowed_blocks_only", false),
                new Expected(CommonRegionPolicies.BREAKABLE_BLOCKS_ONLY, "breakable_blocks_only", false),
                new Expected(CommonRegionPolicies.TEMPORARY_BLOCKS, "temporary_blocks", false),
                new Expected(CommonRegionPolicies.RE_GIVE_BLOCKS, "re_give_blocks", false),
                new Expected(CommonRegionPolicies.REGION_MEMBERS_ONLY, "region_members_only", false),
                new Expected(CommonRegionPolicies.ENTRY, "entry", true),
                new Expected(CommonRegionPolicies.EXIT, "exit", true),
                new Expected(CommonRegionPolicies.ITEM_DROP, "item_drop", true),
                new Expected(CommonRegionPolicies.ITEM_PICKUP, "item_pickup", true),
                new Expected(CommonRegionPolicies.FALL_DAMAGE, "fall_damage", true));

        assertEquals(15, expected.size());
        for (Expected item : expected) {
            assertEquals(new RegionId("exylia", item.value), item.key.id());
            assertEquals(Boolean.class, item.key.type());
            assertEquals(item.defaultValue, item.key.defaultValue());
        }
        assertEquals(15, expected.stream().map(item -> item.key.id()).collect(java.util.stream.Collectors.toSet()).size());
    }

    @Test
    @DisplayName("Snapshot order is priority descending with identifier as the stable tie-breaker")
    void snapshotOrderIsCanonical() {
        RegionSnapshot low = region("test:z-low", 1, PolicySet.empty());
        RegionSnapshot tieZ = region("test:z", 10, PolicySet.empty());
        RegionSnapshot tieA = region("test:a", 10, PolicySet.empty());
        List<RegionSnapshot> ordered = java.util.stream.Stream.of(low, tieZ, tieA)
                .sorted(RegionSnapshot.ORDER).toList();
        assertEquals(List.of(tieA, tieZ, low), ordered);
    }

    @Test
    @DisplayName("Policy resolution records explicit default-valued declarations as a source")
    void policyResolutionPreservesExplicitSource() {
        PolicyKey<Boolean> key = key("entry", Boolean.class, true);
        RegionSnapshot source = region("test:source", 5, PolicySet.of(key, true));
        PolicyResolution<Boolean> resolution = new PolicyResolution<>(key, true, source);
        assertTrue(resolution.explicit());
        assertSame(source, resolution.source());
        assertEquals(true, resolution.value());

        PolicyResolution<Boolean> fallback = new PolicyResolution<>(key, key.defaultValue(), null);
        assertFalse(fallback.explicit());
    }

    private static RegionSnapshot region(String id, int priority, PolicySet policies) {
        return new RegionSnapshot(RegionId.parse(id), "owner", WORLD,
                new Cuboid(0, 0, 0, 10, 10, 10), priority, policies);
    }

    private static <T> PolicyKey<T> key(String value, Class<T> type, T defaultValue) {
        return PolicyKey.of(new RegionId("test", value), type, defaultValue);
    }
}
