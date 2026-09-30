package net.exylia.lib.util.reward;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** {@link Rewards#scaled}: a share of a pot pays that share of its money and experience, nothing else changes. */
class RewardScaleTest {

    @Test
    @DisplayName("money and experience are multiplied and rounded down; the rest is kept")
    void scaled() {
        RewardEntry money = RewardEntry.economy("100").currency("gems").build();
        RewardEntry exp = RewardEntry.experience(10).build();
        RewardEntry ranged = RewardEntry.economy("0").amountBetween(10, 20).build();
        RewardEntry command = RewardEntry.command("say hi").build();
        List<RewardEntry> pot = List.of(money, exp, ranged, command);

        List<RewardEntry> quarter = Rewards.scaled(pot, 0.25);
        assertEquals(4, quarter.size());
        assertEquals("25", quarter.get(0).value());
        assertEquals("gems", quarter.get(0).currency());
        assertEquals("2", quarter.get(1).value());
        assertEquals(2, quarter.get(2).minAmount());
        assertEquals(5, quarter.get(2).maxAmount());
        assertSame(command, quarter.get(3));

        assertEquals("33.33", Rewards.scaled(List.of(money), 1 / 3.0).getFirst().value());
        assertEquals(List.of(command), Rewards.scaled(pot, 0), "nothing of a pot is nothing");
        assertSame(pot, Rewards.scaled(pot, 1));
    }
}
