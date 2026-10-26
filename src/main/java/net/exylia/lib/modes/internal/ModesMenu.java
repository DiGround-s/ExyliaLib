package net.exylia.lib.modes.internal;

import net.exylia.lib.action.ActionContext;
import net.exylia.lib.action.ActionResult;
import net.exylia.lib.action.Actions;
import net.exylia.lib.action.PluginActions;
import net.exylia.lib.config.Languages;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.modes.Mode;
import net.exylia.lib.modes.Modes;
import net.exylia.lib.modes.Modes.Category;
import net.exylia.lib.settings.internal.SettingsMenu;
import net.exylia.lib.text.Lines;
import net.exylia.lib.text.Phrases;
import net.exylia.lib.text.Text;
import net.exylia.lib.ui.PluginMenus;
import net.exylia.lib.ui.UiEntry;
import net.exylia.lib.ui.UiKeys;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The screens behind {@link Modes#open}.
 *
 * <p>A hub of category cards → one category's paged list of modes. With a
 * single category the hub is skipped, and a list opened for one category is a
 * root screen with no BACK. Past seven categories the hub becomes the paged
 * list too.
 *
 * <p>The rows are built again on every redraw from the registry, so a mode
 * that opens, closes, gains players or is registered shows without reopening.
 *
 * <p>Compiled into the library's one {@link PluginMenus}, beside the settings
 * screens. The files ({@link #FOLDER} in the library's language folder) are
 * the server's to restyle: {@link Languages#refresh} only adds what is missing.
 */
public final class ModesMenu {

    static final String HUB = "modes_hub_";
    static final String LIST = "modes_list";
    static final String ROOT_LIST = "modes_list_root";
    static final String FOLDER = "menus/modes";

    /** Cards the hub centres on one row before the list takes over. */
    static final int ROW = 7;

    private static volatile PluginMenus menus;
    private static volatile @Nullable Plugin library;

    /** The class the packaged screens are found beside; a test points it at its own output. */
    private static volatile Class<?> anchor = ModesMenu.class;

    private ModesMenu() {
    }

    /**
     * Registers the actions. Once, before the screens are loaded.
     *
     * @param plugin    the library
     * @param namespace the library's menu and action namespace
     */
    public static void init(@NotNull Plugin plugin, @NotNull String namespace) {
        library = plugin;
        PluginActions actions = Actions.of(plugin, namespace);
        actions.registerSync("modes_click", (context, arguments) -> click(context));
        actions.registerSync("modes_back", (context, arguments) -> {
            open(context.player());
            return ActionResult.success();
        });
    }

    /** Compiles the screens into the library's menus, from {@code lang/<code>/menus/modes/}. */
    public static void load(@NotNull PluginMenus target) {
        Plugin owner = target.plugin();
        Languages.refresh(owner, anchor, FOLDER);
        YamlConfiguration hub = SettingsMenu.read(owner, FOLDER, "modes");
        YamlConfiguration list = SettingsMenu.read(owner, FOLDER, LIST);
        for (int count = 1; count <= ROW; count++) {
            target.load(HUB + count, SettingsMenu.centred(hub, 18, count));
        }
        target.load(LIST, list);
        target.load(ROOT_LIST, withoutBack(list));
        menus = target;
    }

    /**
     * Where the packaged screens are read from. For tests, whose classes and
     * resources sit in different directories.
     *
     * @param packaged a class whose artifact holds {@code lang/<code>/menus/modes/}
     */
    public static void anchor(@NotNull Class<?> packaged) {
        anchor = packaged;
    }

    /** Forgets the screens, when the library disables. */
    public static void release() {
        menus = null;
        library = null;
    }

    // ------------------------------------------------------------------ opening

    /** The hub, or the one category's list. Any thread. */
    public static void open(@NotNull Player player) {
        List<Category> categories = Modes.categories();
        if (categories.size() == 1) {
            open(player, categories.getFirst().id());
            return;
        }
        Map<String, Object> context = context(Phrases.tr("Categories"));
        context.put("modes_playing", (Supplier<String>) () -> count(playing(Modes.modes())));
        show(player, layout(categories.size()), context,
                () -> Modes.categories().stream().map(ModesMenu::categoryRow).toList());
    }

    /** One category's list as a root screen. Any thread. */
    public static void open(@NotNull Player player, @NotNull String category) {
        show(player, ROOT_LIST, context(title(category)), () -> modeRows(player, category));
    }

    private static void openFromHub(Player player, String category) {
        show(player, LIST, context(title(category)), () -> modeRows(player, category));
    }

    private static void show(Player player, String id, Map<String, Object> context,
                             Supplier<List<UiEntry>> rows) {
        PluginMenus current = menus;
        if (current != null) current.open(player, id, context, rows);
    }

    /** The hub centred for this many cards, or the paged list past one row. */
    static String layout(int count) {
        return count >= 1 && count <= ROW ? HUB + count : ROOT_LIST;
    }

    private static Map<String, Object> context(String title) {
        Map<String, Object> context = new HashMap<>();
        context.put("modes_title", title);
        context.put("modes_parent", Phrases.tr("the modes menu"));
        return context;
    }

    private static String title(String category) {
        return Modes.categories().stream().filter(entry -> entry.id().equals(category))
                .map(Category::name).findFirst().orElse(category);
    }

    // ------------------------------------------------------------------ clicks

    private static ActionResult click(ActionContext context) {
        Object row = context.get(UiKeys.ENTRY).orElse(null);
        Player player = context.player();
        if (row instanceof Category category) {
            openFromHub(player, category.id());
        } else if (row instanceof Mode mode) {
            play(player, mode);
        }
        return ActionResult.success();
    }

    private static void play(Player player, Mode mode) {
        // Asked again: the card may have been drawn a second before the mode closed or went away.
        if (!Modes.modes().contains(mode) || !mode.allowed(player) || !mode.isOpen()) {
            Text.of(Phrases.tr("[sound:ENTITY_VILLAGER_NO|1.0|1.0]{error}✘ {letters}That mode is not open right now."))
                    .send(player);
            return;
        }
        player.closeInventory();
        try {
            mode.action().accept(player);
        } catch (RuntimeException failure) {
            Plugin owner = library;
            if (owner != null) {
                Debug.of(owner).error("Modes: " + mode + " failed to open for " + player.getName() + ".", failure);
            }
        }
    }

    // ------------------------------------------------------------------ rows

    static List<UiEntry> modeRows(Player player, String category) {
        return Modes.modes(category).stream().map(mode -> modeRow(player, mode)).toList();
    }

    static UiEntry modeRow(Player player, Mode mode) {
        boolean allowed = mode.allowed(player);
        boolean open = mode.isOpen();
        return UiEntry.of(mode)
                .with("icon", mode.icon())
                .withFormatted("name", "{primary}&l" + mode.name().toUpperCase(Locale.ROOT))
                .with("glow", false)
                .withFormatted("lore", lore(mode, allowed, open))
                .template(allowed && open ? null : "inert")
                .build();
    }

    /** A mode's lore: what it is, where it stands, and what a click does. */
    static String lore(Mode mode, boolean allowed, boolean open) {
        List<String> lore = new ArrayList<>();
        lore.add("");
        if (!mode.description().isEmpty()) {
            lore.add(Phrases.tr("{secondary}Information:"));
            mode.description().forEach(line -> lore.add(" {letters_black}▎ {letters}" + line));
            lore.add("");
        }
        lore.add(Phrases.tr("{secondary}Status:"));
        String state = !allowed ? Phrases.tr("{error}Locked") : open ? Phrases.tr("{success}Open")
                : Phrases.tr("{error}Closed");
        lore.add(Phrases.tr(" {letters_black}▎ {letters}State {letters_black}» {0}", state));
        if (mode.players() >= 0) {
            lore.add(Phrases.tr(" {letters_black}▎ {letters}Playing {letters_black}» {info}{0}", mode.players()));
        }
        if (!allowed) {
            lore.add(" {letters_black}▎ {muted}" + Phrases.tr("Your rank does not include it."));
        }
        lore.add("");
        if (allowed && open) {
            lore.add(Phrases.tr("{warning}➥ Click to play"));
            lore.add("");
        }
        return String.join(Lines.NEWLINE, lore);
    }

    static UiEntry categoryRow(Category category) {
        List<Mode> modes = Modes.modes(category.id());
        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add(Phrases.tr("{secondary}Information:"));
        category.description().forEach(line -> lore.add(" {letters_black}▎ {letters}" + line));
        lore.add(Phrases.tr(" {letters_black}▎ {letters}Modes {letters_black}» {info}{0}", modes.size()));
        int playing = playing(modes);
        if (playing >= 0) {
            lore.add(Phrases.tr(" {letters_black}▎ {letters}Playing {letters_black}» {info}{0}", playing));
        }
        lore.add("");
        lore.add(Phrases.tr("{warning}➥ Click to open"));
        lore.add("");
        return UiEntry.of(category)
                .with("icon", category.icon())
                .withFormatted("name", "{primary}&l" + category.name().toUpperCase(Locale.ROOT))
                .with("glow", false)
                .withFormatted("lore", String.join(Lines.NEWLINE, lore))
                .build();
    }

    /** Everybody playing these modes, or {@code -1} when none of them counts. */
    static int playing(List<Mode> modes) {
        int total = -1;
        for (Mode mode : modes) {
            int players = mode.players();
            if (players >= 0) total = Math.max(total, 0) + players;
        }
        return total;
    }

    private static String count(int playing) {
        return playing < 0 ? "-" : Integer.toString(playing);
    }

    // ------------------------------------------------------------------ screens

    /** The paged list as a root screen: the same file, its BACK left to the filler. */
    static YamlConfiguration withoutBack(YamlConfiguration file) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(file.saveToString());
        } catch (InvalidConfigurationException impossible) {
            throw new IllegalStateException(impossible);
        }
        yaml.set("items.back", null);
        return yaml;
    }
}
