package net.exylia.lib.util.upgrade;

import net.exylia.lib.economy.Economy;
import net.exylia.lib.economy.EconomyResponse;
import net.exylia.lib.economy.Transaction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;

/**
 * Buying the next level of an {@link Upgrade}: checked, charged once, answered.
 *
 * <pre>{@code
 * int owned = profile.level("luck");
 * Upgrades.buy(player.getUniqueId(), luck, owned, profile::requirement).thenAccept(purchase -> {
 *     if (purchase.bought()) profile.raise("luck", purchase.level());   // the plugin stores the level
 *     else tell(player, purchase.outcome());
 * });
 * }</pre>
 *
 * <p>The charge is {@link Economy.CurrencyView#withdrawLater}, so it works from
 * any thread and in every currency, items and experience included. The future
 * never completes exceptionally.
 *
 * <p><b>One purchase at a time per player and upgrade id.</b> A second click
 * while the first charge is under way answers {@link Purchase.Outcome#BUSY}
 * instead of charging twice for the same level. The library stores no levels:
 * the plugin raises its own on {@link Purchase#bought()}, before the next click
 * can read the old one.
 *
 * @since 1.381.0
 */
public final class Upgrades {

    /** How a price is charged: the economy, or a fake in tests. */
    @FunctionalInterface
    interface Charge {
        CompletableFuture<EconomyResponse> charge(String currency, UUID player, BigDecimal price, Transaction transaction);
    }

    static volatile Charge charge = (currency, player, price, transaction) ->
            Economy.of(currency).withdrawLater(player, price, transaction);

    private static final Set<String> BUYING = ConcurrentHashMap.newKeySet();

    private Upgrades() {
        throw new AssertionError("No instances.");
    }

    /**
     * Charges the next level of an upgrade.
     *
     * @param player   who pays
     * @param upgrade  what is bought
     * @param owned    the level they have now
     * @param progress their value of each requirement name, as {@link Upgrade#check} takes it
     * @return the outcome; {@link Purchase#level()} is the level reached on success
     */
    public static @NotNull CompletableFuture<Purchase> buy(@NotNull UUID player, @NotNull Upgrade upgrade, int owned,
                                                           @NotNull ToIntFunction<String> progress) {
        Upgrade.Check check = upgrade.check(owned, progress);
        switch (check.state()) {
            case MAXED -> {
                return done(Purchase.Outcome.MAXED, check, BigDecimal.ZERO, null);
            }
            case LOCKED -> {
                return done(Purchase.Outcome.LOCKED, check, BigDecimal.ZERO, null);
            }
            default -> { }
        }
        String key = player + ":" + upgrade.id();
        if (!BUYING.add(key)) return done(Purchase.Outcome.BUSY, check, BigDecimal.ZERO, null);
        if (check.price().signum() <= 0) {
            BUYING.remove(key);
            return done(Purchase.Outcome.BOUGHT, check, BigDecimal.ZERO, null);
        }
        CompletableFuture<EconomyResponse> charged;
        try {
            charged = charge.charge(upgrade.currency(), player, check.price(), Transaction.of("upgrade:" + upgrade.id()));
        } catch (RuntimeException failure) {
            charged = CompletableFuture.failedFuture(failure);
        }
        return charged.handle((response, failure) -> {
            BUYING.remove(key);
            if (failure != null || response == null) return purchase(Purchase.Outcome.FAILED, check, BigDecimal.ZERO, null);
            return switch (response.type()) {
                case SUCCESS -> purchase(Purchase.Outcome.BOUGHT, check, BigDecimal.ZERO, response);
                case INSUFFICIENT_FUNDS -> purchase(Purchase.Outcome.INSUFFICIENT_FUNDS, check, response.shortfall(), response);
                default -> purchase(Purchase.Outcome.FAILED, check, BigDecimal.ZERO, response);
            };
        });
    }

    private static CompletableFuture<Purchase> done(Purchase.Outcome outcome, Upgrade.Check check, BigDecimal shortfall,
                                                    @Nullable EconomyResponse response) {
        return CompletableFuture.completedFuture(purchase(outcome, check, shortfall, response));
    }

    private static Purchase purchase(Purchase.Outcome outcome, Upgrade.Check check, BigDecimal shortfall,
                                     @Nullable EconomyResponse response) {
        return new Purchase(outcome, check.next(), check.price(), shortfall, check.requirement(), check.needed(), response);
    }
}
