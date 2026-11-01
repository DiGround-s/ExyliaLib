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

    @Test
    void theLevelIsStoredBeforeTheNextClickCanStart() {
        CompletableFuture<EconomyResponse> pending = new CompletableFuture<>();
        Upgrades.charge = (currency, who, price, transaction) -> pending;
        int[] stored = {0};
        CompletableFuture<Purchase> first = Upgrades.buy(player, LUCK, 0, name -> 99, level -> {
            stored[0] = level;
            // a click arriving while storing must still be refused
            assertEquals(Purchase.Outcome.BUSY, Upgrades.buy(player, LUCK, 0, name -> 99).join().outcome());
        });
        pending.complete(EconomyResponse.success(BigDecimal.TEN, BigDecimal.ZERO));
        assertTrue(first.join().bought());
        assertEquals(1, stored[0]);
    }

    /** Speed: formulas in coins, level 3 costs diamonds and a permission, level 4 written only as a list entry. */
    private static Upgrade speed() {
        Map<String, Upgrade.Level> levels = new LinkedHashMap<>();
        levels.put("3", new Upgrade.Level("64", "diamonds", "p.speed.3", Map.of("prestige", 2), Map.of("delay", 300.0)));
        levels.put("1", new Upgrade.Level("", "", "", Map.of(), Map.of("delay", 500.0)));
        levels.put("4", new Upgrade.Level());
        levels.put("nope", new Upgrade.Level());
        return new Upgrade("speed", 2, "1000 * %level%", "coins", "%level%", Map.of("level", 1), "p.speed", levels);
    }

    @Test
    void levelsOverrideTheTrackOneByOne() {
        Upgrade speed = speed();
        assertEquals(4, speed.maxLevel(), "the highest written level raises the max; a key that is no number is dropped");
        assertEquals(0, new BigDecimal("2000").compareTo(speed.price(2)));
        assertEquals(0, new BigDecimal("64").compareTo(speed.price(3)));
        assertEquals("coins", speed.currency(2));
        assertEquals("diamonds", speed.currency(3));
        assertEquals("p.speed", speed.permission(1));
        assertEquals("p.speed.3", speed.permission(3));
        assertEquals(Map.of("level", 1, "prestige", 2), speed.requirements(3));
        assertEquals(800, speed.value(0, "delay", 800));
        assertEquals(500, speed.value(2, "delay", 800), "level 2 writes none: level 1's holds");
        assertEquals(300, speed.value(9, "delay", 800));
        assertTrue(speed.problems().isEmpty());
    }

    @Test
    void aMissingPermissionLocksAfterTheMinimums() {
        Upgrade speed = speed();
        Upgrade.Check noNode = speed.check(0, name -> 9, node -> false);
        assertEquals(Upgrade.State.LOCKED, noNode.state());
        assertEquals(Upgrade.PERMISSION, noNode.requirement());
        assertEquals("level", speed.check(0, name -> 0, node -> false).requirement(), "minimums are named first");
        assertEquals(Upgrade.State.READY, speed.check(0, name -> 9, node -> node.equals("p.speed")).state());
        assertEquals(Upgrade.State.LOCKED, speed.check(2, name -> 9, node -> node.equals("p.speed")).state(),
                "level 3 asks for its own node");
        assertEquals(Upgrade.State.READY, speed.check(0, name -> 9).state(), "the old check ignores permissions");
    }

    @Test
    void aWrittenLevelIsChargedInItsOwnCurrency() {
        List<String> charged = new ArrayList<>();
        Upgrades.charge = (currency, who, price, transaction) -> {
            charged.add(currency + ":" + price.toPlainString());
            return CompletableFuture.completedFuture(EconomyResponse.success(price, BigDecimal.TEN));
        };
        assertTrue(Upgrades.buy(player, speed(), 2, name -> 9).join().bought());
        assertEquals(List.of("diamonds:64"), charged);
    }

    @Test
    void menuPlaceholdersDescribeTheCard() {
        Upgrade speed = speed();
        Map<String, String> ready = Upgrades.placeholders(speed, 1, speed.check(1, name -> 9, node -> true));
        assertEquals("1", ready.get("level"));
        assertEquals("4", ready.get("max_level"));
        assertEquals("2", ready.get("next_level"));
        assertEquals("coins", ready.get("currency"));
        assertEquals("2", ready.get("next_effect"));
        assertEquals("ready", ready.get("state"));
        assertEquals("", ready.get("requirement"));
        Map<String, String> maxed = Upgrades.placeholders(speed, 4, speed.check(4, name -> 9, node -> true));
        assertEquals("maxed", maxed.get("state"));
        assertEquals("", maxed.get("price"));
        assertEquals("4", maxed.get("next_effect"));
        Map<String, String> locked = Upgrades.placeholders(speed, 0, speed.check(0, name -> 0, node -> true));
        assertEquals("level", locked.get("requirement"));
        assertEquals("1", locked.get("needed"));
    }
}
