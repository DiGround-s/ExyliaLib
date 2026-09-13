package net.exylia.lib.internal;

import net.exylia.lib.action.ActionContext;
import net.exylia.lib.action.ActionResult;
import net.exylia.lib.action.Actions;
import net.exylia.lib.action.PluginActions;
import net.exylia.lib.config.internal.DefaultUpdates;
import net.exylia.lib.config.internal.DefaultsMerge;
import net.exylia.lib.text.Lines;
import net.exylia.lib.text.Text;
import net.exylia.lib.ui.Menus;
import net.exylia.lib.ui.PluginMenus;
import net.exylia.lib.ui.UiEntry;
import net.exylia.lib.ui.UiKeys;
import net.exylia.lib.ui.UiSection;
import net.exylia.lib.ui.UiSession;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The screens behind {@code /exylialib updates}: plugins, their files, and each change.
 *
 * <p>The library ships no resource files, so the screens are YAML held here and
 * compiled like any plugin's menu. Which plugin and file a screen is showing lives
 * in its session context, never in a per-player map, so two admins reviewing at
 * once never answer each other's clicks.
 */
public final class UpdatesMenu {

    static final String NAMESPACE = "exylialib";
    static final String PLUGINS = "updates";
    static final String FILES = "updates_files";
    static final String CHANGES = "updates_changes";

    private static final String PLUGIN_KEY = "updates_plugin";
    private static final String FILE_KEY = "updates_file";

    /** Lore lines a value may take before the rest is summarised. */
    private static final int MAX_LINES = 8;

    /** Characters a lore line may take before it is cut. */
    private static final int MAX_WIDTH = 48;

    private static volatile PluginMenus menus;

    /**
     * What a row or a button decides on: a whole plugin, one of its files, or one change.
     *
     * @param plugin the plugin, or {@code null} for every plugin
     * @param file   the file, or {@code null} for every file of the plugin
     * @param id     the change, or {@code -1} for every change in scope
     */
    record Scope(@Nullable String plugin, @Nullable String file, int id) {

        Predicate<DefaultUpdates.Pending> matches() {
            return pending -> (plugin == null || pending.plugin().equals(plugin))
                    && (file == null || pending.file().equals(file))
                    && (id < 0 || pending.id() == id);
        }
    }

    private UpdatesMenu() {
    }

    /**
     * Registers the actions and compiles the screens.
     *
     * <p>After the menu listener is registered, and once: registering an action
     * twice throws.
     *
     * @param plugin the library
     */
    public static void init(@NotNull Plugin plugin) {
        PluginActions actions = Actions.of(plugin, NAMESPACE);
        actions.registerSync("updates_fill", (context, arguments) -> {
            fill(context.require(UiKeys.SESSION));
            return ActionResult.success();
        });
        actions.registerSync("updates_open", (context, arguments) -> openScope(context));
        actions.registerSync("updates_apply", (context, arguments) -> decide(context, true));
        actions.registerSync("updates_keep", (context, arguments) -> decide(context, false));

        PluginMenus built = Menus.of(plugin, NAMESPACE);
        load(built);
        menus = built;
    }

    /** Recompiles the screens, so a palette reload recolours them. */
    public static void reload() {
        PluginMenus current = menus;
        if (current != null) {
            current.unload();
            load(current);
        }
    }

    /** Forgets the screens, when the library disables. */
    public static void release() {
        menus = null;
    }

    /**
     * Opens the list of plugins with pending changes.
     *
     * @param player who reviews; any thread
     */
    public static void open(@NotNull Player player) {
        PluginMenus current = menus;
        if (current != null) {
            current.open(player, PLUGINS);
        }
    }

    private static void load(PluginMenus target) {
        target.load(PLUGINS, yaml(PLUGINS_YAML));
        target.load(FILES, yaml(FILES_YAML));
        target.load(CHANGES, yaml(CHANGES_YAML));
    }

    private static YamlConfiguration yaml(String text) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(text);
        } catch (InvalidConfigurationException broken) {
            throw new IllegalStateException("The library's own updates menu does not parse", broken);
        }
        return yaml;
    }

    // ------------------------------------------------------------------ actions

    private static ActionResult openScope(ActionContext context) {
        Scope scope = context.get(UiKeys.ENTRY).filter(Scope.class::isInstance).map(Scope.class::cast).orElse(null);
        PluginMenus current = menus;
        if (scope == null || scope.plugin() == null || current == null) {
            return ActionResult.stop("no row");
        }
        Map<String, Object> screen = new HashMap<>();
        screen.put(PLUGIN_KEY, scope.plugin());
        if (scope.file() != null) {
            screen.put(FILE_KEY, scope.file());
        }
        current.open(context.player(), scope.file() == null ? FILES : CHANGES, screen);
        return ActionResult.success();
    }

    private static ActionResult decide(ActionContext context, boolean apply) {
        UiSession session = context.require(UiKeys.SESSION);
        // A row decides on what it shows; a button on everything the screen shows.
        Scope scope = context.get(UiKeys.ENTRY).filter(Scope.class::isInstance).map(Scope.class::cast)
                .orElseGet(() -> new Scope(
                        session.context(PLUGIN_KEY, String.class).orElse(null),
                        session.context(FILE_KEY, String.class).orElse(null),
                        -1));
        DefaultUpdates.Decision decision = DefaultUpdates.decide(scope.matches(), apply);
        feedback(decision, apply).send(context.player());

        fill(session);
        PluginMenus current = menus;
        if (current != null && session.entries(UiSection.MAIN).isEmpty()
                && session.context(PLUGIN_KEY, String.class).isPresent()) {
            // Nothing left on this screen: step back to the one that listed it.
            current.back(context.player());
        }
        return ActionResult.success();
    }

    static Text feedback(DefaultUpdates.Decision decision, boolean apply) {
        int decided = apply ? decision.applied() : decision.kept();
        StringBuilder raw = new StringBuilder(apply
                ? "{success}✔ {letters}Applied {info}" + decided + (decided == 1 ? " {letters}change" : " {letters}changes")
                : "{secondary}✔ {letters}Kept {info}" + decided + (decided == 1 ? " {letters}value" : " {letters}values"));
        if (!decision.reloaded().isEmpty()) {
            raw.append(" {letters_black}» {letters}reloaded ").append(String.join(", ", decision.reloaded()));
        }
        if (!decision.manual().isEmpty()) {
            raw.append(" {letters_black}» {warning}reload ").append(String.join(", ", decision.manual()))
                    .append(" {warning}to see it");
        }
        if (decision.stale() > 0) {
            raw.append(" {letters_black}» {warning}").append(decision.stale()).append(" changed on disk and were left alone");
        }
        return Text.of(raw.toString());
    }

    // ------------------------------------------------------------------ rows

    private static void fill(UiSession session) {
        String plugin = session.context(PLUGIN_KEY, String.class).orElse(null);
        String file = session.context(FILE_KEY, String.class).orElse(null);
        List<DefaultUpdates.Pending> pending = DefaultUpdates.pending();
        if (plugin == null) {
            session.entries(pluginRows(pending));
        } else if (file == null) {
            session.entries(fileRows(pending, plugin));
        } else {
            session.entries(changeRows(pending, plugin, file));
        }
    }

    static List<UiEntry> pluginRows(List<DefaultUpdates.Pending> pending) {
        Map<String, List<DefaultUpdates.Pending>> byPlugin = new LinkedHashMap<>();
        pending.forEach(entry -> byPlugin.computeIfAbsent(entry.plugin(), key -> new ArrayList<>()).add(entry));
        List<UiEntry> rows = new ArrayList<>(byPlugin.size());
        byPlugin.forEach((plugin, changes) -> rows.add(UiEntry.of(new Scope(plugin, null, -1))
                .with("plugin", plugin.toUpperCase(Locale.ROOT))
                .with("files", changes.stream().map(DefaultUpdates.Pending::file).distinct().count())
                .with("changes", changes.size())
                .build()));
        return rows;
    }

    static List<UiEntry> fileRows(List<DefaultUpdates.Pending> pending, String plugin) {
        Map<String, Integer> byFile = new LinkedHashMap<>();
        pending.stream().filter(entry -> entry.plugin().equals(plugin))
                .forEach(entry -> byFile.merge(entry.file(), 1, Integer::sum));
        List<UiEntry> rows = new ArrayList<>(byFile.size());
        byFile.forEach((file, count) -> rows.add(UiEntry.of(new Scope(plugin, file, -1))
                .with("file", file)
                .with("changes", count)
                .build()));
        return rows;
    }

    static List<UiEntry> changeRows(List<DefaultUpdates.Pending> pending, String plugin, String file) {
        return pending.stream()
                .filter(entry -> entry.plugin().equals(plugin) && entry.file().equals(file))
                .map(entry -> UiEntry.of(new Scope(plugin, file, entry.id()))
                        .with("key", entry.change().dotted())
                        .with("current", lines(entry.change().current()))
                        .with("shipped", lines(entry.change().shipped()))
                        .template(switch (entry.change().kind()) {
                            case ADDED -> "added";
                            case REMOVED -> "removed";
                            case CHANGED -> null;
                        })
                        .build())
                .toList();
    }

    /**
     * A value as lore lines: a list one element per line, a section one key per
     * line, indented, and anything long cut short.
     *
     * <p>Inserted literally, so a lore line full of palette tokens reads as the
     * owner will find it in the file.
     */
    static String lines(@Nullable Object value) {
        List<String> out = new ArrayList<>();
        collect(value, "", out);
        if (out.isEmpty()) {
            out.add("nothing");
        }
        if (out.size() > MAX_LINES) {
            int hidden = out.size() - (MAX_LINES - 1);
            out = new ArrayList<>(out.subList(0, MAX_LINES - 1));
            out.add("… " + hidden + " more");
        }
        List<String> clipped = new ArrayList<>(out.size());
        for (String line : out) {
            clipped.add(line.length() > MAX_WIDTH ? line.substring(0, MAX_WIDTH - 1) + "…" : line);
        }
        return String.join(Lines.NEWLINE, clipped);
    }

    private static void collect(@Nullable Object value, String indent, List<String> out) {
        if (value == null) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, entry) -> {
                if (entry instanceof Map<?, ?> || entry instanceof List<?>) {
                    out.add(indent + key + ":");
                    collect(entry, indent + "  ", out);
                } else {
                    out.add(indent + key + ": " + entry);
                }
            });
            return;
        }
        if (value instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> || entry instanceof List<?>) {
                    out.add(indent + "-");
                    collect(entry, indent + "  ", out);
                } else {
                    out.add(indent + "- " + entry);
                }
            }
            return;
        }
        out.add(indent + value);
    }

    // ------------------------------------------------------------------ screens

    private static final String FRAME = """
            filler:
              global:
                material: BLACK_STAINED_GLASS_PANE
                hide_tooltip: true
              pagination:
                material: LIME_STAINED_GLASS_PANE
                name: '{success}&lALL CAUGHT UP'
                lore:
                  - ''
                  - ' {letters_black}▎ {letters}No default change waits for a decision.'
                  - ''
            """;

    private static final String NAVIGATION = """
              navigation:
                previous:
                  slot: 45
                  material: ARROW
                  name: '{error}&l← PREVIOUS PAGE'
                next:
                  slot: 53
                  material: ARROW
                  name: '{success}&lNEXT PAGE →'
            """;

    static final String PLUGINS_YAML = """
            title: '{primary}&lUPDATES {muted}%current_page%/%total_pages%'
            size: 54
            open-actions:
              - 'exylialib:updates_fill'
            """ + FRAME + """
            pagination:
              slots: '10-16,19-25,28-34,37-43'
              item_template:
                material: WRITABLE_BOOK
                name: '{primary}&l%plugin% &8[{warning}%changes%&8]'
                lore:
                  - ''
                  - '{secondary}Information:'
                  - ' {letters_black}▎ {letters}Files {letters_black}» {info}%files%'
                  - ' {letters_black}▎ {letters}Changes {letters_black}» {info}%changes%'
                  - ''
                  - '{warning}➥ Click to review'
                  - '{warning}➥ Shift + left click to apply all'
                  - '{warning}➥ Shift + right click to keep all'
                  - ''
                actions:
                  - 'left,right: exylialib:updates_open'
                  - 'shift_left: exylialib:updates_apply'
                  - 'shift_right: exylialib:updates_keep'
            """ + NAVIGATION + """
            items:
              info:
                slot: 4
                material: NETHER_STAR
                name: '{primary}&lPLUGIN UPDATES'
                lore:
                  - ''
                  - '{secondary}Information:'
                  - ' {letters_black}▎ {letters}New settings arrive on their own.'
                  - ' {letters_black}▎ {letters}A {highlight}changed default {letters}waits here:'
                  - ' {letters_black}▎ {letters}your value may be the one you want.'
                  - ''
              apply:
                slot: 48
                material: LIME_DYE
                name: '{success}&lAPPLY EVERYTHING'
                lore:
                  - ''
                  - ' {letters_black}▎ {letters}Writes every new default and reloads'
                  - ' {letters_black}▎ {letters}the plugins they belong to.'
                  - ''
                  - '{warning}➥ Click to apply all'
                  - ''
                actions:
                  - 'exylialib:updates_apply'
              close:
                slot: 49
                material: BARRIER
                name: '{error}&lCLOSE'
                actions:
                  - 'close'
              keep:
                slot: 50
                material: GRAY_DYE
                name: '{letters_black}&lKEEP EVERYTHING'
                lore:
                  - ''
                  - ' {letters_black}▎ {letters}Leaves every value as it is and stops'
                  - ' {letters_black}▎ {letters}listing these changes.'
                  - ''
                  - '{warning}➥ Click to keep all'
                  - ''
                actions:
                  - 'exylialib:updates_keep'
            """;

    static final String FILES_YAML = """
            title: '{primary}&l%updates_plugin% {muted}%current_page%/%total_pages%'
            size: 54
            parent: 'exylialib:updates'
            open-actions:
              - 'exylialib:updates_fill'
            """ + FRAME + """
            pagination:
              slots: '10-16,19-25,28-34,37-43'
              item_template:
                material: PAPER
                name: '{primary}&l%file% &8[{warning}%changes%&8]'
                lore:
                  - ''
                  - '{secondary}Information:'
                  - ' {letters_black}▎ {letters}Changes {letters_black}» {info}%changes%'
                  - ''
                  - '{warning}➥ Click to review'
                  - '{warning}➥ Shift + left click to apply all'
                  - '{warning}➥ Shift + right click to keep all'
                  - ''
                actions:
                  - 'left,right: exylialib:updates_open'
                  - 'shift_left: exylialib:updates_apply'
                  - 'shift_right: exylialib:updates_keep'
            """ + NAVIGATION + """
            items:
              apply:
                slot: 48
                material: LIME_DYE
                name: '{success}&lAPPLY ALL'
                lore:
                  - ''
                  - ' {letters_black}▎ {letters}Every change in this plugin.'
                  - ''
                  - '{warning}➥ Click to apply all'
                  - ''
                actions:
                  - 'exylialib:updates_apply'
              back:
                slot: 49
                material: BARRIER
                name: '{error}&lBACK'
                actions:
                  - 'back'
              keep:
                slot: 50
                material: GRAY_DYE
                name: '{letters_black}&lKEEP ALL'
                lore:
                  - ''
                  - ' {letters_black}▎ {letters}Every value in this plugin stays as it is.'
                  - ''
                  - '{warning}➥ Click to keep all'
                  - ''
                actions:
                  - 'exylialib:updates_keep'
            """;

    static final String CHANGES_YAML = """
            title: '{primary}&l%updates_file% {muted}%current_page%/%total_pages%'
            size: 54
            parent: 'exylialib:updates'
            open-actions:
              - 'exylialib:updates_fill'
            """ + FRAME + """
            pagination:
              slots: '10-16,19-25,28-34,37-43'
              item_template:
                material: ORANGE_DYE
                name: '{primary}&l%key%'
                lore:
                  - ''
                  - '{secondary}Your value:'
                  - ' {letters_black}▎ {muted}%current%'
                  - ''
                  - '{secondary}New default:'
                  - ' {letters_black}▎ {highlight}%shipped%'
                  - ''
                  - '{warning}➥ Left click to apply the new default'
                  - '{warning}➥ Right click to keep your value'
                  - ''
                actions:
                  - 'left,shift_left: exylialib:updates_apply'
                  - 'right,shift_right: exylialib:updates_keep'
              added_template:
                material: LIME_DYE
                name: '{primary}&l%key% &8[{success}NEW&8]'
                lore:
                  - ''
                  - '{secondary}New setting:'
                  - ' {letters_black}▎ {highlight}%shipped%'
                  - ''
                  - ' {letters_black}▎ {letters}Not in your file yet. It may be one'
                  - ' {letters_black}▎ {letters}you removed on purpose.'
                  - ''
                  - '{warning}➥ Left click to add it'
                  - '{warning}➥ Right click to leave it out'
                  - ''
                actions:
                  - 'left,shift_left: exylialib:updates_apply'
                  - 'right,shift_right: exylialib:updates_keep'
              removed_template:
                material: RED_DYE
                name: '{primary}&l%key% &8[{error}REMOVED&8]'
                lore:
                  - ''
                  - '{secondary}Your value:'
                  - ' {letters_black}▎ {muted}%current%'
                  - ''
                  - ' {letters_black}▎ {letters}The plugin no longer ships it.'
                  - ''
                  - '{warning}➥ Left click to remove it'
                  - '{warning}➥ Right click to keep it'
                  - ''
                actions:
                  - 'left,shift_left: exylialib:updates_apply'
                  - 'right,shift_right: exylialib:updates_keep'
            """ + NAVIGATION + """
            items:
              apply:
                slot: 48
                material: LIME_DYE
                name: '{success}&lAPPLY ALL'
                lore:
                  - ''
                  - ' {letters_black}▎ {letters}Every change in this file.'
                  - ''
                  - '{warning}➥ Click to apply all'
                  - ''
                actions:
                  - 'exylialib:updates_apply'
              back:
                slot: 49
                material: BARRIER
                name: '{error}&lBACK'
                actions:
                  - 'back'
              keep:
                slot: 50
                material: GRAY_DYE
                name: '{letters_black}&lKEEP ALL'
                lore:
                  - ''
                  - ' {letters_black}▎ {letters}Every value in this file stays as it is.'
                  - ''
                  - '{warning}➥ Click to keep all'
                  - ''
                actions:
                  - 'exylialib:updates_keep'
            """;
}
