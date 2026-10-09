package net.exylia.lib.internal;

import net.exylia.lib.settings.Settings;
import org.bukkit.entity.Player;
import revxrsal.commands.annotation.CommandPlaceholder;
import revxrsal.commands.bukkit.annotation.CommandPermission;
import revxrsal.commands.orphan.OrphanCommand;

/**
 * {@code /settings}, under whatever names the library's config gives it.
 * Registered only when {@code settings.command.enabled} is on.
 */
@CommandPermission("exylialib.settings")
public final class SettingsCommand implements OrphanCommand {

    @CommandPlaceholder
    public void open(Player player) {
        Settings.open(player);
    }
}
