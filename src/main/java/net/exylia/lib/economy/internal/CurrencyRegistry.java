package net.exylia.lib.economy.internal;

import net.exylia.lib.economy.CurrencyProvider;
import net.exylia.lib.economy.EconomyException;
import net.exylia.lib.economy.EconomySettings;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Every currency the library knows about, and the rules for which one answers.
 *
 * <p>Providers register under their {@link CurrencyProvider#id()} and stay
 * until they unregister; resolution then applies {@link EconomySettings} on
 * top: the named or default currency serves when it is there and available,
 * the fallback list serves when it is not. The fallback walk is announced in
 * the log rather than silent, because a currency that changes on its own is
 * how a balance "disappears" — the money is still there, under a currency the
 * shop no longer asks for.
 *
 * <h2>Why detection is reflective</h2>
 * The built-in adapters for Vault and PlayerPoints live in this same package,
 * but this class never links against them, and they never link against Vault
 * or PlayerPoints. Detection is a {@link Class#forName(String)} probe plus a
 * reflective call to the adapter's {@code tryCreate()}, so a server without
 * those plugins pays nothing more than a caught {@link NoClassDefFoundError},
 * and a broken economy plugin degrades to "not registered" instead of a
 * startup failure.
 *
 * <h2>Threading</h2>
 * Safe from any thread: the provider map is concurrent, the settings and the
 * logger are {@code volatile}, and registration is serialised on a lock so the
 * duplicate-id check cannot race.
 *
 * @since 1.26.0
 */
public final class CurrencyRegistry {

    private static final String VAULT_HOOK_CLASS = "net.milkbowl.vault.economy.Economy";
    private static final String POINTS_HOOK_CLASS = "org.black_ixx.playerpoints.PlayerPoints";

    private static final String VAULT_ADAPTER = "net.exylia.lib.economy.internal.VaultCurrency";
    private static final String POINTS_ADAPTER = "net.exylia.lib.economy.internal.PlayerPointsCurrency";

    private static volatile Logger logger = Logger.getLogger("ExyliaLib");

    /** Every registered provider, id → provider. */
    private static final Map<String, CurrencyProvider> providers = new ConcurrentHashMap<>();

    /** The settings resolution follows. Swapped atomically on reload. */
    private static volatile EconomySettings settings = new EconomySettings();

    /**
     * Which provider last served each requested currency, so a fallback switch
     * is announced once — when it happens — and not on every call of every
     * tick. Keyed by the id that was asked for, valued by the id that served.
     */
    private static final Map<String, String> announced = new ConcurrentHashMap<>();

    private static final Object LOCK = new Object();

    private CurrencyRegistry() {
    }

    // ------------------------------------------------------------------
    // Wiring
    // ------------------------------------------------------------------

    /**
     * Sets the library's logger and detects the built-in providers.
     *
     * <p>Called by ExyliaLib at startup.
     */
    public static void init(@NotNull Plugin libPlugin) {
        logger = libPlugin.getLogger();
        detect(libPlugin);
    }

    /**
     * Probes for Vault and PlayerPoints and registers an adapter for each one
     * found and enabled.
     *
     * <p>Every step is reflective and individually guarded: a missing economy
     * plugin is the normal case, a broken one must not keep the other from
     * registering, and neither may fail startup. A provider a plugin already
     * registered under a built-in id wins over detection, the same precedence
     * registered bridges have everywhere else in the library.
     */
    public static void detect(@NotNull Plugin libPlugin) {
        detectOne(VAULT_HOOK_CLASS, "Vault", VAULT_ADAPTER);
        detectOne(POINTS_HOOK_CLASS, "PlayerPoints", POINTS_ADAPTER);
    }

    private static void detectOne(String hookClass, String pluginName, String adapterClass) {
        try {
            Class.forName(hookClass);
        } catch (Throwable absent) {
            // The hook class is not on the server: the economy plugin is not
            // installed. This is the normal case, not an error.
            return;
        }
        try {
            Plugin plugin = Bukkit.getPluginManager().getPlugin(pluginName);
            if (plugin == null || !plugin.isEnabled()) {
                return;
            }
            Method tryCreate = Class.forName(adapterClass).getMethod("tryCreate");
            Object created = tryCreate.invoke(null);
            if (!(created instanceof CurrencyProvider provider)) {
                return;
            }
            synchronized (LOCK) {
                if (providers.containsKey(provider.id())) {
                    // A plugin registered this id itself; its provider wins.
                    return;
                }
                providers.put(provider.id(), provider);
            }
            logger.info("Economy: detected '" + provider.id() + "' (" + pluginName + ").");
        } catch (Throwable t) {
            // A present-but-broken economy plugin — wrong version, half-loaded —
            // degrades to "not registered". Startup must not fail over money
            // the library can simply report as unavailable.
            logger.warning("Economy: " + pluginName + " was found but its adapter failed: " + t);
        }
    }

    /**
     * Forgets every provider and restores the default settings.
     *
     * <p>Balances cached against the old set of providers are dropped too: a
     * currency id that means one economy before shutdown and another after a
     * reload must not keep serving the first one's numbers.
     */
    public static void shutdown() {
        synchronized (LOCK) {
            providers.clear();
            announced.clear();
            settings = new EconomySettings();
        }
        BalanceCache.invalidateAll();
    }

    /**
     * Swaps the settings resolution follows. Reload-safe: the reference is
     * volatile, so a thread mid-resolution sees either the old settings or the
     * new, never a mix.
     *
     * <p>The announcement memory is cleared so the new fallback order is
     * announced fresh rather than measured against switches the old order made,
     * and cached balances are dropped because the default currency may now be
     * a different economy.
     */
    public static void apply(@NotNull EconomySettings newSettings) {
        synchronized (LOCK) {
            settings = newSettings;
            announced.clear();
        }
        BalanceCache.invalidateAll();
    }

    // ------------------------------------------------------------------
    // Registration
    // ------------------------------------------------------------------

    /**
     * Registers a provider under its {@link CurrencyProvider#id()}.
     *
     * <p>A taken id throws rather than overwriting: two plugins both believing
     * they own {@code "coins"} and silently taking turns serving it is exactly
     * the bug a registry exists to prevent, and it must surface while the
     * second plugin is being developed, not after balances split across two
     * backends.
     *
     * @param provider the provider to register
     * @throws EconomyException when the id is already registered
     */
    public static void register(@NotNull CurrencyProvider provider) {
        synchronized (LOCK) {
            CurrencyProvider existing = providers.get(provider.id());
            if (existing != null) {
                throw new EconomyException("Currency id '" + provider.id() + "' is already "
                        + "registered by " + existing.displayName() + " ("
                        + existing.getClass().getName() + "); "
                        + provider.displayName() + " (" + provider.getClass().getName()
                        + ") cannot take it. Pick a distinct id.");
            }
            providers.put(provider.id(), provider);
        }
    }

    /**
     * Removes the provider registered under {@code id}, when a plugin shuts
     * down. Unknown ids are ignored: unregistering something never registered
     * is a harmless order of shutdown events, not an error.
     *
     * <p>Cached balances for the removed currency are dropped, since whatever
     * registers that id next is a different backend.
     */
    public static void unregister(@NotNull String id) {
        CurrencyProvider removed;
        synchronized (LOCK) {
            removed = providers.remove(id);
            announced.values().removeIf(id::equals);
        }
        if (removed != null) {
            BalanceCache.invalidateAll();
        }
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    /**
     * Resolves the provider that should serve an operation.
     *
     * <p>A {@code null} or empty id asks for the default currency of the
     * current {@link EconomySettings}. That provider serves when it is
     * registered and {@link CurrencyProvider#isAvailable() available};
     * otherwise the settings' fallback list is walked in order and the first
     * available provider serves. The switch to a fallback — and the switch
     * back when the named currency recovers — is logged once per change,
     * never per call: a scoreboard resolves every tick, and a warning every
     * tick is a warning nobody reads.
     *
     * <h2>Fallback applies only to the default</h2>
     * A caller that names a currency gets that currency or nothing. Falling
     * back from a named id would mean a plugin asking to charge 500 points,
     * finding points unavailable, and charging 500 from the player's money
     * instead — the right number in the wrong currency, which is worse than
     * the operation failing. The fallback list exists so a server whose
     * default economy disappears keeps working, not so an explicit request
     * quietly becomes a different one.
     *
     * @param id the currency id, or {@code null}/empty for the default
     * @return the provider that can serve, or empty when none can — which
     *         callers turn into {@code EconomyResponse.notAvailable()}
     */
    public static @NotNull Optional<CurrencyProvider> resolve(@Nullable String id) {
        EconomySettings current = settings;
        boolean wantsDefault = id == null || id.isEmpty();
        String wanted = wantsDefault ? current.defaultCurrency() : id;
        CurrencyProvider named = providers.get(wanted);
        if (named != null && named.isAvailable()) {
            announceRecovery(wanted);
            return Optional.of(named);
        }
        if (!wantsDefault) {
            return Optional.empty();
        }
        for (String fallbackId : current.fallback()) {
            if (fallbackId.equals(wanted)) {
                continue;
            }
            CurrencyProvider fallback = providers.get(fallbackId);
            if (fallback != null && fallback.isAvailable()) {
                announceFallback(wanted, fallback);
                return Optional.of(fallback);
            }
        }

        String previous = announced.remove(wanted);
        if (previous != null) {
            logger.warning("Economy: no registered provider can serve '" + wanted
                    + "' any more; economy operations will report not-available.");
        }
        return Optional.empty();
    }

    /** The provider registered under an exact id, without fallback. */
    public static @NotNull Optional<CurrencyProvider> provider(@NotNull String id) {
        return Optional.ofNullable(providers.get(id));
    }

    /** Every registered provider, id → provider. The map is read-only. */
    public static @NotNull Map<String, CurrencyProvider> providers() {
        return Collections.unmodifiableMap(providers);
    }

    /**
     * Whether any registered provider can serve right now. The cheap check a
     * feature makes before offering anything that costs money.
     */
    public static boolean isAvailable() {
        for (CurrencyProvider provider : providers.values()) {
            if (provider.isAvailable()) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Announcements
    // ------------------------------------------------------------------

    /** Logs a fallback switch once per change of serving currency. */
    private static void announceFallback(String wanted, CurrencyProvider serving) {
        String previous = announced.put(wanted, serving.id());
        if (!serving.id().equals(previous)) {
            logger.warning("Economy: currency '" + wanted + "' is not available; '"
                    + serving.id() + "' (" + serving.displayName()
                    + ") is serving instead, per the fallback order in economy.yml.");
        }
    }

    /** Logs the return to the named currency after it served from a fallback. */
    private static void announceRecovery(String wanted) {
        String previous = announced.put(wanted, wanted);
        if (previous != null && !previous.equals(wanted)) {
            logger.info("Economy: currency '" + wanted + "' is available again; '"
                    + previous + "' no longer serves as its fallback.");
        }
    }

    // ------------------------------------------------------------------
    // Test hooks
    // ------------------------------------------------------------------

    /**
     * For tests: forces a provider in place of detection, without touching a
     * server. Package-private, like the equivalent hook in the clan module.
     */
    static void install(@NotNull String id, @NotNull CurrencyProvider provider) {
        synchronized (LOCK) {
            providers.put(id, provider);
        }
    }

    /** For tests: forgets every installed provider and the announcements made. */
    static void clearForTests() {
        synchronized (LOCK) {
            providers.clear();
            announced.clear();
            settings = new EconomySettings();
        }
    }
}
