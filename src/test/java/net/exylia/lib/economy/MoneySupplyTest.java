package net.exylia.lib.economy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class MoneySupplyTest {

    @Test
    void reportsReplaceAndForget() {
        assertNull(MoneySupply.latest("test-coins"));
        MoneySupply.report("Test-Coins", BigDecimal.TEN, 3);
        MoneySupply.report("test-coins", new BigDecimal("25"), -1);
        MoneySupply.Snapshot latest = MoneySupply.latest("TEST-COINS");
        assertNotNull(latest);
        assertEquals(new BigDecimal("25"), latest.total());
        assertEquals(0, latest.holders());
        MoneySupply.forget("test-coins");
        assertNull(MoneySupply.latest("test-coins"));
    }
}
