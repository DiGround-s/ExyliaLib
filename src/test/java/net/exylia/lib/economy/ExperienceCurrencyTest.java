package net.exylia.lib.economy;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.economy.internal.PlayerThreadBalances;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.reward.PendingRewards;
import net.exylia.lib.util.reward.RewardEntry;
import net.exylia.lib.util.reward.RewardType;
import net.exylia.lib.util.reward.Rewards;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A currency that lives on the player: touched only on their thread, and never
 * lost for a player who is not there.
 */
class ExperienceCurrencyTest {

    private Plugin plugin;
    private FakePlayer steve;
    private final List<RewardEntry> kept = new ArrayList<>();

    private final PendingRewards store = new PendingRewards() {
        @Override
        public void keep(@NotNull UUID player, @NotNull List<RewardEntry> owed) {
            kept.addAll(owed);
        }

        @Override
        public @NotNull List<RewardEntry> claim(@NotNull UUID player) {
            return List.of();
        }
    };

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        plugin = FakeServer.newPlugin("Survival");
        steve = new FakePlayer("Steve");
        FakeServer.online(steve.player());
        FakeServer.setPrimaryThread(true);
    }

    @AfterEach
    void tearDown() {
        FakeServer.setPrimaryThread(true);
        Rewards.releaseAll();
        Tasks.releaseAll();
        FakeServer.reset();
    }

    @Test
    @DisplayName("a player who is not here is owed the experience, not denied it")
    void absentPlayerIsOwed() {
        ExperienceCurrency points = ExperienceCurrency.points(Rewards.of(plugin).pending(store));

        EconomyResponse response = points.deposit(UUID.randomUUID(), new BigDecimal("30"));

        assertTrue(response.isSuccess());
        assertEquals(1, kept.size());
        assertEquals(RewardType.EXPERIENCE, kept.get(0).type());
        assertEquals("30", kept.get(0).value());
    }

    @Test
    @DisplayName("with nowhere to keep it, the deposit says it failed")
    void noStoreFails() {
        ExperienceCurrency points = ExperienceCurrency.points(Rewards.of(plugin));

        assertFalse(points.deposit(UUID.randomUUID(), BigDecimal.TEN).isSuccess());
    }

    @Test
    @DisplayName("off the player's thread a deposit waits for it, and a withdraw is refused")
    void offThreadWaitsForThePlayer() {
        ExperienceCurrency points = ExperienceCurrency.points(Rewards.of(plugin).pending(store));
        FakeServer.setPrimaryThread(false);

        assertTrue(points.deposit(steve.player().getUniqueId(), BigDecimal.valueOf(5)).isSuccess());
        assertEquals(0, steve.experience(), "nothing touched the player off their thread");
        assertFalse(points.withdraw(steve.player().getUniqueId(), BigDecimal.ONE).isSuccess());

        FakeServer.tick(1);
        assertEquals(5, steve.experience());
        assertTrue(kept.isEmpty());
    }

    @Test
    @DisplayName("a balance asked off the player's thread answers the last count and takes one fresh count")
    void balanceOffThreadIsCached() {
        AtomicInteger reads = new AtomicInteger();
        PlayerThreadBalances balances = new PlayerThreadBalances(Tasks.of(plugin),
                player -> BigDecimal.valueOf(reads.incrementAndGet()));
        UUID id = steve.player().getUniqueId();
        FakeServer.setPrimaryThread(false);

        assertEquals(BigDecimal.ZERO, balances.balance(id));
        assertEquals(BigDecimal.ZERO, balances.balance(id));
        assertEquals(0, reads.get(), "the player was not read off their thread");

        FakeServer.tick(1);
        assertEquals(1, reads.get(), "two asks, one refresh");
        assertEquals(BigDecimal.ONE, balances.balance(id));
    }
}
