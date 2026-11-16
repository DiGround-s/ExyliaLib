package net.exylia.lib.cosmetic;

import net.exylia.lib.economy.Economy;
import net.exylia.lib.internal.LibrarySettings;
import net.exylia.lib.network.NetworkGroups;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.Supplier;

/**
 * Where what a player unlocks in a cosmetic plugin counts: the {@code cosmetics:}
 * block of ExyliaLib's config.yml.
 *
 * <p>An unlock is kept either globally, counting on every server, or under this
 * server's scope, counting only on servers of that scope. What was unlocked
 * globally always counts. A purchase paid in one of {@code global-currencies}
 * is kept globally whatever the scope; while its grant runs,
 * {@link #grantingShared()} answers {@code true}.
 *
 * @since 1.618.0
 */
public final class CosmeticScopes {

    private static final ThreadLocal<Boolean> SHARED = ThreadLocal.withInitial(() -> false);

    private CosmeticScopes() {
        throw new AssertionError("No instances.");
    }

    /** This server's scope for a plugin's unlocks; empty when they are global. */
    public static @NotNull String home(@NotNull Plugin plugin) {
        LibrarySettings.Cosmetics cosmetics = LibrarySettings.get().cosmetics();
        return cosmetics == null ? "" : NetworkGroups.scope(plugin, cosmetics.scope());
    }

    /** Whether the grant running on this thread is one kept globally. */
    public static boolean grantingShared() {
        return SHARED.get();
    }

    /** Whether a purchase at this price is kept globally. */
    public static boolean isShared(@NotNull Price price) {
        LibrarySettings.Cosmetics cosmetics = LibrarySettings.get().cosmetics();
        List<String> global = cosmetics == null ? null : cosmetics.globalCurrencies();
        if (global == null || global.isEmpty()) return false;
        String currency;
        try {
            currency = price.currency() == null ? Economy.defaultId() : Economy.canonical(price.currency());
        } catch (RuntimeException noEconomy) {
            return false;
        }
        for (String id : global) if (id != null && id.trim().equalsIgnoreCase(currency)) return true;
        return false;
    }

    /** Runs a grant, kept globally when {@code shared}. */
    public static <T> T granting(boolean shared, @NotNull Supplier<T> grant) {
        if (!shared) return grant.get();
        boolean before = SHARED.get();
        SHARED.set(true);
        try {
            return grant.get();
        } finally {
            SHARED.set(before);
        }
    }
}
