package net.exylia.lib.cosmetic;

import net.exylia.lib.config.Comment;
import net.exylia.lib.config.Key;
import net.exylia.lib.economy.Economy;
import net.exylia.lib.network.NetworkGroups;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Where what a player unlocks in a cosmetic plugin counts. Each plugin declares
 * it in its own config with a {@link Scope} and hands it over with
 * {@link #set(Plugin, Scope)} before its crates or stores start; a plugin that
 * declares nothing keeps every unlock global.
 *
 * <p>An unlock is kept either globally, counting on every server, or under this
 * server's scope, counting only on servers of that scope. What was unlocked
 * globally always counts. A purchase paid in one of the scope's global
 * currencies is kept globally whatever the scope; while its grant runs,
 * {@link #grantingShared()} answers {@code true}.
 *
 * @since 1.618.0
 */
public final class CosmeticScopes {

    private static final ThreadLocal<Boolean> SHARED = ThreadLocal.withInitial(() -> false);
    private static final Map<String, Scope> SCOPES = new ConcurrentHashMap<>();

    private CosmeticScopes() {
        throw new AssertionError("No instances.");
    }

    /**
     * A plugin's scope block, meant to sit in its config record.
     *
     * @param scope            global, server, default (this server's network-group) or a group name
     * @param globalCurrencies currencies whose purchases count on every server
     * @since 1.619.0
     */
    public record Scope(
            @Comment("Where what is unlocked here counts: global, server, default (this server's")
            @Comment("network-group) or a group name. What was unlocked globally always counts. Restart to apply.")
            String scope,

            @Key("global-currencies")
            @Comment("Currencies whose purchases count on every server, whatever the scope.")
            List<String> globalCurrencies
    ) {

        /** The default: everything is global. */
        public Scope() {
            this("global", List.of());
        }
    }

    /** Declares a plugin's scope; {@code null} returns it to global. */
    public static void set(@NotNull Plugin plugin, @Nullable Scope scope) {
        if (scope == null) SCOPES.remove(plugin.getName());
        else SCOPES.put(plugin.getName(), scope);
    }

    /** This server's scope for a plugin's unlocks; empty when they are global. */
    public static @NotNull String home(@NotNull Plugin plugin) {
        Scope scope = SCOPES.get(plugin.getName());
        return scope == null || scope.scope() == null ? "" : NetworkGroups.scope(plugin, scope.scope());
    }

    /** Whether the grant running on this thread is one kept globally. */
    public static boolean grantingShared() {
        return SHARED.get();
    }

    /** Whether a purchase in a plugin at this price is kept globally. */
    public static boolean isShared(@NotNull Plugin plugin, @NotNull Price price) {
        Scope scope = SCOPES.get(plugin.getName());
        List<String> global = scope == null ? null : scope.globalCurrencies();
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
