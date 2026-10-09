package net.exylia.lib.settings.internal;

import net.exylia.lib.action.ActionContext;
import net.exylia.lib.action.ActionResult;
import net.exylia.lib.action.Actions;
import net.exylia.lib.action.PluginActions;
import net.exylia.lib.settings.Setting;
import net.exylia.lib.settings.Settings;
import net.exylia.lib.settings.internal.SettingsRuntime.Category;
import net.exylia.lib.settings.internal.SettingsRuntime.PluginStore;
import net.exylia.lib.text.Lines;
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
 * screens: its menus are unloaded together.
 */
public final class SettingsMenu {

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

    private static final String BACK_HEAD = "basehead-eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5l"
            + "Y3JhZnQubmV0L3RleHR1cmUvMjIzZmI2NzQyOTcxNmIyMWJjNmU4ZTdkNjY5Y2VkZGY2NWIxM2UwNzkwYTVjZTU1YjJlMDc3YjgyZDE5"
            + "ZTEyNCJ9fX0=";

    private static volatile PluginMenus menus;

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

    /** Compiles the screens into the library's menus. */
    public static void load(@NotNull PluginMenus target) {
        target.load(ROOT, root());
        target.load(LIST, list());
        for (int count = 1; count <= ROW; count++) {
            target.load(HUB + count, centred(45, 18, count, 40, true));
            target.load(PAGE + count, centred(36, 9, count, 31, false));
        }
        menus = target;
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
            return new Parent(Phrases.tr("the plugin list"), player -> openPlugins(player, mode));
        }
        return new Parent(Phrases.tr("the settings menu"), SettingsMenu::open);
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
            Text.of(Phrases.tr("[sound:ENTITY_VILLAGER_NO|1.0|1.0]{error}✘ {letters}Your settings are still loading. Try again in a moment."))
                    .send(player);
            return;
        }
        context.get(UiKeys.SESSION).ifPresent(UiSession::refresh);
    }

    // ------------------------------------------------------------------ rows

    static String label(PluginStore store) {
        String name = store.plugin().getName();
        return name.startsWith("Exylia") && name.length() > "Exylia".length() ? name.substring("Exylia".length()) : name;
    }

    static UiEntry pluginRow(Player player, PluginStore store, String mode) {
        boolean announcements = mode.equals(MODE_ANNOUNCEMENTS);
        List<Setting> listed = announcements ? store.settings(Setting.ANNOUNCEMENTS)
                : store.settings().stream().filter(setting -> !setting.category().equals(Setting.ANNOUNCEMENTS)).toList();
        String icon = announcements ? listed.get(0).icon() : store.categories().get(0).icon();
        return UiEntry.of(new PluginRow(store.plugin().getName(), mode))
                .with("icon", icon)
                .withFormatted("name", "{primary}&l" + label(store).toUpperCase(Locale.ROOT))
                .with("glow", false)
                .withFormatted("lore", () -> {
                    List<String> lore = new ArrayList<>();
                    lore.add("");
                    lore.add(Phrases.tr("{secondary}Information:"));
                    if (announcements) {
                        long muted = listed.stream().filter(setting -> !Settings.enabled(player, store.plugin(), setting.key())).count();
                        lore.add(Phrases.tr(" {letters_black}▎ {letters}Channels {letters_black}» {info}{0}", listed.size()));
                        lore.add(Phrases.tr(" {letters_black}▎ {letters}Muted {letters_black}» {info}{0}", muted));
                    } else {
                        lore.add(Phrases.tr(" {letters_black}▎ {letters}Settings {letters_black}» {info}{0}", listed.size()));
                    }
                    lore.add("");
                    lore.add(Phrases.tr("{warning}➥ Click to open"));
                    lore.add("");
                    return String.join(Lines.NEWLINE, lore);
                })
                .build();
    }

    static UiEntry categoryRow(PluginStore store, Category category) {
        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add(Phrases.tr("{secondary}Information:"));
        category.description().forEach(line -> lore.add(" {letters_black}▎ {letters}" + line));
        lore.add(Phrases.tr(" {letters_black}▎ {letters}Settings {letters_black}» {info}{0}",
                store.settings(category.id()).size()));
        lore.add("");
        lore.add(Phrases.tr("{warning}➥ Click to open"));
        lore.add("");
        return UiEntry.of(new CategoryRow(store.plugin().getName(), category.id()))
                .with("icon", category.icon())
                .withFormatted("name", "{primary}&l" + category.name().toUpperCase(Locale.ROOT))
                .with("glow", false)
                .withFormatted("lore", String.join(Lines.NEWLINE, lore))
                .build();
    }

    static UiEntry settingRow(Player player, PluginStore store, Setting setting) {
        boolean allowed = setting.allowed(player);
        boolean available = setting.available();
        return UiEntry.of(new SettingRow(store.plugin().getName(), setting.key()))
                .with("icon", setting.icon())
                .withFormatted("name", "{primary}&l" + setting.name().toUpperCase(Locale.ROOT))
                .with("glow", () -> available && allowed && setting.kind() == Setting.Kind.TOGGLE
                        && Settings.enabled(player, store.plugin(), setting.key()))
                .withFormatted("lore", () -> lore(setting, allowed, available,
                        Settings.value(player, store.plugin(), setting.key())))
                .template(allowed && available ? null : "inert")
                .build();
    }

    /** A setting's lore: what it does, where it stands, and what a click does. */
    static String lore(Setting setting, boolean allowed, boolean available, @Nullable String value) {
        String current = value == null ? setting.defaultValue() : value;
        List<String> lore = new ArrayList<>();
        lore.add("");
        if (!setting.description().isEmpty()) {
            lore.add(Phrases.tr("{secondary}Information:"));
            setting.description().forEach(line -> lore.add(" {letters_black}▎ {letters}" + line));
            lore.add("");
        }
        lore.add(Phrases.tr("{secondary}Status:"));
        if (!available) {
            lore.add(Phrases.tr(" {letters_black}▎ {letters}State {letters_black}» {0}", Phrases.tr("{muted}Unavailable")));
            lore.add(" {letters_black}▎ {muted}" + (setting.unavailableReason() != null ? setting.unavailableReason()
                    : Phrases.tr("This feature is off on this server.")));
            lore.add("");
            return String.join(Lines.NEWLINE, lore);
        }
        if (!allowed) {
            lore.add(Phrases.tr(" {letters_black}▎ {letters}State {letters_black}» {0}", Phrases.tr("{error}Locked")));
            lore.add(" {letters_black}▎ {muted}" + Phrases.tr("Your rank does not include it."));
            lore.add("");
            return String.join(Lines.NEWLINE, lore);
        }
        switch (setting.kind()) {
            case TOGGLE -> lore.add(Phrases.tr(" {letters_black}▎ {letters}State {letters_black}» {0}",
                    Boolean.parseBoolean(current) ? Phrases.tr("{success}Enabled") : Phrases.tr("{error}Disabled")));
            case CHOICE -> lore.add(Phrases.tr(" {letters_black}▎ {letters}Current {letters_black}» {info}{0}",
                    setting.label(current)));
            case NUMBER -> lore.add(Phrases.tr(" {letters_black}▎ {letters}Current {letters_black}» {info}{0}", current));
        }
        if (setting.perServer()) {
            lore.add(Phrases.tr(" {letters_black}▎ {letters}Applies to {letters_black}» {info}{0}",
                    Phrases.tr("this server")));
        }
        lore.add("");
        switch (setting.kind()) {
            case TOGGLE -> lore.add(Phrases.tr("{warning}➥ Click to toggle"));
            case CHOICE -> {
                lore.add(Phrases.tr("{warning}➥ Left-click {letters_black}» {letters}next option"));
                lore.add(Phrases.tr("{warning}➥ Right-click {letters_black}» {letters}previous option"));
            }
            case NUMBER -> {
                double raw = setting.step();
                String step = raw == Math.rint(raw) ? Long.toString((long) raw) : Double.toString(raw);
                lore.add(Phrases.tr("{warning}➥ Left-click {letters_black}» {letters}+{0}", step));
                lore.add(Phrases.tr("{warning}➥ Right-click {letters_black}» {letters}-{0}", step));
            }
        }
        lore.add("");
        return String.join(Lines.NEWLINE, lore);
    }

    // ------------------------------------------------------------------ screens

    private static final String SOUNDS = """
            open_sounds:
              - "minecraft:block.ender_chest.open|1.0|1.4"
            click_sounds:
              - "minecraft:block.note_block.hat|1.0|1.0"
            close_sounds:
              - "minecraft:block.barrel.close|1.0|0.7"
            filler:
              global:
                material: GRAY_STAINED_GLASS_PANE
                hide_tooltip: true
            """;

    private static final String ROW_TEMPLATE = """
            pagination:
              slots: '$SLOTS'
              item_template:
                material: '%icon%'
                name: '%name%'
                glow: '%glow%'
                lore:
                  - '%lore%'
                actions:
                  - 'left,shift_left: exylialib:settings_click 1'
                  - 'right,shift_right: exylialib:settings_click -1'
              inert_template:
                material: '%icon%'
                name: '%name%'
                lore:
                  - '%lore%'
            """;

    private static final String BACK = """
            items:
              back:
                slot: $BACK
                sound: "minecraft:item.bundle.remove_one|1.0|0.5"
                material: '$HEAD'
                actions:
                  - 'exylialib:settings_back'
            """;

    static YamlConfiguration root() {
        YamlConfiguration yaml = yaml("""
                size: 27
                type: SIMPLE
                """ + SOUNDS + """
                items:
                  settings:
                    slot: 12
                    material: COMPARATOR
                    actions:
                      - 'exylialib:settings_open settings'
                  announcements:
                    slot: 14
                    material: BELL
                    actions:
                      - 'exylialib:settings_open announcements'
                """);
        yaml.set("title", Phrases.tr("{primary}&lSETTINGS"));
        yaml.set("items.settings.name", Phrases.tr("{primary}&lSETTINGS"));
        yaml.set("items.settings.lore", List.of(
                "",
                Phrases.tr("{secondary}Information:"),
                Phrases.tr(" {letters_black}▎ {letters}How other players reach you"),
                Phrases.tr(" {letters_black}▎ {letters}and how each feature treats you."),
                "",
                Phrases.tr("{warning}➥ Click to open"),
                ""));
        yaml.set("items.announcements.name", Phrases.tr("{primary}&lANNOUNCEMENTS"));
        yaml.set("items.announcements.lore", List.of(
                "",
                Phrases.tr("{secondary}Information:"),
                Phrases.tr(" {letters_black}▎ {letters}Which server-wide messages"),
                Phrases.tr(" {letters_black}▎ {letters}reach your {highlight}chat{letters}."),
                "",
                Phrases.tr("{warning}➥ Click to open"),
                ""));
        return yaml;
    }

    /** Up to seven cards centred on one row; a hub also shows its subject at slot 4. */
    static YamlConfiguration centred(int size, int rowStart, int count, int back, boolean header) {
        String slots = columns(count).stream().map(column -> String.valueOf(rowStart + column))
                .collect(Collectors.joining(","));
        YamlConfiguration yaml = yaml("size: " + size + "\ntype: PAGINATION\n" + SOUNDS
                + ROW_TEMPLATE.replace("$SLOTS", slots)
                + BACK.replace("$BACK", String.valueOf(back)).replace("$HEAD", BACK_HEAD));
        yaml.set("title", Phrases.tr("{primary}&l%settings_section% {letters_black}» {highlight}%settings_title%"));
        if (header) {
            yaml.set("items.header.slot", 4);
            yaml.set("items.header.material", "COMPARATOR");
            yaml.set("items.header.name", Phrases.tr("{primary}&l%settings_title%"));
            yaml.set("items.header.lore", List.of(
                    "",
                    Phrases.tr("{secondary}Information:"),
                    Phrases.tr(" {letters_black}▎ {letters}Every setting this plugin offers,"),
                    Phrases.tr(" {letters_black}▎ {letters}grouped by what it is about."),
                    ""));
        }
        backWords(yaml);
        return yaml;
    }

    /** The paged list, for more than one row of cards. */
    static YamlConfiguration list() {
        YamlConfiguration yaml = yaml("size: 54\ntype: PAGINATION\n" + SOUNDS
                + ROW_TEMPLATE.replace("$SLOTS", "0-35")
                + """
                  navigation:
                    previous:
                      slot: 48
                      sound: "minecraft:item.book.page_turn|1.0|1.0"
                      material: 'basehead-eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZGExZDU1YjNmOTg5NDEwYTM0NzUyNjUwZTI0OGM5YjZjMTc4M2E3ZWMyYWEzZmQ3Nzg3YmRjNGQwZTYzN2QzOSJ9fX0='
                      actions:
                        - 'previous_page'
                    next:
                      slot: 50
                      sound: "minecraft:item.book.page_turn|1.0|1.0"
                      material: 'basehead-eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZmE4N2UzZDk2ZTFjZmViOWNjZmIzYmE1M2EyMTdmYWY1MjQ5ZTI4NTUzM2IyNzFhMmZiMjg0YzMwZGJkOTgyOSJ9fX0='
                      actions:
                        - 'next_page'
                """
                + BACK.replace("$BACK", "49").replace("$HEAD", BACK_HEAD));
        yaml.set("title", Phrases.tr(
                "{primary}&l%settings_section% {letters_black}» {highlight}%settings_title% {muted}%current_page%/%total_pages%"));
        yaml.set("filler.pagination.material", "LIGHT_GRAY_STAINED_GLASS_PANE");
        yaml.set("filler.pagination.hide_tooltip", false);
        yaml.set("filler.pagination.name", Phrases.tr("{muted}Nothing more here"));
        yaml.set("filler.pagination.lore", List.of(
                "",
                Phrases.tr(" {letters_black}▎ {letters}Plugins on this server add theirs here."),
                ""));
        List<String> page = List.of("", Phrases.tr(" {letters_black}▎ {letters}Page {info}%current_page%{letters_black}/{info}%total_pages%"), "");
        yaml.set("pagination.navigation.previous.name", Phrases.tr("{error}&l← PREVIOUS PAGE"));
        yaml.set("pagination.navigation.previous.lore", page);
        yaml.set("pagination.navigation.next.name", Phrases.tr("{success}&lNEXT PAGE →"));
        yaml.set("pagination.navigation.next.lore", page);
        backWords(yaml);
        return yaml;
    }

    private static void backWords(YamlConfiguration yaml) {
        yaml.set("items.back.name", Phrases.tr("{error}&lBACK"));
        yaml.set("items.back.lore", List.of(
                "",
                Phrases.tr(" {letters_black}▎ {letters}Returns to %settings_parent%."),
                "",
                Phrases.tr("{warning}➥ Click to go back"),
                ""));
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

    private static YamlConfiguration yaml(String text) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(text);
        } catch (InvalidConfigurationException broken) {
            throw new IllegalStateException("The library's own settings menu does not parse", broken);
        }
        return yaml;
    }
}
