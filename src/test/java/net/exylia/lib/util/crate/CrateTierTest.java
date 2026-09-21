package net.exylia.lib.util.crate;

import net.exylia.lib.util.crate.internal.TierTable;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A roll that does not follow the weights is a crate advertising odds it does not honour. */
class CrateTierTest {

    private static TierTable tiers(double... chances) {
        Map<String, CrateTier> map = new LinkedHashMap<>();
        for (int i = 0; i < chances.length; i++) {
            map.put("t" + i, new CrateTier("T" + i, "{muted}", chances[i], i));
        }
        return new TierTable(map);
    }

    @Test
    void theDefaultsAreTheFourKillEffectShipsWith() {
        TierTable shipped = new TierTable(CrateTier.defaults());
        assertEquals(List.of("common", "rare", "epic", "legendary"), shipped.ids());
        assertEquals(0.03, shipped.shareOf("legendary", id -> true), 1e-9);
    }

    @Test
    void aRollFollowsTheWeights() {
        TierTable tiers = tiers(60, 25, 12, 3);
        assertEquals("t0", tiers.roll(0.0, id -> true));
        assertEquals("t0", tiers.roll(0.599, id -> true));
        assertEquals("t1", tiers.roll(0.60, id -> true));
        assertEquals("t1", tiers.roll(0.849, id -> true));
        assertEquals("t2", tiers.roll(0.85, id -> true));
        assertEquals("t3", tiers.roll(0.97, id -> true));
        assertEquals("t3", tiers.roll(1.0, id -> true), "the top of the range must land on something");
    }

    @Test
    void aRarityWithNothingInItIsSkippedAndItsOddsReSpread() {
        TierTable tiers = tiers(50, 50);
        Set<String> pool = Set.of("t1");
        assertEquals("t1", tiers.roll(0.0, pool::contains));
        assertEquals("t1", tiers.roll(0.99, pool::contains));
        assertEquals(1.0, tiers.shareOf("t1", pool::contains), 1e-9);
        assertEquals(0.0, tiers.shareOf("t0", pool::contains), 1e-9);
    }

    @Test
    void weightsThatDoNotSumToOneHundredStillGiveHonestOdds() {
        TierTable tiers = tiers(3, 1);
        assertEquals(0.75, tiers.shareOf("t0", id -> true), 1e-9);
        assertEquals(0.25, tiers.shareOf("t1", id -> true), 1e-9);
    }

    @Test
    void anUnknownRarityFallsBackToTheFirstOne() {
        TierTable tiers = tiers(1, 1);
        assertEquals("t0", tiers.resolveId("no_such_rarity"));
        assertEquals("t0", tiers.resolveId(null));
        assertEquals("t1", tiers.resolveId("  T1  "));
    }

    @Test
    void aServerWithNoRaritiesRollsNothingRatherThanThrowing() {
        TierTable none = new TierTable(Map.of());
        assertTrue(none.isEmpty());
        assertNull(none.roll(0.5, id -> true));
        assertEquals("", none.resolveId("common"));
        assertNotNull(none.get("common"));
    }

    @Test
    void everyWeightBeingZeroRollsNothing() {
        assertNull(tiers(0, 0).roll(0.5, id -> true));
    }
}
