package net.exylia.lib.skull;

import net.exylia.lib.skull.internal.PendingHeads;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PendingHeadsTest {

    @Test
    @DisplayName("An inner collector hands what it caught to the outer one")
    void nests() {
        List<SkullHandle> outer = new ArrayList<>();
        List<SkullHandle> inner = new ArrayList<>();
        String result = PendingHeads.collect(() -> PendingHeads.collect(() -> {
            inner.add(null);
            return "drawn";
        }, inner), outer);
        assertEquals("drawn", result);
        assertEquals(1, inner.size());
        assertEquals(1, outer.size());
    }
}
