package net.exylia.lib.economy.internal;

import net.exylia.lib.economy.CurrencyProvider;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /coins}, {@code /gems}: a command per currency, named by its file.
 *
 * <p>Registered straight into the server's command map, because the names
 * are not known until {@code currencies.yml} is read and a Lamp command is
 * declared by annotation. Each one is {@code /economy} with the currency
 * filled in, so there is exactly one implementation of what "pay" means.
 */
final class AliasCommands {

    private static final List<Command> REGISTERED = new ArrayList<>();

    private AliasCommands() {
    }

    /** Replaces every alias command with the ones the file names now. */
    static void install(Plugin plugin, StoredEconomy economy) {
        for (Command old : REGISTERED) {
            old.unregister(Bukkit.getCommandMap());
            Bukkit.getCommandMap().getKnownCommands().values().removeIf(known -> known == old);
        }
        REGISTERED.clear();

        EconomyActions actions = new EconomyActions(plugin);
        for (StoredCurrency currency : economy.currencies()) {
            if (currency.settings().commands()) {
                register(plugin, actions, currency, currency.settings().aliases());
            }
        }
        for (CurrencyProvider extra : economy.extras()) {
            if (extra instanceof ItemCurrency item && !item.settings().aliases().isEmpty()) {
                register(plugin, actions, item, item.settings().aliases());
            }
        }
        for (Player online : Bukkit.getOnlinePlayers()) online.updateCommands();
    }

    private static void register(Plugin plugin, EconomyActions actions, CurrencyProvider currency,
                                 List<String> aliases) {
        if (aliases.isEmpty()) return;
        Alias command = new Alias(aliases.get(0), aliases.subList(1, aliases.size()), currency.id(), actions);
        if (Bukkit.getCommandMap().register(plugin.getName().toLowerCase(Locale.ROOT), command)) {
            REGISTERED.add(command);
        } else {
            plugin.getLogger().warning("Economy: the command /" + aliases.get(0) + " for '" + currency.id()
                    + "' is taken by another plugin; use /" + plugin.getName().toLowerCase(Locale.ROOT)
                    + ":" + aliases.get(0) + " or rename the alias.");
            REGISTERED.add(command);
        }
    }

    /** One currency's command. */
    private static final class Alias extends Command {

        private final String currency;
        private final EconomyActions actions;

        Alias(String name, List<String> aliases, String currency, EconomyActions actions) {
            super(name, "Your " + currency + " balance", "/" + name + " [pay <player> <amount>|top|history]",
                    aliases);
            this.currency = currency;
            this.actions = actions;
            setPermission(EconomyActions.PERMISSION_BALANCE);
        }

        @Override
        public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
            if (!testPermission(sender)) return true;
            if (args.length == 0) {
                actions.balance(sender, currency, null);
                return true;
            }
            String sub = args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "pay" -> {
                    if (args.length < 3 || !(sender instanceof Player player)) {
                        EconomyActions.send(sender, net.exylia.lib.text.LibraryMessages.get().economy().usage(),
                                "command", label);
                    } else if (sender.hasPermission(EconomyActions.PERMISSION_PAY)) {
                        actions.pay(player, currency, args[1], args[2]);
                    }
                }
                case "top" -> {
                    if (sender.hasPermission(EconomyActions.PERMISSION_TOP)) {
                        actions.top(sender, currency, args.length > 1 ? parsePage(args[1]) : 1);
                    }
                }
                case "history" -> {
                    if (sender.hasPermission(EconomyActions.PERMISSION_HISTORY)) {
                        actions.history(sender, currency, args.length > 1 ? args[1] : null);
                    }
                }
                case "exchange" -> {
                    if (args.length < 3 || !(sender instanceof Player player)) {
                        EconomyActions.send(sender, net.exylia.lib.text.LibraryMessages.get().economy().usage(),
                                "command", label);
                    } else if (sender.hasPermission(EconomyActions.PERMISSION_EXCHANGE)) {
                        actions.exchange(player, currency, args[2], args[1]);
                    }
                }
                case "give", "take", "set", "reset" -> {
                    if (!sender.hasPermission(EconomyActions.PERMISSION_ADMIN)) return true;
                    if (args.length < 2 || (!sub.equals("reset") && args.length < 3)) {
                        EconomyActions.send(sender, net.exylia.lib.text.LibraryMessages.get().economy().usage(),
                                "command", label);
                        return true;
                    }
                    switch (sub) {
                        case "give" -> actions.give(sender, currency, args[1], args[2]);
                        case "take" -> actions.take(sender, currency, args[1], args[2]);
                        case "set" -> actions.set(sender, currency, args[1], args[2]);
                        default -> actions.reset(sender, currency, args[1]);
                    }
                }
                default -> actions.balance(sender, currency, args[0]);
            }
            return true;
        }

        @Override
        public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias,
                                                 @NotNull String[] args) {
            if (args.length == 1) {
                List<String> options = new ArrayList<>(List.of("pay", "top", "history", "exchange"));
                if (sender.hasPermission(EconomyActions.PERMISSION_ADMIN)) {
                    options.addAll(List.of("give", "take", "set", "reset"));
                }
                options.removeIf(option -> !option.startsWith(args[0].toLowerCase(Locale.ROOT)));
                return options;
            }
            if (args.length == 2 && !args[0].equalsIgnoreCase("top")) {
                return null; // The server offers player names.
            }
            return List.of();
        }

        private static int parsePage(String typed) {
            try {
                return Math.max(1, Integer.parseInt(typed));
            } catch (NumberFormatException notANumber) {
                return 1;
            }
        }
    }
}
