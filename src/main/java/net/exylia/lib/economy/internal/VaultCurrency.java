package net.exylia.lib.economy.internal;

import net.exylia.lib.economy.CurrencyProvider;
import net.exylia.lib.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Vault as a currency, reached through pure reflection.
 *
 * <p>Vault is not a compile dependency of this library, and it cannot be one:
 * a hard {@code import net.milkbowl.vault.economy.Economy} would make this
 * class fail to load with a {@code NoClassDefFoundError} on every server that
 * runs without Vault, which is most of the servers that use another economy.
 * Reflection turns "Vault is absent" into {@code tryCreate()} returning
 * {@code null} — a value the caller branches on — instead of a linkage error
 * that takes the whole class down with it.
 *
 * <p>Everything expensive is resolved exactly once in {@link #tryCreate()}:
 * the service registration, the {@link Method} handles and the public
 * {@link Field} handles of Vault's response type. Balance reads happen on
 * every scoreboard tick of every player; a {@code Class.forName} or a
 * {@code getMethod} lookup on that path would be a hash-map miss plus a
 * reflection access check paid thousands of times a second for nothing.
 *
 * <h2>Precision</h2>
 * Vault reports balances as {@code double}. Every double that enters this
 * class is converted with {@link BigDecimal#valueOf(double)}, never with
 * {@code new BigDecimal(double)}: the constructor takes the exact binary
 * value, so the double nearest 0.1 becomes
 * {@code 0.1000000000000000055511151231257827021181583404541015625}, and a
 * balance a player can read becomes a number with twenty decimal places of
 * noise. {@code valueOf} goes through the shortest decimal string that
 * round-trips to the same double — the {@code 0.1} the economy plugin meant.
 *
 * <p>Going the other way, a {@link BigDecimal} is handed to Vault as a
 * {@code double}, because that is the only thing Vault accepts. The decimal
 * amount the library validated is rounded to the nearest double, which is the
 * best a double-based backend can hold; the library keeps its own precise
 * accounting, so the noise never propagates upward.
 *
 * <h2>What is deliberately not overridden</h2>
 * {@link #set(UUID, BigDecimal)} keeps the interface default — Vault has no
 * native set, so a deposit-or-withdraw of the difference is the only honest
 * implementation, and the default already writes it once. {@code transfer}
 * keeps the default too (returns {@code null}): Vault has no atomic pay
 * operation, and a transfer faked as withdraw-then-deposit belongs in the
 * library, where the partial-failure handling lives.
 *
 * @since 1.26.0
 */
public final class VaultCurrency implements CurrencyProvider {

    private static final String PLUGIN = "Vault";
    private static final String ECONOMY_CLASS = "net.milkbowl.vault.economy.Economy";
    private static final String RESPONSE_CLASS = "net.milkbowl.vault.economy.EconomyResponse";

    private final Object economy;
    private final Method getBalance;
    private final Method depositPlayer;
    private final Method withdrawPlayer;
    private final Method currencyNameSingular;
    private final Method currencyNamePlural;
    private final Method transactionSuccess;
    private final Field responseBalance;
    private final Field responseError;

    private VaultCurrency(Object economy,
                          Method getBalance, Method depositPlayer, Method withdrawPlayer,
                          Method currencyNameSingular, Method currencyNamePlural,
                          Method transactionSuccess, Field responseBalance, Field responseError) {
        this.economy = economy;
        this.getBalance = getBalance;
        this.depositPlayer = depositPlayer;
        this.withdrawPlayer = withdrawPlayer;
        this.currencyNameSingular = currencyNameSingular;
        this.currencyNamePlural = currencyNamePlural;
        this.transactionSuccess = transactionSuccess;
        this.responseBalance = responseBalance;
        this.responseError = responseError;
    }

    /**
     * Builds the provider, or returns {@code null} when Vault is not there to
     * back it.
     *
     * <p>{@code null} — rather than a disabled instance — because a provider
     * that can never serve a request should not be registered at all: the
     * library's fallback to another currency only runs over providers that
     * exist. {@code null} is also returned when Vault is installed but no
     * economy plugin has registered a service with it, and when the API
     * surface has changed under us (a method renamed by a fork); in the last
     * case a warning is logged, because that is a bug report, not an absence.
     *
     * @return the provider, or {@code null}
     */
    public static @Nullable VaultCurrency tryCreate() {
        if (!Bukkit.getPluginManager().isPluginEnabled(PLUGIN)) {
            return null;
        }
        try {
            Class<?> economyClass = Class.forName(ECONOMY_CLASS);
            RegisteredServiceProvider<?> registration =
                    Bukkit.getServicesManager().getRegistration(economyClass);
            if (registration == null) {
                // Vault without an economy plugin behind it serves nobody.
                return null;
            }
            Object economy = registration.getProvider();
            if (economy == null) {
                return null;
            }
            Class<?> responseClass = Class.forName(RESPONSE_CLASS);
            return new VaultCurrency(economy,
                    economyClass.getMethod("getBalance", OfflinePlayer.class),
                    economyClass.getMethod("depositPlayer", OfflinePlayer.class, double.class),
                    economyClass.getMethod("withdrawPlayer", OfflinePlayer.class, double.class),
                    economyClass.getMethod("currencyNameSingular"),
                    economyClass.getMethod("currencyNamePlural"),
                    responseClass.getMethod("transactionSuccess"),
                    responseClass.getField("balance"),
                    responseClass.getField("errorMessage"));
        } catch (Throwable e) {
            // NoClassDefFoundError included: Vault can be enabled while its
            // classes are shaded elsewhere or stripped by a fork.
            Logger.getLogger("ExyliaLib").warning(
                    "Vault is installed but its economy API could not be reached: "
                            + e.getMessage());
            return null;
        }
    }

    @Override
    public @NotNull String id() {
        return "vault";
    }

    @Override
    public @NotNull String displayName() {
        return "Vault";
    }

    @Override
    public boolean isAvailable() {
        // A plugin-manager lookup, not a flag frozen at startup: Vault can be
        // disabled after we were created, and answering true then would send
        // every purchase into a provider that no longer exists.
        return Bukkit.getPluginManager().isPluginEnabled(PLUGIN);
    }

    @Override
    public @NotNull BigDecimal balance(@NotNull UUID player) {
        try {
            double value = (Double) getBalance.invoke(economy, offline(player));
            return BigDecimal.valueOf(value);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // balance() has no failure channel, and inventing a number is
            // worse than zero: zero blocks spending, a made-up balance lets a
            // purchase through that the backend may not honour.
            return BigDecimal.ZERO;
        }
    }

    @Override
    public @NotNull EconomyResponse deposit(@NotNull UUID player, @NotNull BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            // The library validates first; this guard is for the caller who
            // reached the provider directly, because Vault backends are free
            // to define a negative deposit as a withdrawal.
            return EconomyResponse.invalidAmount();
        }
        try {
            Object response = depositPlayer.invoke(economy, offline(player), amount.doubleValue());
            return adapt(response, amount);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return EconomyResponse.failure("Vault deposit failed: " + describe(e));
        }
    }

    @Override
    public @NotNull EconomyResponse withdraw(@NotNull UUID player, @NotNull BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return EconomyResponse.invalidAmount();
        }
        // Vault's withdrawPlayer does not check the balance; several backends
        // happily drive an account negative. The check lives here, as close
        // to the mutation as this API allows, so a direct caller cannot skip
        // it by bypassing the library facade.
        BigDecimal current = balance(player);
        if (current.compareTo(amount) < 0) {
            return EconomyResponse.insufficientFunds(amount, current);
        }
        try {
            Object response = withdrawPlayer.invoke(economy, offline(player), amount.doubleValue());
            return adapt(response, amount);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return EconomyResponse.failure("Vault withdraw failed: " + describe(e));
        }
    }

    @Override
    public @NotNull String currencyName(boolean plural) {
        try {
            String name = (String) (plural ? currencyNamePlural : currencyNameSingular)
                    .invoke(economy);
            if (name != null && !name.isBlank()) {
                return name;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // A cosmetic read must not fail an operation; fall through.
        }
        return plural ? "dollars" : "dollar";
    }

    @Override
    public @NotNull String symbol() {
        return "$";
    }

    /**
     * Translates Vault's response object into ours.
     *
     * <p>The new balance is read from the response rather than re-queried,
     * because it is the value the backend itself committed; a second
     * {@code getBalance} could observe a concurrent transaction and report a
     * balance that does not correspond to this operation.
     */
    private @NotNull EconomyResponse adapt(@NotNull Object response, @NotNull BigDecimal amount)
            throws ReflectiveOperationException {
        if ((Boolean) transactionSuccess.invoke(response)) {
            return EconomyResponse.success(amount,
                    BigDecimal.valueOf(responseBalance.getDouble(response)));
        }
        String error = (String) responseError.get(response);
        return EconomyResponse.failure(error == null || error.isBlank()
                ? "Vault refused the transaction" : error);
    }

    private static @NotNull OfflinePlayer offline(@NotNull UUID player) {
        return Bukkit.getOfflinePlayer(player);
    }

    /**
     * Unwraps the real cause from {@link InvocationTargetException}.
     *
     * <p>Without this every message would read "null" — the target exception
     * itself carries no message, the backend's actual reason sits in its
     * cause, and a log full of "Vault deposit failed: null" helps nobody.
     */
    private static @NotNull String describe(@NotNull Throwable e) {
        Throwable cause = e instanceof InvocationTargetException && e.getCause() != null
                ? e.getCause() : e;
        String message = cause.getMessage();
        return message != null ? message : cause.getClass().getSimpleName();
    }
}
