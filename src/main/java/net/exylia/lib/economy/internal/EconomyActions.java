package net.exylia.lib.economy.internal;

import net.exylia.lib.economy.CurrencyInfo;
import net.exylia.lib.economy.CurrencyProvider;
import net.exylia.lib.economy.Economy;
import net.exylia.lib.economy.EconomyResponse;
import net.exylia.lib.economy.LedgerEntry;
import net.exylia.lib.economy.Transaction;
import net.exylia.lib.economy.TransferResult;
import net.exylia.lib.format.Formats;
import net.exylia.lib.player.ExyliaPlayer;
import net.exylia.lib.player.ExyliaPlayers;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.LibraryMessages;
import net.exylia.lib.text.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * What the economy commands do, whichever spelling reached them.
 *
 * <p>{@code /economy pay}, {@code /coins pay} and a plugin's own button all
 * end here, so the rules — who may use a currency, the least that may be
 * sent, the tax — are written once.
 */
final class EconomyActions {

    static final String PERMISSION_BALANCE = "exylialib.economy.balance";
    static final String PERMISSION_OTHERS = "exylialib.economy.others";
    static final String PERMISSION_PAY = "exylialib.economy.pay";
    static final String PERMISSION_TOP = "exylialib.economy.top";
    static final String PERMISSION_HISTORY = "exylialib.economy.history";
    static final String PERMISSION_EXCHANGE = "exylialib.economy.exchange";
    static final String PERMISSION_ADMIN = "exylialib.economy.admin";

    private final Plugin plugin;

    EconomyActions(Plugin plugin) {
        this.plugin = plugin;
    }

    private static LibraryMessages.Economy text() {
        return LibraryMessages.get().economy();
    }

    // -------------------------------------------------------------- parsing

    /**
     * Reads an amount the way players type them: {@code 100}, {@code 2.5k},
     * {@code 1m}, {@code 3b}.
     *
     * @return the amount, or {@code null} when the text is not one
     */
    static @Nullable BigDecimal amount(@Nullable String typed) {
        if (typed == null || typed.isBlank()) return null;
        String text = typed.trim().toLowerCase(Locale.ROOT).replace(",", "");
        BigDecimal scale = BigDecimal.ONE;
        char last = text.charAt(text.length() - 1);
        switch (last) {
            case 'k' -> scale = BigDecimal.valueOf(1_000);
            case 'm' -> scale = BigDecimal.valueOf(1_000_000);
            case 'b' -> scale = BigDecimal.valueOf(1_000_000_000);
            default -> { }
        }
        if (scale.compareTo(BigDecimal.ONE) != 0) text = text.substring(0, text.length() - 1);
        try {
            BigDecimal value = new BigDecimal(text).multiply(scale);
            return value.signum() > 0 ? value : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** The currency an argument names, or the default when it names none. */
    static @NotNull Optional<CurrencyProvider> currency(@Nullable String id) {
        if (id == null || id.isBlank()) return CurrencyRegistry.resolve(null);
        return CurrencyRegistry.provider(id.trim().toLowerCase(Locale.ROOT)).filter(CurrencyProvider::isAvailable);
    }

    /** Whether a player may use a stored currency at all. */
    static boolean allowed(CommandSender sender, CurrencyProvider currency) {
        if (!(currency instanceof StoredCurrency stored)) return true;
        String permission = stored.settings().permission();
        return permission.isBlank() || sender.hasPermission(permission);
    }

    // ----------------------------------------------------------- questions

    void balance(CommandSender sender, @Nullable String currencyId, @Nullable String targetName) {
        CurrencyProvider currency = resolved(sender, currencyId);
        if (currency == null) return;
        CurrencyInfo info = Economy.info(currency.id());
        if (targetName == null || targetName.isBlank()) {
            if (!(sender instanceof Player self)) {
                send(sender, text().usage(), "command", "economy");
                return;
            }
            send(sender, text().balance(), "currency", info.namePlural(),
                    "amount", info.format(Economy.of(currency.id()).balance(self.getUniqueId())));
            return;
        }
        if (!sender.hasPermission(PERMISSION_OTHERS)) {
            send(sender, text().noPermission(), "currency", info.namePlural());
            return;
        }
        ExyliaPlayers.then(sender, targetName, found -> send(sender, text().balanceOther(),
                "player", found.name(), "currency", info.namePlural(),
                "amount", info.format(Economy.of(currency.id()).balance(found.id()))));
    }

    void wallet(CommandSender sender, @Nullable String targetName) {
        Consumer<ExyliaPlayer> show = found -> {
            send(sender, text().walletHeader(), "player", found.name());
            for (CurrencyProvider currency : CurrencyRegistry.providers().values()) {
                if (!currency.isAvailable() || !allowed(sender, currency)) continue;
                CurrencyInfo info = Economy.info(currency.id());
                send(sender, text().walletLine(), "currency", info.namePlural(),
                        "amount", info.format(Economy.of(currency.id()).balance(found.id())));
            }
        };
        if (targetName == null || targetName.isBlank()) {
            if (sender instanceof Player self) show.accept(ExyliaPlayers.of(self));
            return;
        }
        if (!sender.hasPermission(PERMISSION_OTHERS)) {
            send(sender, text().noPermission(), "currency", "");
            return;
        }
        ExyliaPlayers.then(sender, targetName, show);
    }

    void currencies(CommandSender sender) {
        send(sender, text().currenciesHeader());
        for (CurrencyProvider currency : CurrencyRegistry.providers().values()) {
            CurrencyInfo info = Economy.info(currency.id());
            send(sender, text().currenciesLine(), "id", currency.id(), "currency", info.namePlural(),
                    "provider", currency.displayName());
        }
    }

    void top(CommandSender sender, @Nullable String currencyId, int page) {
        CurrencyProvider currency = resolved(sender, currencyId);
        if (currency == null) return;
        CurrencyInfo info = Economy.info(currency.id());
        int size = 10;
        int from = Math.max(0, page - 1) * size;
        List<Economy.TopEntry> entries = Economy.top(currency.id(), from + size);
        send(sender, text().topHeader(), "currency", info.namePlural(), "page", page);
        if (entries.size() <= from) {
            send(sender, text().topEmpty());
            return;
        }
        for (Economy.TopEntry entry : entries.subList(from, entries.size())) {
            send(sender, text().topLine(), "position", entry.position(), "player", entry.name(),
                    "amount", info.format(entry.amount()));
        }
    }

    void history(CommandSender sender, @Nullable String currencyId, @Nullable String targetName) {
        CurrencyProvider currency = resolved(sender, currencyId);
        if (currency == null) return;
        CurrencyInfo info = Economy.info(currency.id());
        Consumer<ExyliaPlayer> show = found -> Economy.history(currency.id(), found.id(), 15)
                .thenAccept(lines -> Tasks.of(plugin).run(() -> {
                    send(sender, text().historyHeader(), "currency", info.namePlural(), "player", found.name());
                    if (lines.isEmpty()) {
                        send(sender, text().historyEmpty());
                        return;
                    }
                    for (LedgerEntry line : lines) {
                        String delta = (line.isDeposit() ? "{success}+" : "{error}-") + info.format(line.delta().abs());
                        send(sender, text().historyLine(), "date", Formats.relative(line.at()),
                                "delta", delta, "reason", line.reason(),
                                "balance", info.format(line.balanceAfter()));
                    }
                }));
        if (targetName == null || targetName.isBlank()) {
            if (sender instanceof Player self) show.accept(ExyliaPlayers.of(self));
            return;
        }
        if (!sender.hasPermission(PERMISSION_OTHERS)) {
            send(sender, text().noPermission(), "currency", info.namePlural());
            return;
        }
        ExyliaPlayers.then(sender, targetName, show);
    }

    // ------------------------------------------------------------- players

    void pay(Player sender, @Nullable String currencyId, String targetName, String typedAmount) {
        CurrencyProvider currency = resolved(sender, currencyId);
        if (currency == null) return;
        CurrencyInfo info = Economy.info(currency.id());
        BigDecimal amount = amount(typedAmount);
        if (amount == null) {
            send(sender, text().invalidAmount());
            return;
        }
        StoredCurrency stored = currency instanceof StoredCurrency it ? it : null;
        if (stored != null && !stored.settings().transferable()) {
            send(sender, text().payDisabled(), "currency", info.namePlural());
            return;
        }
        if (stored != null && amount.compareTo(stored.settings().minimumTransfer()) < 0) {
            send(sender, text().payMinimum(), "amount", info.format(stored.settings().minimumTransfer()));
            return;
        }
        ExyliaPlayers.then(sender, targetName, found -> {
            if (found.id().equals(sender.getUniqueId())) {
                send(sender, text().paySelf());
                return;
            }
            BigDecimal tax = stored == null ? BigDecimal.ZERO : StoredEconomy.tax(stored, amount);
            BigDecimal total = amount.add(tax);
            Economy.CurrencyView view = Economy.of(currency.id());
            if (!view.has(sender.getUniqueId(), total)) {
                send(sender, text().notEnough(), "currency", info.namePlural(),
                        "amount", info.format(total.subtract(view.balance(sender.getUniqueId()))));
                return;
            }
            Transaction transaction = Transaction.of("pay").by(sender.getUniqueId());
            if (tax.signum() > 0) {
                EconomyResponse taxed = view.withdraw(sender.getUniqueId(), tax, Transaction.of("pay:tax").by(sender.getUniqueId()));
                if (!taxed.isSuccess()) {
                    send(sender, text().notEnough(), "currency", info.namePlural(), "amount", info.format(tax));
                    return;
                }
            }
            TransferResult result = view.transfer(sender.getUniqueId(), found.id(), amount, transaction);
            if (!result.isSuccess()) {
                if (tax.signum() > 0) view.deposit(sender.getUniqueId(), tax, Transaction.of("pay:tax-refund"));
                send(sender, text().notEnough(), "currency", info.namePlural(), "amount", info.format(amount));
                return;
            }
            send(sender, text().paid(), "player", found.name(), "amount", info.format(amount),
                    "tax", info.format(tax));
            Player receiver = found.here();
            if (receiver != null) {
                send(receiver, text().received(), "player", sender.getName(), "amount", info.format(amount));
            }
        });
    }

    void exchange(Player sender, @Nullable String fromId, String toId, String typedAmount) {
        CurrencyProvider from = resolved(sender, fromId);
        if (from == null) return;
        BigDecimal amount = amount(typedAmount);
        if (amount == null) {
            send(sender, text().invalidAmount());
            return;
        }
        EconomyResponse response = Economy.exchange(sender.getUniqueId(), from.id(), toId, amount);
        if (!response.isSuccess()) {
            String reason = response.type() == EconomyResponse.Type.INSUFFICIENT_FUNDS
                    ? Text.of(text().notEnough()).with("%currency%", Economy.info(from.id()).namePlural())
                            .with("%amount%", Economy.info(from.id()).format(response.shortfall())).plain()
                    : String.valueOf(response.message());
            send(sender, text().exchangeFailed(), "reason", reason);
            return;
        }
        send(sender, text().exchanged(), "from", Economy.info(from.id()).format(amount),
                "to", Economy.format(toId, response.amount()));
    }

    // --------------------------------------------------------------- admin

    void give(CommandSender sender, @Nullable String currencyId, String targetName, String typedAmount) {
        adminOp(sender, currencyId, targetName, typedAmount, (currency, found, amount) -> {
            CurrencyInfo info = Economy.info(currency.id());
            EconomyResponse response = Economy.of(currency.id())
                    .deposit(found.id(), amount, Transaction.of("admin:give").by(initiator(sender)));
            if (!response.isSuccess()) {
                send(sender, text().notAvailable(), "currency", info.namePlural());
                return;
            }
            send(sender, text().given(), "player", found.name(), "amount", info.format(amount),
                    "balance", info.format(response.balance()));
            Player target = found.here();
            if (target != null) send(target, text().givenNotify(), "amount", info.format(amount));
        });
    }

    void take(CommandSender sender, @Nullable String currencyId, String targetName, String typedAmount) {
        adminOp(sender, currencyId, targetName, typedAmount, (currency, found, amount) -> {
            CurrencyInfo info = Economy.info(currency.id());
            EconomyResponse response = Economy.of(currency.id())
                    .withdraw(found.id(), amount, Transaction.of("admin:take").by(initiator(sender)));
            if (!response.isSuccess()) {
                send(sender, text().notEnough(), "currency", info.namePlural(),
                        "amount", info.format(response.shortfall()));
                return;
            }
            send(sender, text().taken(), "player", found.name(), "amount", info.format(amount),
                    "balance", info.format(response.balance()));
            Player target = found.here();
            if (target != null) send(target, text().takenNotify(), "amount", info.format(amount));
        });
    }

    void set(CommandSender sender, @Nullable String currencyId, String targetName, String typedAmount) {
        CurrencyProvider currency = resolved(sender, currencyId);
        if (currency == null) return;
        BigDecimal amount = "0".equals(typedAmount) ? BigDecimal.ZERO : amount(typedAmount);
        if (amount == null) {
            send(sender, text().invalidAmount());
            return;
        }
        ExyliaPlayers.then(sender, targetName, found -> {
            CurrencyInfo info = Economy.info(currency.id());
            EconomyResponse response = Economy.of(currency.id())
                    .set(found.id(), amount, Transaction.of("admin:set").by(initiator(sender)));
            if (!response.isSuccess()) {
                send(sender, text().notAvailable(), "currency", info.namePlural());
                return;
            }
            send(sender, text().set(), "player", found.name(), "currency", info.namePlural(),
                    "amount", info.format(response.balance()));
        });
    }

    void reset(CommandSender sender, @Nullable String currencyId, String targetName) {
        CurrencyProvider currency = resolved(sender, currencyId);
        if (currency == null) return;
        ExyliaPlayers.then(sender, targetName, found -> {
            CurrencyInfo info = Economy.info(currency.id());
            BigDecimal start = currency instanceof StoredCurrency stored ? stored.settings().start() : BigDecimal.ZERO;
            Economy.of(currency.id()).set(found.id(), start, Transaction.of("admin:reset").by(initiator(sender)));
            send(sender, text().reset(), "player", found.name(), "currency", info.namePlural());
        });
    }

    /**
     * Copies every known player's balance from another currency into a stored one.
     *
     * <p>Walks the server's own player list, because neither Vault nor
     * PlayerPoints can list balances: what has played here is what can be
     * imported. Additive — run it once.
     */
    void importFrom(CommandSender sender, String fromId, String intoId) {
        CurrencyProvider from = currency(fromId).orElse(null);
        StoredEconomy economy = StoredEconomy.get();
        StoredCurrency into = economy == null ? null : economy.currency(intoId);
        if (from == null || into == null) {
            send(sender, text().noCurrency(), "currency", from == null ? fromId : intoId);
            return;
        }
        send(sender, text().importStarted(), "from", from.displayName(), "currency", into.info().namePlural());
        Tasks.of(plugin).runAsync(() -> {
            int count = 0;
            for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
                UUID id = player.getUniqueId();
                BigDecimal balance = from.balance(id);
                if (balance.signum() <= 0) continue;
                Economy.of(into.id()).deposit(id, balance, Transaction.of("import:" + from.id()).by(initiator(sender)));
                count++;
            }
            int imported = count;
            Tasks.of(plugin).run(() -> send(sender, text().importDone(), "count", imported,
                    "currency", into.info().namePlural()));
        });
    }

    // -------------------------------------------------------------- inside

    private interface AdminOp {
        void run(CurrencyProvider currency, ExyliaPlayer found, BigDecimal amount);
    }

    private void adminOp(CommandSender sender, @Nullable String currencyId, String targetName,
                         String typedAmount, AdminOp op) {
        CurrencyProvider currency = resolved(sender, currencyId);
        if (currency == null) return;
        BigDecimal amount = amount(typedAmount);
        if (amount == null) {
            send(sender, text().invalidAmount());
            return;
        }
        ExyliaPlayers.then(sender, targetName, found -> op.run(currency, found, amount));
    }

    /** The currency asked for, told to the sender when it cannot be used. */
    private @Nullable CurrencyProvider resolved(CommandSender sender, @Nullable String currencyId) {
        Optional<CurrencyProvider> currency = currency(currencyId);
        if (currency.isEmpty()) {
            send(sender, text().noCurrency(), "currency", currencyId == null ? "" : currencyId);
            return null;
        }
        if (!allowed(sender, currency.get())) {
            send(sender, text().noPermission(), "currency", Economy.info(currency.get().id()).namePlural());
            return null;
        }
        return currency.get();
    }

    private static @Nullable UUID initiator(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : null;
    }

    /** Sends a line with its values, names written without percent signs. */
    static void send(CommandSender to, String line, Object... pairs) {
        if (line == null || line.isBlank()) return;
        Text text = Text.of(line);
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            String name = "%" + pairs[index] + "%";
            Object value = pairs[index + 1];
            // Amounts are formatted with palette tokens; names are what players typed.
            if ("amount".equals(pairs[index]) || "delta".equals(pairs[index]) || "balance".equals(pairs[index])
                    || "from".equals(pairs[index]) || "to".equals(pairs[index]) || "tax".equals(pairs[index])) {
                text = text.withFormatted(name, value);
            } else {
                text = text.with(name, value);
            }
        }
        text.send(to);
    }
}
