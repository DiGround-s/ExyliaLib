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
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.List;
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
 * <h2>A choice of currencies</h2>
 * A {@link Price} with alternatives is paid in one of them. When the player can
 * pay more than one, they are asked which, each choice saying whether it counts
 * on this server only or on the whole network ({@link CosmeticScopes}); that
 * question is the confirmation. With only one they can pay, that one is charged.
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

    /** Players with a purchase in flight, across every plugin: one at a time each, until it ends or stalls. */
    private static final java.util.Map<UUID, CompletableFuture<Result>> IN_FLIGHT = new ConcurrentHashMap<>();

    /**
     * Each {@code plugin:uuid:item} being bought, held until its purchase ends however long that takes.
     * A stalled grant frees the player for other purchases, never for paying twice for the same item.
     */
    private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();

    /** How long a grant may hold the player's lock before other purchases may go on beside it. */
    private static final long STALL_SECONDS = 30;

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
        List<Price> options = price.options();
        if (options.size() > 1) {
            if (owned.getAsBoolean()) {
                say(player, LibraryMessages.get().purchase().alreadyOwned(), itemName, price, null);
                return CompletableFuture.completedFuture(Result.ALREADY_OWNED);
            }
            return choose(plugin, player, itemId, itemName, options, owned, grant);
        }
        if (options.size() == 1) price = options.getFirst();
        Result refused = precheck(player, itemName, price, owned);
        if (refused != null) return CompletableFuture.completedFuture(refused);

        LibraryMessages.Purchase lines = LibraryMessages.get().purchase();
        Price single = price;
        // The name and the price come from the server's own config, never from a player.
        String prompt = lines.confirm().replace("%item%", itemName).replace("%price%", price.format());
        return Inputs.of(plugin).confirm(player, prompt)
                .confirmLabel(lines.confirmButton())
                .open()
                .toCompletableFuture()
                .thenCompose(answer -> answer.completed() && Boolean.TRUE.equals(answer.value())
                        ? purchase(plugin, player, itemId, itemName, single, owned, grant)
                        : CompletableFuture.completedFuture(Result.CANCELLED));
    }

    /** Asks which currency to pay in, each choice saying where the unlock counts, then buys with it. */
    private static CompletableFuture<Result> choose(Plugin plugin, Player player, String itemId, String itemName,
                                                    List<Price> options, BooleanSupplier owned,
                                                    Supplier<CompletableFuture<Boolean>> grant) {
        LibraryMessages.Purchase lines = LibraryMessages.get().purchase();
        boolean global = CosmeticScopes.home(plugin).isEmpty();
        return Inputs.of(plugin).choice(player, lines.choose().replace("%item%", itemName), options)
                .label(Price::format)
                .key(option -> option.currency() == null ? "default" : option.currency())
                .description(option -> global || CosmeticScopes.isShared(plugin, option)
                        ? lines.optionShared() : lines.optionLocal())
                .open()
                .toCompletableFuture()
                .thenCompose(answer -> answer.completed()
                        ? purchase(plugin, player, itemId, itemName, answer.value(), owned, grant)
                        : CompletableFuture.completedFuture(Result.CANCELLED));
    }

    /** The ways of paying {@code price} that {@code player} can afford, in the config's order. */
    private static List<Price> affordable(Player player, List<Price> options) {
        UUID uuid = player.getUniqueId();
        return options.stream().filter(option -> option.view().balance(uuid).compareTo(option.amount()) >= 0).toList();
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
     * Charges the price and grants the cosmetic, with no confirmation. A choice of currencies the player
     * can pay more than one of still asks which.
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
        List<Price> options = price.options();
        if (options.size() > 1) {
            List<Price> can = affordable(player, options);
            if (can.size() > 1 && !owned.getAsBoolean()) return choose(plugin, player, itemId, itemName, can, owned, grant);
            // None: the first is refused for want of money, as a one-currency price is.
            price = can.isEmpty() ? options.getFirst() : can.getFirst();
        } else if (options.size() == 1) {
            price = options.getFirst();
        }
        Price paid = price;
        UUID uuid = player.getUniqueId();
        // Taken before any hop, so two clicks in the same tick cannot both get past it.
        CompletableFuture<Result> done = new CompletableFuture<>();
        String item = plugin.getName() + ':' + uuid + ':' + itemId;
        if (!PENDING.add(item)) {
            tell(plugin, player, LibraryMessages.get().purchase().busy(), itemName, paid, null);
            return CompletableFuture.completedFuture(Result.BUSY);
        }
        if (IN_FLIGHT.putIfAbsent(uuid, done) != null) {
            PENDING.remove(item);
            tell(plugin, player, LibraryMessages.get().purchase().busy(), itemName, paid, null);
            return CompletableFuture.completedFuture(Result.BUSY);
        }
        done.whenComplete((result, failure) -> {
            IN_FLIGHT.remove(uuid, done);
            PENDING.remove(item);
        });
        // A grant that never answers (a database gone quiet) must not lock the player out of buying
        // anything until a restart. Only the lock goes: nothing is refunded, since the grant may still
        // land, and its refund-on-failure stays attached. The item itself stays pending, so it cannot
        // be charged twice.
        CompletableFuture.delayedExecutor(STALL_SECONDS, java.util.concurrent.TimeUnit.SECONDS).execute(() -> {
            if (!done.isDone() && IN_FLIGHT.remove(uuid, done)) {
                plugin.getLogger().warning("Purchase of '" + itemId + "' by " + uuid + " has waited "
                        + STALL_SECONDS + "s for its grant; other purchases may go on, this one settles when it answers.");
            }
        });
        try {
            // The player's thread, not the global one: items and experience can only be taken there.
            Tasks.of(plugin).runAtEntity(player, () -> {
                try {
                    charge(plugin, player, itemId, itemName, paid, owned, grant, done);
                } catch (Throwable failure) {
                    done.completeExceptionally(failure);
                }
            }, () -> done.complete(Result.UNAVAILABLE));
        } catch (Throwable failure) {
            done.completeExceptionally(failure);
        }
        return done;
    }

    /** Runs on the player's thread: re-checks, charges, and waits for the grant. */
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
            granted = CosmeticScopes.granting(CosmeticScopes.isShared(plugin, price), grant);
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

    /**
     * What would stop a purchase before any money moves, told to the player as a purchase would tell
     * it: already owned, no economy for the currency, or not enough money. {@code null} when it may go
     * on. For a menu that asks in place (a basket's total, before its CONFIRM is armed); call it on the
     * player's thread.
     *
     * @since 1.416.0
     */
    public static @Nullable Result check(@NotNull Player player, @NotNull String itemName, @NotNull Price price,
                                         @NotNull BooleanSupplier owned) {
        List<Price> options = price.options();
        if (options.size() > 1) {
            List<Price> can = affordable(player, options);
            if (!can.isEmpty()) return precheck(player, itemName, can.getFirst(), owned);
            return precheck(player, itemName, options.getFirst(), owned);
        }
        return precheck(player, itemName, options.isEmpty() ? price : options.getFirst(), owned);
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
