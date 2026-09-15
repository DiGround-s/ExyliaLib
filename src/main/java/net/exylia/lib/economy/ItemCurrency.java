package net.exylia.lib.economy;

import net.exylia.lib.economy.internal.PlayerThreadBalances;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.reward.PluginRewards;
import net.exylia.lib.util.reward.RewardEntry;
import net.exylia.lib.util.reward.RewardResult;
import net.exylia.lib.util.reward.Rewards;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * An item as a currency: emeralds, diamonds, a custom token.
 *
 * <pre>{@code
 * PluginRewards rewards = Rewards.of(this)
 *         .overflow(OverflowPolicy.QUEUE)
 *         .pending(PendingRewards.database(this));
 * Economy.register(ItemCurrency.of(rewards, info, new ItemStack(Material.EMERALD)));
 * }</pre>
 *
 * <p>The balance is how many of the item a player carries; paying takes them
 * out of the inventory and being paid puts them in. Two items are the same
 * currency when {@link ItemStack#isSimilar} says so, so a renamed token is a
 * currency of its own.
 *
 * <h2>Nothing lands on the ground</h2>
 * A deposit is a reward delivery, under the plugin's
 * {@link PluginRewards#overflow overflow policy}: with {@code QUEUE} what does
 * not fit is kept and handed over on the next join. Somebody who is not on
 * this server, or who leaves before their thread runs the deposit, is owed the
 * items through the plugin's pending rewards. A delivery that gives nothing —
 * {@code FAIL} with a full inventory, no pending store for an absent player —
 * answers a failure; one that gives part answers the part.
 *
 * <h2>Threads</h2>
 * An inventory belongs to the player's thread. A withdraw anywhere else is
 * refused, and a balance anywhere else answers the last count taken there
 * while a fresh one is taken.
 *
 * @since 1.163.0
 */
public final class ItemCurrency implements CurrencyProvider {

    private final PluginRewards rewards;
    private final TaskScheduler tasks;
    private final CurrencyInfo info;
    private final ItemStack item;
    private final String snapshot;
    private final PlayerThreadBalances balances;

    private ItemCurrency(PluginRewards rewards, CurrencyInfo info, ItemStack item) {
        this.rewards = rewards;
        this.tasks = Tasks.of(rewards.plugin());
        this.info = info;
        this.item = item.clone();
        this.item.setAmount(1);
        this.snapshot = Rewards.snapshot(this.item);
        this.balances = new PlayerThreadBalances(tasks, this::count);
    }

    /**
     * An item currency.
     *
     * @param rewards how deposits are delivered: its overflow policy and pending store apply
     * @param info    the currency's id, names and formats
     * @param item    the item; its amount is ignored
     * @return the currency, to register
     */
    public static @NotNull ItemCurrency of(@NotNull PluginRewards rewards, @NotNull CurrencyInfo info,
                                           @NotNull ItemStack item) {
        return new ItemCurrency(Objects.requireNonNull(rewards, "rewards"), Objects.requireNonNull(info, "info"),
                Objects.requireNonNull(item, "item"));
    }

    /** The item this currency is, one of it. */
    public @NotNull ItemStack item() {
        return item.clone();
    }

    @Override
    public @NotNull String id() {
        return info.id();
    }

    @Override
    public @NotNull String displayName() {
        return info.namePlural();
    }

    @Override
    public @NotNull CurrencyInfo info() {
        return info;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public @NotNull BigDecimal balance(@NotNull UUID player) {
        return balances.balance(player);
    }

    @Override
    public @NotNull CompletableFuture<BigDecimal> balanceLater(@NotNull UUID player) {
        return balances.later(player);
    }

    @Override
    public @NotNull EconomyResponse deposit(@NotNull UUID player, @NotNull BigDecimal amount) {
        int units = PlayerThreadBalances.units(amount);
        if (units <= 0) {
            return EconomyResponse.invalidAmount();
        }
        Player online = Bukkit.getPlayer(player);
        if (online != null && tasks.isOwnedBy(online)) {
            int given = give(online, units);
            return given > 0
                    ? EconomyResponse.success(BigDecimal.valueOf(given), balances.read(online))
                    : EconomyResponse.failure("The items did not fit and the overflow policy refused to keep them.");
        }
        boolean queued = true;
        if (online != null) {
            try {
                tasks.runAtEntity(online, () -> give(online, units), () -> owe(player, units));
            } catch (RuntimeException stopped) {
                queued = owe(player, units);
            }
        } else {
            queued = owe(player, units);
        }
        return queued
                ? EconomyResponse.success(BigDecimal.valueOf(units), balances.last(player))
                : EconomyResponse.failure("The items could not be kept for a player who is not here.");
    }

    @Override
    public @NotNull EconomyResponse withdraw(@NotNull UUID player, @NotNull BigDecimal amount) {
        Player online = Bukkit.getPlayer(player);
        if (online == null || !tasks.isOwnedBy(online)) {
            return EconomyResponse.failure("Items can only be taken on the player's own thread, on their server.");
        }
        int units = PlayerThreadBalances.units(amount);
        if (units <= 0) {
            return EconomyResponse.invalidAmount();
        }
        BigDecimal current = balances.read(online);
        if (current.compareTo(BigDecimal.valueOf(units)) < 0) {
            return EconomyResponse.insufficientFunds(amount, current);
        }
        int left = units;
        PlayerInventory inventory = online.getInventory();
        ItemStack[] storage = inventory.getStorageContents();
        for (int slot = 0; slot < storage.length && left > 0; slot++) {
            ItemStack stack = storage[slot];
            if (stack == null || !stack.isSimilar(item)) continue;
            int taken = Math.min(left, stack.getAmount());
            if (taken == stack.getAmount()) {
                storage[slot] = null;
            } else {
                stack.setAmount(stack.getAmount() - taken);
            }
            left -= taken;
        }
        inventory.setStorageContents(storage);
        return EconomyResponse.success(BigDecimal.valueOf(units), balances.read(online));
    }

    @Override
    public @NotNull String currencyName(boolean plural) {
        return plural ? info.namePlural() : info.name();
    }

    @Override
    public @NotNull String symbol() {
        return info.symbol();
    }

    private BigDecimal count(Player online) {
        long count = 0;
        for (ItemStack stack : online.getInventory().getStorageContents()) {
            if (stack != null && stack.isSimilar(item)) count += stack.getAmount();
        }
        return BigDecimal.valueOf(count);
    }

    /** Delivers on the player's thread and answers how many were given or kept by the policy. */
    private int give(Player online, int units) {
        int size = Math.max(1, item.getMaxStackSize());
        int given = 0;
        for (int left = units; left > 0; left -= size) {
            int amount = Math.min(size, left);
            RewardResult result = rewards.give(online, stack(amount));
            if (result.isGiven()) {
                given += amount;
            }
        }
        balances.read(online);
        return given;
    }

    /** One entry per full stack, so no entry holds more than the item stacks to. */
    private boolean owe(UUID player, int units) {
        int size = Math.max(1, item.getMaxStackSize());
        List<RewardEntry> stacks = new ArrayList<>(units / size + 1);
        for (int left = units; left > 0; left -= size) {
            stacks.add(stack(Math.min(size, left)));
        }
        return rewards.giveLater(player, stacks);
    }

    private RewardEntry stack(int amount) {
        return RewardEntry.item(snapshot).fixedAmount(amount).build();
    }
}
