package net.exylia.lib.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PluralsTest {

    @Test
    void twoFormsAreOneAndOther() {
        assertEquals("layer", Plurals.form(1, "layer|layers"));
        assertEquals("layers", Plurals.form(0, "layer|layers"));
        assertEquals("layers", Plurals.form(2, "layer|layers"));
        assertEquals("layers", Plurals.form(21, "layer|layers"));
    }

    @Test
    void threeFormsCountAsRussianDoes() {
        String forms = "слой|слоя|слоёв";
        long[] one = {1, 21, 101};
        long[] few = {2, 3, 4, 22, 104};
        long[] many = {0, 5, 11, 12, 14, 19, 20, 25, 111, 112};
        for (long n : one) assertEquals("слой", Plurals.form(n, forms), String.valueOf(n));
        for (long n : few) assertEquals("слоя", Plurals.form(n, forms), String.valueOf(n));
        for (long n : many) assertEquals("слоёв", Plurals.form(n, forms), String.valueOf(n));
    }

    @Test
    void oneFormIsEveryCounts() {
        assertEquals("katman", Plurals.form(1, "katman"));
        assertEquals("katman", Plurals.form(7, "katman"));
    }

    @Test
    void aLineGetsItsCountAndEveryWord() {
        String line = "{info}%layers% {letters_black}%layers:layer|layers% · {info}%uses% %uses:use|uses%";
        String once = Plurals.apply(Plurals.apply(line, "layers", 1), "uses", 1);
        assertEquals("{info}1 {letters_black}layer · {info}1 use", once);
        assertEquals("{info}3 {letters_black}layers · {info}0 uses", Plurals.apply(Plurals.apply(line, "layers", 3), "uses", 0));
        assertEquals("{info}2 layers", Plurals.apply("{info}%layers% layers", "layers", 2), "an older line keeps working");
        assertEquals("%other:a|b% 1", Plurals.apply("%other:a|b% %layers%", "layers", 1), "another name is left alone");
        assertEquals("", Plurals.apply(null, "layers", 1));
    }
}
