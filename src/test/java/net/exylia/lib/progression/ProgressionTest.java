package net.exylia.lib.progression;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prestige layers, offline income, prices as time, the pace engine and the funnel. */
class ProgressionTest {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Test
    void rebirthPricesAddUpAndCapAtWhatCoinsPay() {
        // the n-th rebirth costs 100 * n
        assertEquals(0, BigDecimal.valueOf(500).compareTo(Prestige.price(n -> HUNDRED.multiply(BigDecimal.valueOf(n)), 1, 2)));
        assertEquals(2, Prestige.affordable(n -> HUNDRED, 0, BigDecimal.valueOf(250), 5));
        assertEquals(1, Prestige.affordable(n -> HUNDRED, 0, BigDecimal.valueOf(250), 1));
        assertEquals(0, Prestige.affordable(n -> HUNDRED, 0, BigDecimal.valueOf(99), 5));
        assertEquals(1 + 2 + 3, Prestige.reward(n -> n, 0, 3));
    }

    @Test
    void milestonesAreReachedOnce() {
        assertTrue(Prestige.reached(4, 5, 5));
        assertTrue(Prestige.reached(3, 9, 5));
        assertFalse(Prestige.reached(5, 6, 5));
        assertFalse(Prestige.reached(0, 9, 0));
    }

    @Test
    void layersResetWhatIsBelowThem() {
        List<String> order = List.of("ascension", "transcendence", "eternity");
        assertNull(Prestige.below(order, "ascension"));
        assertEquals("ascension", Prestige.below(order, "transcendence"));
        assertEquals(List.of("ascension", "transcendence"), Prestige.resets(order, "eternity"));
        assertEquals(List.of(), Prestige.resets(order, "ascension"));
    }

    @Test
    void layerNumbers() {
        Prestige.Layer layer = new Prestige.Layer(25, 5, 3, 5, 1.0);
        assertEquals(25, layer.needs(0));
        assertEquals(35, layer.needs(2));
        assertFalse(layer.ready(24, 0));
        assertTrue(layer.ready(25, 0));
        // 12 past the need: two more every five
        assertEquals(5, layer.reward(37, 0));
        assertEquals(3.0, layer.multiplier(2));
        assertEquals(1, new Prestige.Layer(0, -3, 0, 0, 0).needs(4));
    }

    @Test
    void offlinePaysAShareUpToTheCap() {
        long hour = 3_600_000L;
        Offline away = Offline.of(1, 1 + 10 * hour, 8 * hour, 0.3);
        assertEquals(8 * hour, away.awayMillis());
        assertEquals(8 * 60 * 0.3, away.minutes(), 1e-9);
        assertEquals(8, away.hours());
        Offline short_ = Offline.of(1000, 1000 + 95 * 60_000L, 8 * hour, 1);
        assertEquals(1, short_.hours());
        assertEquals(35, short_.minutesPart());
        assertNull(Offline.of(0, 10 * hour, 8 * hour, 0.3));
        assertNull(Offline.of(5 * hour, 4 * hour, 8 * hour, 0.3));
        assertNull(Offline.of(1, 1 + 60_000L, 8 * hour, 0.3)); // under a minute of income
    }

    @Test
    void pricesReadAsTime() {
        assertEquals(0, Waits.seconds(HUNDRED, BigDecimal.TEN, BigDecimal.ONE));
        assertEquals(-1, Waits.seconds(BigDecimal.ZERO, BigDecimal.TEN, BigDecimal.ZERO));
        assertEquals(45, Waits.seconds(BigDecimal.TEN, BigDecimal.valueOf(100), BigDecimal.valueOf(2)), 1e-9);
        assertEquals(45, Waits.secondsPerMinute(BigDecimal.TEN, BigDecimal.valueOf(100), BigDecimal.valueOf(120)), 1e-9);
        assertNull(Waits.text(0));
        assertNull(Waits.text(11 * 365 * 86400.0));
        assertTrue(Waits.text(12).contains("12"));
        assertEquals(0.25, Waits.progress(BigDecimal.valueOf(25), HUNDRED), 1e-9);
        assertEquals(1, Waits.progress(BigDecimal.TEN, BigDecimal.ZERO));
        assertEquals(0, BigDecimal.valueOf(500).compareTo(Waits.minutes(BigDecimal.ONE, HUNDRED, 5)));
        assertEquals(0, BigDecimal.valueOf(1001).compareTo(Waits.minutes(new BigDecimal("200.25"), HUNDRED, 5)));
    }

    record Option(String name, double price, double rateAfter) {
    }

    @Test
    void pacePicksTheQuickestPaybackThatHelps() {
        List<Option> options = List.of(
                new Option("too dear", 1000, 100),
                new Option("no gain", 10, 1),
                new Option("slow payback", 50, 1.5),
                new Option("quick payback", 20, 2));
        Option pick = Pacing.pick(0, 1, 1000, options, Option::price, Option::rateAfter);
        assertEquals("quick payback", pick.name());
        assertNull(Pacing.pick(0, 1, 1000, List.of(new Option("no gain", 10, 1)), Option::price, Option::rateAfter));
    }

    @Test
    void paceRanges() {
        assertEquals(2.0, Pacing.median(List.of(3.0, 1.0, 2.0)));
        assertEquals(2.5, Pacing.median(List.of(4.0, 1.0, 2.0, 3.0)));
        assertTrue(Double.isNaN(Pacing.median(List.of())));
        assertNull(Pacing.outside("A rebirth every", 60, 40, 120, "min"));
        assertTrue(Pacing.outside("A rebirth every", 130, 40, 120, "min").endsWith("wanted 40-120"));
        assertTrue(Pacing.outside("x", Double.NaN, 0, 1, "s") != null);
    }

    record Player(long joined, long seen, boolean sold, boolean backDay, String zone) {
    }

    @Test
    void funnelCountsStepsReturnsAndWhereTheyLeft() {
        long day = Funnel.DAY;
        long now = 30 * day;
        List<Player> players = List.of(
                new Player(now - 10 * day, now - 9 * day, true, true, "forest"),
                new Player(now - 10 * day, now - 8 * day, true, false, "forest"),
                new Player(now - 10 * day, now - 9 * day, false, false, "meadow"),
                new Player(now - 1000, now, true, false, "desert"));
        Funnel funnel = Funnel.<Player>builder(Player::joined, Player::seen)
                .step("sold", Player::sold)
                .step("never", p -> false)
                .returned("day", day, Player::backDay)
                .left(7 * day, Player::zone)
                .build(players, now);
        assertEquals(4, funnel.players());
        assertEquals(3, funnel.step("sold"));
        assertEquals(0, funnel.step("never"));
        assertEquals(new Funnel.Return(3, 1), funnel.returned("day"));
        assertEquals(33, funnel.returned("day").percent());
        assertEquals(Map.of("forest", 2, "meadow", 1), funnel.leftAt());
        assertEquals("forest", funnel.leftAt().keySet().iterator().next());
        assertEquals(-1, Funnel.percent(0, 0));
    }
}
