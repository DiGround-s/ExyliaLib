package net.exylia.lib.economy.internal;

import net.exylia.lib.economy.CurrencyInfo;
import net.exylia.lib.economy.Economy;
import net.exylia.lib.placeholder.Placeholders;
import net.exylia.lib.placeholder.Request;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * {@code %economy_...%}: balances of every currency.
 *
 * <pre>
 * %economy_balance%              the default currency, formatted
 * %economy_balance_coins%        one currency, formatted
 * %economy_compact_coins%        the same, short
 * %economy_raw_coins%            the number alone
 * %economy_name_coins%           the currency's plural name
 * %economy_symbol_coins%
 * </pre>
 *
 * <p>Every one of these reads memory: a loaded balance or the balance cache.
 * None touches the database on the thread that asked. Leaderboards belong to
 * whichever plugin keeps the balances, because only it can rank them.
 */
public final class EconomyPlaceholders {

    private EconomyPlaceholders() {
    }

    public static void register(Plugin plugin) {
        Placeholders.group(plugin, "economy")
                .describe("Balances of every currency")
                .add("balance", request -> info(request).format(balance(request)))
                .add("compact", request -> info(request).formatCompact(balance(request)))
                .add("raw", request -> info(request).scale(balance(request)).toPlainString())
                .add("name", request -> info(request).namePlural())
                .add("symbol", request -> info(request).symbol())
                .register();
    }

    private static CurrencyInfo info(Request request) {
        String id = request.arg(0, "");
        return Economy.info(id.isEmpty() ? null : id);
    }

    private static BigDecimal balance(Request request) {
        UUID viewer = request.requireViewer().getUniqueId();
        String id = request.arg(0, "");
        return Economy.of(id.isEmpty() ? "" : id).balance(viewer);
    }
}
