package net.exylia.lib.settings.internal;

import net.exylia.lib.action.ActionContext;
import net.exylia.lib.action.ActionResult;
import net.exylia.lib.action.Actions;
import net.exylia.lib.action.PluginActions;
import net.exylia.lib.config.Languages;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.screen.ScreenSet;
import net.exylia.lib.screen.Screens;
import net.exylia.lib.settings.Setting;
import net.exylia.lib.settings.Settings;
import net.exylia.lib.settings.internal.SettingsRuntime.Category;
import net.exylia.lib.settings.internal.SettingsRuntime.PluginStore;
import net.exylia.lib.text.Lines;
import net.exylia.lib.text.Lore;
import net.exylia.lib.text.Phrases;
import net.exylia.lib.text.Text;
import net.exylia.lib.ui.PluginMenus;
import net.exylia.lib.ui.UiEntry;
import net.exylia.lib.ui.UiKeys;
import net.exylia.lib.ui.UiSession;
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
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * The screens behind {@code /settings}.
 *
 * <p>Root (two cards) → the plugins that registered something → a plugin's
 * categories → one category's settings; Announcements → the plugins with
 * channels → their channels. A step with a single choice is skipped, and BACK
 * skips it the same way.
 *
 * <p>Every list is drawn on the grid the rest of the suite uses: up to seven
 * cards centred on one row of a hub or sub-page, more on a paged list. The
 * layouts are compiled once per count, so a screen never has a ragged row.
 * A row's state is a lambda reading memory, so a click, or a change made on
 * another server, shows without reopening.
 *
 * <p>Compiled into the library's one {@link PluginMenus}, beside the updates
 * screens: its menus are unloaded together. Unlike those, the screens are
 * files the server owner may restyle ({@link #FOLDER} in the library's
 * language folder), installed by {@link Languages#refresh} so an update only
 * adds keys and never overwrites the owner's.
 */
public final class SettingsMenu {

    /** The tone of every card these screens build. */
    private static final Lore.Tone TONE = Lore.Tone.PRIMARY;

    static final String ROOT = "settings";
    static final String LIST = "settings_list";
    static final String HUB = "settings_hub_";
    static final String PAGE = "settings_page_";

    /** Cards a hub or sub-page centres on one row before the list takes over. */
    static final int ROW = 7;

    static final String MODE_SETTINGS = "settings";
    static final String MODE_ANNOUNCEMENTS = "announcements";

    private static final String MODE_KEY = "settings_mode";
    private static final String SCREEN_KEY = "settings_screen";
    private static final String PLUGIN_KEY = "settings_plugin";
    private static final String CATEGORY_KEY = "settings_category";

    private static volatile PluginMenus menus;

    /** The class the packaged screens are found beside; a test points it at its own output. */
    private static volatile Class<?> anchor = SettingsMenu.class;

    /** A plugin on the plugins screen. */
    record PluginRow(String plugin, String mode) {
    }

    /** A category on a plugin's hub. */
    record CategoryRow(String plugin, String category) {
    }

    /** A setting or a channel on a category page. */
    record SettingRow(String plugin, String key) {
    }

    /** Where BACK goes, and what its lore calls it. */
    private record Parent(String name, java.util.function.Consumer<Player> open) {
    }

    private SettingsMenu() {
    }

    /**
     * Registers the actions. Once, before the screens are loaded.
     *
     * @param plugin    the library
     * @param namespace the library's menu and action namespace
     */
    public static void init(@NotNull Plugin plugin, @NotNull String namespace) {
        PluginActions actions = Actions.of(plugin, namespace);
        actions.registerSync("settings_open", (context, arguments) -> {
            String target = arguments.string(0, "");
            if (target.equals(MODE_ANNOUNCEMENTS)) {
                openPlugins(context.player(), MODE_ANNOUNCEMENTS);
            } else if (target.equals(MODE_SETTINGS)) {
                openPlugins(context.player(), MODE_SETTINGS);
            } else {
                open(context.player());
            }
            return ActionResult.success();
        });
        actions.registerSync("settings_click", (context, arguments) -> click(context, arguments.integer(0, 1)));
        actions.registerSync("settings_back", (context, arguments) -> {
            back(context.require(UiKeys.SESSION)).open().accept(context.player());
            return ActionResult.success();
        });
    }

    /**
     * Compiles the screens into the library's menus, from
     * {@code plugins/ExyliaLib/lang/<code>/menus/settings/}.
     */
    public static void load(@NotNull PluginMenus target) {
        Map<String, YamlConfiguration> files = files(target.plugin());
        target.load(ROOT, files.get(ROOT));
        target.load(LIST, files.get(LIST));
        for (int count = 1; count <= ROW; count++) {
            target.load(HUB + count, centred(files.get("settings_hub"), 18, count));
            target.load(PAGE + count, centred(files.get("settings_page"), 9, count));
        }
        menus = target;
        screens(target.plugin());
    }

    /**
     * The full-screen version of these menus, for the SCREEN menu style: {@code settings/settings.html} routes every
     * one of their ids, so the menus above open it with no other code. A file the owner broke keeps the chest menus.
     */
    private static void screens(Plugin library) {
        if (anchor != SettingsMenu.class) return;
        try {
            ScreenSet screens = Screens.of(library).files(SettingsMenu.class, "settings");
            screens.reload();
            screens.markup("settings").problems().forEach(problem -> Debug.of(library).warn("Screen " + problem));
        } catch (RuntimeException broken) {
            Debug.of(library).warn("The settings screen could not be read, its chest menus open instead: " + broken.getMessage());
        }
    }

    /**
     * Where the packaged screens are read from. For tests, whose classes and
     * resources sit in different directories.
     *
     * @param packaged a class whose artifact holds {@code lang/<code>/menus/settings/}
     */
    public static void anchor(@NotNull Class<?> packaged) {
        anchor = packaged;
    }

    /** Forgets the screens, when the library disables. */
    public static void release() {
        menus = null;
    }

    // ------------------------------------------------------------------ opening

    /** The root screen. Any thread. */
    public static void open(@NotNull Player player) {
        PluginMenus current = menus;
        if (current != null) current.open(player, ROOT);
    }

    /** Straight to the announcements. Any thread. */
    public static void openAnnouncements(@NotNull Player player) {
        openPlugins(player, MODE_ANNOUNCEMENTS);
    }

    static List<PluginStore> storesFor(String mode) {
        return SettingsRuntime.stores().stream()
                .filter(store -> mode.equals(MODE_ANNOUNCEMENTS) ? store.hasAnnouncements() : !store.categories().isEmpty())
                .toList();
    }

    static void openPlugins(Player player, String mode) {
        List<PluginStore> stores = storesFor(mode);
        if (stores.size() == 1) {
            openPlugin(player, mode, stores.get(0));
            return;
        }
        List<UiEntry> rows = stores.stream().map(store -> pluginRow(player, store, mode)).toList();
        show(player, LIST, context(mode, "plugins", null, null, Phrases.tr("Plugins"),
                parent("plugins", mode, null).name()), rows);
    }

    static void openPlugin(Player player, String mode, PluginStore store) {
        if (mode.equals(MODE_ANNOUNCEMENTS)) {
            openCategory(player, mode, store, Setting.ANNOUNCEMENTS);
            return;
        }
        List<Category> categories = store.categories();
        if (categories.size() == 1) {
            openCategory(player, mode, store, categories.get(0).id());
            return;
        }
        List<UiEntry> rows = categories.stream().map(category -> categoryRow(store, category)).toList();
        show(player, layout(HUB, rows.size()), context(mode, "plugin", store, null, label(store),
                parent("plugin", mode, store).name()), rows);
    }

    static void openCategory(Player player, String mode, PluginStore store, String category) {
        List<UiEntry> rows = store.settings(category).stream().map(setting -> settingRow(player, store, setting))
                .toList();
        String title = mode.equals(MODE_ANNOUNCEMENTS) ? label(store) : store.categories().stream()
                .filter(entry -> entry.id().equals(category)).map(Category::name).findFirst().orElse(category);
        show(player, layout(PAGE, rows.size()), context(mode, "category", store, category, title,
                parent("category", mode, store).name()), rows);
    }

    private static void show(Player player, String id, Map<String, Object> context, List<UiEntry> rows) {
        PluginMenus current = menus;
        if (current != null) current.open(player, id, context, () -> rows);
    }

    /** A hub or page centred for this many cards, or the paged list past one row. */
    static String layout(String kind, int count) {
        return count >= 1 && count <= ROW ? kind + count : LIST;
    }

    private static Map<String, Object> context(String mode, String screen, @Nullable PluginStore store,
                                               @Nullable String category, String title, String parent) {
        Map<String, Object> context = new HashMap<>();
        context.put(MODE_KEY, mode);
        context.put(SCREEN_KEY, screen);
        if (store != null) context.put(PLUGIN_KEY, store.plugin().getName());
        if (category != null) context.put(CATEGORY_KEY, category);
        context.put("settings_section", mode.equals(MODE_ANNOUNCEMENTS)
                ? Phrases.tr("ANNOUNCEMENTS") : Phrases.tr("SETTINGS"));
        context.put("settings_title", title);
        context.put("settings_parent", parent);
        return context;
    }

    // ------------------------------------------------------------------ back

    private static Parent back(UiSession session) {
        String mode = session.context(MODE_KEY, String.class).orElse(MODE_SETTINGS);
        String screen = session.context(SCREEN_KEY, String.class).orElse("plugins");
        PluginStore store = session.context(PLUGIN_KEY, String.class).flatMap(SettingsRuntime::find).orElse(null);
        return parent(screen, mode, store);
    }

    /** The screen above this one, skipping the ones a single choice skips on the way in. */
    private static Parent parent(String screen, String mode, @Nullable PluginStore store) {
        if (screen.equals("category") && store != null && mode.equals(MODE_SETTINGS) && store.categories().size() > 1) {
            return new Parent(label(store), player -> openPlugin(player, mode, store));
        }
        if (!screen.equals("plugins") && storesFor(mode).size() > 1) {
            return new Parent(Phrases.tr("Plugin list"), player -> openPlugins(player, mode));
        }
        return new Parent(Phrases.tr("Settings menu"), SettingsMenu::open);
    }

    // ------------------------------------------------------------------ clicks

    private static ActionResult click(ActionContext context, int direction) {
        Object row = context.get(UiKeys.ENTRY).orElse(null);
        Player player = context.player();
        if (row instanceof PluginRow plugin) {
            SettingsRuntime.find(plugin.plugin()).ifPresent(store -> openPlugin(player, plugin.mode(), store));
        } else if (row instanceof CategoryRow category) {
            SettingsRuntime.find(category.plugin())
                    .ifPresent(store -> openCategory(player, MODE_SETTINGS, store, category.category()));
        } else if (row instanceof SettingRow entry) {
            change(context, player, entry, direction);
        }
        return ActionResult.success();
    }

    private static void change(ActionContext context, Player player, SettingRow entry, int direction) {
        PluginStore store = SettingsRuntime.find(entry.plugin()).orElse(null);
        Setting setting = store == null ? null : store.setting(entry.key());
        if (setting == null || !setting.allowed(player) || !setting.available()) return;
        if (!Settings.step(player, store.plugin(), setting.key(), direction)) {
            Text.of(Phrases.tr("[sound:ENTITY_VILLAGER_NO|0.7|1.0]{error}✘ {letters}Your settings are still loading. Try again in a moment."))
                    .send(player);
            return;
        }
        context.get(UiKeys.SESSION).ifPresent(UiSession::refresh);
    }

    // ------------------------------------------------------------------ rows

    static String label(PluginStore store) {
        String name = store.plugin().getName();
        // The library's own settings are about how everything looks, not about a plugin called "Lib".
        if (name.equals("ExyliaLib")) return Phrases.tr("Interface");
        return name.startsWith("Exylia") && name.length() > "Exylia".length() ? name.substring("Exylia".length()) : name;
    }

    static UiEntry pluginRow(Player player, PluginStore store, String mode) {
        boolean announcements = mode.equals(MODE_ANNOUNCEMENTS);
        List<Setting> listed = announcements ? store.settings(Setting.ANNOUNCEMENTS)
                : store.settings().stream().filter(setting -> !setting.category().equals(Setting.ANNOUNCEMENTS)).toList();
        String icon = announcements ? listed.get(0).icon() : store.categories().get(0).icon();
        return UiEntry.of(new PluginRow(store.plugin().getName(), mode))
                .with("icon", icon)
                .withFormatted("name", Lore.name(TONE, announcements ? Phrases.tr("Announcements") : Phrases.tr("Settings"),
                        label(store)))
                .with("glow", false)
                .withFormatted("entry_name", label(store))
                .with("entry_kind", announcements ? "channels" : "plugin")
                .with("entry_state", "open")
                .with("entry_count", listed.size())
                .with("entry_muted", () -> announcements ? listed.stream()
                        .filter(setting -> !Settings.enabled(player, store.plugin(), setting.key())).count() : 0)
                .withFormatted("lore", () -> {
                    Lore lore = Lore.of(TONE)
                            .subtitle(announcements ? Phrases.tr("Chat announcements") : Phrases.tr("Plugin settings"))
                            .section("✦", Phrases.tr("Status"));
                    if (announcements) {
                        long muted = listed.stream().filter(setting -> !Settings.enabled(player, store.plugin(), setting.key())).count();
                        lore.stat("note", "info", Phrases.tr("Channels"), String.valueOf(listed.size()));
                        lore.stat("barrier", "info", Phrases.tr("Muted"), String.valueOf(muted));
                    } else {
                        lore.stat("iron", "info", Phrases.tr("Settings"), String.valueOf(listed.size()));
                    }
                    return String.join(Lines.NEWLINE, lore.action(Phrases.tr("Click to open")).lines());
                })
                .build();
    }

    static UiEntry categoryRow(PluginStore store, Category category) {
        Lore lore = Lore.of(TONE).subtitle(Phrases.tr("Settings category"));
        if (!category.description().isEmpty()) {
            lore.section(Phrases.tr("Information"));
            category.description().forEach(lore::text);
        }
        lore.section("✦", Phrases.tr("Status"))
                .stat("iron", "info", Phrases.tr("Settings"), String.valueOf(store.settings(category.id()).size()))
                .action(Phrases.tr("Click to open"));
        return UiEntry.of(new CategoryRow(store.plugin().getName(), category.id()))
                .with("icon", category.icon())
                .withFormatted("name", Lore.name(TONE, label(store), category.name()))
                .with("glow", false)
                .withFormatted("entry_name", category.name())
                .withFormatted("entry_about", String.join("<nl>", category.description()))
                .with("entry_kind", "category")
                .with("entry_state", "open")
                .with("entry_count", store.settings(category.id()).size())
                .withFormatted("lore", String.join(Lines.NEWLINE, lore.lines()))
                .build();
    }

    static UiEntry settingRow(Player player, PluginStore store, Setting setting) {
        boolean allowed = setting.allowed(player);
        boolean available = setting.available();
        return UiEntry.of(new SettingRow(store.plugin().getName(), setting.key()))
                .with("icon", setting.icon())
                .withFormatted("name", Lore.name(TONE, Phrases.tr("Setting"), setting.name()))
                .with("glow", () -> available && allowed && setting.kind() == Setting.Kind.TOGGLE
                        && Settings.enabled(player, store.plugin(), setting.key()))
                .withFormatted("lore", () -> lore(setting, allowed, available,
                        Settings.value(player, store.plugin(), setting.key())))
                .withFormatted("entry_name", setting.name())
                .withFormatted("entry_about", String.join("<nl>", setting.description()))
                .with("entry_kind", setting.kind().name().toLowerCase(Locale.ROOT))
                .with("entry_state", () -> state(setting, allowed, available,
                        Settings.value(player, store.plugin(), setting.key())))
                .withFormatted("entry_value", () -> {
                    String value = Settings.value(player, store.plugin(), setting.key());
                    String current = value == null ? setting.defaultValue() : value;
                    return setting.kind() == Setting.Kind.CHOICE ? setting.label(current) : current;
                })
                .with("entry_server", setting.perServer() ? 1 : 0)
                .template(allowed && available ? null : "inert")
                .build();
    }

    /** What a setting's card shows: unavailable, locked, on, off, or set (a choice or a number). */
    static String state(Setting setting, boolean allowed, boolean available, @Nullable String value) {
        if (!available) return "unavailable";
        if (!allowed) return "locked";
        if (setting.kind() != Setting.Kind.TOGGLE) return "set";
        return Boolean.parseBoolean(value == null ? setting.defaultValue() : value) ? "on" : "off";
    }

    /** A setting's lore: what it does, where it stands, and what a click does. */
    static String lore(Setting setting, boolean allowed, boolean available, @Nullable String value) {
        String current = value == null ? setting.defaultValue() : value;
        Lore lore = Lore.of(TONE).subtitle(Phrases.tr("Your setting"));
        if (!setting.description().isEmpty()) {
            lore.section(Phrases.tr("Information"));
            setting.description().forEach(lore::text);
        }
        lore.section("✦", Phrases.tr("Status"));
        if (!available) {
            lore.stat("redstone", "muted", Phrases.tr("State"), Phrases.tr("Unavailable"));
            lore.line("    {muted}" + (setting.unavailableReason() != null ? setting.unavailableReason()
                    : Phrases.tr("This feature is off on this server.")));
            return String.join(Lines.NEWLINE, lore.lines());
        }
        if (!allowed) {
            lore.stat("key", "error", Phrases.tr("State"), Phrases.tr("Locked"));
            lore.line("    {muted}" + Phrases.tr("Your rank does not include it."));
            return String.join(Lines.NEWLINE, lore.lines());
        }
        switch (setting.kind()) {
            case TOGGLE -> {
                boolean on = Boolean.parseBoolean(current);
                lore.stat("redstone", on ? "success" : "error", Phrases.tr("State"),
                        on ? Phrases.tr("Enabled") : Phrases.tr("Disabled"));
            }
            case CHOICE -> lore.stat("compass", "info", Phrases.tr("Current"), setting.label(current));
            case NUMBER -> lore.stat("iron", "info", Phrases.tr("Current"), current);
        }
        if (setting.perServer()) {
            lore.stat("ping", "info", Phrases.tr("Applies to"), Phrases.tr("this server"));
        }
        switch (setting.kind()) {
            case TOGGLE -> lore.action(Phrases.tr("Click to toggle"));
            case CHOICE -> {
                lore.click(Phrases.tr("Left-click"), Phrases.tr("next option"));
                lore.click(Phrases.tr("Right-click"), Phrases.tr("previous option"));
            }
            case NUMBER -> {
                double raw = setting.step();
                String step = raw == Math.rint(raw) ? Long.toString((long) raw) : Double.toString(raw);
                lore.click(Phrases.tr("Left-click"), "+" + step);
                lore.click(Phrases.tr("Right-click"), "-" + step);
            }
        }
        return String.join(Lines.NEWLINE, lore.lines());
    }

    // ------------------------------------------------------------------ screens

    /** Where the screens live, inside the library's language folder. */
    static final String FOLDER = "menus/settings";

    /**
     * Installs the screens in the library's data folder, then reads them from
     * there: the server's to restyle, gaining only what a new version adds.
     */
    private static Map<String, YamlConfiguration> files(Plugin library) {
        Languages.refresh(library, anchor, FOLDER);
        Map<String, YamlConfiguration> files = new HashMap<>();
        for (String name : List.of(ROOT, LIST, "settings_hub", "settings_page")) {
            files.put(name, read(library, name));
        }
        return files;
    }

    /** One screen from disk, or the packaged one when the owner's copy does not parse. */
    static YamlConfiguration read(Plugin library, String name) {
        return read(library, FOLDER, name);
    }

    /**
     * One of the library's restylable screens from disk, or the packaged
     * English one when the owner's copy does not parse.
     *
     * @param library the library
     * @param folder  the screens' folder inside the language folder, such as {@code menus/logs}
     * @param name    the file, without {@code .yml}
     * @return the screen
     */
    public static YamlConfiguration read(Plugin library, String folder, String name) {
        String resource = folder + "/" + name + ".yml";
        java.io.File file = Languages.file(library, resource);
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
            if (yaml.contains("size")) return yaml;
            Debug.of(library).warn(file + " is not a menu; showing the packaged one.");
        } catch (java.io.IOException | InvalidConfigurationException broken) {
            Debug.of(library).warn("Could not read " + file + " (" + broken.getMessage()
                    + "); showing the packaged one.");
        }
        try (var stream = SettingsMenu.class.getClassLoader().getResourceAsStream("lang/en/" + resource)) {
            YamlConfiguration packaged = new YamlConfiguration();
            packaged.loadFromString(new String(java.util.Objects.requireNonNull(stream).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8));
            return packaged;
        } catch (java.io.IOException | InvalidConfigurationException | NullPointerException broken) {
            throw new IllegalStateException("The library's own " + resource + " is missing from the jar", broken);
        }
    }

    /** A hub or page with its cards centred on one row: a copy of the file with the slots filled in. */
    public static YamlConfiguration centred(YamlConfiguration file, int rowStart, int count) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(file.saveToString());
        } catch (InvalidConfigurationException impossible) {
            throw new IllegalStateException(impossible);
        }
        yaml.set("pagination.slots", columns(count).stream().map(column -> String.valueOf(rowStart + column))
                .collect(Collectors.joining(",")));
        return yaml;
    }

    /** The suite's centred columns for a row of this many cards. */
    static List<Integer> columns(int count) {
        return switch (count) {
            case 1 -> List.of(4);
            case 2 -> List.of(3, 5);
            case 3 -> List.of(2, 4, 6);
            case 4 -> List.of(1, 3, 5, 7);
            case 5 -> List.of(2, 3, 4, 5, 6);
            case 6 -> List.of(1, 2, 3, 5, 6, 7);
            default -> IntStream.rangeClosed(1, 7).boxed().toList();
        };
    }
}
