package net.exylia.lib.log.internal;

import net.exylia.lib.action.ActionContext;
import net.exylia.lib.action.ActionResult;
import net.exylia.lib.action.Actions;
import net.exylia.lib.action.PluginActions;
import net.exylia.lib.config.Languages;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.format.Formats;
import net.exylia.lib.log.Logs;
import net.exylia.lib.settings.internal.SettingsMenu;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.LibraryMessages;
import net.exylia.lib.text.Text;
import net.exylia.lib.text.Values;
import net.exylia.lib.ui.PluginMenus;
import net.exylia.lib.ui.UiEntry;
import net.exylia.lib.ui.UiKeys;
import net.exylia.lib.ui.UiSession;
import net.exylia.lib.internal.LibrarySettings;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The screens behind {@code /exylialib logs}: a paged list of entries with a
 * plugin and an action filter, and one entry in full with the items it kept.
 *
 * <p>The list is a root screen a command opens, so it has no BACK; the entry
 * returns to it with the same filters. The filters live in the session
 * context, never in a per-player map, and a filter click redraws the open
 * list in place.
 *
 * <p>Compiled into the library's one {@link PluginMenus}, beside the settings
 * screens. The files ({@link #FOLDER} in the library's language folder) are
 * the server's to restyle: {@link Languages#refresh} only adds what is missing.
 */
public final class LogsMenu {

    /** What staff need to read the logs. */
    public static final String PERMISSION = "exylialib.logs";

    static final String LIST = "logs";
    static final String ENTRY = "logs_entry";
    static final String FOLDER = "menus/logs";

    /** The subject key of the feed of every player. */
    public static final String EVERYBODY = "*";

    private static final String SUBJECT_KEY = "logs_subject_key";
    private static final String NAME_KEY = "logs_subject_name";
    private static final String PLUGIN_KEY = "logs_plugin_key";
    private static final String ACTION_KEY = "logs_action_key";

    /** How wide a detail line is before it wraps. */
    private static final int WIDTH = 40;
    /** How many detail lines a list row shows before it is cut. */
    private static final int LINES = 10;
    /** How many detail lines the entry's header shows. */
    private static final int ENTRY_LINES = 24;
    /** What a continuation line and an item under a heading are indented by. */
    private static final String INDENT = "  ";
    /** Slots per row of the entry's list, which is what groups are aligned to. */
    private static final int ROW = 9;
    /** Where a lone row sits on an empty entry list: the middle of its second row. */
    private static final int CENTRE = 13;

    private static volatile PluginMenus menus;
    private static volatile Plugin library;
    private static volatile Class<?> anchor = LogsMenu.class;

    /**
     * What the list shows: whose entries, and which filters.
     *
     * @param player whose entries, or {@code null} for everybody
     * @param name   what to call them
     * @param plugin only this plugin, or {@code null}
     * @param action only this action, or {@code null}
     */
    public record Filter(@Nullable UUID player, @NotNull String name, @Nullable String plugin,
                         @Nullable String action) {
    }

    private LogsMenu() {
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
        actions.registerSync("logs_plugin", PERMISSION, (context, arguments) -> {
            Filter now = filter(context.require(UiKeys.SESSION));
            String chosen = "all".equals(arguments.string(0, "next")) ? null : next(Logs.plugins(), now.plugin());
            open(context.player(), new Filter(now.player(), now.name(), chosen, null));
            return ActionResult.success();
        });
        actions.registerSync("logs_action", PERMISSION, (context, arguments) -> {
            Filter now = filter(context.require(UiKeys.SESSION));
            String action = "all".equals(arguments.string(0, "next")) ? null
                    : next(Logs.actions(now.plugin()), now.action());
            open(context.player(), new Filter(now.player(), now.name(), now.plugin(), action));
            return ActionResult.success();
        });
        actions.registerSync("logs_entry", PERMISSION, (context, arguments) -> {
            openEntry(context.player(), arguments.string(0, ""), filter(context.require(UiKeys.SESSION)));
            return ActionResult.success();
        });
        actions.registerSync("logs_back", PERMISSION, (context, arguments) -> {
            open(context.player(), filter(context.require(UiKeys.SESSION)));
            return ActionResult.success();
        });
    }

    /** Compiles the screens into the library's menus, from {@code lang/<code>/menus/logs/}. */
    public static void load(@NotNull PluginMenus target) {
        Plugin owner = target.plugin();
        // Admin screens: rewritten from the jar on every start.
        Languages.replace(owner, anchor, FOLDER);
        target.load(LIST, SettingsMenu.read(owner, FOLDER, "logs"));
        target.load(ENTRY, SettingsMenu.read(owner, FOLDER, "entry"));
        menus = target;
    }

    /**
     * Where the packaged screens are read from. For tests, whose classes and
     * resources sit in different directories.
     *
     * @param packaged a class whose artifact holds {@code lang/<code>/menus/logs/}
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

    /**
     * Reads, then shows the list, keeping the page when it is already open.
     *
     * @param viewer who reads; any thread
     * @param filter what to show
     */
    public static void open(@NotNull Player viewer, @NotNull Filter filter) {
        Plugin plugin = library;
        if (plugin == null) return;
        Logs.read(filter.player(), filter.plugin(), filter.action()).whenComplete((entries, failure) ->
                Tasks.of(plugin).runAtEntity(viewer, () -> {
                    if (failure != null) {
                        Debug.of(plugin).error("Staff logs could not be read.", failure);
                        Text.of(LibraryMessages.get().logs().unavailable()).send(viewer);
                        return;
                    }
                    draw(viewer, filter, entries);
                }));
    }

    /**
     * Tells the console the newest entries, since it cannot open a menu.
     *
     * @param sender who asked; any thread
     * @param header the panel's first line
     * @param filter what to show
     */
    public static void print(@NotNull CommandSender sender, @NotNull String header, @NotNull Filter filter) {
        Plugin plugin = library;
        Logs.read(filter.player(), filter.plugin(), filter.action()).whenComplete((entries, failure) -> {
            if (failure != null) {
                if (plugin != null) Debug.of(plugin).error("Staff logs could not be read.", failure);
                Text.of(LibraryMessages.get().logs().unavailable()).send(sender);
                return;
            }
            LibrarySettings.Logs settings = LibrarySettings.get().logs();
            Text.of(panel(header, filter, entries, settings == null ? 20 : settings.consoleRows())).send(sender);
        });
    }

    /** The console's listing: the newest lines, then how many more there are. */
    static String panel(String header, Filter filter, List<LogRow> entries, int most) {
        LibraryMessages.Logs text = LibraryMessages.get().logs();
        Values about = Values.of()
                .putText("subject", filter.name())
                .putText("plugin", filter.plugin() == null ? text.all() : filter.plugin())
                .putText("action", filter.action() == null ? text.all() : filter.action())
                .put("count", entries.size());
        StringBuilder panel = new StringBuilder(header);
        if (entries.isEmpty()) {
            return panel.append("\n").append(about.apply(text.empty())).toString();
        }
        panel.append("\n").append(about.apply(text.header()));
        int shown = Math.min(entries.size(), Math.max(1, most));
        for (LogRow entry : entries.subList(0, shown)) {
            panel.append("\n").append(Values.of()
                    .put("when", Formats.date(entry.createdAt()))
                    .putText("plugin", entry.plugin())
                    .putText("action", entry.action())
                    .putText("actor", entry.actorName())
                    .putText("target", orNone(entry.targetName()))
                    .putText("detail", orNone(entry.detail()))
                    .putText("server", orNone(entry.server()))
                    .apply(text.entry()));
        }
        if (entries.size() > shown) {
            panel.append("\n").append(Values.of().put("count", entries.size() - shown).apply(text.more()));
        }
        return panel.toString();
    }

    private static void draw(Player viewer, Filter filter, List<LogRow> entries) {
        PluginMenus current = menus;
        if (current == null || !viewer.isOnline()) return;
        List<UiEntry> rows = new ArrayList<>(entries.size());
        for (LogRow entry : entries) {
            UiEntry.Builder row = UiEntry.of(entry);
            values(entry, LINES).map().forEach((key, value) -> row.withFormatted(key, String.valueOf(value)));
            rows.add(row.with("log_id", entry.id()).build());
        }
        Map<String, Object> context = context(filter);
        context.put("logs_count", rows.size());
        if (current.update(viewer, LIST, context, rows)) return;
        current.open(viewer, LIST, context, () -> rows);
    }

    private static void openEntry(Player viewer, String id, Filter filter) {
        Plugin plugin = library;
        if (plugin == null || id.isBlank()) return;
        Logs.find(id).whenComplete((found, failure) -> Tasks.of(plugin).runAtEntity(viewer, () -> {
            PluginMenus current = menus;
            if (current == null || !viewer.isOnline()) return;
            if (failure != null || found.isEmpty()) {
                if (failure != null) Debug.of(plugin).error("A staff log entry could not be read.", failure);
                Text.of(LibraryMessages.get().logs().unavailable()).send(viewer);
                return;
            }
            LogRow entry = found.get();
            Map<String, Object> context = context(filter);
            context.putAll(values(entry, ENTRY_LINES).map());
            List<UiEntry> rows = rows(entry.groups());
            current.open(viewer, ENTRY, context, () -> rows);
        }));
    }

    /** The filters, as the session carries them and the screens draw them. */
    private static Map<String, Object> context(Filter filter) {
        LibraryMessages.Logs text = LibraryMessages.get().logs();
        return Values.of()
                .put(SUBJECT_KEY, filter.player() == null ? EVERYBODY : filter.player().toString())
                .put(NAME_KEY, filter.name())
                .put(PLUGIN_KEY, filter.plugin() == null ? "" : filter.plugin())
                .put(ACTION_KEY, filter.action() == null ? "" : filter.action())
                .putText("logs_subject", filter.name())
                .put("logs_plugin", filter.plugin() == null ? text.all() : Values.inert(filter.plugin()))
                .put("logs_action", filter.action() == null ? text.all() : action(filter.action()))
                .map();
    }

    /** The filters an open screen was drawn with. */
    static Filter filter(UiSession session) {
        String subject = session.context(SUBJECT_KEY, String.class).orElse(EVERYBODY);
        String name = session.context(NAME_KEY, String.class).orElse(LibraryMessages.get().logs().everybody());
        String plugin = session.context(PLUGIN_KEY, String.class).filter(value -> !value.isBlank()).orElse(null);
        String action = session.context(ACTION_KEY, String.class).filter(value -> !value.isBlank()).orElse(null);
        return new Filter(uuid(subject), name, plugin, action);
    }

    /** The one after {@code current} in {@code options}; {@code null} (all) after the last. */
    static @Nullable String next(List<String> options, @Nullable String current) {
        if (options.isEmpty()) return null;
        if (current == null) return options.get(0);
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).equalsIgnoreCase(current)) {
                return i + 1 < options.size() ? options.get(i + 1) : null;
            }
        }
        return options.get(0);
    }

    // ------------------------------------------------------------------ values

    /** An entry's placeholders, formatted and with every typed part made inert. */
    static Values values(LogRow entry, int lines) {
        LibraryMessages.Logs text = LibraryMessages.get().logs();
        return Values.of()
                .put("log_icon", Logs.icon(entry.plugin(), entry.action()))
                .put("log_plugin", Values.inert(orNone(entry.plugin())))
                .put("log_action", action(entry.action()))
                .put("log_actor", Values.inert(orNone(entry.actorName())))
                .put("log_target", Values.inert(orNone(entry.targetName())))
                .put("log_detail", detail(entry.detail(), lines))
                .put("log_server", Values.inert(orNone(entry.server())))
                .put("log_location", Values.inert(entry.world() == null ? text.none()
                        : entry.world() + " " + entry.x() + ", " + entry.y() + ", " + entry.z()))
                .put("log_when", Formats.relative(entry.createdAt()))
                .put("log_date", Formats.date(entry.createdAt()))
                .put("log_view", entry.hasItems() ? text.viewItems() : text.viewEntry());
    }

    /** {@code crate-keys-give} as a name reads it: {@code CRATE KEYS GIVE}. */
    public static String action(@Nullable String key) {
        return key == null ? "" : Values.inert(key.replace('-', ' ').replace('_', ' ').toUpperCase(Locale.ROOT));
    }

    /** A stored detail as lore, at most {@code max} lines, each drawn with the configured line. */
    public static String detail(@Nullable String detail, int max) {
        LibraryMessages.Logs text = LibraryMessages.get().logs();
        List<String> lines = layout(detail == null || detail.isBlank() ? text.none() : detail, WIDTH, max);
        List<String> drawn = new ArrayList<>(lines.size());
        for (String part : lines) drawn.add(text.detailLine().replace("%line%", Values.inert(part)));
        return String.join("<nl>", drawn);
    }

    /**
     * A detail cut into the lines it reads as: each {@code " | "} part on its
     * own, a list of several items one per line under the heading before its
     * colon, and every line wrapped with its continuation indented. Commas and
     * bars inside brackets, braces or quotes belong to an item, not the list.
     */
    static List<String> layout(String detail, int width, int max) {
        List<String> lines = new ArrayList<>();
        for (String segment : split(detail, " | ")) {
            List<String> parts = split(segment, ", ");
            int colon = parts.size() > 1 ? depthZero(parts.get(0), ": ", 0) : -1;
            String indent = "";
            if (colon >= 0) {
                lines.addAll(wrap(parts.get(0).substring(0, colon + 1), width, ""));
                parts.set(0, parts.get(0).substring(colon + 2));
                indent = INDENT;
            }
            for (String part : parts.size() > 1 ? parts : List.of(segment)) {
                for (String wrapped : wrap(part, width - indent.length(), INDENT)) lines.add(indent + wrapped);
            }
        }
        if (lines.size() > max) {
            List<String> cut = new ArrayList<>(lines.subList(0, max));
            cut.set(max - 1, cut.get(max - 1) + " …");
            return cut;
        }
        return lines;
    }

    /** {@code text} split at every {@code separator} outside brackets, braces and quotes. */
    static List<String> split(String text, String separator) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int at; (at = depthZero(text, separator, start)) >= 0; start = at + separator.length()) {
            parts.add(text.substring(start, at));
        }
        parts.add(text.substring(start));
        return parts;
    }

    /** The first {@code separator} from {@code from} on that is not inside brackets, braces or quotes; -1 if none. */
    private static int depthZero(String text, String separator, int from) {
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') quoted = !quoted;
            else if (!quoted && (c == '[' || c == '{')) depth++;
            else if (!quoted && (c == ']' || c == '}')) depth = Math.max(0, depth - 1);
            else if (i >= from && depth == 0 && !quoted && text.startsWith(separator, i)) return i;
        }
        return -1;
    }

    /** Greedy word wrap; lines after the first start with {@code indent}. */
    static List<String> wrap(String text, int width, String indent) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            if (!current.isEmpty() && current.length() + 1 + word.length() > width) {
                lines.add(current.toString());
                current.setLength(0);
                current.append(indent);
            }
            if (!current.isEmpty() && !current.toString().equals(indent)) current.append(' ');
            current.append(word);
        }
        if (!current.isEmpty()) lines.add(current.toString());
        return lines;
    }

    // ------------------------------------------------------------------ entry rows

    /** Each group from the start of a row: its label, its items, the rest of the row left blank. */
    static List<UiEntry> rows(List<Logs.Group> groups) {
        List<UiEntry> rows = new ArrayList<>();
        if (groups.isEmpty()) {
            pad(rows, CENTRE);
            rows.add(UiEntry.row().template("empty").build());
            return rows;
        }
        for (Logs.Group group : groups) {
            pad(rows, ceil(rows.size()));
            rows.add(UiEntry.row().template("group")
                    .with("group_label", group.label())
                    .with("group_count", group.items().size())
                    .build());
            if (group.items().isEmpty()) {
                rows.add(UiEntry.row().template("nothing").build());
            }
            for (ItemStack stack : group.items()) rows.add(UiEntry.row().item(stack).build());
        }
        pad(rows, ceil(rows.size()));
        return rows;
    }

    private static int ceil(int size) {
        return (size + ROW - 1) / ROW * ROW;
    }

    private static void pad(List<UiEntry> rows, int size) {
        while (rows.size() < size) rows.add(UiEntry.row().template("spacer").build());
    }

    private static String orNone(@Nullable String value) {
        return value == null || value.isBlank() ? LibraryMessages.get().logs().none() : value;
    }

    private static @Nullable UUID uuid(@Nullable String subject) {
        try {
            return subject == null || subject.equals(EVERYBODY) ? null : UUID.fromString(subject);
        } catch (IllegalArgumentException notAnId) {
            return null;
        }
    }
}
