package net.exylia.lib.economy.internal;

import net.exylia.lib.economy.CurrencyInfo;
import net.exylia.lib.economy.Economy;
import net.exylia.lib.placeholder.Placeholders;
import net.exylia.lib.placeholder.Request;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * {@code %economy_...%}: balances and tops for every currency.
 *
 * <pre>
 * %economy_balance%              the default currency, formatted
 * %economy_balance_coins%        one currency, formatted
 * %economy_compact_coins%        the same, short
 * %economy_raw_coins%            the number alone
 * %economy_name_coins%           the currency's plural name
 * %economy_symbol_coins%
 * %economy_top_name_1_coins%     the richest player
 * %economy_top_amount_1_coins%
 * </pre>
 *
 * <p>Every one of these reads memory: a loaded balance, the balance cache or
 * the cached leaderboard. None touches the database on the thread that asked.
 */
public final class EconomyPlaceholders {

    private EconomyPlaceholders() {
    }

    public static void register(Plugin plugin) {
        Placeholders.group(plugin, "economy")
                .describe("Balances and leaderboards of every currency")
                .add("balance", request -> info(request, 0).format(balance(request, 0)))
                .add("compact", request -> info(request, 0).formatCompact(balance(request, 0)))
                .add("raw", request -> info(request, 0).scale(balance(request, 0)).toPlainString())
                .add("name", request -> info(request, 0).namePlural())
                .add("symbol", request -> info(request, 0).symbol())
                .add("top_name", request -> top(request).map(Economy.TopEntry::name).orElse("—"))
                .add("top_amount", request -> top(request)
                        .map(entry -> info(request, 1).format(entry.amount()))
                        .orElse(info(request, 1).format(BigDecimal.ZERO)))
                .register();
    }

    private static CurrencyInfo info(Request request, int index) {
        String id = request.arg(index, "");
        return Economy.info(id.isEmpty() ? null : id);
    }

    private static BigDecimal balance(Request request, int index) {
        UUID viewer = request.requireViewer().getUniqueId();
        String id = request.arg(index, "");
        return Economy.of(id.isEmpty() ? "" : id).balance(viewer);
    }

    /** {@code top_name_<position>_<currency>}. */
    private static java.util.Optional<Economy.TopEntry> top(Request request) {
        int position = Math.max(1, request.arg(0, 1));
        String id = request.arg(1, "");
        List<Economy.TopEntry> entries = Economy.top(id.isEmpty() ? CurrencyRegistry.defaultId() : id, position);
        return entries.size() >= position ? java.util.Optional.of(entries.get(position - 1)) : java.util.Optional.empty();
    }
}
