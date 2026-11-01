package net.exylia.lib.util.upgrade;

import net.exylia.lib.economy.Economy;
import net.exylia.lib.economy.EconomyResponse;
import net.exylia.lib.economy.Transaction;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;
import java.util.function.ToIntFunction;

/**
 * Buying the next level of an {@link Upgrade}: checked, charged once, answered.
 *
 * <pre>{@code
 * int owned = profile.level("luck");
 * Upgrades.buy(player.getUniqueId(), luck, owned, profile::requirement,
 *         level -> profile.raise("luck", level)).thenAccept(purchase -> {   // stored before the guard opens
 *     if (!purchase.bought()) tell(player, purchase.outcome());
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
 * the plugin hands {@code store}, which raises its own level before the guard
 * opens, so the next click never reads the old level and pays for it again.
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
        return buy(player, upgrade, owned, progress, level -> { });
    }

    /**
     * Charges the next level of an upgrade and stores it before another purchase
     * of the same upgrade by the same player can start.
     *
     * @param store raises the plugin's stored level to the one bought; called once,
     *              only on success, before the one-at-a-time guard is released
     * @since 1.384.0
     */
    public static @NotNull CompletableFuture<Purchase> buy(@NotNull UUID player, @NotNull Upgrade upgrade, int owned,
                                                           @NotNull ToIntFunction<String> progress,
                                                           @NotNull IntConsumer store) {
        return settle(player, upgrade.check(owned, progress), upgrade, store);
    }

    /**
     * Charges the next level for an online player, the upgrade's permissions
     * included: a missing node answers {@link Purchase.Outcome#LOCKED} with
     * {@link Upgrade#PERMISSION} as the requirement.
     *
     * <pre>{@code
     * Upgrades.buy(player, speed, spawner.level("speed"), name -> 0, level -> spawner.raise("speed", level))
     *         .thenAccept(purchase -> tasks.runAtEntity(player, () -> tell(player, purchase)));
     * }</pre>
     *
     * <p>Call it on the player's thread: the permissions are read there, before
     * anything is charged. The future may complete on another thread.
     *
     * @since 1.432.0
     */
    public static @NotNull CompletableFuture<Purchase> buy(@NotNull Player player, @NotNull Upgrade upgrade, int owned,
                                                           @NotNull ToIntFunction<String> progress,
                                                           @NotNull IntConsumer store) {
        return settle(player.getUniqueId(), upgrade.check(owned, progress, player::hasPermission), upgrade, store);
    }

    /**
     * The price of a level as the player reads it, in its own currency:
     * {@code "$1,250.00"}, {@code "64 Diamonds"}. Zero for a level past the max.
     *
     * @since 1.432.0
     */
    public static @NotNull String price(@NotNull Upgrade upgrade, int level) {
        String currency = upgrade.currency(level);
        return Economy.format(currency.isEmpty() ? null : currency, upgrade.price(level));
    }

    /**
     * The plain values a menu card shows for a track, ready for
     * {@code UiEntry.with(name, value)} or a {@code Values} map:
     * {@code level}, {@code max_level}, {@code next_level}, {@code price}
     * (formatted, in the next level's currency; empty when maxed), {@code currency},
     * {@code effect}, {@code next_effect} (the current one when maxed),
     * {@code state} ({@code maxed}, {@code locked}, {@code ready}),
     * {@code requirement} and {@code needed} (empty when nothing is short).
     * Labels such as "Maxed" or "Free" stay the plugin's messages, keyed on
     * {@code state} and on {@link Upgrade.Check#price()}.
     *
     * @param check the answer of {@link Upgrade#check} for the same level
     * @since 1.432.0
     */
    public static @NotNull Map<String, String> placeholders(@NotNull Upgrade upgrade, int owned, @NotNull Upgrade.Check check) {
        boolean maxed = check.state() == Upgrade.State.MAXED;
        Map<String, String> values = new LinkedHashMap<>();
        values.put("level", String.valueOf(Math.max(0, Math.min(owned, upgrade.maxLevel()))));
        values.put("max_level", String.valueOf(upgrade.maxLevel()));
        values.put("next_level", String.valueOf(check.next()));
        values.put("price", maxed ? "" : price(upgrade, check.next()));
        values.put("currency", upgrade.currency(check.next()));
        values.put("effect", number(upgrade.effect(owned)));
        values.put("next_effect", number(upgrade.effect(maxed ? owned : check.next())));
        values.put("state", check.state().name().toLowerCase(Locale.ROOT));
        values.put("requirement", check.requirement() == null ? "" : check.requirement());
        values.put("needed", check.requirement() == null ? "" : String.valueOf(check.needed()));
        return values;
    }

    private static String number(double value) {
        if (!Double.isFinite(value)) return "0";
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static CompletableFuture<Purchase> settle(UUID player, Upgrade.Check check, Upgrade upgrade, IntConsumer store) {
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
            try {
                store.accept(check.next());
            } finally {
                BUYING.remove(key);
            }
            return done(Purchase.Outcome.BOUGHT, check, BigDecimal.ZERO, null);
        }
        CompletableFuture<EconomyResponse> charged;
        try {
            charged = charge.charge(upgrade.currency(check.next()), player, check.price(), Transaction.of("upgrade:" + upgrade.id()));
        } catch (RuntimeException failure) {
            charged = CompletableFuture.failedFuture(failure);
        }
        return charged.handle((response, failure) -> {
            try {
                if (failure != null || response == null) return purchase(Purchase.Outcome.FAILED, check, BigDecimal.ZERO, null);
                return switch (response.type()) {
                    case SUCCESS -> {
                        store.accept(check.next());
                        yield purchase(Purchase.Outcome.BOUGHT, check, BigDecimal.ZERO, response);
                    }
                    case INSUFFICIENT_FUNDS -> purchase(Purchase.Outcome.INSUFFICIENT_FUNDS, check, response.shortfall(), response);
                    default -> purchase(Purchase.Outcome.FAILED, check, BigDecimal.ZERO, response);
                };
            } finally {
                BUYING.remove(key);
            }
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
