package net.exylia.lib.economy.internal;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.CommandPlaceholder;
import revxrsal.commands.annotation.Default;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.annotation.Subcommand;
import revxrsal.commands.bukkit.annotation.CommandPermission;

/**
 * {@code /economy}: every currency, one command.
 *
 * <p>The per-currency spellings — {@code /coins pay ...} — are
 * {@link AliasCommands}, and both end in {@link EconomyActions}.
 */
@Command({"economy", "eco", "currency"})
public final class EconomyCommand {

    private final EconomyActions actions;

    public EconomyCommand(Plugin plugin) {
        this.actions = new EconomyActions(plugin);
    }

    @CommandPlaceholder
    @CommandPermission(EconomyActions.PERMISSION_BALANCE)
    public void root(CommandSender sender) {
        actions.wallet(sender, null);
    }

    @Subcommand("balance")
    @CommandPermission(EconomyActions.PERMISSION_BALANCE)
    public void balance(CommandSender sender, @Optional String currency, @Optional String player) {
        actions.balance(sender, currency, player);
    }

    @Subcommand("wallet")
    @CommandPermission(EconomyActions.PERMISSION_BALANCE)
    public void wallet(CommandSender sender, @Optional String player) {
        actions.wallet(sender, player);
    }

    @Subcommand("currencies")
    @CommandPermission(EconomyActions.PERMISSION_BALANCE)
    public void currencies(CommandSender sender) {
        actions.currencies(sender);
    }

    @Subcommand("pay")
    @CommandPermission(EconomyActions.PERMISSION_PAY)
    public void pay(Player sender, String player, String amount, @Optional String currency) {
        actions.pay(sender, currency, player, amount);
    }

    @Subcommand("top")
    @CommandPermission(EconomyActions.PERMISSION_TOP)
    public void top(CommandSender sender, @Optional String currency, @Default("1") int page) {
        actions.top(sender, currency, page);
    }

    @Subcommand("history")
    @CommandPermission(EconomyActions.PERMISSION_HISTORY)
    public void history(CommandSender sender, @Optional String currency, @Optional String player) {
        actions.history(sender, currency, player);
    }

    @Subcommand("exchange")
    @CommandPermission(EconomyActions.PERMISSION_EXCHANGE)
    public void exchange(Player sender, String amount, String from, String to) {
        actions.exchange(sender, from, to, amount);
    }

    @Subcommand("give")
    @CommandPermission(EconomyActions.PERMISSION_ADMIN)
    public void give(CommandSender sender, String player, String amount, @Optional String currency) {
        actions.give(sender, currency, player, amount);
    }

    @Subcommand("take")
    @CommandPermission(EconomyActions.PERMISSION_ADMIN)
    public void take(CommandSender sender, String player, String amount, @Optional String currency) {
        actions.take(sender, currency, player, amount);
    }

    @Subcommand("set")
    @CommandPermission(EconomyActions.PERMISSION_ADMIN)
    public void set(CommandSender sender, String player, String amount, @Optional String currency) {
        actions.set(sender, currency, player, amount);
    }

    @Subcommand("reset")
    @CommandPermission(EconomyActions.PERMISSION_ADMIN)
    public void reset(CommandSender sender, String player, @Optional String currency) {
        actions.reset(sender, currency, player);
    }

    @Subcommand("import")
    @CommandPermission(EconomyActions.PERMISSION_ADMIN)
    public void importFrom(CommandSender sender, String from, String into) {
        actions.importFrom(sender, from, into);
    }
}
