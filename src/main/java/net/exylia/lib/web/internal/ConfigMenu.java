package net.exylia.lib.web.internal;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.exylia.lib.action.ActionResult;
import net.exylia.lib.action.Actions;
import net.exylia.lib.action.PluginActions;
import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.config.ConfigIssue;
import net.exylia.lib.config.ConfigMenus;
import net.exylia.lib.config.Languages;
import net.exylia.lib.config.Menu;
import net.exylia.lib.config.Schema;
import net.exylia.lib.input.InputResult;
import net.exylia.lib.input.Inputs;
import net.exylia.lib.input.PluginInputs;
import net.exylia.lib.settings.internal.SettingsMenu;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Phrases;
import net.exylia.lib.text.Text;
import net.exylia.lib.ui.PluginMenus;
import net.exylia.lib.ui.UiDefinition;
import net.exylia.lib.ui.UiEntry;
import net.exylia.lib.ui.UiKeys;
import net.exylia.lib.ui.UiSection;
import net.exylia.lib.ui.UiSession;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The screens behind {@code /exylialib config}: the plugins with settings, a plugin's settings
 * files, and one section of a file with a button per key, drawn from its {@link Schema}.
 *
 * <p>Same files as the web editor: what discovery offers as {@code settings}
 * ({@link WebEditorRuntime#settingsOf}). A click writes through {@code ConfigFile.set}, so the
 * file reads as if the owner had typed the value, then the plugin is brought up to date the way
 * an applied web edit does it ({@link WebEditorRuntime#reloadWritten}). Every list is a lambda
 * over {@code ConfigFile.get()}: a value the owner changes in the YAML and reloads shows up on
 * an open screen within a second.
 *
 * <p>After the settings come a plugin's content files: the YAML subjects it registered, drawn
 * from the schema {@link YamlSource} infers ({@link YamlFields}), and the record files it
 * registered, drawn from their record's. A block of entries or a list of blocks is a list with
 * create, duplicate and delete. Each click is read and written by {@link WebEditorRuntime#inGame},
 * the path an applied web edit takes, checker or binder included. A map or a list inside a
 * settings file is edited on those same screens, as its file's discovered subject.
 *
 * <p>Admin screens, rewritten from the jar on every start ({@link #FOLDER}).
 */
public final class ConfigMenu {

    static final String PLUGINS = "config_plugins";
    static final String FILES = "config_files";
    static final String FIELDS = "config_fields";
    static final String ENTRIES_MENU = "config_entries";
    static final String FOLDER = "menus/config";

    private static final String PLUGIN_KEY = "cfg_plugin";
    private static final String FILE_KEY = "cfg_file";
    private static final String PATH_KEY = "cfg_path";
    /** On a settings file's screens: the page shown ({@link ConfigPages}); empty on the file's hub. */
    private static final String PAGE_KEY = "cfg_page";
    /** Set instead of {@link #FILE_KEY} on a content file's screens: the YAML subject's id; the path is then {@link YamlFields#join}ed. */
    private static final String YAML_KEY = "cfg_yaml";

    /** What a content file held when last read, kept until the file changes on disk. */
    private record Read(long modified, long length, JsonObject schema, JsonObject values) {
    }

    // ponytail: one cached read per content file, checked by modification time and length on each redraw.
    private static final Map<String, Read> READS = new ConcurrentHashMap<>();

    /**
     * Where each viewer came in from a plugin's own hub: that plugin, the permission it accepts
     * on top of {@link ConfigMenus#PERMISSION}, and what BACK on the top screen does. Replaced on
     * every open, dropped by a plain open.
     */
    // ponytail: an entry outlives a quit until the next open or a disable; it holds a name, a string and a lambda.
    private static final Map<UUID, Entry> ENTRIES = new ConcurrentHashMap<>();

    private record Entry(String plugin, @Nullable String permission, @Nullable Consumer<Player> back) {
        boolean covers(@Nullable Plugin target) {
            return target != null && plugin.equals(target.getName());
        }
    }

    private static volatile PluginMenus menus;
    private static volatile Plugin library;
    private static volatile Class<?> anchor = ConfigMenu.class;

    private ConfigMenu() {
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
        actions.registerSync("config_plugin", (context, arguments) -> {
            if (!allowed(context.player(), null)) return refused(context.player());
            Plugin target = Bukkit.getPluginManager().getPlugin(arguments.string(0, ""));
            if (target != null) open(context.player(), target);
            return ActionResult.success();
        });
        actions.registerSync("config_file", (context, arguments) -> {
            UiSession session = context.require(UiKeys.SESSION);
            Plugin target = plugin(session);
            if (!allowed(context.player(), target)) return refused(context.player());
            ConfigFile<?> file = target == null ? null : file(target, arguments.string(0, ""));
            if (file != null) openFile(context.player(), target, file);
            return ActionResult.success();
        });
        actions.registerSync("config_page", (context, arguments) -> {
            UiSession session = context.require(UiKeys.SESSION);
            Plugin target = plugin(session);
            if (!allowed(context.player(), target)) return refused(context.player());
            ConfigFile<?> file = target == null ? null : file(target, session.context(FILE_KEY, String.class).orElse(""));
            ConfigPages.Page page = file == null ? null : ConfigPages.page(file.schema(), arguments.string(0, ""));
            if (page != null) openCard(context.player(), target, file, page);
            return ActionResult.success();
        });
        actions.registerSync("config_yaml", (context, arguments) -> {
            UiSession session = context.require(UiKeys.SESSION);
            Plugin target = plugin(session);
            SubjectImpl subject = target == null ? null : content(context.player(), target, arguments.string(0, ""));
            if (subject == null) return refused(context.player());
            openYaml(context.player(), target, subject, List.of());
            return ActionResult.success();
        });
        actions.registerSync("config_entry", (context, arguments) -> {
            UiSession session = context.require(UiKeys.SESSION);
            Plugin target = plugin(session);
            SubjectImpl subject = target == null ? null
                    : content(context.player(), target, session.context(YAML_KEY, String.class).orElse(""));
            if (subject == null) return refused(context.player());
            entry(context.player(), target, subject, YamlFields.split(session.context(PATH_KEY, String.class).orElse("")),
                    arguments.string(0, "open"), arguments.string(1, ""));
            return ActionResult.success();
        });
        actions.registerSync("config_edit", (context, arguments) -> {
            UiSession session = context.require(UiKeys.SESSION);
            if (!allowed(context.player(), plugin(session))) return refused(context.player());
            edit(context.player(), session, arguments.string(0, "left"),
                    arguments.string(1, ""));
            return ActionResult.success();
        });
        actions.registerSync("config_back", (context, arguments) -> {
            UiSession session = context.require(UiKeys.SESSION);
            if (!allowed(context.player(), plugin(session))) return refused(context.player());
            back(context.player(), session);
            return ActionResult.success();
        });
    }

    /** Compiles the screens into the library's menus, from {@code lang/<code>/menus/config/}. */
    public static void load(@NotNull PluginMenus target) {
        Plugin owner = target.plugin();
        // Admin screens: rewritten from the jar on every start.
        Languages.replace(owner, anchor, FOLDER);
        target.load(PLUGINS, SettingsMenu.read(owner, FOLDER, "plugins"));
        target.load(FILES, SettingsMenu.read(owner, FOLDER, "files"));
        target.load(FIELDS, SettingsMenu.read(owner, FOLDER, "fields"));
        target.load(ENTRIES_MENU, SettingsMenu.read(owner, FOLDER, "entries"));
        menus = target;
    }

    /**
     * Where the packaged screens are read from. For tests.
     *
     * @param packaged a class whose artifact holds {@code lang/<code>/menus/config/}
     */
    public static void anchor(@NotNull Class<?> packaged) {
        anchor = packaged;
    }

    /** Forgets the screens, when the library disables. */
    public static void release() {
        menus = null;
        library = null;
        ENTRIES.clear();
        READS.clear();
    }

    // ------------------------------------------------------------------ opening

    /**
     * The plugins with settings.
     *
     * @param viewer who edits; any thread
     */
    public static void open(@NotNull Player viewer) {
        ENTRIES.remove(viewer.getUniqueId());
        showPlugins(viewer);
    }

    private static void showPlugins(Player viewer) {
        PluginMenus current = menus;
        if (current == null) return;
        current.open(viewer, PLUGINS, new LinkedHashMap<>(), ConfigMenu::pluginRows);
    }

    /**
     * One plugin's settings: its file straight away when it has one, the list of files otherwise.
     *
     * @param viewer who edits; any thread
     * @param plugin whose settings
     */
    public static void open(@NotNull Player viewer, @NotNull Plugin plugin) {
        open(viewer, plugin, null, null);
    }

    /**
     * One plugin's settings, entered from that plugin's own screens.
     *
     * @param viewer     who edits; any thread
     * @param plugin     whose settings
     * @param permission accepted for this plugin's files on top of {@link ConfigMenus#PERMISSION}
     * @param back       what BACK on the top screen does; {@code null} goes to the plugin list
     */
    public static void open(@NotNull Player viewer, @NotNull Plugin plugin, @Nullable String permission,
                            @Nullable Consumer<Player> back) {
        if (permission == null && back == null) ENTRIES.remove(viewer.getUniqueId());
        else ENTRIES.put(viewer.getUniqueId(), new Entry(plugin.getName(), permission, back));
        showPlugin(viewer, plugin);
    }

    private static void showPlugin(Player viewer, Plugin plugin) {
        PluginMenus current = menus;
        if (current == null) return;
        List<ConfigFile<?>> files = WebEditorRuntime.get().settingsOf(plugin);
        List<SubjectImpl> content = contentOf(viewer, plugin);
        if (files.isEmpty() && content.isEmpty()) {
            Text.of(Phrases.tr("{warning}✘ {letters}{0} has no settings to change here.", plugin.getName())).send(viewer);
            return;
        }
        if (files.size() + content.size() == 1) {
            if (files.isEmpty()) openYaml(viewer, plugin, content.get(0), List.of());
            else openFile(viewer, plugin, files.get(0));
            return;
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put(PLUGIN_KEY, plugin.getName());
        context.put("cfg_title", Phrases.tr("Settings").toUpperCase(java.util.Locale.ROOT));
        context.put("cfg_where", net.exylia.lib.text.Values.inert(plugin.getName()));
        context.put("cfg_back_to", returning(viewer, plugin) ? Phrases.tr("the previous screen") : Phrases.tr("the plugins"));
        String description = plugin.getDescription().getDescription();
        header(context, "BEACON", plugin.getName(),
                description == null || description.isBlank() ? net.exylia.lib.text.Lines.OMIT : ConfigFields.summary(List.of(description)),
                List.of(Phrases.tr(" {letters_black}▎ {letters}Version {letters_black}» {info}{0}",
                                net.exylia.lib.text.Values.inert(plugin.getDescription().getVersion())),
                        Phrases.tr(" {letters_black}▎ {letters}Settings {letters_black}» {info}{0}", files.size() + content.size())));
        show(viewer, FILES, context, () -> fileRows(viewer, plugin), files.size() + content.size(), 2);
    }

    /**
     * Opens a screen cut to what it shows ({@link ConfigLayout}), its list read from a lambda.
     *
     * @param top rows above the buttons: 1 for a header, 2 for a header and a gap
     */
    private static void show(Player viewer, String id, Map<String, Object> context,
                             Supplier<List<UiEntry>> rows, int count, int top) {
        PluginMenus current = menus;
        Plugin owner = library;
        UiDefinition base = current == null ? null : current.definition(id).orElse(null);
        if (base == null || owner == null) return;
        UiDefinition fitted = ConfigLayout.apply(base, ConfigLayout.fit(count, top));
        Tasks.of(owner).runAtEntity(viewer, () -> current.openNow(viewer, fitted, context, rows));
    }

    /** The header in slot 4: an item, a name, an Information block and a Status block. */
    private static void header(Map<String, Object> context, String icon, String name, String about, List<String> status) {
        List<String> lore = new ArrayList<>();
        if (!net.exylia.lib.text.Lines.OMIT.equals(about)) lore.add(about);
        if (!status.isEmpty()) {
            lore.add(Phrases.tr("{secondary}Status:"));
            lore.addAll(status);
        } else if (!lore.isEmpty()) {
            // The Information block ends in a blank line of its own.
            String last = lore.remove(lore.size() - 1);
            lore.add(last.endsWith(net.exylia.lib.text.Lines.NEWLINE)
                    ? last.substring(0, last.length() - net.exylia.lib.text.Lines.NEWLINE.length()) : last);
        }
        context.put("cfg_head_icon", icon);
        context.put("cfg_head_name", net.exylia.lib.text.Values.inert(name.toUpperCase(java.util.Locale.ROOT)));
        context.put("cfg_head_lore", lore.isEmpty() ? net.exylia.lib.text.Lines.OMIT : net.exylia.lib.text.Lines.join(lore));
    }

    /** A settings file: the hub of its pages, or its only page straight away. */
    private static void openFile(Player viewer, Plugin plugin, ConfigFile<?> file) {
        List<ConfigPages.Page> cards = ConfigPages.cards(file.schema());
        if (cards.size() == 1) {
            openCard(viewer, plugin, file, cards.get(0));
            return;
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put(PLUGIN_KEY, plugin.getName());
        context.put(FILE_KEY, file.name());
        context.put(PAGE_KEY, "");
        context.put("cfg_title", fileName(file));
        context.put("cfg_where", net.exylia.lib.text.Values.inert(plugin.getName()));
        context.put("cfg_back_to", backTo(viewer, plugin, null, file));
        Class<?> type = file.schema().type();
        Menu.Icon icon = type.getAnnotation(Menu.Icon.class);
        Menu.About about = type.getAnnotation(Menu.About.class);
        header(context, icon == null ? "COMPARATOR" : icon.value(), fileName(file),
                about != null ? ConfigFields.block(List.of(about.value())) : ConfigFields.summary(file.schema().comments()),
                List.of(Phrases.tr(" {letters_black}▎ {letters}File {letters_black}» {info}{0}",
                        net.exylia.lib.text.Values.inert(file.name() + ".yml"))));
        show(viewer, FILES, context, cardRows(file), cards.size(), 2);
    }

    /** What a file's screens call it: the record's {@link Menu.Label}, or its name in words. */
    private static String fileName(ConfigFile<?> file) {
        Menu.Label label = file.schema().type().getAnnotation(Menu.Label.class);
        String name = label != null ? label.value() : Discovery.title(file.name());
        return name.toUpperCase(java.util.Locale.ROOT);
    }

    /** A card of a file's hub: its page, or the list of entries it stands for. */
    private static void openCard(Player viewer, Plugin plugin, ConfigFile<?> file, ConfigPages.Page page) {
        if (page.entries() == null) {
            openPage(viewer, plugin, file, page.token());
            return;
        }
        SubjectImpl subject = settingSubject(plugin, file);
        if (subject != null) openYaml(viewer, plugin, subject, List.of(page.entries().key().split("\\.")));
    }

    /** One page of a settings file. */
    private static void openPage(Player viewer, Plugin plugin, ConfigFile<?> file, String token) {
        ConfigPages.Page page = ConfigPages.page(file.schema(), token);
        if (page == null || page.entries() != null) {
            if (page != null) openCard(viewer, plugin, file, page);
            else openFile(viewer, plugin, file);
            return;
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put(PLUGIN_KEY, plugin.getName());
        context.put(FILE_KEY, file.name());
        context.put(PAGE_KEY, token);
        context.put("cfg_title", page.label());
        context.put("cfg_where", net.exylia.lib.text.Values.inert(Discovery.title(file.name())));
        context.put("cfg_back_to", backTo(viewer, plugin, page, file));
        header(context, page.icon(), page.label(), page.about(),
                List.of(Phrases.tr(" {letters_black}▎ {letters}Settings {letters_black}» {info}{0}", page.fields().size())));
        show(viewer, FIELDS, context, pageRows(file, token), page.fields().size(), 1);
    }

    /** A section of a settings file, reached from a content screen: its page, or the file's hub. */
    private static void openSection(Player viewer, Plugin plugin, ConfigFile<?> file, String key) {
        if (!key.isEmpty() && ConfigPages.page(file.schema(), "s:" + key) != null) openPage(viewer, plugin, file, "s:" + key);
        else openFile(viewer, plugin, file);
    }

    /** Where BACK goes from a page ({@code null}: from the file's hub), in words. */
    private static String backTo(Player viewer, Plugin plugin, @Nullable ConfigPages.Page page, ConfigFile<?> file) {
        if (page != null) {
            ConfigPages.Page holder = page.token().startsWith("s:") ? ConfigPages.holder(file.schema(), page.token().substring(2)) : null;
            if (holder != null) return Phrases.tr("the section above");
            if (ConfigPages.cards(file.schema()).size() > 1) return Phrases.tr("the settings of {0}", fileName(file));
        }
        if (count(viewer, plugin) > 1) return Phrases.tr("the files of {0}", plugin.getName());
        return returning(viewer, plugin) ? Phrases.tr("the previous screen") : Phrases.tr("the plugins");
    }

    private static String backTo(Player viewer, Plugin plugin, boolean top) {
        if (!top) return Phrases.tr("the section above");
        if (count(viewer, plugin) > 1) return Phrases.tr("the files of {0}", plugin.getName());
        return returning(viewer, plugin) ? Phrases.tr("the previous screen") : Phrases.tr("the plugins");
    }

    private static int count(Player viewer, Plugin plugin) {
        return WebEditorRuntime.get().settingsOf(plugin).size() + contentOf(viewer, plugin).size();
    }

    // ------------------------------------------------------------------ rows

    private static List<UiEntry> pluginRows() {
        List<UiEntry> rows = new ArrayList<>();
        for (Plugin plugin : WebEditorRuntime.get().configurablePlugins()) {
            rows.add(UiEntry.of(plugin.getName())
                    .with("cfg_plugin_name", plugin.getName())
                    .with("cfg_plugin_version", plugin.getDescription().getVersion())
                    .with("cfg_plugin_files", WebEditorRuntime.get().settingsOf(plugin).size())
                    .build());
        }
        return rows;
    }

    private static List<UiEntry> fileRows(Player viewer, Plugin plugin) {
        List<UiEntry> rows = new ArrayList<>();
        for (ConfigFile<?> file : WebEditorRuntime.get().settingsOf(plugin)) {
            Menu.Icon icon = file.schema().type().getAnnotation(Menu.Icon.class);
            Menu.About about = file.schema().type().getAnnotation(Menu.About.class);
            rows.add(UiEntry.of(file.name())
                    .template("file")
                    .with("cfg_card", file.name())
                    .with("cfg_card_icon", icon == null ? "COMPARATOR" : icon.value())
                    .with("cfg_card_name", fileName(file))
                    .withFormatted("cfg_card_about", about != null ? ConfigFields.block(List.of(about.value()))
                            : ConfigFields.summary(file.schema().comments()))
                    .withFormatted("cfg_card_status", net.exylia.lib.text.Lines.join(List.of(
                            Phrases.tr(" {letters_black}▎ {letters}File {letters_black}» {info}{0}",
                                    net.exylia.lib.text.Values.inert(file.name() + ".yml")),
                            Phrases.tr(" {letters_black}▎ {letters}Settings {letters_black}» {info}{0}",
                                    ConfigPages.pages(file.schema()).stream().mapToInt(page -> page.fields().size()).sum()))))
                    .build());
        }
        for (SubjectImpl subject : contentOf(viewer, plugin)) {
            Read read = read(subject);
            rows.add(UiEntry.of("content:" + subject.id())
                    .template("content")
                    .with("cfg_card", subject.id())
                    .with("cfg_card_icon", "CHEST")
                    .with("cfg_card_name", subject.title().toUpperCase(java.util.Locale.ROOT))
                    .withFormatted("cfg_card_about", ConfigFields.summary(read == null ? List.of() : comments(read.schema())))
                    .withFormatted("cfg_card_status", net.exylia.lib.text.Lines.join(List.of(
                            Phrases.tr(" {letters_black}▎ {letters}File {letters_black}» {info}{0}",
                                    net.exylia.lib.text.Values.inert(subject.source().name() + ".yml")),
                            Phrases.tr(" {letters_black}▎ {letters}Entries {letters_black}» {info}{0}",
                                    read == null ? 0 : read.values().size()))))
                    .build());
        }
        return rows;
    }

    private static List<String> comments(JsonObject schema) {
        List<String> lines = new ArrayList<>();
        if (schema.has("comments")) schema.getAsJsonArray("comments").forEach(line -> lines.add(line.getAsString()));
        return lines;
    }

    /** One page's buttons, read again on every redraw so the screen follows the file. */
    static Supplier<List<UiEntry>> pageRows(ConfigFile<?> file, String token) {
        return () -> {
            Schema schema = file.schema();
            ConfigPages.Page page = ConfigPages.page(schema, token);
            if (page == null) return List.of();
            Object values = file.get();
            List<UiEntry> rows = new ArrayList<>();
            for (ConfigFields.Field field : page.fields()) {
                Object value = field.kind() == ConfigFields.Kind.SECTION ? null
                        : ConfigFields.read(schema, values, field.key());
                boolean opens = field.kind() == ConfigFields.Kind.SECTION || field.kind() == ConfigFields.Kind.ENTRIES;
                rows.add(UiEntry.of(field.key())
                        .template(opens ? "section" : field.kind() == ConfigFields.Kind.LOCKED ? "locked" : null)
                        .with("cfg_key", field.key())
                        .with("cfg_name", ConfigFields.label(field))
                        .with("cfg_icon", ConfigFields.icon(field, value))
                        .withFormatted("cfg_value", opens && field.kind() == ConfigFields.Kind.SECTION
                                ? sectionValue(schema, field) : ConfigFields.display(field, value))
                        .withFormatted("cfg_about", ConfigFields.about(field))
                        .withFormatted("cfg_hint", ConfigFields.hint(field))
                        .build());
            }
            return rows;
        };
    }

    private static String sectionValue(Schema schema, ConfigFields.Field field) {
        ConfigPages.Page page = ConfigPages.page(schema, "s:" + field.key());
        return Phrases.tr("{info}{0} {letters}settings", page == null ? 0 : page.fields().size());
    }

    /** The cards of a file's hub, each with the first values of its page, read again on every redraw. */
    static Supplier<List<UiEntry>> cardRows(ConfigFile<?> file) {
        return () -> {
            Schema schema = file.schema();
            Object values = file.get();
            List<UiEntry> rows = new ArrayList<>();
            for (ConfigPages.Page page : ConfigPages.cards(schema)) {
                rows.add(UiEntry.of(page.token())
                        .with("cfg_card", page.token())
                        .with("cfg_card_icon", page.icon())
                        .with("cfg_card_name", page.label())
                        .withFormatted("cfg_card_about", page.about())
                        .withFormatted("cfg_card_status", summary(schema, values, page))
                        .build());
            }
            return rows;
        };
    }

    /** Up to four values of a page, {@code Label » value}; for a list of entries, how many it holds. */
    private static String summary(Schema schema, Object values, ConfigPages.Page page) {
        List<String> lines = new ArrayList<>();
        if (page.entries() != null) {
            Object value = ConfigFields.read(schema, values, page.entries().key());
            lines.add(Phrases.tr(" {letters_black}▎ {letters}Entries {letters_black}» {info}{0}", ConfigFields.size(value)));
            return net.exylia.lib.text.Lines.join(lines);
        }
        int shown = 0;
        for (ConfigFields.Field field : page.fields()) {
            ConfigFields.Kind kind = field.kind();
            if (kind == ConfigFields.Kind.SECTION || kind == ConfigFields.Kind.LOCKED) continue;
            if (shown++ == 4) {
                lines.add(Phrases.tr(" {letters_black}▎ {letters_black}… and {0} more", page.fields().size() - 4));
                break;
            }
            Object value = ConfigFields.read(schema, values, field.key());
            lines.add(" {letters_black}▎ {letters}" + net.exylia.lib.text.Values.inert(ConfigFields.words(ConfigFields.label(field)))
                    + " {letters_black}» " + ConfigFields.display(field, value));
        }
        if (lines.isEmpty()) {
            lines.add(Phrases.tr(" {letters_black}▎ {letters}Settings {letters_black}» {info}{0}", page.fields().size()));
        }
        return net.exylia.lib.text.Lines.join(lines);
    }

    // ------------------------------------------------------------------ clicks

    private static void edit(Player viewer, UiSession session, String click, String key) {
        Plugin plugin = plugin(session);
        String yaml = session.context(YAML_KEY, String.class).orElse("");
        if (!yaml.isEmpty()) {
            SubjectImpl subject = plugin == null ? null : content(viewer, plugin, yaml);
            if (subject != null) {
                editYaml(viewer, plugin, subject, YamlFields.split(session.context(PATH_KEY, String.class).orElse("")), click, key);
            }
            return;
        }
        ConfigFile<?> file = plugin == null ? null : file(plugin, session.context(FILE_KEY, String.class).orElse(""));
        if (file == null) return;
        ConfigFields.Field field = ConfigFields.field(file.schema(), key);
        if (field == null) return;
        String token = session.context(PAGE_KEY, String.class).orElse("");
        Object current = ConfigFields.read(file.schema(), file.get(), key);
        Runnable back = () -> openPage(viewer, plugin, file, token);
        boolean big = click.startsWith("shift");
        int direction = click.endsWith("right") ? -1 : 1;
        switch (field.kind()) {
            case SECTION -> openPage(viewer, plugin, file, "s:" + key);
            case TOGGLE, CHOICE -> write(viewer, plugin, file, field, ConfigFields.step(field, current, direction, big), null);
            case INTEGER, DECIMAL -> {
                if (click.equals("drop")) number(viewer, plugin, file, field, current, back);
                else write(viewer, plugin, file, field, ConfigFields.step(field, current, direction, big), null);
            }
            case TIME -> ask(viewer, back, inputs(viewer).duration(viewer, prompt(field))
                    .defaultValue(ConfigFields.duration(field, current)).open(),
                    duration -> write(viewer, plugin, file, field, ConfigFields.time(field, duration), back));
            case TEXT -> {
                String now = current == null ? "" : current.toString();
                ask(viewer, back, inputs(viewer).text(viewer, prompt(field)).defaultValue(now)
                        .lines(now.length() > 30 ? 3 : 1).open(),
                        typed -> guarded(viewer, plugin, file, field, typed, back));
            }
            case LINES -> {
                List<?> now = current instanceof List<?> list ? list : List.of();
                List<String> joined = now.stream().map(String::valueOf).toList();
                ask(viewer, back, inputs(viewer).text(viewer, prompt(field)).defaultValue(String.join("\n", joined))
                        .lines(Math.max(4, Math.min(10, joined.size() + 1)))
                        .hint(Phrases.tr("One entry per line")).open(),
                        typed -> guarded(viewer, plugin, file, field, ConfigFields.lines(typed), back));
            }
            case MATERIAL -> ask(viewer, back, inputs(viewer).icon(viewer, prompt(field)).open(),
                    material -> write(viewer, plugin, file, field, material, back));
            case ENTRIES -> {
                SubjectImpl subject = settingSubject(plugin, file);
                if (subject != null) openYaml(viewer, plugin, subject, List.of(key.split("\\.")));
            }
            case LOCKED -> { }
        }
    }

    /** The discovered subject a settings file is, so its maps and lists are edited as content is. */
    private static @Nullable SubjectImpl settingSubject(Plugin plugin, ConfigFile<?> file) {
        for (SubjectImpl subject : WebEditorRuntime.get().settingSubjectsOf(plugin)) {
            if (subject.file() == file) return subject;
        }
        return null;
    }

    private static void number(Player viewer, Plugin plugin, ConfigFile<?> file, ConfigFields.Field field,
                               @Nullable Object current, Runnable back) {
        PluginInputs inputs = inputs(viewer);
        if (field.kind() == ConfigFields.Kind.INTEGER) {
            long now = current instanceof Number number ? number.longValue() : 0;
            ask(viewer, back, inputs.integer(viewer, prompt(field)).defaultValue(now).open(), typed -> {
                if (!inRange(viewer, field, typed, back)) return;
                Class<?> type = field.schema().type();
                Object value = type == int.class || type == Integer.class
                        ? (Object) (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, typed)) : typed;
                write(viewer, plugin, file, field, value, back);
            });
            return;
        }
        BigDecimal now = current instanceof Number number ? new BigDecimal(number.toString()) : BigDecimal.ZERO;
        ask(viewer, back, inputs.decimal(viewer, prompt(field)).defaultValue(now.stripTrailingZeros()).open(), typed -> {
            if (inRange(viewer, field, typed.doubleValue(), back)) write(viewer, plugin, file, field, typed.doubleValue(), back);
        });
    }

    /** Whether a typed number is inside the key's {@link Menu.Range}; tells the viewer and reopens the screen when not. */
    private static boolean inRange(Player viewer, ConfigFields.Field field, double typed, Runnable back) {
        if (ConfigFields.allowed(field, typed)) return true;
        String[] bounds = ConfigFields.bounds(field);
        Text.of(Phrases.tr("[sound:ENTITY_VILLAGER_NO|0.7|1]{error}✘ {letters}{0} must be between {info}{1} {letters}and {info}{2}{letters}.",
                ConfigFields.label(field), bounds[0], bounds[1])).send(viewer);
        back.run();
        return false;
    }

    /** Text that starts a console or op command is written only after a dangerous confirm. */
    private static void guarded(Player viewer, Plugin plugin, ConfigFile<?> file, ConfigFields.Field field,
                                Object value, Runnable back) {
        boolean privileged = value instanceof List<?> list
                ? list.stream().anyMatch(line -> Diff.privileged(new JsonPrimitive(String.valueOf(line))))
                : Diff.privileged(new JsonPrimitive(String.valueOf(value)));
        if (!privileged) {
            write(viewer, plugin, file, field, value, back);
            return;
        }
        ask(viewer, back, inputs(viewer).confirm(viewer,
                        Phrases.tr("{error}This runs a command as the console. Save it?")).dangerous().open(),
                yes -> {
                    if (yes) write(viewer, plugin, file, field, value, back);
                    else back.run();
                });
    }

    private static <T> void ask(Player viewer, Runnable back, CompletionStage<InputResult<T>> asked,
                                java.util.function.Consumer<? super T> accepted) {
        inputs(viewer).answered(viewer, asked, accepted, back);
    }

    private static PluginInputs inputs(Player viewer) {
        viewer.closeInventory();
        return Inputs.of(library);
    }

    private static String prompt(ConfigFields.Field field) {
        return Phrases.tr("{primary}&lNEW {0}", ConfigFields.label(field));
    }

    /**
     * Writes one value on the global thread, brings the plugin up to date, then shows the
     * result on the viewer's thread: the screen redrawn in place, or reopened when an input
     * had closed it ({@code reopen}).
     */
    private static void write(Player viewer, Plugin plugin, ConfigFile<?> file, ConfigFields.Field field,
                              @Nullable Object value, @Nullable Runnable reopen) {
        Plugin owner = library;
        if (owner == null || value == null) return;
        Tasks.of(owner).run(() -> {
            List<ConfigIssue> issues = file.set(field.key(), value);
            WebEditorRuntime.reloadWritten(plugin, List.of());
            Tasks.of(owner).runAtEntity(viewer, () -> {
                if (!issues.isEmpty()) {
                    Text.of(Phrases.tr("[sound:ENTITY_VILLAGER_NO|0.7|1]{error}✘ {letters}{0} was not accepted: {1}",
                            ConfigFields.label(field), issues.get(0).describe())).send(viewer);
                }
                if (reopen != null) {
                    reopen.run();
                    return;
                }
                PluginMenus current = menus;
                UiSession session = current == null ? null : current.session(viewer).orElse(null);
                if (session != null && session.menuId().endsWith(FIELDS)) {
                    session.entries(UiSection.MAIN, pageRows(file, session.context(PAGE_KEY, String.class).orElse("")));
                }
            });
        });
    }

    private static void back(Player viewer, UiSession session) {
        Plugin plugin = plugin(session);
        if (plugin == null) {
            showPlugins(viewer);
            return;
        }
        String yaml = session.context(YAML_KEY, String.class).orElse("");
        if (!yaml.isEmpty()) {
            List<String> path = YamlFields.split(session.context(PATH_KEY, String.class).orElse(""));
            SubjectImpl subject = content(viewer, plugin, yaml);
            ConfigFile<?> settings = subject == null || !subject.isDiscovered() ? null : subject.file();
            if (settings != null && !path.isEmpty() && ConfigFields.field(settings.schema(), String.join(".", path)) != null) {
                // A map or list of a settings file: back to the page its button is on, or the file's hub.
                ConfigPages.Page holder = ConfigPages.holder(settings.schema(), String.join(".", path));
                if (holder != null) openPage(viewer, plugin, settings, holder.token());
                else fileUp(viewer, plugin, settings);
            } else if (subject != null && !path.isEmpty()) openYaml(viewer, plugin, subject, YamlFields.parent(path));
            else pluginUp(viewer, plugin);
            return;
        }
        ConfigFile<?> file = file(plugin, session.context(FILE_KEY, String.class).orElse(""));
        String token = session.context(PAGE_KEY, String.class).orElse("");
        if (file == null || (session.menuId().endsWith(FILES) && token.isEmpty())) {
            pluginUp(viewer, plugin);
            return;
        }
        if (token.startsWith("s:")) {
            ConfigPages.Page holder = ConfigPages.holder(file.schema(), token.substring(2));
            if (holder != null) {
                openPage(viewer, plugin, file, holder.token());
                return;
            }
        }
        fileUp(viewer, plugin, file);
    }

    /** BACK from a page of a file: the file's hub when it has one, the plugin's files otherwise. */
    private static void fileUp(Player viewer, Plugin plugin, ConfigFile<?> file) {
        if (ConfigPages.cards(file.schema()).size() > 1) openFile(viewer, plugin, file);
        else pluginUp(viewer, plugin);
    }

    /** BACK from a file: the plugin's files when it has several, where it was entered from otherwise. */
    private static void pluginUp(Player viewer, Plugin plugin) {
        if (count(viewer, plugin) > 1) showPlugin(viewer, plugin);
        else top(viewer, plugin);
    }

    /** BACK from a plugin's top screen: where the plugin's own hub said, or the plugin list. */
    private static void top(Player viewer, Plugin plugin) {
        Entry entry = ENTRIES.get(viewer.getUniqueId());
        if (entry != null && entry.covers(plugin) && entry.back() != null) entry.back().accept(viewer);
        else showPlugins(viewer);
    }

    private static boolean returning(Player viewer, Plugin plugin) {
        Entry entry = ENTRIES.get(viewer.getUniqueId());
        return entry != null && entry.covers(plugin) && entry.back() != null;
    }

    /** {@link ConfigMenus#PERMISSION}, or the permission the plugin's own hub opened these screens with. */
    static boolean allowed(Player viewer, @Nullable Plugin plugin) {
        if (viewer.hasPermission(ConfigMenus.PERMISSION)) return true;
        Entry entry = ENTRIES.get(viewer.getUniqueId());
        return entry != null && entry.covers(plugin) && entry.permission() != null
                && viewer.hasPermission(entry.permission());
    }

    private static ActionResult refused(Player viewer) {
        viewer.closeInventory();
        return ActionResult.success();
    }

    // ------------------------------------------------------------------ content files

    /** The content files the viewer may change: the plugin's registered YAML subjects, each behind its own permission too. */
    private static List<SubjectImpl> contentOf(Player viewer, Plugin plugin) {
        List<SubjectImpl> visible = new ArrayList<>();
        for (SubjectImpl subject : WebEditorRuntime.get().contentOf(plugin)) {
            String permission = subject.permission();
            if (permission == null || viewer.hasPermission(permission) || viewer.hasPermission(ConfigMenus.PERMISSION)) {
                visible.add(subject);
            }
        }
        return visible;
    }

    private static @Nullable SubjectImpl content(Player viewer, Plugin plugin, String id) {
        if (!allowed(viewer, plugin)) return null;
        for (SubjectImpl subject : contentOf(viewer, plugin)) {
            if (subject.id().equals(id)) return subject;
        }
        // A map or list of a settings file: behind the same permission as the file's own screens.
        for (SubjectImpl subject : WebEditorRuntime.get().settingSubjectsOf(plugin)) {
            if (subject.id().equals(id)) return subject;
        }
        return null;
    }

    /** What a content file holds, read again only when it changed on disk; {@code null} when it cannot be read. */
    private static @Nullable Read read(SubjectImpl subject) {
        java.io.File disk = subject.source().disk();
        long modified = disk == null ? 0 : disk.lastModified();
        long length = disk == null ? 0 : disk.length();
        // A record file is already in memory: read it every time, so a write in the same second shows.
        boolean memory = subject.source() instanceof Source.RecordFile;
        Read known = memory ? null : READS.get(subject.key());
        if (known != null && known.modified() == modified && known.length() == length) return known;
        try {
            String name = subject.plugin().getName();
            Read fresh = new Read(modified, length, subject.source().schema(name), subject.source().values(name));
            if (!memory) READS.put(subject.key(), fresh);
            return fresh;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /** A place in a content file: the list of entries for a block of them, the keys for anything else. */
    private static void openYaml(Player viewer, Plugin plugin, SubjectImpl subject, List<String> path) {
        PluginMenus current = menus;
        Read read = read(subject);
        if (current == null || read == null) return;
        ConfigFile<?> file = subject.file();
        if (subject.isDiscovered() && file != null && YamlFields.sections(read.schema(), path)) {
            // A settings file's own sections keep their record screen, with every control it has.
            openSection(viewer, plugin, file, String.join(".", path));
            return;
        }
        boolean entries = YamlFields.kind(YamlFields.type(read.schema(), path)) == ConfigFields.Kind.ENTRIES;
        Map<String, Object> context = new LinkedHashMap<>();
        context.put(PLUGIN_KEY, plugin.getName());
        context.put(YAML_KEY, subject.id());
        context.put(PATH_KEY, YamlFields.join(path));
        String where = subject.title() + (path.isEmpty() ? "" : " › " + String.join(" › ", path));
        context.put("cfg_where", net.exylia.lib.text.Values.inert(where));
        context.put("cfg_back_to", backTo(viewer, plugin, path.isEmpty()));
        if (entries) {
            current.open(viewer, ENTRIES_MENU, context, entryRows(subject, path));
            return;
        }
        String name = path.isEmpty() ? subject.title() : ConfigFields.words(path.get(path.size() - 1));
        context.put("cfg_title", name.toUpperCase(java.util.Locale.ROOT));
        context.put("cfg_where", net.exylia.lib.text.Values.inert(path.isEmpty() ? plugin.getName() : subject.title()));
        int count = YamlFields.fields(YamlFields.type(read.schema(), path)).size();
        header(context, path.isEmpty() ? "CHEST" : "BOOKSHELF", name,
                ConfigFields.summary(path.isEmpty() ? comments(read.schema()) : List.of()),
                List.of(Phrases.tr(" {letters_black}▎ {letters}File {letters_black}» {info}{0}",
                        net.exylia.lib.text.Values.inert(subject.source().name() + ".yml"))));
        show(viewer, FIELDS, context, yamlRows(subject, path), count, 1);
    }

    /** One section of a content file; a row's key is its position, so any key a YAML file allows works. */
    private static Supplier<List<UiEntry>> yamlRows(SubjectImpl subject, List<String> path) {
        return () -> {
            Read read = read(subject);
            if (read == null) return List.of();
            List<UiEntry> rows = new ArrayList<>();
            List<YamlFields.Node> nodes = YamlFields.fields(YamlFields.type(read.schema(), path));
            for (int i = 0; i < nodes.size(); i++) {
                YamlFields.Node node = nodes.get(i);
                ConfigFields.Kind kind = node.kind();
                Object value = YamlFields.plain(kind, YamlFields.value(read.values(), YamlFields.child(path, node.key())));
                boolean opens = kind == ConfigFields.Kind.SECTION || kind == ConfigFields.Kind.ENTRIES;
                rows.add(UiEntry.of(node.key())
                        .template(opens ? "section" : kind == ConfigFields.Kind.LOCKED ? "locked" : null)
                        .with("cfg_key", String.valueOf(i))
                        .with("cfg_name", ConfigFields.title(node.key()))
                        .with("cfg_icon", yamlIcon(node.key(), kind, value))
                        .withFormatted("cfg_value", ConfigFields.plain(kind, value))
                        .withFormatted("cfg_about", ConfigFields.summary(node.comments()))
                        .withFormatted("cfg_hint", ConfigFields.hint(kind))
                        .build());
            }
            return rows;
        };
    }

    private static String yamlIcon(String key, ConfigFields.Kind kind, @Nullable Object value) {
        String named = kind == ConfigFields.Kind.TOGGLE || kind == ConfigFields.Kind.MATERIAL ? null : ConfigFields.named(key);
        return named != null ? named : yamlIcon(kind, value);
    }

    private static String yamlIcon(ConfigFields.Kind kind, @Nullable Object value) {
        return switch (kind) {
            case SECTION -> "BOOKSHELF";
            case ENTRIES -> "CHEST";
            case TOGGLE -> Boolean.TRUE.equals(value) ? "LIME_DYE" : "GRAY_DYE";
            case INTEGER, DECIMAL -> "PAPER";
            case TEXT -> "NAME_TAG";
            case LINES -> "WRITABLE_BOOK";
            case CHOICE -> "COMPARATOR";
            case TIME -> "CLOCK";
            case MATERIAL -> {
                org.bukkit.Material material = value == null ? null : org.bukkit.Material.matchMaterial(String.valueOf(value));
                yield material != null && material.isItem() && !material.isAir() ? material.name() : "BARRIER";
            }
            default -> "STRUCTURE_VOID";
        };
    }

    /**
     * The entries of a block or a list, each drawn with the item it names when it names one; an
     * entry that is a single value (a map of numbers, of text) shows that value and is edited
     * straight from here.
     */
    private static Supplier<List<UiEntry>> entryRows(SubjectImpl subject, List<String> path) {
        return () -> {
            Read read = read(subject);
            if (read == null) return List.of();
            JsonObject type = YamlFields.type(read.schema(), path);
            boolean list = YamlFields.isList(type);
            JsonObject of = type == null ? null : type.getAsJsonObject("of");
            ConfigFields.Kind kind = YamlFields.kind(of);
            boolean opens = kind == ConfigFields.Kind.SECTION || kind == ConfigFields.Kind.ENTRIES;
            List<UiEntry> rows = new ArrayList<>();
            List<String> ids = YamlFields.ids(read.values(), path);
            for (int i = 0; i < ids.size(); i++) {
                JsonElement entry = YamlFields.value(read.values(), YamlFields.child(path, ids.get(i)));
                Object plain = YamlFields.plain(kind, entry);
                rows.add(UiEntry.of(ids.get(i))
                        .with("cfg_key", String.valueOf(i))
                        .with("cfg_name", list ? label(i, entry) : ids.get(i))
                        .with("cfg_icon", opens ? entryIcon(entry) : yamlIcon(kind, plain))
                        .withFormatted("cfg_value", opens && kind == ConfigFields.Kind.SECTION
                                ? Phrases.tr("{info}{0} {letters}keys", entry != null && entry.isJsonObject() ? entry.getAsJsonObject().size() : 0)
                                : ConfigFields.plain(kind, plain))
                        .withFormatted("cfg_hint", entryHint(kind))
                        .build());
            }
            return rows;
        };
    }

    /** What a click on an entry does: open it, or change the one value it is. */
    private static String entryHint(ConfigFields.Kind kind) {
        return switch (kind) {
            case TOGGLE -> Phrases.tr("{warning}➥ Click {letters_black}» {letters}switch");
            case CHOICE -> Phrases.tr("{warning}➥ Click {letters_black}» {letters}next");
            case INTEGER, DECIMAL, TEXT, LINES, TIME, MATERIAL -> Phrases.tr("{warning}➥ Click {letters_black}» {letters}change");
            default -> Phrases.tr("{warning}➥ Click {letters_black}» {letters}open");
        };
    }

    /** A list's entry: its position, and the name it gives itself when it has one. */
    private static String label(int index, @Nullable JsonElement entry) {
        String label = "#" + (index + 1);
        if (entry != null && entry.isJsonObject()) {
            for (String key : List.of("name", "id", "title")) {
                JsonElement named = entry.getAsJsonObject().get(key);
                if (named != null && named.isJsonPrimitive() && !named.getAsString().isBlank()) {
                    String text = named.getAsString();
                    return label + " · " + (text.length() <= 24 ? text : text.substring(0, 23) + "…");
                }
            }
        }
        return label;
    }

    private static String entryIcon(@Nullable JsonElement entry) {
        if (entry != null && entry.isJsonObject()) {
            for (String key : List.of("material", "icon", "item", "display-item", "type")) {
                JsonElement value = entry.getAsJsonObject().get(key);
                if (value == null || !value.isJsonPrimitive()) continue;
                org.bukkit.Material material = org.bukkit.Material.matchMaterial(value.getAsString());
                if (material != null && material.isItem() && !material.isAir()) return material.name();
            }
        }
        return "PAPER";
    }

    /** A click on a key of a content file. */
    private static void editYaml(Player viewer, Plugin plugin, SubjectImpl subject, List<String> path,
                                 String click, String index) {
        Read read = read(subject);
        if (read == null) return;
        List<YamlFields.Node> nodes = YamlFields.fields(YamlFields.type(read.schema(), path));
        int at = parse(index);
        if (at < 0 || at >= nodes.size()) return;
        YamlFields.Node node = nodes.get(at);
        editAt(viewer, plugin, subject, path, YamlFields.child(path, node.key()), node.kind(), node.type(), node.key(), click);
    }

    /**
     * Changes the value at one place of a content file, the way its kind is changed.
     *
     * @param screen where the viewer is, reopened after a prompt
     * @param key    the place
     * @param name   what the prompt calls it
     */
    private static void editAt(Player viewer, Plugin plugin, SubjectImpl subject, List<String> screen, List<String> key,
                               ConfigFields.Kind kind, @Nullable JsonObject type, String name, String click) {
        Read read = read(subject);
        if (read == null) return;
        JsonElement current = YamlFields.value(read.values(), key);
        Runnable back = () -> openYaml(viewer, plugin, subject, screen);
        boolean big = click.startsWith("shift");
        int direction = click.endsWith("right") ? -1 : 1;
        String prompt = Phrases.tr("{primary}&lNEW {0}", ConfigFields.title(name));
        switch (kind) {
            case SECTION, ENTRIES -> openYaml(viewer, plugin, subject, key);
            case TOGGLE -> set(viewer, plugin, subject, key,
                    new JsonPrimitive(!(Boolean.TRUE.equals(YamlFields.plain(kind, current)))), null);
            case CHOICE -> {
                if (type != null) set(viewer, plugin, subject, key, YamlFields.next(type, current, direction), null);
            }
            case INTEGER -> {
                long now = current != null && current.isJsonPrimitive() && current.getAsJsonPrimitive().isNumber() ? current.getAsLong() : 0;
                if (click.equals("drop")) {
                    ask(viewer, back, inputs(viewer).integer(viewer, prompt).defaultValue(now).open(),
                            typed -> set(viewer, plugin, subject, key, new JsonPrimitive(typed), back));
                } else {
                    set(viewer, plugin, subject, key, new JsonPrimitive(now + (long) direction * (big ? 10 : 1)), null);
                }
            }
            case DECIMAL -> {
                BigDecimal now = current != null && current.isJsonPrimitive() && current.getAsJsonPrimitive().isNumber()
                        ? current.getAsBigDecimal() : BigDecimal.ZERO;
                if (click.equals("drop")) {
                    ask(viewer, back, inputs(viewer).decimal(viewer, prompt).defaultValue(now.stripTrailingZeros()).open(),
                            typed -> set(viewer, plugin, subject, key, new JsonPrimitive(typed.doubleValue()), back));
                } else {
                    BigDecimal next = now.add(new BigDecimal(big ? "1" : "0.1").multiply(BigDecimal.valueOf(direction)));
                    set(viewer, plugin, subject, key, new JsonPrimitive(next.doubleValue()), null);
                }
            }
            case TIME -> {
                if (type == null) return;
                ask(viewer, back, inputs(viewer).duration(viewer, prompt).defaultValue(YamlFields.duration(type, current)).open(),
                        duration -> set(viewer, plugin, subject, key, YamlFields.time(type, duration), back));
            }
            case MATERIAL -> {
                if (type == null) return;
                ask(viewer, back, inputs(viewer).icon(viewer, prompt).open(),
                        material -> set(viewer, plugin, subject, key, YamlFields.material(type, material), back));
            }
            case TEXT -> {
                String now = current == null ? "" : String.valueOf(YamlFields.plain(kind, current));
                ask(viewer, back, inputs(viewer).text(viewer, prompt).defaultValue(now).lines(now.length() > 30 ? 3 : 1).open(),
                        typed -> set(viewer, plugin, subject, key, new JsonPrimitive(typed), back));
            }
            case LINES -> {
                Object plain = YamlFields.plain(kind, current);
                List<String> now = plain instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
                ask(viewer, back, inputs(viewer).text(viewer, prompt).defaultValue(String.join("\n", now))
                                .lines(Math.max(4, Math.min(10, now.size() + 1))).hint(Phrases.tr("One entry per line")).open(),
                        typed -> set(viewer, plugin, subject, key, YamlFields.lines(ConfigFields.lines(typed)), back));
            }
            default -> { }
        }
    }

    /** A click on the list of entries: open, create, duplicate or delete one. */
    private static void entry(Player viewer, Plugin plugin, SubjectImpl subject, List<String> path,
                              String action, String index) {
        Read read = read(subject);
        if (read == null) return;
        JsonObject type = YamlFields.type(read.schema(), path);
        boolean list = YamlFields.isList(type);
        JsonObject of = type == null ? null : type.getAsJsonObject("of");
        ConfigFields.Kind kind = YamlFields.kind(of);
        boolean opens = kind == ConfigFields.Kind.SECTION || kind == ConfigFields.Kind.ENTRIES;
        List<String> ids = YamlFields.ids(read.values(), path);
        Runnable back = () -> openYaml(viewer, plugin, subject, path);
        if (action.equals("create")) {
            if (list) {
                String id = String.valueOf(ids.size());
                change(viewer, plugin, subject, path,
                        values -> YamlFields.inserted(values, path, Integer.MAX_VALUE, fresh(subject, read.schema(), path, of, values)),
                        opens ? () -> openYaml(viewer, plugin, subject, YamlFields.child(path, id)) : null);
                return;
            }
            ask(viewer, back, inputs(viewer).text(viewer, Phrases.tr("{primary}&lNEW ENTRY")).open(), typed -> {
                String id = typed.trim();
                if (!YamlFields.ID.matcher(id).matches() || ids.contains(id)) {
                    Text.of(Phrases.tr("[sound:ENTITY_VILLAGER_NO|0.7|1]{error}✘ {letters}{0} cannot be used: letters, digits, - _ and . only, and not one already taken.",
                            net.exylia.lib.text.Values.inert(id))).send(viewer);
                    back.run();
                    return;
                }
                change(viewer, plugin, subject, path,
                        values -> YamlFields.with(values, YamlFields.child(path, id), fresh(subject, read.schema(), path, of, values)),
                        opens ? () -> openYaml(viewer, plugin, subject, YamlFields.child(path, id)) : back);
            });
            return;
        }
        int at = parse(index);
        if (at < 0 || at >= ids.size()) return;
        String id = ids.get(at);
        String shown = list ? label(at, YamlFields.value(read.values(), YamlFields.child(path, id))) : id;
        switch (action) {
            case "open" -> {
                if (opens) {
                    openYaml(viewer, plugin, subject, YamlFields.child(path, id));
                } else {
                    boolean typed = kind == ConfigFields.Kind.INTEGER || kind == ConfigFields.Kind.DECIMAL;
                    editAt(viewer, plugin, subject, path, YamlFields.child(path, id), kind, of, id, typed ? "drop" : "left");
                }
            }
            case "duplicate" -> {
                if (list) {
                    change(viewer, plugin, subject, path, values -> {
                        JsonElement copy = YamlFields.value(values, YamlFields.child(path, id));
                        return copy == null ? values : YamlFields.inserted(values, path, at + 1, copy);
                    }, back);
                } else {
                    String copy = YamlFields.freeId(ids, id);
                    change(viewer, plugin, subject, path, values -> YamlFields.added(values, path, copy, id), back);
                }
            }
            case "delete" -> ask(viewer, back, inputs(viewer).confirm(viewer,
                            Phrases.tr("{error}Delete {0}? It is removed from the file.", net.exylia.lib.text.Values.inert(shown))).dangerous().open(),
                    yes -> {
                        if (yes) change(viewer, plugin, subject, path, values -> YamlFields.with(values, YamlFields.child(path, id), null), back);
                        else back.run();
                    });
            default -> { }
        }
    }

    /**
     * What a new entry starts as. In a record file: a block the binder fills with the record's
     * defaults, and for a list (whose blocks the binder wants whole) the element record's own
     * defaults. In a YAML file, which has no defaults: a copy of an entry it already holds, so
     * it passes the plugin's checker.
     */
    static JsonElement fresh(SubjectImpl subject, JsonObject schema, List<String> path, @Nullable JsonObject of, JsonObject values) {
        boolean list = YamlFields.isList(YamlFields.type(schema, path));
        ConfigFile<?> file = subject.file();
        if (file != null) {
            if (!list) return YamlFields.blank(of);
            JsonElement defaults = defaults(subject, file, schema, path);
            if (defaults != null) return defaults;
        }
        List<String> ids = YamlFields.ids(values, path);
        if (!ids.isEmpty()) {
            JsonElement sample = YamlFields.value(values, YamlFields.child(path, ids.get(list ? ids.size() - 1 : 0)));
            if (sample != null) return sample;
        }
        return YamlFields.blank(of);
    }

    /** A list element's record defaults, as the file writes them; {@code null} when its type cannot be followed. */
    private static @Nullable JsonElement defaults(SubjectImpl subject, ConfigFile<?> file, JsonObject schema, List<String> path) {
        java.lang.reflect.Type type = YamlFields.javaType(file.schema().type(), schema, YamlFields.child(path, "0"));
        if (!(type instanceof Class<?> record) || !record.isRecord()) return null;
        try {
            var constructor = record.getDeclaredConstructor();
            constructor.setAccessible(true);
            return ValueCodec.toJson(record, subject.plugin().getName(), constructor.newInstance());
        } catch (ReflectiveOperationException | RuntimeException unbuildable) {
            return null;
        }
    }

    private static int parse(String index) {
        try {
            return Integer.parseInt(index);
        } catch (NumberFormatException notIndex) {
            return -1;
        }
    }

    private static void set(Player viewer, Plugin plugin, SubjectImpl subject, List<String> key, JsonElement value,
                            @Nullable Runnable reopen) {
        change(viewer, plugin, subject, YamlFields.parent(key), values -> YamlFields.with(values, key, value), reopen);
    }

    /**
     * Writes one change to a content file the way an applied web edit is written: on the global
     * thread, from what the file holds then, through the subject's checker; a refusal is told
     * and nothing is written, and what the web editor would hold for {@code confirm} is asked
     * with a dangerous confirm first. Then the screen is redrawn in place, or reopened when a
     * prompt had closed it.
     */
    private static void change(Player viewer, Plugin plugin, SubjectImpl subject, List<String> screen,
                               java.util.function.UnaryOperator<JsonObject> edit, @Nullable Runnable reopen) {
        Plugin owner = library;
        if (owner == null) return;
        Runnable shown = reopen != null ? reopen : () -> redraw(viewer);
        // A confirm closes the screen, so after one it is opened again rather than redrawn.
        Runnable reshown = reopen != null ? reopen : () -> openYaml(viewer, plugin, subject, screen);
        Tasks.of(owner).run(() -> {
            WebEditorRuntime.InGame change;
            try {
                change = WebEditorRuntime.get().inGame(subject, edit.apply(subject.source().values(plugin.getName())));
            } catch (RuntimeException failure) {
                change = new WebEditorRuntime.InGame(String.valueOf(failure.getMessage()), List.of(), null);
            }
            WebEditorRuntime.InGame read = change;
            Tasks.of(owner).runAtEntity(viewer, () -> {
                if (read.problem() != null || read.write() == null) {
                    Text.of(Phrases.tr("[sound:ENTITY_VILLAGER_NO|0.7|1]{error}✘ {letters}{0} was not saved: {1}",
                            subject.title(), net.exylia.lib.text.Values.inert(String.valueOf(read.problem())))).send(viewer);
                    shown.run();
                    return;
                }
                if (read.confirm().isEmpty()) {
                    commit(viewer, owner, subject, read.write(), shown);
                    return;
                }
                ask(viewer, reshown, inputs(viewer).confirm(viewer,
                                Phrases.tr("{error}Save this change? {0}", read.confirm().get(0))).dangerous().open(),
                        yes -> {
                            if (yes) commit(viewer, owner, subject, read.write(), reshown);
                            else reshown.run();
                        });
            });
        });
    }

    private static void commit(Player viewer, Plugin owner, SubjectImpl subject, Runnable write, Runnable shown) {
        Tasks.of(owner).run(() -> {
            String failed = null;
            try {
                write.run();
            } catch (RuntimeException failure) {
                failed = String.valueOf(failure.getMessage());
            }
            String reason = failed;
            Tasks.of(owner).runAtEntity(viewer, () -> {
                if (reason != null) {
                    Text.of(Phrases.tr("[sound:ENTITY_VILLAGER_NO|0.7|1]{error}✘ {letters}{0} was not saved: {1}",
                            subject.title(), net.exylia.lib.text.Values.inert(reason))).send(viewer);
                }
                shown.run();
            });
        });
    }

    /** Redraws an open content screen in place, after a click that closed nothing. */
    private static void redraw(Player viewer) {
        PluginMenus current = menus;
        UiSession session = current == null ? null : current.session(viewer).orElse(null);
        if (session == null) return;
        Plugin plugin = plugin(session);
        String yaml = session.context(YAML_KEY, String.class).orElse("");
        SubjectImpl subject = plugin == null || yaml.isEmpty() ? null : content(viewer, plugin, yaml);
        if (subject == null) return;
        List<String> path = YamlFields.split(session.context(PATH_KEY, String.class).orElse(""));
        session.entries(UiSection.MAIN, session.menuId().endsWith(ENTRIES_MENU) ? entryRows(subject, path) : yamlRows(subject, path));
    }

    // ------------------------------------------------------------------ lookups

    private static @Nullable Plugin plugin(UiSession session) {
        String name = session.context(PLUGIN_KEY, String.class).orElse("");
        Plugin plugin = name.isEmpty() ? null : Bukkit.getPluginManager().getPlugin(name);
        return plugin != null && plugin.isEnabled() ? plugin : null;
    }

    private static @Nullable ConfigFile<?> file(Plugin plugin, String name) {
        for (ConfigFile<?> file : WebEditorRuntime.get().settingsOf(plugin)) {
            if (file.name().equals(name)) return file;
        }
        return null;
    }
}
