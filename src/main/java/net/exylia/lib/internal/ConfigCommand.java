package net.exylia.lib.internal;

import net.exylia.lib.config.ConfigMenus;
import net.exylia.lib.text.Phrases;
import net.exylia.lib.text.Text;
import net.exylia.lib.web.internal.WebEditorRuntime;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.CommandPlaceholder;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.annotation.Single;
import revxrsal.commands.annotation.SuggestWith;
import revxrsal.commands.autocomplete.SuggestionProvider;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;
import revxrsal.commands.node.ExecutionContext;

import java.util.Collection;

/** {@code /exylialib config [plugin]}: every plugin's settings, changed from a menu. */
@Command("exylialib config")
@CommandPermission(ConfigMenus.PERMISSION)
public final class ConfigCommand {

    /**
     * Opens the plugins with settings, or one plugin's.
     *
     * @param plugin one plugin's settings; the list of plugins otherwise
     */
    @CommandPlaceholder
    public void open(@NotNull CommandSender sender, @Optional @Single @SuggestWith(Configurable.class) String plugin) {
        if (!(sender instanceof Player viewer)) {
            Text.of(Phrases.tr("{error}✘ {letters}Settings are changed in game. From here, use {highlight}/exylialib editor{letters}.")).send(sender);
            return;
        }
        if (plugin == null || plugin.isBlank()) {
            ConfigMenus.open(viewer);
            return;
        }
        Plugin target = Bukkit.getPluginManager().getPlugin(plugin.trim());
        if (target == null || !target.isEnabled()) {
            Text.of(Phrases.tr("{error}✘ {letters}No plugin called {0} is running.", plugin.trim())).send(sender);
            return;
        }
        ConfigMenus.open(viewer, target);
    }

    /** The plugins with settings. */
    public static final class Configurable implements SuggestionProvider<BukkitCommandActor> {

        @Override
        public Collection<String> getSuggestions(@NotNull ExecutionContext<BukkitCommandActor> context) {
            return WebEditorRuntime.get().configurablePlugins().stream().map(Plugin::getName).toList();
        }
    }
}
