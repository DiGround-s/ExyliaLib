package net.exylia.lib.util.upgrade;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Endless tracks: geometric prices of any size, bulk purchases and diminishing levels. */
class CurveTest {

    private static final Curve STRENGTH = new Curve(new BigDecimal("50"), new BigDecimal("1.15"), 100, 0.5);

    @Test
    void pricesGrowGeometrically() {
        assertEquals(0, new BigDecimal("50").compareTo(STRENGTH.price(0)));
        assertEquals(0, new BigDecimal("57.5").compareTo(STRENGTH.price(1)));
        // three levels together are the three prices added
        BigDecimal three = STRENGTH.price(4).add(STRENGTH.price(5)).add(STRENGTH.price(6));
        assertEquals(0, three.round(Curve.MC).compareTo(STRENGTH.price(4, 3).round(Curve.MC)));
        assertEquals(BigDecimal.ZERO, STRENGTH.price(4, 0));
    }

    @Test
    void pricesPastADouble() {
        Curve rebirth = new Curve(new BigDecimal("1000000"), new BigDecimal("3"));
        BigDecimal late = rebirth.price(1000);
        assertTrue(Curve.log10(late) > 400, "3^1000 × 1e6 is about 1e483");
        assertEquals(1, rebirth.affordable(1000, late));
        assertEquals(2, rebirth.affordable(1000, rebirth.price(1000, 2)));
    }

    @Test
    void affordableIsExact() {
        for (int owned : new int[]{0, 7, 250}) {
            for (int want : new int[]{1, 2, 10, 37}) {
                BigDecimal exact = STRENGTH.price(owned, want);
                assertEquals(want, STRENGTH.affordable(owned, exact), owned + "+" + want);
                assertEquals(want - 1, STRENGTH.affordable(owned, exact.multiply(new BigDecimal("0.999999999999"))), owned + "+" + want + " short");
            }
        }
        assertEquals(0, STRENGTH.affordable(0, new BigDecimal("49")));
        Curve flat = new Curve(new BigDecimal("5"), BigDecimal.ONE);
        assertEquals(20, flat.affordable(3, new BigDecimal("100")));
    }

    @Test
    void levelsDiminishPastTheCap() {
        assertEquals(50, STRENGTH.levels(50));
        assertEquals(100, STRENGTH.levels(100));
        assertEquals(110, STRENGTH.levels(200), 1e-9);
        assertTrue(STRENGTH.levels(201) > STRENGTH.levels(200), "never flat");
        assertEquals(0, STRENGTH.levels(-3));
    }

    @Test
    void refusesNonsense() {
        assertThrows(IllegalArgumentException.class, () -> new Curve(BigDecimal.ZERO, BigDecimal.TEN));
        assertThrows(IllegalArgumentException.class, () -> new Curve(BigDecimal.ONE, new BigDecimal("0.9")));
        assertThrows(IllegalArgumentException.class, () -> new Curve(BigDecimal.ONE, BigDecimal.TEN, 5, 0));
    }
}
