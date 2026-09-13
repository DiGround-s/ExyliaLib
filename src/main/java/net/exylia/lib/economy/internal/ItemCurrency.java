package net.exylia.lib.economy.internal;

import net.exylia.lib.economy.CurrencyInfo;
import net.exylia.lib.economy.CurrencyProvider;
import net.exylia.lib.economy.EconomyResponse;
import net.exylia.lib.economy.Transaction;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * An item as a currency: emeralds, diamonds, a custom token.
 *
 * <p>The balance is how many of the item a player is carrying; paying takes
 * them out of the inventory, being paid puts them in, and what does not fit
 * lands at their feet — which is what a chest shop paying in emeralds has
 * always done. The item is in their hands, not in a table, so only somebody
 * on this server has a balance or can pay; somebody who is not here is still
 * paid, on the next server that holds them.
 *
 * <p>Two items are the same currency when {@link ItemStack#isSimilar} says so,
 * which is what lets a renamed custom token be a currency of its own.
 */
public final class ItemCurrency implements CurrencyProvider {

    private final CurrencyFile.Item settings;
    private final Plugin plugin;
    private final StoredEconomy economy;
    private volatile ItemStack prototype;

    ItemCurrency(CurrencyFile.Item settings, Plugin plugin, StoredEconomy economy) {
        this.settings = settings;
        this.plugin = plugin;
        this.economy = economy;
    }

    public @NotNull CurrencyFile.Item settings() {
        return settings;
    }

    @Override
    public @NotNull String id() {
        return settings.id();
    }

    @Override
    public @NotNull String displayName() {
        return settings.info().namePlural();
    }

    @Override
    public @NotNull CurrencyInfo info() {
        return settings.info();
    }

    @Override
    public boolean isAvailable() {
        return prototype() != null;
    }

    @Override
    public @NotNull BigDecimal balance(@NotNull UUID player) {
        Player online = Bukkit.getPlayer(player);
        ItemStack sample = prototype();
        if (online == null || sample == null) return BigDecimal.ZERO;
        long count = 0;
        for (ItemStack stack : online.getInventory().getStorageContents()) {
            if (stack != null && stack.isSimilar(sample)) count += stack.getAmount();
        }
        return BigDecimal.valueOf(count);
    }

    @Override
    public @NotNull EconomyResponse deposit(@NotNull UUID player, @NotNull BigDecimal amount) {
        return deposit(player, amount, Transaction.NONE);
    }

    @Override
    public @NotNull EconomyResponse deposit(@NotNull UUID player, @NotNull BigDecimal amount,
                                            @NotNull Transaction transaction) {
        ItemStack sample = prototype();
        if (sample == null) return EconomyResponse.failure("This item currency names no item.");
        int units = amount.intValue();
        if (units <= 0) return EconomyResponse.invalidAmount();
        EconomyResponse later = economy.give(this, player, BigDecimal.valueOf(units), transaction, online -> {
            int left = units;
            while (left > 0) {
                ItemStack stack = sample.clone();
                int size = Math.min(left, stack.getMaxStackSize());
                stack.setAmount(size);
                Map<Integer, ItemStack> overflow = online.getInventory().addItem(stack);
                for (ItemStack dropped : overflow.values()) {
                    online.getWorld().dropItemNaturally(online.getLocation(), dropped);
                }
                left -= size;
            }
        });
        return later != null ? later : EconomyResponse.success(BigDecimal.valueOf(units), balance(player));
    }

    @Override
    public @NotNull EconomyResponse withdraw(@NotNull UUID player, @NotNull BigDecimal amount) {
        Player online = Bukkit.getPlayer(player);
        ItemStack sample = prototype();
        if (online == null || sample == null) {
            return EconomyResponse.failure("Items can only be taken from a player who is here.");
        }
        int units = amount.intValue();
        if (units <= 0) return EconomyResponse.invalidAmount();
        BigDecimal current = balance(player);
        if (current.compareTo(BigDecimal.valueOf(units)) < 0) {
            return EconomyResponse.insufficientFunds(amount, current);
        }
        int left = units;
        PlayerInventory inventory = online.getInventory();
        ItemStack[] storage = inventory.getStorageContents();
        for (int slot = 0; slot < storage.length && left > 0; slot++) {
            ItemStack stack = storage[slot];
            if (stack == null || !stack.isSimilar(sample)) continue;
            int taken = Math.min(left, stack.getAmount());
            if (taken == stack.getAmount()) {
                storage[slot] = null;
            } else {
                stack.setAmount(stack.getAmount() - taken);
            }
            left -= taken;
        }
        inventory.setStorageContents(storage);
        return EconomyResponse.success(BigDecimal.valueOf(units), balance(player));
    }

    @Override
    public @NotNull String currencyName(boolean plural) {
        return plural ? settings.info().namePlural() : settings.info().name();
    }

    @Override
    public @NotNull String symbol() {
        return settings.info().symbol();
    }

    /**
     * The item this currency is, built once.
     *
     * <p>A material name or a {@code bytes:} snapshot, the same two spellings
     * every item in the ecosystem's files uses. Unreadable is reported once
     * and the currency stays unavailable rather than counting nothing.
     */
    private @Nullable ItemStack prototype() {
        ItemStack built = prototype;
        if (built != null) return built;
        String raw = settings.item().trim();
        try {
            if (raw.toLowerCase(Locale.ROOT).startsWith("bytes:")) {
                built = ItemStack.deserializeBytes(Base64.getDecoder().decode(raw.substring("bytes:".length())));
            } else {
                Material material = Material.matchMaterial(raw.toUpperCase(Locale.ROOT));
                if (material == null || !material.isItem()) throw new IllegalArgumentException("not an item: " + raw);
                built = new ItemStack(material);
            }
            built.setAmount(1);
            prototype = built;
            return built;
        } catch (RuntimeException unreadable) {
            plugin.getLogger().warning("currencies.yml: item currency '" + settings.id() + "' names '"
                    + raw + "', which is not an item (" + unreadable.getMessage() + ").");
            return null;
        }
    }
}
