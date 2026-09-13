package net.exylia.lib.economy.internal;

import net.exylia.lib.economy.Economy;
import net.exylia.lib.economy.EconomyResponse;
import net.exylia.lib.economy.Transaction;
import net.exylia.lib.player.ExyliaPlayer;
import net.exylia.lib.player.ExyliaPlayers;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Publishes one stored currency as the server's Vault economy.
 *
 * <p>Every plugin that only speaks Vault — a chest shop, a jobs plugin, a
 * rank ladder — then runs on a currency the library keeps, with no
 * EssentialsX or CMI underneath. The Vault interface is implemented as a
 * {@link Proxy} over a class looked up by name, the same way the library
 * <em>reads</em> Vault: nothing here links against it, and a server without
 * Vault pays a caught {@code ClassNotFoundException} and nothing more.
 *
 * <p>Polite by default: {@code vault.provide} names the currency, and it is
 * registered at the lowest priority, so an economy plugin the owner installed —
 * EssentialsX, CMI — sits above it and serves, whichever of the two loaded
 * first. This one serves only while nothing else does. {@code vault.force}
 * registers it at the highest priority instead. Two plugins both certain they
 * are the server's economy is how balances split in two.
 */
final class VaultBridge {

    private static final String ECONOMY = "net.milkbowl.vault.economy.Economy";
    private static final String RESPONSE = "net.milkbowl.vault.economy.EconomyResponse";
    private static final String RESPONSE_TYPE = "net.milkbowl.vault.economy.EconomyResponse$ResponseType";

    private final Plugin plugin;
    private Object published;
    private Class<?> economyClass;

    VaultBridge(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Registers the currency with Vault, or does nothing when it should not. */
    void publish(@Nullable StoredCurrency currency, boolean force) {
        unpublish();
        if (currency == null) return;
        try {
            economyClass = Class.forName(ECONOMY);
        } catch (ClassNotFoundException absent) {
            plugin.getLogger().warning("Economy: vault.provide names '" + currency.id()
                    + "' but Vault is not installed, so nothing is published.");
            return;
        }
        RegisteredServiceProvider<?> other = Bukkit.getServicesManager().getRegistration(economyClass);
        Object proxy = Proxy.newProxyInstance(economyClass.getClassLoader(),
                new Class<?>[] {economyClass}, new Handler(currency));
        registerService(proxy, force ? ServicePriority.Highest : ServicePriority.Lowest);
        published = proxy;
        if (other != null && !force) {
            plugin.getLogger().info("Economy: " + other.getPlugin().getName() + " provides the Vault economy; '"
                    + currency.id() + "' is registered beneath it and serves only without it."
                    + " Set vault.force to put it on top.");
        } else {
            plugin.getLogger().info("Economy: '" + currency.id() + "' is the server's Vault economy.");
        }
        // Vault's own view is a currency too, and it now points at us.
        CurrencyRegistry.detect(plugin);
    }

    /** Whether an economy Vault hands out is this bridge. */
    static boolean isBridge(@Nullable Object economy) {
        return economy != null && Proxy.isProxyClass(economy.getClass())
                && Proxy.getInvocationHandler(economy) instanceof Handler;
    }

    void unpublish() {
        if (published == null || economyClass == null) return;
        unregisterService(published);
        published = null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void registerService(Object proxy, ServicePriority priority) {
        Bukkit.getServicesManager().register((Class) economyClass, proxy, plugin, priority);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void unregisterService(Object proxy) {
        Bukkit.getServicesManager().unregister((Class) economyClass, proxy);
    }

    /** Answers Vault's interface with the library's operations. */
    private final class Handler implements InvocationHandler {

        private final StoredCurrency currency;

        Handler(StoredCurrency currency) {
            this.currency = currency;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            switch (name) {
                case "isEnabled" -> { return true; }
                case "getName" -> { return "ExyliaLib:" + currency.id(); }
                case "hasBankSupport" -> { return false; }
                case "fractionalDigits" -> { return currency.info().decimals(); }
                case "format" -> { return currency.info().format(BigDecimal.valueOf((Double) args[0])); }
                case "currencyNamePlural" -> { return currency.info().namePlural(); }
                case "currencyNameSingular" -> { return currency.info().name(); }
                case "hasAccount", "createPlayerAccount" -> { return true; }
                case "getBanks" -> { return List.of(); }
                case "getBalance" -> { return balance(player(args[0])); }
                case "has" -> { return balance(player(args[0])) >= amount(args); }
                case "depositPlayer" -> { return response(deposit(player(args[0]), amount(args)), amount(args)); }
                case "withdrawPlayer" -> { return response(withdraw(player(args[0]), amount(args)), amount(args)); }
                case "equals" -> { return proxy == args[0]; }
                case "hashCode" -> { return System.identityHashCode(proxy); }
                case "toString" -> { return "VaultBridge[" + currency.id() + "]"; }
                default -> {
                    // Banks, and anything Vault grows later: not supported, said so.
                    if (method.getReturnType().getName().equals(RESPONSE)) {
                        return notImplemented();
                    }
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == double.class) return 0D;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                }
            }
        }

        /** Vault hands a player as an {@link OfflinePlayer} or as a name. */
        private @Nullable UUID player(Object argument) {
            if (argument instanceof OfflinePlayer offline) return offline.getUniqueId();
            if (argument instanceof String typed) {
                ExyliaPlayer cached = ExyliaPlayers.cached(typed);
                return cached == null ? null : cached.id();
            }
            return null;
        }

        /** The amount is the last double argument, whatever else is there. */
        private double amount(Object[] args) {
            for (int index = args.length - 1; index >= 0; index--) {
                if (args[index] instanceof Double value) return value;
            }
            return 0D;
        }

        private double balance(@Nullable UUID player) {
            return player == null ? 0D : Economy.of(currency.id()).balance(player).doubleValue();
        }

        private EconomyResponse deposit(@Nullable UUID player, double amount) {
            if (player == null) return EconomyResponse.failure("Unknown player.");
            return Economy.of(currency.id()).deposit(player, BigDecimal.valueOf(amount), Transaction.of("vault"));
        }

        private EconomyResponse withdraw(@Nullable UUID player, double amount) {
            if (player == null) return EconomyResponse.failure("Unknown player.");
            return Economy.of(currency.id()).withdraw(player, BigDecimal.valueOf(amount), Transaction.of("vault"));
        }

        private Object response(EconomyResponse ours, double asked) throws ReflectiveOperationException {
            String type = ours.isSuccess() ? "SUCCESS" : "FAILURE";
            return vaultResponse(asked, ours.balance().doubleValue(), type, ours.message());
        }

        private Object notImplemented() throws ReflectiveOperationException {
            return vaultResponse(0D, 0D, "NOT_IMPLEMENTED", "ExyliaLib does not support bank accounts.");
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private Object vaultResponse(double amount, double balance, String type, String message)
                throws ReflectiveOperationException {
            Class<?> responseClass = Class.forName(RESPONSE);
            Class typeClass = Class.forName(RESPONSE_TYPE);
            Object responseType = Enum.valueOf(typeClass, type);
            return responseClass.getConstructor(double.class, double.class, typeClass, String.class)
                    .newInstance(amount, balance, responseType, message);
        }
    }
}
