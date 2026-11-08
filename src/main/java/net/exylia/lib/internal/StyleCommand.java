package net.exylia.lib.internal;

import net.exylia.lib.text.Phrases;
import net.exylia.lib.text.Text;
import net.exylia.lib.ui.MenuStyle;
import net.exylia.lib.ui.MenuStyles;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.annotation.Single;
import revxrsal.commands.annotation.Subcommand;
import revxrsal.commands.annotation.SuggestWith;
import revxrsal.commands.autocomplete.SuggestionProvider;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;
import revxrsal.commands.node.ExecutionContext;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * {@code /exylialib style <classic|screen|default> [player] [plugin]}: switches a player's menu style on the spot,
 * for one plugin or all, to compare chest and screen menus without editing any config. {@code default} clears it.
 */
@Command("exylialib")
@CommandPermission("exylialib.admin")
public final class StyleCommand {

    @Subcommand("style")
    public void style(@NotNull CommandSender sender, @Single @SuggestWith(Styles.class) String style,
                      @Optional Player target, @Optional @Single @SuggestWith(Plugins.class) String plugin) {
        Player who = target != null ? target : sender instanceof Player self ? self : null;
        if (who == null) {
            reply(sender, Phrases.tr("{letters_black}▎ {error}Name a player"));
            return;
        }
        String wanted = style.toLowerCase(Locale.ROOT);
        MenuStyle parsed = MenuStyle.parse(wanted);
        if (parsed == null && !wanted.equals("default")) {
            reply(sender, Phrases.tr("{letters_black}▎ {error}Style must be classic, screen or default"));
            return;
        }
        MenuStyles.override(who, plugin, parsed);
        if (parsed == null) {
            reply(sender, Phrases.tr("{letters_black}▎ {success}Menu style cleared {letters_black}» {letters}{0}", who.getName()));
            return;
        }
        reply(sender, Phrases.tr("{letters_black}▎ {success}Menu style set {letters_black}» {letters}{0}{letters_black}: {info}{1}",
                who.getName(), wanted + (plugin == null ? "" : " · " + plugin)));
        if (parsed == MenuStyle.SCREEN && !MenuStyles.canShowScreens(who)) {
            reply(sender, Phrases.tr("{letters_black}▎ {warning}{0} cannot draw screens, they still get classic menus", who.getName()));
        }
    }

    private static void reply(CommandSender sender, String line) {
        Text.of("{primary}&lSTYLE\n" + line).send(sender);
    }

    public static final class Styles implements SuggestionProvider<BukkitCommandActor> {
        @Override
        public Collection<String> getSuggestions(@NotNull ExecutionContext<BukkitCommandActor> context) {
            return List.of("classic", "screen", "default");
        }
    }

    public static final class Plugins implements SuggestionProvider<BukkitCommandActor> {
        @Override
        public Collection<String> getSuggestions(@NotNull ExecutionContext<BukkitCommandActor> context) {
            return MenuStyles.plugins();
        }
    }
}
