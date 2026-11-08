package net.exylia.lib.util.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PreviewsTest {

    @Test
    void aRangeOfOneValueReadsAsThatValue() {
        assertEquals("8 XP", xp(8, 8).preview());
    }

    @Test
    void aRealRangeKeepsBothEnds() {
        assertEquals("2-8 XP", xp(2, 8).preview());
    }

    private static RewardEntry xp(int min, int max) {
        return RewardEntry.of(RewardType.EXPERIENCE).amountBetween(min, max).build();
    }
}
