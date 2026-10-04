package net.exylia.lib.cosmetic;

import net.exylia.lib.economy.EconomyResponse;
import net.exylia.lib.economy.Transaction;
import net.exylia.lib.input.Inputs;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.LibraryMessages;
import net.exylia.lib.text.Text;
import net.exylia.lib.util.crate.PluginCrates;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Buying a cosmetic with currency: confirm, charge, grant, refund on failure.
 *
 * <pre>{@code
 * // A locked entry in a menu, clicked:
 * Price.resolve(entry.price(), entry.tier(), tierPrices).ifPresent(price ->
 *         CosmeticPurchases.confirmAndPurchase(this, player, entry.id(), entry.name(), price, crates)
 *                 .thenAccept(result -> { if (result.bought()) tasks.runAtEntity(player, () -> menu.open(player)); }));
 * }</pre>
 *
 * <h2>The order</h2>
 * Already owned, then one purchase per player at a time, then the charge, then
 * the grant. A grant that answers {@code false} or fails gives the money back;
 * a refund that is refused too is logged as severe with everything needed to
 * fix it by hand. Every outcome tells the player, from {@code messages.yml}
 * under {@code purchase}.
 *
 * <h2>Threads</h2>
 * Callable from any thread. Money moves on the server's global thread, where
 * Vault expects it; the grant's future is awaited, never peeked at. The answer
 * completes on whichever thread finished, so hop back before touching a menu.
 *
 * @since 1.239.0
 */
public final class CosmeticPurchases {

    /** How a purchase ended. */
    public enum Result {
        /** Charged and granted. */
        SUCCESS,
        /** They own it already; nothing was charged. */
        ALREADY_OWNED,
        /** They cannot afford it; nothing was charged. */
        INSUFFICIENT_FUNDS,
        /** No economy serves the currency; nothing was charged. */
        UNAVAILABLE,
        /** A purchase of theirs is still in flight; nothing was charged. */
        BUSY,
        /** They declined the confirmation; nothing was charged. */
        CANCELLED,
        /** Charged, the grant failed, and the money was refunded (or the refund failure logged). */
        FAILED;

        /** Whether the player now owns what they paid for. */
        public boolean bought() {
            return this == SUCCESS;
        }
    }

    /** Players with a purchase in flight, across every plugin: one at a time each. */
    private static final Set<UUID> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    private CosmeticPurchases() {
        throw new AssertionError("No instances.");
    }

    // ------------------------------------------------------------ confirm first

    /**
     * Asks the player to confirm, then {@link #purchase buys} a crate reward.
     *
     * @param plugin   the cosmetic plugin
     * @param player   the buyer
     * @param itemId   the reward id, as the crate stores it
     * @param itemName how the player knows it, from the plugin's config (may carry colours)
     * @param price    what it costs
     * @param crates   the plugin's crates, which own and grant the reward
     * @return how it ended
     */
    public static @NotNull CompletableFuture<Result> confirmAndPurchase(
            @NotNull Plugin plugin, @NotNull Player player, @NotNull String itemId, @NotNull String itemName,
            @NotNull Price price, @NotNull PluginCrates crates) {
        UUID uuid = player.getUniqueId();
        return confirmAndPurchase(plugin, player, itemId, itemName, price,
                () -> crates.owns(uuid, itemId), () -> crates.unlock(uuid, itemId));
    }

    /**
     * Asks the player to confirm, then {@link #purchase buys}.
     *
     * <p>Ownership and funds are checked before the screen opens, so nobody
     * confirms something they cannot buy, and again before the charge.
     *
     * @param owned whether the player owns it already, by every route the plugin knows
     * @param grant gives it to them; answers whether it did
     * @return how it ended; {@link Result#CANCELLED} when they declined or closed the screen
     */
    public static @NotNull CompletableFuture<Result> confirmAndPurchase(
            @NotNull Plugin plugin, @NotNull Player player, @NotNull String itemId, @NotNull String itemName,
            @NotNull Price price, @NotNull BooleanSupplier owned,
            @NotNull Supplier<CompletableFuture<Boolean>> grant) {
        Result refused = precheck(player, itemName, price, owned);
        if (refused != null) return CompletableFuture.completedFuture(refused);

        LibraryMessages.Purchase lines = LibraryMessages.get().purchase();
        // The name and the price come from the server's own config, never from a player.
        String prompt = lines.confirm().replace("%item%", itemName).replace("%price%", price.format());
        return Inputs.of(plugin).confirm(player, prompt)
                .confirmLabel(lines.confirmButton())
                .open()
                .toCompletableFuture()
                .thenCompose(answer -> answer.completed() && Boolean.TRUE.equals(answer.value())
                        ? purchase(plugin, player, itemId, itemName, price, owned, grant)
                        : CompletableFuture.completedFuture(Result.CANCELLED));
    }

    // ------------------------------------------------------------ buy

    /** {@link #purchase(Plugin, Player, String, String, Price, BooleanSupplier, Supplier)} for a crate reward. */
    public static @NotNull CompletableFuture<Result> purchase(
            @NotNull Plugin plugin, @NotNull Player player, @NotNull String itemId, @NotNull String itemName,
            @NotNull Price price, @NotNull PluginCrates crates) {
        UUID uuid = player.getUniqueId();
        return purchase(plugin, player, itemId, itemName, price,
                () -> crates.owns(uuid, itemId), () -> crates.unlock(uuid, itemId));
    }

    /**
     * Charges the price and grants the cosmetic, with no confirmation.
     *
     * @param plugin   the cosmetic plugin; names the ledger entries {@code <plugin>:buy:<itemId>}
     * @param player   the buyer
     * @param itemId   the cosmetic's id
     * @param itemName how the player knows it (may carry colours)
     * @param price    what it costs
     * @param owned    whether the player owns it already
     * @param grant    gives it to them; answers whether it did. {@code false} means refund,
     *                 so a grant that finds it already owned must answer {@code false}
     * @return how it ended
     */
    public static @NotNull CompletableFuture<Result> purchase(
            @NotNull Plugin plugin, @NotNull Player player, @NotNull String itemId, @NotNull String itemName,
            @NotNull Price price, @NotNull BooleanSupplier owned,
            @NotNull Supplier<CompletableFuture<Boolean>> grant) {
        UUID uuid = player.getUniqueId();
        // Taken before any hop, so two clicks in the same tick cannot both get past it.
        if (!IN_FLIGHT.add(uuid)) {
            tell(plugin, player, LibraryMessages.get().purchase().busy(), itemName, price, null);
            return CompletableFuture.completedFuture(Result.BUSY);
        }
        CompletableFuture<Result> done = new CompletableFuture<>();
        done.whenComplete((result, failure) -> IN_FLIGHT.remove(uuid));
        try {
            onGlobal(plugin, () -> {
                try {
                    charge(plugin, player, itemId, itemName, price, owned, grant, done);
                } catch (Throwable failure) {
                    done.completeExceptionally(failure);
                }
            });
        } catch (Throwable failure) {
            done.completeExceptionally(failure);
        }
        return done;
    }

    /** Runs on the global thread: re-checks, charges, and waits for the grant. */
    private static void charge(Plugin plugin, Player player, String itemId, String itemName, Price price,
                               BooleanSupplier owned, Supplier<CompletableFuture<Boolean>> grant,
                               CompletableFuture<Result> done) {
        Result refused = precheck(player, itemName, price, owned);
        if (refused != null) {
            done.complete(refused);
            return;
        }
        UUID uuid = player.getUniqueId();
        LibraryMessages.Purchase lines = LibraryMessages.get().purchase();
        String label = plugin.getName() + ":";
        EconomyResponse charged = price.view().withdraw(uuid, price.amount(),
                Transaction.of(label + "buy:" + itemId).by(uuid));
        if (!charged.isSuccess()) {
            if (charged.type() == EconomyResponse.Type.INSUFFICIENT_FUNDS) {
                tell(plugin, player, lines.insufficient(), itemName, price, price.view().balance(uuid));
                done.complete(Result.INSUFFICIENT_FUNDS);
            } else {
                tell(plugin, player, lines.unavailable(), itemName, price, null);
                done.complete(Result.UNAVAILABLE);
            }
            return;
        }

        CompletableFuture<Boolean> granted;
        try {
            granted = grant.get();
        } catch (Throwable failure) {
            granted = CompletableFuture.failedFuture(failure);
        }
        if (granted == null) granted = CompletableFuture.completedFuture(false);
        granted.whenComplete((ok, failure) -> {
            if (Boolean.TRUE.equals(ok) && failure == null) {
                tell(plugin, player, lines.success(), itemName, price, null);
                done.complete(Result.SUCCESS);
                return;
            }
            // Charged and not granted: the money goes back, on the thread money moves on.
            Runnable refund = () -> {
                Transaction why = Transaction.of(label + "refund:" + itemId).by(uuid);
                EconomyResponse back = price.view().deposit(uuid, price.amount(), why);
                if (back.isSuccess()) {
                    plugin.getLogger().warning("Purchase of '" + itemId + "' by " + uuid + " was charged but not "
                            + "granted (" + (failure == null ? "grant answered false" : failure) + "); "
                            + price.amount() + " " + currencyOf(price) + " refunded.");
                    tell(plugin, player, lines.failed(), itemName, price, null);
                } else {
                    plugin.getLogger().severe("Purchase of '" + itemId + "' by " + uuid + " was charged "
                            + price.amount() + " " + currencyOf(price) + " but not granted, and the refund failed ("
                            + back.message() + "). Give the player " + price.amount() + " " + currencyOf(price)
                            + " back by hand.");
                    tell(plugin, player, lines.refundFailed(), itemName, price, null);
                }
                done.complete(Result.FAILED);
            };
            try {
                onGlobal(plugin, refund);
            } catch (Throwable disabled) {
                // The plugin's scheduler is gone (it is disabling): refund here rather than never.
                refund.run();
            }
        });
    }

    /** What stops a purchase before any money moves, already told to the player; {@code null} to go on. */
    private static Result precheck(Player player, String itemName, Price price, BooleanSupplier owned) {
        LibraryMessages.Purchase lines = LibraryMessages.get().purchase();
        if (owned.getAsBoolean()) {
            say(player, lines.alreadyOwned(), itemName, price, null);
            return Result.ALREADY_OWNED;
        }
        if (!price.payable()) {
            say(player, lines.unavailable(), itemName, price, null);
            return Result.UNAVAILABLE;
        }
        BigDecimal balance = price.view().balance(player.getUniqueId());
        if (balance.compareTo(price.amount()) < 0) {
            say(player, lines.insufficient(), itemName, price, balance);
            return Result.INSUFFICIENT_FUNDS;
        }
        return null;
    }

    private static String currencyOf(Price price) {
        return price.currency() == null ? "(default currency)" : price.currency();
    }

    private static void onGlobal(Plugin plugin, Runnable task) {
        Tasks.of(plugin).execute(task);
    }

    /** Sends on the player's own thread; dropped when they left. */
    private static void tell(Plugin plugin, Player player, String line, String itemName, Price price,
                             BigDecimal balance) {
        TaskScheduler tasks = Tasks.of(plugin);
        try {
            tasks.runAtEntity(player, () -> say(player, line, itemName, price, balance));
        } catch (Throwable disabled) {
            // Nobody left to schedule on; the outcome is still logged and returned.
        }
    }

    private static void say(Player player, String line, String itemName, Price price, BigDecimal balance) {
        if (!player.isOnline()) return;
        Text.of(line)
                .withFormatted("%item%", itemName)
                .withFormatted("%price%", price.format())
                .withFormatted("%balance%", balance == null ? "" : price.view().format(balance))
                .send(player);
    }
}
