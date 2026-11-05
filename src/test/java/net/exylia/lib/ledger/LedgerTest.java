package net.exylia.lib.ledger;

import net.exylia.lib.ledger.internal.LedgerPlayerRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Records add up per hour, source and player in memory; amounts keep two decimals; tops sum across rows. */
class LedgerTest {

    private static final long HOUR = 3_600_000L;
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void amountsKeepTwoDecimals() {
        assertEquals(1050, Ledger.cents(new BigDecimal("10.5")));
        assertEquals(1, Ledger.cents(new BigDecimal("0.005")));
        assertEquals(0, Ledger.cents(new BigDecimal("0.004")));
        assertEquals(new BigDecimal("10.50"), Ledger.amount(1050));
    }

    @Test
    void hoursAndDaysAreUtcFromTheEpoch() {
        assertEquals(0, Ledger.hourOf(HOUR - 1));
        assertEquals(1, Ledger.hourOf(HOUR));
        assertEquals(-1, Ledger.hourOf(-1));
        assertEquals(1, Ledger.dayOf(24 * HOUR));
        assertEquals(-1, Ledger.dayOf(-1));
    }

    @Test
    void recordsAddUpPerHourAndSource() {
        AtomicLong now = new AtomicLong(10 * HOUR);
        Ledger ledger = new Ledger(now::get);
        ledger.add("Jobs", "Jobs", Ledger.Flow.CREATED, ALICE, "vault", new BigDecimal("1.25"));
        ledger.add("Jobs", "jobs", Ledger.Flow.CREATED, ALICE, "vault", new BigDecimal("2"));
        ledger.add("Jobs", "jobs", Ledger.Flow.SUNK, ALICE, "vault", new BigDecimal("3"));
        ledger.add("Jobs", "jobs", Ledger.Flow.CREATED, null, "vault", BigDecimal.ONE);
        now.addAndGet(HOUR);
        ledger.add("Jobs", "jobs", Ledger.Flow.CREATED, ALICE, "vault", BigDecimal.ONE);
        ledger.add("Jobs", "jobs", Ledger.Flow.CREATED, BOB, "vault", new BigDecimal("0.001"));

        assertEquals(3, ledger.sources.size());
        assertArrayEquals(new long[]{425, 3}, ledger.sources.entrySet().stream()
                .filter(entry -> entry.getKey().toString().contains("hour=10")
                        && entry.getKey().toString().contains("CREATED"))
                .findFirst().orElseThrow().getValue());
        assertEquals(3, ledger.players.size(), "the record with nobody adds no player row; a dust amount nothing");
    }

    @Test
    void playerRowsArePerPlugin() {
        Ledger ledger = new Ledger(() -> 10 * HOUR);
        ledger.add("Jobs", "jobs", Ledger.Flow.CREATED, ALICE, "vault", BigDecimal.ONE);
        ledger.add("Jobs", "orders", Ledger.Flow.CREATED, ALICE, "vault", BigDecimal.ONE);
        ledger.add("Quests", "quests", Ledger.Flow.CREATED, ALICE, "vault", BigDecimal.ONE);
        assertEquals(2, ledger.players.size(), "one row per plugin, sources of one plugin together");
        assertTrue(ledger.players.keySet().stream().anyMatch(key -> key.plugin().equals("Quests")));
    }

    @Test
    void topSumsEveryRowOfAPlayer() {
        List<Ledger.PlayerTotal> top = Ledger.top(List.of(
                row(ALICE, 100), row(BOB, 150), row(ALICE, 100), new LedgerPlayerRow("x", 1, 0, "nope", "vault", "CREATED", 999, "Jobs")
        ), 5);
        assertEquals(2, top.size());
        assertEquals(new Ledger.PlayerTotal(ALICE, new BigDecimal("2.00")), top.get(0));
        assertEquals(BOB, top.get(1).player());
        assertTrue(Ledger.top(List.of(row(ALICE, 1), row(BOB, 2)), 1).size() == 1);
    }

    @Test
    void playerSumOnlyCountsHoursInTheSpan() {
        List<LedgerPlayerRow> rows = List.of(
                new LedgerPlayerRow("a", 9, 0, ALICE.toString(), "vault", "CREATED", 100, "Jobs"),
                new LedgerPlayerRow("b", 10, 0, ALICE.toString(), "vault", "CREATED", 250, "Jobs"),
                new LedgerPlayerRow("c", 12, 0, ALICE.toString(), "vault", "CREATED", 7, "Jobs"),
                new LedgerPlayerRow("d", 13, 0, ALICE.toString(), "vault", "CREATED", 1000, "Jobs"));
        assertEquals(new BigDecimal("2.57"), Ledger.sum(rows, 10, 13), "from inclusive, to exclusive");
        assertEquals(new BigDecimal("0.00"), Ledger.sum(List.of(), 0, 100));
    }

    private static LedgerPlayerRow row(UUID player, long cents) {
        return new LedgerPlayerRow("id", 1, 0, player.toString(), "vault", "CREATED", cents, "Jobs");
    }
}
