package net.exylia.lib.web;

import net.exylia.lib.util.reward.RewardEntry;
import net.exylia.lib.util.reward.RewardType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebRewardTest {

    @Test
    void roundTripKeepsIdAndFields() {
        RewardEntry entry = RewardEntry.of(RewardType.COMMAND).id("a").command("say hi").chance(50).build();
        List<RewardEntry> back = WebReward.toEntries(WebReward.fromEntries(List.of(entry)), List.of(entry));
        assertEquals("a", back.get(0).id());
        assertEquals("say hi", back.get(0).command());
        assertEquals(50, back.get(0).chance());
    }

    @Test
    void newRewardGetsAnId() {
        WebReward fresh = new WebReward("", RewardType.COMMAND, "say hi", "", "", "", 100, "", "");
        assertTrue(!WebReward.toEntries(List.of(fresh), List.of()).get(0).id().isBlank());
    }

    @Test
    void problemsArePinnedToTheField() {
        List<WebReward> bad = List.of(
                new WebReward("", RewardType.COMMAND, "", "", "", "", 100, "", ""),
                new WebReward("x", RewardType.ECONOMY, "", "abc", "", "", 120, "", ""),
                new WebReward("y", RewardType.ITEM, "", "", "", "", 100, "", ""));
        List<String> paths = WebReward.problems("r", bad, List.of()).stream().map(Problem::path).toList();
        assertEquals(List.of("r.0.command", "r.1.value", "r.1.chance", "r.2.type"), paths);
    }
}
