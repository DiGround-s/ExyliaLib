package net.exylia.lib.input.internal;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrocksTest {

    @Test
    void aFloodgateUuidIsBedrock() {
        assertTrue(Bedrocks.isBedrock(UUID.fromString("00000000-0000-0000-0009-01f64f65c7c3")));
    }

    @Test
    void aJavaUuidIsNot() {
        assertFalse(Bedrocks.isBedrock(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")));
    }
}
