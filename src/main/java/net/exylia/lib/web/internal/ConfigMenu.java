package net.exylia.lib.web.internal;

import com.google.gson.JsonPrimitive;
import net.exylia.lib.action.ActionResult;
import net.exylia.lib.action.Actions;
import net.exylia.lib.action.PluginActions;
import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.config.ConfigIssue;
import net.exylia.lib.config.ConfigMenus;
import net.exylia.lib.config.Languages;
import net.exylia.lib.config.Schema;
import net.exylia.lib.input.InputResult;
import net.exylia.lib.input.Inputs;
import net.exylia.lib.input.PluginInputs;
import net.exylia.lib.settings.internal.SettingsMenu;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Phrases;
import net.exylia.lib.text.Text;
import net.exylia.lib.ui.PluginMenus;
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
 * <p>Admin screens, rewritten from the jar on every start ({@link #FOLDER}).
 */
public final class ConfigMenu {

    static final String PLUGINS = "config_plugins";
    static final String FILES = "config_files";
    static final String FIELDS = "config_fields";
    static final String FOLDER = "menus/config";

    private static final String PLUGIN_KEY = "cfg_plugin";
    private static final String FILE_KEY = "cfg_file";
    private static final String PATH_KEY = "cfg_path";

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
            if (file != null) openFields(context.player(), target, file, "");
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
        if (files.isEmpty()) {
            Text.of(Phrases.tr("{warning}✘ {letters}{0} has no settings to change here.", plugin.getName())).send(viewer);
            return;
        }
        if (files.size() == 1) {
            openFields(viewer, plugin, files.get(0), "");
            return;
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put(PLUGIN_KEY, plugin.getName());
        context.put("cfg_back_to", returning(viewer, plugin) ? Phrases.tr("the previous screen") : Phrases.tr("the plugins"));
        current.open(viewer, FILES, context, () -> fileRows(plugin));
    }

    private static void openFields(Player viewer, Plugin plugin, ConfigFile<?> file, String path) {
        PluginMenus current = menus;
        if (current == null) return;
        current.open(viewer, FIELDS, fieldsContext(viewer, plugin, file, path), rows(file, path));
    }

    private static Map<String, Object> fieldsContext(Player viewer, Plugin plugin, ConfigFile<?> file, String path) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put(PLUGIN_KEY, plugin.getName());
        context.put(FILE_KEY, file.name());
        context.put(PATH_KEY, path);
        String where = Discovery.title(file.name()) + (path.isEmpty() ? "" : " › " + path);
        context.put("cfg_where", net.exylia.lib.text.Values.inert(where));
        context.put("cfg_back_to", path.isEmpty()
                ? (WebEditorRuntime.get().settingsOf(plugin).size() == 1
                        ? (returning(viewer, plugin) ? Phrases.tr("the previous screen") : Phrases.tr("the plugins")) : Phrases.tr("the files of {0}", plugin.getName()))
                : Phrases.tr("the section above"));
        return context;
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

    private static List<UiEntry> fileRows(Plugin plugin) {
        List<UiEntry> rows = new ArrayList<>();
        for (ConfigFile<?> file : WebEditorRuntime.get().settingsOf(plugin)) {
            rows.add(UiEntry.of(file.name())
                    .with("cfg_file_id", file.name())
                    .with("cfg_file_name", Discovery.title(file.name()))
                    .with("cfg_file_path", file.name() + ".yml")
                    .with("cfg_file_keys", file.schema().fields().size())
                    .withFormatted("cfg_file_about", ConfigFields.about(file.schema().comments()))
                    .build());
        }
        return rows;
    }

    /** One section's buttons, read again on every redraw so the screen follows the file. */
    static Supplier<List<UiEntry>> rows(ConfigFile<?> file, String path) {
        return () -> {
            Schema schema = file.schema();
            Object values = file.get();
            List<UiEntry> rows = new ArrayList<>();
            for (ConfigFields.Field field : ConfigFields.fields(schema, path)) {
                Object value = field.kind() == ConfigFields.Kind.SECTION ? null
                        : ConfigFields.read(schema, values, field.key());
                rows.add(UiEntry.of(field.key())
                        .template(field.kind() == ConfigFields.Kind.SECTION ? "section"
                                : field.kind() == ConfigFields.Kind.LOCKED ? "locked" : null)
                        .with("cfg_key", field.key())
                        .with("cfg_name", ConfigFields.title(field.name()))
                        .with("cfg_icon", ConfigFields.icon(field, value))
                        .withFormatted("cfg_value", ConfigFields.display(field, value))
                        .withFormatted("cfg_about", ConfigFields.about(field.schema().comments()))
                        .withFormatted("cfg_hint", ConfigFields.hint(field))
                        .build());
            }
            return rows;
        };
    }

    // ------------------------------------------------------------------ clicks

    private static void edit(Player viewer, UiSession session, String click, String key) {
        Plugin plugin = plugin(session);
        ConfigFile<?> file = plugin == null ? null : file(plugin, session.context(FILE_KEY, String.class).orElse(""));
        if (file == null) return;
        ConfigFields.Field field = ConfigFields.field(file.schema(), key);
        if (field == null) return;
        String path = session.context(PATH_KEY, String.class).orElse("");
        Object current = ConfigFields.read(file.schema(), file.get(), key);
        Runnable back = () -> openFields(viewer, plugin, file, path);
        boolean big = click.startsWith("shift");
        int direction = click.endsWith("right") ? -1 : 1;
        switch (field.kind()) {
            case SECTION -> openFields(viewer, plugin, file, key);
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
            case LOCKED -> { }
        }
    }

    private static void number(Player viewer, Plugin plugin, ConfigFile<?> file, ConfigFields.Field field,
                               @Nullable Object current, Runnable back) {
        PluginInputs inputs = inputs(viewer);
        if (field.kind() == ConfigFields.Kind.INTEGER) {
            long now = current instanceof Number number ? number.longValue() : 0;
            ask(viewer, back, inputs.integer(viewer, prompt(field)).defaultValue(now).open(), typed -> {
                Class<?> type = field.schema().type();
                Object value = type == int.class || type == Integer.class
                        ? (Object) (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, typed)) : typed;
                write(viewer, plugin, file, field, value, back);
            });
            return;
        }
        BigDecimal now = current instanceof Number number ? new BigDecimal(number.toString()) : BigDecimal.ZERO;
        ask(viewer, back, inputs.decimal(viewer, prompt(field)).defaultValue(now.stripTrailingZeros()).open(),
                typed -> write(viewer, plugin, file, field, typed.doubleValue(), back));
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
        return Phrases.tr("{primary}&lNEW {0}", ConfigFields.title(field.name()));
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
                            ConfigFields.title(field.name()), issues.get(0).describe())).send(viewer);
                }
                if (reopen != null) {
                    reopen.run();
                    return;
                }
                PluginMenus current = menus;
                UiSession session = current == null ? null : current.session(viewer).orElse(null);
                if (session != null && session.menuId().endsWith(FIELDS)) {
                    session.entries(UiSection.MAIN, rows(file, session.context(PATH_KEY, String.class).orElse("")));
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
        if (!session.menuId().endsWith(FIELDS)) {
            top(viewer, plugin);
            return;
        }
        String path = session.context(PATH_KEY, String.class).orElse("");
        ConfigFile<?> file = file(plugin, session.context(FILE_KEY, String.class).orElse(""));
        if (file != null && !path.isEmpty()) {
            int dot = path.lastIndexOf('.');
            openFields(viewer, plugin, file, dot < 0 ? "" : path.substring(0, dot));
            return;
        }
        if (WebEditorRuntime.get().settingsOf(plugin).size() > 1) showPlugin(viewer, plugin);
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
