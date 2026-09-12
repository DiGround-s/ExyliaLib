package net.exylia.lib.economy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrencyInfoTest {

    private final CurrencyInfo dollars = new CurrencyInfo("vault", "Dollar", "Dollars", "$", "GOLD_INGOT", 2,
            "%symbol%%amount%", "%symbol%%amount%");
    private final CurrencyInfo coins = new CurrencyInfo("coins", "Coin", "Coins", "⛃", "SUNFLOWER", 0,
            "%amount% %symbol%", "%amount%%symbol%");

    @Test
    @DisplayName("an amount is grouped, scaled and wrapped in the format")
    void formats() {
        assertEquals("$1,250.00", dollars.format(new BigDecimal("1250")));
        assertEquals("$0.50", dollars.format(new BigDecimal("0.5")));
        assertEquals("-$3.00", dollars.format(new BigDecimal("-3")));
        assertEquals("1,000,000 ⛃", coins.format(new BigDecimal("1000000.99")));
    }

    @Test
    @DisplayName("scaling rounds down, never up: money nobody paid is never created")
    void scalesDown() {
        assertEquals(new BigDecimal("2"), coins.scale(new BigDecimal("2.99")));
        assertEquals(new BigDecimal("2.99"), dollars.scale(new BigDecimal("2.999")));
    }

    @Test
    @DisplayName("the compact form shortens thousands and keeps small amounts whole")
    void compact() {
        assertTrue(coins.formatCompact(new BigDecimal("1500")).startsWith("1.5"));
        assertEquals("999⛃", coins.formatCompact(new BigDecimal("999")));
    }

    @Test
    @DisplayName("the name follows the amount")
    void names() {
        CurrencyInfo tokens = new CurrencyInfo("tokens", "Token", "Tokens", "", "", 0, "%amount% %name%", "");
        assertEquals("1 Token", tokens.format(BigDecimal.ONE));
        assertEquals("3 Tokens", tokens.format(new BigDecimal("3")));
    }

    @Test
    @DisplayName("an overlay only replaces what it says")
    void overlay() {
        CurrencyInfo over = new CurrencyInfo("vault", "Buck", "", "", "", -1, "", "");
        CurrencyInfo merged = dollars.overlaid(over);
        assertEquals("Buck", merged.name());
        assertEquals("Bucks", merged.namePlural());
        assertEquals("$", merged.symbol());
        assertEquals(2, merged.decimals());
        assertEquals("$5.00", merged.format(new BigDecimal("5")));
    }

    @Test
    @DisplayName("a currency with nothing but an id still reads as one")
    void bareId() {
        CurrencyInfo bare = CurrencyInfo.of("mystery_coins", "", "", "");
        assertEquals("Mystery coins", bare.name());
        assertEquals("Mystery coins", bare.namePlural());
        assertEquals("12.00", bare.format(new BigDecimal("12")));
    }
}
