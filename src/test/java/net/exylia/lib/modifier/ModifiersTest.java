package net.exylia.lib.modifier;

import net.exylia.lib.FakeServer;
import net.exylia.lib.modifier.internal.ModifierRuntime;
import net.exylia.lib.util.reward.RewardEntry;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModifiersTest {

    private final UUID player = UUID.randomUUID();
    private Plugin boosters;
    private Plugin ranks;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        boosters = FakeServer.newPlugin("Boosters");
        ranks = FakeServer.newPlugin("Ranks");
    }

    @AfterEach
    void tearDown() {
        ModifierRuntime.release("Boosters");
        ModifierRuntime.release("Ranks");
    }

    @Test
    @DisplayName("nothing registered is the identity")
    void identity() {
        assertEquals(1.0, Modifiers.factor(player, Modifiers.MONEY, "mines"));
        assertEquals(new BigDecimal("12.34"), Modifiers.money(player, "mines", new BigDecimal("12.34")));
        assertEquals(7, Modifiers.xp(player, "mines", 7));
        List<RewardEntry> list = List.of(RewardEntry.economy("10").build());
        assertSame(list, Modifiers.rewards(player, "crates", list));
    }

    @Test
    @DisplayName("providers multiply, and a failing or NaN one counts as 1")
    void providersMultiply() {
        Modifiers.register(boosters, (id, type, source, scope) -> Modifiers.MONEY.equals(type) ? 2.0 : 1.0);
        Modifiers.register(ranks, (id, type, source, scope) -> Modifiers.MONEY.equals(type) ? 1.5 : 1.0);
        Modifiers.register(ranks, (id, type, source, scope) -> { throw new IllegalStateException("broken"); });
        Modifiers.register(ranks, (id, type, source, scope) -> Double.NaN);
        assertEquals(3.0, Modifiers.factor(player, "MONEY", "Shop-Sell"), 1e-9);
        assertEquals(1.0, Modifiers.factor(player, "elo", "*"), "a money provider never moves a custom type");
        assertEquals(new BigDecimal("30.00"), Modifiers.money(player, "shop-sell", new BigDecimal("10")));
        assertEquals(75.0, Modifiers.apply(player, Modifiers.MONEY, "x", 25), 1e-9);
    }

    @Test
    @DisplayName("the scope reaches the provider")
    void scope() {
        Modifiers.register(boosters, (id, type, source, scope) -> "mine:gold".equals(scope) ? 2.0 : 1.0);
        assertEquals(2.0, Modifiers.factor(player, Modifiers.DROPS, "mines", "mine:gold"));
        assertEquals(1.0, Modifiers.factor(player, Modifiers.DROPS, "mines"));
        assertEquals(10, Modifiers.xp(player, "mines", "mine:gold", 5));
    }

    @Test
    @DisplayName("a disabled plugin's providers and sources are gone; unregister takes one away")
    void release() {
        ModifierProvider provider = (id, type, source, scope) -> 2.0;
        Modifiers.register(boosters, provider);
        Modifiers.source(boosters, "mines", "IRON_PICKAXE", Modifiers.MONEY);
        Modifiers.unregister(provider);
        assertEquals(1.0, Modifiers.factor(player, Modifiers.MONEY, "mines"));
        Modifiers.register(boosters, provider);
        ModifierRuntime.release("Boosters");
        assertEquals(1.0, Modifiers.factor(player, Modifiers.MONEY, "mines"));
        assertTrue(Modifiers.sources().isEmpty());
    }

    @Test
    @DisplayName("sources keep their order, the first plugin wins an id, listing again replaces")
    void sources() {
        Modifiers.source(boosters, "*", "NETHER_STAR", "money", "xp", "drops");
        Modifiers.source(boosters, "Orbs", "EXPERIENCE_BOTTLE", "XP");
        Modifiers.source(ranks, "orbs", "STONE", "money");
        Modifiers.source(boosters, "orbs", "EXPERIENCE_BOTTLE", "xp", "drops");
        assertEquals(List.of("*", "orbs"), Modifiers.sources().stream().map(ModifierSource::id).toList());
        assertEquals("EXPERIENCE_BOTTLE", Modifiers.source("ORBS").orElseThrow().icon());
        assertTrue(Modifiers.source("orbs").orElseThrow().supports("Drops"));
        assertEquals(List.of("*"), Modifiers.sources("money").stream().map(ModifierSource::id).toList());
        ModifierRuntime.release("Boosters");
        assertEquals("STONE", Modifiers.source("orbs").orElseThrow().icon(), "the other plugin's listing remains");
    }

    @Test
    @DisplayName("reward lists: money in the default currency, experience and items scaled, the rest kept")
    void rewards() {
        Modifiers.register(boosters, (id, type, source, scope) -> switch (type) {
            case Modifiers.MONEY -> 2.0;
            case Modifiers.XP -> 3.0;
            case Modifiers.DROPS -> 4.0;
            default -> 1.0;
        });
        RewardEntry money = RewardEntry.economy("10").build();
        RewardEntry gems = RewardEntry.economy("10").currency("gems").build();
        RewardEntry ranged = RewardEntry.economy("0").amountBetween(5, 10).build();
        RewardEntry exp = RewardEntry.experience(5).build();
        RewardEntry item = RewardEntry.item("snapshot").fixedAmount(2).build();
        RewardEntry command = RewardEntry.command("say hi").build();
        List<RewardEntry> out = Modifiers.rewards(player, "crates",
                List.of(money, gems, ranged, exp, item, command));
        assertEquals("20.00", out.get(0).value());
        assertSame(gems, out.get(1), "another currency is never boosted");
        assertEquals(10, out.get(2).minAmount());
        assertEquals(20, out.get(2).maxAmount());
        assertEquals("15", out.get(3).value());
        assertEquals(8, out.get(4).itemAmount());
        assertSame(command, out.get(5));
    }

    @Test
    @DisplayName("reward lists inside a scope: the provider sees it, and no scope means null")
    void rewardsScope() {
        Modifiers.register(boosters, (id, type, source, scope) ->
                Modifiers.MONEY.equals(type) && "event:koth".equals(scope) ? 2.0 : 1.0);
        List<RewardEntry> list = List.of(RewardEntry.economy("10").build());
        assertEquals("20.00", Modifiers.rewards(player, "events", "event:koth", list).get(0).value());
        assertSame(list, Modifiers.rewards(player, "events", "event:ctf", list));
        assertSame(list, Modifiers.rewards(player, "events", list));
    }
}
