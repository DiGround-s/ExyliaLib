package net.exylia.lib.leaderboard;

import org.bukkit.Material;
import org.bukkit.Statistic;
import org.junit.jupiter.api.Test;

import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Placeholder values read as numbers, statistics refuse the wrong sub-type, and the formats. */
class LeaderboardSourceTest {

    @Test
    void placeholdersReadAsNumbers() {
        assertEquals(OptionalDouble.of(1234), LeaderboardSource.number("%x%", "&a1,234"));
        assertEquals(OptionalDouble.of(12.5), LeaderboardSource.number("%x%", "<green>12.5</green>"));
        assertEquals(OptionalDouble.of(1500), LeaderboardSource.number("%x%", "1.5K"));
        assertTrue(LeaderboardSource.number("%x%", "%x%").isEmpty());
        assertTrue(LeaderboardSource.number("%x%", "none").isEmpty());
    }

    @Test
    void statisticsNeedTheirSubType() {
        assertThrows(IllegalArgumentException.class, () -> LeaderboardSource.statistic(Statistic.MINE_BLOCK));
        assertThrows(IllegalArgumentException.class, () -> LeaderboardSource.statistic(Statistic.PLAYER_KILLS, Material.STONE));
        assertTrue(LeaderboardSource.statistic(Statistic.MINE_BLOCK, Material.DIAMOND_ORE).isPerServer());
        assertFalse(LeaderboardSource.balance().isPerServer());
        assertTrue(LeaderboardSource.balance().perServer().isPerServer());
        assertFalse(LeaderboardSource.placeholder("%x%").seedsOffline());
    }

    @Test
    void valuesFormatForPlayers() {
        assertEquals("12,345", LeaderboardView.format(12345, null));
        assertEquals("1,234.50", LeaderboardView.format(1234.5, ""));
        assertEquals("1,234.5", LeaderboardView.format(1234.5, "#,##0.##"));
        assertEquals("1h 1m", LeaderboardView.format(3_660_000, "duration"));
    }
}
