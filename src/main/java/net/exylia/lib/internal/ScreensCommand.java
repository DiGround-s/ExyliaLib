package net.exylia.lib.internal;

import net.exylia.lib.screen.ScreenSet;
import net.exylia.lib.text.Phrases;
import net.exylia.lib.text.Text;
import net.exylia.lib.screen.ScreenMarkup;
import net.exylia.lib.screen.Screens;
import net.exylia.lib.ui.UiEntry;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.annotation.Subcommand;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import revxrsal.commands.annotation.Single;
import revxrsal.commands.annotation.SuggestWith;
import revxrsal.commands.autocomplete.SuggestionProvider;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.node.ExecutionContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * {@code /exylialib screens check|reload [plugin]}: every plugin's screen files compiled, with what is wrong in them
 * by file, line and column, or read again after an edit; {@code gallery} opens every element on one screen, and
 * {@code preview <plugin> <id>} opens any plugin's screen as it is now.
 */
@Command("exylialib screens")
@CommandPermission("exylialib.admin")
public final class ScreensCommand {

    private static final int SHOWN = 30;

    /** Compiles every screen (of one plugin) and lists its problems. */
    @Subcommand("check")
    public void check(@NotNull CommandSender sender, @Optional @Single @SuggestWith(Plugins.class) String plugin) {
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
    public void reload(@NotNull CommandSender sender, @Optional @Single @SuggestWith(Plugins.class) String plugin) {
        int screens = 0;
        for (ScreenSet set : sets(plugin)) {
            set.reload();
            screens += set.markups().size();
        }
        Text.of("{primary}&lSCREENS\n" + Phrases.tr("{letters_black}▎ {success}Read again {letters_black}» {letters}Screens{letters_black}: {info}{0}", screens))
                .send(sender);
    }

    /** Opens the gallery: every element, the component library and the motion (plugins/ExyliaLib/gallery). */
    @Subcommand("gallery")
    public void gallery(@NotNull Player player) {
        ScreenMarkup gallery = gallery();
        gallery.problems().forEach(problem -> Text.of("{warning}" + Text.escape(problem)).send(player));
        Screens.of(JavaPlugin.getProvidingPlugin(ScreensCommand.class)).open(player, gallery.screen());
    }

    /** Opens a plugin's screen by id, as its files are now, with no state: for authors checking an edit. */
    @Subcommand("preview")
    public void preview(@NotNull Player player, @Single @SuggestWith(Plugins.class) String plugin,
                        @Single @SuggestWith(Ids.class) String id) {
        for (ScreenSet set : sets(plugin)) {
            ScreenMarkup markup = set.markups().get(id);
            if (markup == null) continue;
            Screens.of(set.plugin()).open(player, markup.screen());
            return;
        }
        Text.of("{primary}&lSCREENS\n" + Phrases.tr("{letters_black}▎ {error}No screen {0} in {1}", Text.escape(id),
                Text.escape(plugin))).send(player);
    }

    private static final Material[] ITEMS = {Material.DIAMOND_SWORD, Material.IRON_AXE, Material.BOW,
            Material.FISHING_ROD, Material.SHEARS, Material.DIAMOND_PICKAXE, Material.GOLDEN_APPLE, Material.BREAD,
            Material.COOKED_BEEF, Material.APPLE, Material.CAKE, Material.MUSHROOM_STEW, Material.ENDER_PEARL,
            Material.TOTEM_OF_UNDYING, Material.SHIELD, Material.ELYTRA};

    /** The gallery's markup, its demo list bound once. */
    static ScreenMarkup gallery() {
        JavaPlugin lib = JavaPlugin.getProvidingPlugin(ScreensCommand.class);
        ScreenMarkup markup = Screens.of(lib).files(ScreensCommand.class, "gallery").markup("gallery");
        return markup
                .text("viewer", session -> session.viewer().getName())
                .rows("items", session -> {
                    String kind = session.state("kind", "all");
                    String q = session.state("q", "").toLowerCase(java.util.Locale.ROOT);
                    List<UiEntry> rows = new ArrayList<>();
                    for (Material item : ITEMS) {
                        boolean food = item.isEdible() || item == Material.CAKE;
                        if (kind.equals("food") != food && !kind.equals("all")) continue;
                        String name = item.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
                        if (!q.isEmpty() && !name.contains(q)) continue;
                        rows.add(UiEntry.of(item).with("item_id", item.name().toLowerCase(java.util.Locale.ROOT))
                                .with("item_name", name).build());
                    }
                    return rows;
                });
    }

    /** Suggests the plugins that have screen files. */
    public static final class Plugins implements SuggestionProvider<BukkitCommandActor> {
        @Override
        public Collection<String> getSuggestions(@NotNull ExecutionContext<BukkitCommandActor> context) {
            return ScreenSet.all().stream().map(set -> set.plugin().getName()).distinct().sorted().toList();
        }
    }

    /** Suggests the screen ids of the plugin typed before, or of every plugin. */
    public static final class Ids implements SuggestionProvider<BukkitCommandActor> {
        @Override
        public Collection<String> getSuggestions(@NotNull ExecutionContext<BukkitCommandActor> context) {
            String plugin = context.getResolvedArgumentOrNull("plugin");
            return sets(plugin).stream().flatMap(set -> set.markups().keySet().stream()).distinct().sorted().toList();
        }
    }

    private static List<ScreenSet> sets(String plugin) {
        return ScreenSet.all().stream()
                .filter(set -> plugin == null || set.plugin().getName().equalsIgnoreCase(plugin))
                .toList();
    }
}
