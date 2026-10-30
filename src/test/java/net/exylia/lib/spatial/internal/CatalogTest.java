package net.exylia.lib.spatial.internal;

import net.exylia.lib.cosmetic.Price;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The catalogue's arithmetic and its confirm-in-place, without a server. */
class CatalogTest {

    private static Price price(String amount, String currency) {
        return new Price(new BigDecimal(amount), currency);
    }

    @Test
    void aBasketTotalsInOneCurrencyOnly() {
        assertEquals(new BigDecimal("750"), Catalogs.total(List.of(price("500", null), price("250", null))).amount());
        assertNull(Catalogs.total(List.of(price("500", null), price("3", "gems"))), "mixed currencies have no single total");
        assertNull(Catalogs.total(List.of()));
    }

    @Test
    void ownedFirstKeepsEachHalfInOrder() {
        assertEquals(List.of(2, 4, 1, 3), Catalogs.firstWhere(List.of(1, 2, 3, 4), n -> n % 2 == 0));
    }

    @Test
    void theFilterCyclesAllCategoriesThenFavourites() {
        List<String> categories = List.of("meme", "classic");
        assertEquals("meme", Catalogs.nextFilter("*", categories, true, "*", "fav"));
        assertEquals("fav", Catalogs.nextFilter("classic", categories, true, "*", "fav"));
        assertEquals("*", Catalogs.nextFilter("fav", categories, true, "*", "fav"));
        assertEquals("meme", Catalogs.nextFilter("gone", categories, false, "*", null), "a vanished filter restarts the cycle");
        assertEquals("meme", Catalogs.nextFilter("classic", categories, false, "*", null), "no 'all' step");
        assertEquals(1, Catalogs.pageOf(7, 6));
        assertEquals(0, Catalogs.pageOf(-1, 6));
    }

    @Test
    void aConfirmArmsThenConfirmsTheSameKeyOnly() {
        Confirm confirm = new Confirm();
        assertFalse(confirm.confirm("save:a,", 0), "the first click asks");
        assertTrue(confirm.confirming("save", 10), "the save button reads CONFIRM");
        assertFalse(confirm.confirm("save:b,", 20), "a new subject asks again");
        assertTrue(confirm.confirm("save:b,", 30), "the second click on it confirms");
        assertFalse(confirm.confirming("save", 40), "and disarms");
    }

    @Test
    void aConfirmRunsOutAndIsDroppedOnDemand() {
        Confirm confirm = new Confirm();
        confirm.confirm("buy", 0);
        assertFalse(confirm.expired(Confirm.WINDOW_MILLIS - 1));
        assertTrue(confirm.expired(Confirm.WINDOW_MILLIS), "waiting disarms it, once");
        assertFalse(confirm.expired(Confirm.WINDOW_MILLIS + 1));
        assertFalse(confirm.confirm("buy", Confirm.WINDOW_MILLIS + 2), "a late second click asks again");
        assertTrue(confirm.disarm());
        assertFalse(confirm.confirming("buy", Confirm.WINDOW_MILLIS + 3));
        assertFalse(confirm.confirming("bu", 0) && confirm.confirming("buyer", 0));
    }

    @Test
    void aButtonColumnSitsInTheMiddleAndNeverSpreads() {
        double[] two = Geometry.stack(0.1, 1.0, 2);
        assertArrayEquals(new double[]{0.22, -0.02}, two, 1e-9);
        assertEquals(0.24 * 0.82, Geometry.stackHeight(1.0, 2), 1e-9);
        double[] six = Geometry.stack(0, 0.85, 6);
        assertEquals(six[0], -six[5], 1e-9);
        assertTrue(Geometry.stackHeight(0.85, 6) < 0.85 / 6, "a gap between plates");
        assertEquals(0, Geometry.stack(0, 1, 0).length);
    }
}
