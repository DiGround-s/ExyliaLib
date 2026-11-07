package net.exylia.lib.internal;

import net.exylia.lib.screen.ScreenSet;
import net.exylia.lib.text.Phrases;
import net.exylia.lib.text.Text;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.annotation.Subcommand;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /exylialib screens check|reload [plugin]}: every plugin's screen files compiled, with what is wrong in them
 * by file, line and column, or read again after an edit.
 */
@Command("exylialib screens")
@CommandPermission("exylialib.admin")
public final class ScreensCommand {

    private static final int SHOWN = 30;

    /** Compiles every screen (of one plugin) and lists its problems. */
    @Subcommand("check")
    public void check(@NotNull CommandSender sender, @Optional String plugin) {
        List<String> problems = new ArrayList<>();
        int screens = 0;
        for (ScreenSet set : sets(plugin)) {
            screens += set.markups().size();
            for (String problem : set.problems()) problems.add(set.plugin().getName() + "/" + set.folder() + "/" + problem);
        }
        StringBuilder out = new StringBuilder("{primary}&lSCREENS");
        if (problems.isEmpty()) {
            out.append("\n").append(Phrases.tr("{letters_black}▎ {success}No problems {letters_black}» {letters}Screens{letters_black}: {info}{0}", screens));
        } else {
            out.append("\n").append(Phrases.tr("{letters_black}▎ {warning}Problems{letters_black}: {warning}{0} {letters_black}» {letters}Screens{letters_black}: {info}{1}", problems.size(), screens));
            for (String problem : problems.subList(0, Math.min(SHOWN, problems.size()))) {
                out.append("\n{letters_black}  · {letters}").append(Text.escape(problem));
            }
        }
        Text.of(out.toString()).send(sender);
    }

    /** Reads every screen (of one plugin) again from its files. */
    @Subcommand("reload")
    public void reload(@NotNull CommandSender sender, @Optional String plugin) {
        int screens = 0;
        for (ScreenSet set : sets(plugin)) {
            set.reload();
            screens += set.markups().size();
        }
        Text.of("{primary}&lSCREENS\n" + Phrases.tr("{letters_black}▎ {success}Read again {letters_black}» {letters}Screens{letters_black}: {info}{0}", screens))
                .send(sender);
    }

    private static List<ScreenSet> sets(String plugin) {
        return ScreenSet.all().stream()
                .filter(set -> plugin == null || set.plugin().getName().equalsIgnoreCase(plugin))
                .toList();
    }
}
