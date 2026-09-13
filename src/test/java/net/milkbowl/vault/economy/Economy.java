package net.milkbowl.vault.economy;

import org.bukkit.OfflinePlayer;

/** The part of Vault's economy interface the library calls, for tests without Vault. */
public interface Economy {

    String getName();

    double getBalance(OfflinePlayer player);

    EconomyResponse depositPlayer(OfflinePlayer player, double amount);

    EconomyResponse withdrawPlayer(OfflinePlayer player, double amount);

    String currencyNameSingular();

    String currencyNamePlural();
}
