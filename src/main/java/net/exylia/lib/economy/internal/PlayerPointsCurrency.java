package net.exylia.lib.economy.internal;

import net.exylia.lib.economy.CurrencyProvider;
import net.exylia.lib.economy.EconomyResponse;
import net.exylia.lib.economy.TransferResult;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * PlayerPoints as a currency, reached through pure reflection.
 *
 * <p>PlayerPoints is not a compile dependency, for the same reason Vault is
 * not: a hard reference to {@code org.black_ixx.playerpoints.PlayerPoints}
 * would make this class unloadable on every server without the plugin, and
 * "plugin absent" must be a {@code null} from {@link #tryCreate()}, never a
 * {@code NoClassDefFoundError} at class-load time. All {@link Method} handles
 * are resolved once in {@link #tryCreate()} and cached; nothing is looked up
 * per call, because balances are read on scoreboard ticks.
 *
 * <h2>Precision: a point is an integer</h2>
 * PlayerPoints stores whole points — its API takes and returns {@code int}.
 * Two consequences follow.
 *
 * <p>Reading is exact: an {@code int} becomes a {@link BigDecimal} through
 * {@link BigDecimal#valueOf(long)}, which cannot lose anything, unlike the
 * double path a Vault balance takes.
 *
 * <p>Writing must refuse what the backend cannot hold. A fractional amount is
 * <em>rejected</em>, never truncated or rounded: truncating 1.9 to 1 destroys
 * 0.9 points a player may have paid real money for, and rounding 1.9 up to 2
 * mints 0.9 points out of nothing — either way the sum of all balances stops
 * matching what was deposited, which is the one invariant an economy must
 * keep. An amount outside the {@code int} range is rejected for the same
 * reason: {@code intValue()} would silently wrap {@code 2^31} to a negative
 * balance. Both rejections surface as {@link EconomyResponse#invalidAmount()},
 * which exists for exactly this — an amount the currency cannot represent.
 * The library validates amounts before calling, so these guards only fire for
 * a direct caller; they stay cheap and defensive.
 *
 * <h2>Native operations</h2>
 * PlayerPoints has a native {@code set}, so the diff-based default is
 * overridden — two operations where one exists is two chances to fail
 * halfway. It also has a native {@code pay(from, to, amount)} that moves
 * points atomically in one storage operation: exactly the case
 * {@link CurrencyProvider#transfer} reserves its override for. Overriding it
 * means the library never runs its withdraw-verify-deposit fallback for this
 * currency, and a transfer can never end {@code PARTIAL} here.
 *
 * @since 1.26.0
 */
public final class PlayerPointsCurrency implements CurrencyProvider {

    private static final String PLUGIN = "PlayerPoints";
    private static final String PLUGIN_CLASS = "org.black_ixx.playerpoints.PlayerPoints";

    private final Object api;
    private final Method look;
    private final Method give;
    private final Method take;
    private final Method set;
    private final Method pay;

    private PlayerPointsCurrency(Object api, Method look, Method give,
                                 Method take, Method set, Method pay) {
        this.api = api;
        this.look = look;
        this.give = give;
        this.take = take;
        this.set = set;
        this.pay = pay;
    }

    /**
     * Builds the provider, or returns {@code null} when PlayerPoints is
     * absent or its API has changed under us.
     *
     * <p>{@code null} rather than a disabled instance so the provider is
     * never registered and the library's fallback can pick another currency.
     * A plugin that is present but whose API cannot be resolved (a fork, a
     * renamed method) logs a warning: that is a bug report, not an absence.
     *
     * @return the provider, or {@code null}
     */
    public static @Nullable PlayerPointsCurrency tryCreate() {
        if (!Bukkit.getPluginManager().isPluginEnabled(PLUGIN)) {
            return null;
        }
        try {
            Class<?> pluginClass = Class.forName(PLUGIN_CLASS);
            Object plugin = pluginClass.getMethod("getInstance").invoke(null);
            if (plugin == null) {
                return null;
            }
            Object api = pluginClass.getMethod("getAPI").invoke(plugin);
            if (api == null) {
                return null;
            }
            Class<?> apiClass = api.getClass();
            return new PlayerPointsCurrency(api,
                    apiClass.getMethod("look", UUID.class),
                    apiClass.getMethod("give", UUID.class, int.class),
                    apiClass.getMethod("take", UUID.class, int.class),
                    apiClass.getMethod("set", UUID.class, int.class),
                    apiClass.getMethod("pay", UUID.class, UUID.class, int.class));
        } catch (Throwable e) {
            Logger.getLogger("ExyliaLib").warning(
                    "PlayerPoints is installed but its API could not be reached: "
                            + e.getMessage());
            return null;
        }
    }

    @Override
    public @NotNull String id() {
        return "points";
    }

    @Override
    public @NotNull String displayName() {
        return "PlayerPoints";
    }

    @Override
    public boolean isAvailable() {
        // Checked live rather than cached: the plugin can be disabled after
        // startup, and a stale true would route purchases into a dead API.
        return Bukkit.getPluginManager().isPluginEnabled(PLUGIN);
    }

    @Override
    public @NotNull BigDecimal balance(@NotNull UUID player) {
        try {
            int points = (Integer) look.invoke(api, player);
            return BigDecimal.valueOf(points);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // No failure channel on a read; zero is the fail-closed answer —
            // it blocks a purchase, it never lets one through on invented
            // funds.
            return BigDecimal.ZERO;
        }
    }

    @Override
    public @NotNull EconomyResponse deposit(@NotNull UUID player, @NotNull BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return EconomyResponse.invalidAmount();
        }
        Integer points = toPoints(amount);
        if (points == null) {
            return EconomyResponse.invalidAmount();
        }
        try {
            boolean ok = (Boolean) give.invoke(api, player, points.intValue());
            return ok
                    ? EconomyResponse.success(amount, balance(player))
                    : EconomyResponse.failure("PlayerPoints refused the deposit");
        } catch (ReflectiveOperationException | RuntimeException e) {
            return EconomyResponse.failure("PlayerPoints deposit failed: " + describe(e));
        }
    }

    @Override
    public @NotNull EconomyResponse withdraw(@NotNull UUID player, @NotNull BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return EconomyResponse.invalidAmount();
        }
        Integer points = toPoints(amount);
        if (points == null) {
            return EconomyResponse.invalidAmount();
        }
        // PlayerPoints' take does not check the balance, and a negative point
        // balance is debt the player never agreed to. The check sits here, at
        // the mutation, so no direct caller can skip it.
        BigDecimal current = balance(player);
        if (current.compareTo(amount) < 0) {
            return EconomyResponse.insufficientFunds(amount, current);
        }
        try {
            boolean ok = (Boolean) take.invoke(api, player, points.intValue());
            return ok
                    ? EconomyResponse.success(amount, balance(player))
                    : EconomyResponse.failure("PlayerPoints refused the withdrawal");
        } catch (ReflectiveOperationException | RuntimeException e) {
            return EconomyResponse.failure("PlayerPoints withdraw failed: " + describe(e));
        }
    }

    /**
     * Sets the balance with PlayerPoints' native {@code set}.
     *
     * <p>Overridden because the default — deposit or withdraw the difference —
     * is two operations with a read in between; a concurrent change between
     * that read and the write would make the diff wrong. The native set is a
     * single statement against the storage and lands on the exact value asked.
     */
    @Override
    public @NotNull EconomyResponse set(@NotNull UUID player, @NotNull BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            return EconomyResponse.invalidAmount();
        }
        Integer points = toPoints(amount);
        if (points == null) {
            return EconomyResponse.invalidAmount();
        }
        try {
            boolean ok = (Boolean) set.invoke(api, player, points.intValue());
            return ok
                    ? EconomyResponse.success(amount, balance(player))
                    : EconomyResponse.failure("PlayerPoints refused the set");
        } catch (ReflectiveOperationException | RuntimeException e) {
            return EconomyResponse.failure("PlayerPoints set failed: " + describe(e));
        }
    }

    /**
     * Moves points with PlayerPoints' native {@code pay}.
     *
     * <p>This is the atomic case {@link CurrencyProvider#transfer} exists for:
     * the backend debits the sender and credits the receiver as one operation,
     * so the library's withdraw-verify-deposit fallback — and its
     * {@code PARTIAL} outcome — never runs for this currency.
     *
     * <p>A reflection failure is reported as {@link TransferResult#withdrawFailed}
     * rather than {@code partial}: an exception means {@code pay} never
     * returned a verdict, and claiming money vanished without evidence sends
     * an admin hunting for a loss that likely never happened. The next
     * balance read surfaces any real discrepancy.
     *
     * @return the result, never {@code null} — the backend always handles it
     */
    @Override
    public @NotNull TransferResult transfer(
            @NotNull UUID from, @NotNull UUID to, @NotNull BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return TransferResult.invalidAmount(amount);
        }
        Integer points = toPoints(amount);
        if (points == null) {
            return TransferResult.invalidAmount(amount);
        }
        // pay() does not check funds either; a rejected payment is the only
        // honest answer when the sender cannot cover it.
        if (balance(from).compareTo(amount) < 0) {
            return TransferResult.insufficientFunds(from, to, amount);
        }
        try {
            boolean ok = (Boolean) pay.invoke(api, from, to, points.intValue());
            return ok
                    ? TransferResult.success(from, to, amount)
                    : TransferResult.withdrawFailed(from, to, amount,
                            "PlayerPoints refused the payment");
        } catch (ReflectiveOperationException | RuntimeException e) {
            return TransferResult.withdrawFailed(from, to, amount,
                    "PlayerPoints payment failed: " + describe(e));
        }
    }

    @Override
    public @NotNull String currencyName(boolean plural) {
        return plural ? "points" : "point";
    }

    @Override
    public @NotNull String symbol() {
        return "★";
    }

    /**
     * Converts an amount to whole points, or returns {@code null} when the
     * amount is not a whole number or does not fit an {@code int}.
     *
     * <p>{@code setScale(0, UNNECESSARY)} throws on any fraction and
     * {@code intValueExact()} throws on overflow, so both rejections ride the
     * same {@link ArithmeticException}; there is no path here that silently
     * reshapes the player's money.
     */
    private static @Nullable Integer toPoints(@NotNull BigDecimal amount) {
        try {
            return amount.setScale(0, RoundingMode.UNNECESSARY).intValueExact();
        } catch (ArithmeticException e) {
            return null;
        }
    }

    /**
     * Unwraps the real cause from {@link InvocationTargetException}, so a
     * failure message names the backend's reason instead of "null".
     */
    private static @NotNull String describe(@NotNull Throwable e) {
        Throwable cause = e instanceof InvocationTargetException && e.getCause() != null
                ? e.getCause() : e;
        String message = cause.getMessage();
        return message != null ? message : cause.getClass().getSimpleName();
    }
}
