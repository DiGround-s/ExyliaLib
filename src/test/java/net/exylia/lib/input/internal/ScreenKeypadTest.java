package net.exylia.lib.input.internal;

import net.exylia.lib.format.Amounts;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScreenKeypadTest {

    private static String type(String... keys) {
        String typed = "";
        for (String key : keys) typed = ScreenKeypad.press(typed, key);
        return typed;
    }

    @Test
    void typesShortAmountsTheParserReads() {
        assertEquals("1.7M", type("1", ".", "7", "M"));
        assertEquals(new BigDecimal("1700000"), Amounts.parse(type("1", ".", "7", "M")).orElseThrow().stripTrailingZeros().setScale(0));
        assertEquals("10K", type("1", "0", "K"));
        assertEquals("10M", type("1", "0", "K", "M"), "a second suffix replaces the first");
    }

    @Test
    void refusesKeysThatWouldMakeItUnreadable() {
        assertEquals("", type("K"));
        assertEquals("1.5", type("1", ".", ".", "5"));
        assertEquals("2K", type("2", "K", "3"), "no digit after a suffix");
        assertEquals("0.", type("."));
        assertEquals("5", type("0", "5"));
        assertEquals("1", type("1", "2", "⌫"));
        assertEquals("", type("1", "2", "C"));
    }

    @Test
    void compactStaysExact() {
        assertEquals("1.7M", ScreenKeypad.compact(new BigDecimal("1700000")));
        assertEquals("11K", ScreenKeypad.compact(new BigDecimal("11000")));
        assertEquals("1234.5", ScreenKeypad.compact(new BigDecimal("1234.5")));
        assertEquals("1234567", ScreenKeypad.compact(new BigDecimal("1234567")));
        assertEquals("42", ScreenKeypad.compact(new BigDecimal("42")));
        assertEquals("1,700,000", ScreenKeypad.full(new BigDecimal("1700000")));
        assertEquals("1,234.5", ScreenKeypad.full(new BigDecimal("1234.5")));
    }
}
