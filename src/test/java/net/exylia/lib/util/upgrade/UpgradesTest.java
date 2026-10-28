package net.exylia.lib.util.upgrade;

import net.exylia.lib.economy.EconomyResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Leveled upgrades: prices and effects from formulas, requirements, and a purchase charged once. */
class UpgradesTest {

    private static final Upgrade LUCK = new Upgrade("Luck", 5, "100 * %level%", "gems", "%level% * 2", requirements());

    /** In the order written: the first one short is the one named. */
    private static Map<String, Integer> requirements() {
        Map<String, Integer> requirements = new LinkedHashMap<>();
        requirements.put("level", 10);
        requirements.put("prestige", 1);
        return requirements;
    }

    private final UUID player = UUID.randomUUID();
    private final Upgrades.Charge original = Upgrades.charge;

    @AfterEach
    void restore() {
        Upgrades.charge = original;
    }

    @Test
    void pricesAndEffectsFollowTheirFormulas() {
        assertEquals("luck", LUCK.id());
        assertEquals(0, new BigDecimal("100").compareTo(LUCK.price(1)));
        assertEquals(0, new BigDecimal("500").compareTo(LUCK.price(5)));
        assertEquals(BigDecimal.ZERO, LUCK.price(6));
        assertEquals(BigDecimal.ZERO, LUCK.price(0));
        assertEquals(0, LUCK.effect(0));
        assertEquals(6, LUCK.effect(3));
        assertEquals(10, LUCK.effect(99), "clamped to the max level");
        assertTrue(LUCK.problems().isEmpty());
    }

    @Test
    void anUnreadableFormulaIsReportedAndCostsNothing() {
        Upgrade broken = new Upgrade("x", 3, "100 *", "", "%nope%", Map.of());
        assertEquals(BigDecimal.ZERO, broken.price(1));
        assertEquals(2, broken.problems().size());
    }

    @Test
    void checkNamesTheFirstShortRequirement() {
        Upgrade.Check locked = LUCK.check(0, name -> name.equals("level") ? 12 : 0);
        assertEquals(Upgrade.State.LOCKED, locked.state());
        assertEquals("prestige", locked.requirement());
        assertEquals(1, locked.needed());

        Upgrade.Check ready = LUCK.check(2, name -> 99);
        assertEquals(Upgrade.State.READY, ready.state());
        assertEquals(3, ready.next());
        assertEquals(0, new BigDecimal("300").compareTo(ready.price()));

        assertEquals(Upgrade.State.MAXED, LUCK.check(5, name -> 99).state());
    }

    @Test
    void aPurchaseChargesTheNextLevelInItsCurrency() {
        List<String> charged = new ArrayList<>();
        Upgrades.charge = (currency, who, price, transaction) -> {
            charged.add(currency + ":" + price.toPlainString() + ":" + transaction.reason());
            return CompletableFuture.completedFuture(EconomyResponse.success(price, BigDecimal.TEN));
        };
        Purchase purchase = Upgrades.buy(player, LUCK, 1, name -> 99).join();
        assertTrue(purchase.bought());
        assertEquals(2, purchase.level());
        assertEquals(List.of("gems:200:upgrade:luck"), charged);
    }

    @Test
    void refusalsChargeNothing() {
        Upgrades.charge = (currency, who, price, transaction) -> { throw new AssertionError("charged"); };
        assertEquals(Purchase.Outcome.MAXED, Upgrades.buy(player, LUCK, 5, name -> 99).join().outcome());
        Purchase locked = Upgrades.buy(player, LUCK, 0, name -> 0).join();
        assertEquals(Purchase.Outcome.LOCKED, locked.outcome());
        assertEquals("level", locked.requirement());
    }

    @Test
    void aSecondClickWhileChargingIsBusyNotASecondCharge() {
        CompletableFuture<EconomyResponse> pending = new CompletableFuture<>();
        int[] calls = {0};
        Upgrades.charge = (currency, who, price, transaction) -> {
            calls[0]++;
            return pending;
        };
        CompletableFuture<Purchase> first = Upgrades.buy(player, LUCK, 0, name -> 99);
        Purchase second = Upgrades.buy(player, LUCK, 0, name -> 99).getNow(null);
        assertEquals(Purchase.Outcome.BUSY, second == null ? null : second.outcome());
        assertEquals(1, calls[0]);
        pending.complete(EconomyResponse.success(BigDecimal.TEN, BigDecimal.ZERO));
        assertTrue(first.join().bought());
        // Released: the next level can be bought again.
        Upgrades.charge = (currency, who, price, transaction) ->
                CompletableFuture.completedFuture(EconomyResponse.success(price, BigDecimal.ZERO));
        assertTrue(Upgrades.buy(player, LUCK, 1, name -> 99).join().bought());
    }

    @Test
    void tooLittleMoneyNamesTheShortfall() {
        Upgrades.charge = (currency, who, price, transaction) -> CompletableFuture.completedFuture(
                EconomyResponse.insufficientFunds(price, new BigDecimal("30")));
        Purchase purchase = Upgrades.buy(player, LUCK, 0, name -> 99).join();
        assertFalse(purchase.bought());
        assertEquals(Purchase.Outcome.INSUFFICIENT_FUNDS, purchase.outcome());
        assertEquals(0, new BigDecimal("70").compareTo(purchase.shortfall()));
        assertNull(purchase.requirement());
    }

    @Test
    void aFreeLevelIsBoughtWithoutCharging() {
        Upgrades.charge = (currency, who, price, transaction) -> { throw new AssertionError("charged"); };
        Upgrade free = new Upgrade("slot", 2, "0", "", "%level%", Map.of());
        assertTrue(Upgrades.buy(player, free, 0, name -> 0).join().bought());
    }

    @Test
    void aChargeThatThrowsFailsWithoutLeavingTheUpgradeBusy() {
        Upgrades.charge = (currency, who, price, transaction) -> { throw new IllegalStateException("down"); };
        assertEquals(Purchase.Outcome.FAILED, Upgrades.buy(player, LUCK, 0, name -> 99).join().outcome());
        assertEquals(Purchase.Outcome.FAILED, Upgrades.buy(player, LUCK, 0, name -> 99).join().outcome());
    }
}
