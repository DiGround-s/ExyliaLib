package net.exylia.lib.region;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Ids order exactly as their {@code namespace:value} strings do. */
class RegionIdOrderTest {

    @Test
    void ordersAsTheWrittenForm() {
        List<RegionId> ids = List.of(
                new RegionId("a", "x"), new RegionId("a-b", "x"), new RegionId("a.b", "x"),
                new RegionId("a0", "x"), new RegionId("a_b", "x"), new RegionId("ab", "x"),
                new RegionId("a", "x-1"), new RegionId("a", "x"), new RegionId("a", "xy"),
                new RegionId("zone", "arena_2"), new RegionId("zone", "arena_10"));
        for (RegionId left : ids) {
            for (RegionId right : ids) {
                assertEquals(Integer.signum(left.toString().compareTo(right.toString())),
                        Integer.signum(left.compareTo(right)), left + " vs " + right);
            }
        }
    }
}
