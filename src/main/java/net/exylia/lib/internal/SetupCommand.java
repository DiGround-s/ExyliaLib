package net.exylia.lib.internal;

import org.bukkit.entity.Player;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Subcommand;
import revxrsal.commands.bukkit.annotation.CommandPermission;

/**
 * {@code /exylialib setup}: the server's first-run setup (language, menus, look, database), any time.
 * {@code /exylialib setup later} is the invitation's LATER button.
 */
@Command("exylialib")
@CommandPermission(SetupWizard.PERMISSION)
public final class SetupCommand {

    @Subcommand("setup")
    public void open(Player player) {
        SetupMenu.open(player, SetupMenu.HUB);
    }

    @Subcommand("setup later")
    public void later(Player player) {
        SetupWizard.later(player);
    }
}
