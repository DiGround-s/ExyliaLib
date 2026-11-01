package net.exylia.lib.database.wipe.internal;

import net.exylia.lib.action.ActionResult;
import net.exylia.lib.action.Actions;
import net.exylia.lib.action.PluginActions;
import net.exylia.lib.database.DataKind;
import net.exylia.lib.input.Inputs;
import net.exylia.lib.input.PluginInputs;
import net.exylia.lib.player.ExyliaPlayers;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Lines;
import net.exylia.lib.text.Phrases;
import net.exylia.lib.text.Text;
import net.exylia.lib.ui.PluginMenus;
import net.exylia.lib.ui.UiEntry;
import net.exylia.lib.ui.UiKeys;
import net.exylia.lib.ui.UiSession;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The screens behind {@code /exylialib wipe}: pick a way in, review what goes
 * plugin by plugin and kind by kind, confirm.
 *
 * <p>The choices live in one {@link WipeRequest} carried in the menu context
 * from screen to screen, never in a per-player map. Counts arrive on a
 * background thread and the screens read them through lambdas, so a card says
 * "counting…" until its numbers are in.
 *
 * <p>Compiled into the library's one {@link PluginMenus}, beside the updates
 * screens, in the server's language.
 */
public final class WipeMenu {

    static final String HOME = "wipe";
    static final String PLUGINS = "wipe_plugins";
    static final String TABLES = "wipe_tables";
    static final String KINDS = "wipe_kinds";
    static final String PICK = "wipe_pick";

    private static final String REQUEST_KEY = "wipe_request";
    private static final String PLUGIN_KEY = "wipe_plugin";

    /** The order the kinds are drawn in, slots 10 to 16. */
    static final List<DataKind> ORDER = List.of(DataKind.PROGRESS, DataKind.WORLD, DataKind.CACHE,
            DataKind.ACCOUNT, DataKind.HISTORY, DataKind.SETUP, DataKind.UNSPECIFIED);

    private static final String BACK_HEAD = "basehead-eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMjIzZmI2NzQyOTcxNmIyMWJjNmU4ZTdkNjY5Y2VkZGY2NWIxM2UwNzkwYTVjZTU1YjJlMDc3YjgyZDE5ZTEyNCJ9fX0=";
    private static final String PREVIOUS_HEAD = "basehead-eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZGExZDU1YjNmOTg5NDEwYTM0NzUyNjUwZTI0OGM5YjZjMTc4M2E3ZWMyYWEzZmQ3Nzg3YmRjNGQwZTYzN2QzOSJ9fX0=";
    private static final String NEXT_HEAD = "basehead-eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZmE4N2UzZDk2ZTFjZmViOWNjZmIzYmE1M2EyMTdmYWY1MjQ5ZTI4NTUzM2IyNzFhMmZiMjg0YzMwZGJkOTgyOSJ9fX0=";

    private static volatile @Nullable Plugin library;
    private static volatile @Nullable PluginMenus menus;
    private static volatile Supplier<Path> dumps = () -> Path.of("dumps");

    /** A plugin on the picker or the plugins screen. */
    record Card(String plugin) {
    }

    private WipeMenu() {
    }

    // ---------------------------------------------------------------- lifecycle

    /**
     * Registers the actions. Once, before the screens are loaded.
     *
     * @param plugin    the library
     * @param namespace the library's menu and action namespace
     */
    public static void init(@NotNull Plugin plugin, @NotNull String namespace) {
        library = plugin;
        dumps = () -> plugin.getDataFolder().toPath().resolve("dumps");
        PluginActions actions = Actions.of(plugin, namespace);
        String permission = WipeRuntime.PERMISSION;
        actions.registerSync("wipe_start", permission, (context, arguments) -> {
            start(context.player(), arguments.string(0, ""));
            return ActionResult.success();
        });
        actions.registerSync("wipe_toggle", permission, (context, arguments) -> {
            request(context.require(UiKeys.SESSION)).toggle(arguments.string(0, ""));
            return ActionResult.success();
        });
        actions.registerSync("wipe_open_tables", permission, (context, arguments) -> {
            UiSession session = context.require(UiKeys.SESSION);
            showTables(context.player(), request(session), arguments.string(0, ""));
            return ActionResult.success();
        });
        actions.registerSync("wipe_open_kinds", permission, (context, arguments) -> {
            showKinds(context.player(), request(context.require(UiKeys.SESSION)));
            return ActionResult.success();
        });
        actions.registerSync("wipe_kind", permission, (context, arguments) -> {
            WipeRequest request = request(context.require(UiKeys.SESSION));
            try {
                request.toggle(DataKind.valueOf(arguments.string(0, "").toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException unknown) {
                return ActionResult.stop("unknown kind");
            }
            return ActionResult.success();
        });
        actions.registerSync("wipe_vanilla", permission, (context, arguments) -> {
            WipeRequest request = request(context.require(UiKeys.SESSION));
            request.vanilla(!request.vanilla());
            return ActionResult.success();
        });
        actions.registerSync("wipe_pick", permission, (context, arguments) -> {
            WipeRequest catalog = request(context.require(UiKeys.SESSION));
            WipeRequest picked = new WipeRequest(WipeRequest.Preset.PLUGIN, null, null, null,
                    arguments.string(0, ""), null);
            picked.adopt(catalog);
            // Counted again: the picker may still be counting, and its answers
            // land on the catalog request, not this one.
            WipeRuntime.plan(picked);
            showKinds(context.player(), picked);
            return ActionResult.success();
        });
        actions.registerSync("wipe_back", permission, (context, arguments) -> {
            Player viewer = context.player();
            WipeRequest request = context.get(UiKeys.SESSION).flatMap(s -> s.context(REQUEST_KEY, WipeRequest.class))
                    .orElse(null);
            String to = arguments.string(0, "home");
            if (request == null || to.equals("home")) open(viewer);
            else if (to.equals("kinds_parent") && request.preset() == WipeRequest.Preset.PLUGIN) showPick(viewer, request);
            else showPlugins(viewer, request);
            return ActionResult.success();
        });
        actions.registerSync("wipe_confirm", permission, (context, arguments) -> {
            confirm(context.player(), request(context.require(UiKeys.SESSION)));
            return ActionResult.success();
        });
    }

    /** Compiles the screens into the library's menus. */
    public static void load(@NotNull PluginMenus target) {
        target.load(HOME, home());
        target.load(PLUGINS, plugins());
        target.load(TABLES, tables());
        target.load(KINDS, kinds());
        target.load(PICK, pick());
        menus = target;
    }

    /** Forgets the screens, when the library disables. */
    public static void release() {
        menus = null;
        library = null;
    }

    // ---------------------------------------------------------------- opening

    /**
     * Opens the first screen: the four ways in.
     *
     * @param viewer who wipes; any thread
     */
    public static void open(@NotNull Player viewer) {
        show(viewer, HOME, new HashMap<>(), null);
    }

    /**
     * Opens a player reset, planned and ready to review.
     *
     * @param viewer who resets; any thread
     * @param target whose rows
     * @param name   what to call them
     * @param only   one plugin's tables, or {@code null}
     */
    public static void openPlayerReset(@NotNull Player viewer, @NotNull java.util.UUID target, @NotNull String name,
                                       @Nullable String only) {
        if (!viewer.hasPermission(WipeRuntime.PERMISSION)) {
            Text.of(Phrases.tr("{error}✘ {letters}You need {0} to reset a player.", WipeRuntime.PERMISSION))
                    .send(viewer);
            return;
        }
        WipeRequest request = new WipeRequest(WipeRequest.Preset.PLAYER, null, target, name, only, null);
        WipeRuntime.plan(request);
        showPlugins(viewer, request);
    }

    private static void start(Player viewer, String way) {
        switch (way) {
            case "season", "players" -> {
                WipeRequest request = new WipeRequest(way.equals("season")
                        ? WipeRequest.Preset.SEASON : WipeRequest.Preset.PLAYERS, null, null, null, null, null);
                WipeRuntime.plan(request);
                showPlugins(viewer, request);
            }
            case "plugin" -> {
                WipeRequest catalog = new WipeRequest(WipeRequest.Preset.PLUGIN, null, null, null, null, null);
                WipeRuntime.plan(catalog);
                showPick(viewer, catalog);
            }
            case "player" -> askPlayer(viewer);
            default -> { }
        }
    }

    private static void askPlayer(Player viewer) {
        Plugin plugin = library;
        if (plugin == null) return;
        viewer.closeInventory();
        PluginInputs inputs = Inputs.of(plugin);
        inputs.answered(viewer, inputs.text(viewer, Phrases.tr("{primary}&lWHICH PLAYER?")).maxLength(36).open(),
                name -> ExyliaPlayers.then(viewer, name.trim(),
                        target -> openPlayerReset(viewer, target.id(), target.name(), null)),
                () -> open(viewer));
    }

    private static void confirm(Player viewer, WipeRequest request) {
        Plugin plugin = library;
        if (plugin == null) return;
        if (request.tables() == null) {
            Text.of(Phrases.tr("{warning}✘ {letters}Still reading the tables. Try again in a moment.")).send(viewer);
            return;
        }
        viewer.closeInventory();
        PluginInputs inputs = Inputs.of(plugin);
        Runnable back = () -> showPlugins(viewer, request);
        if (!request.whole()) {
            inputs.answered(viewer, inputs.confirm(viewer, Phrases.tr("{error}Reset {0}? There is no undo.",
                    request.playerName())).dangerous().open(), yes -> {
                if (yes) run(viewer, request);
                else back.run();
            }, back);
            return;
        }
        inputs.answered(viewer, inputs.text(viewer, Phrases.tr("{error}&lTYPE WIPE TO CONFIRM")).maxLength(16).open(),
                typed -> {
                    if (typed.trim().equals("WIPE")) {
                        run(viewer, request);
                    } else {
                        Text.of(Phrases.tr("{error}✘ {letters}Not confirmed: nothing was deleted.")).send(viewer);
                        back.run();
                    }
                }, back);
    }

    private static void run(Player viewer, WipeRequest request) {
        Text.of(Phrases.tr("{secondary}➥ {letters}Backing up, then deleting. This can take a while.")).send(viewer);
        WipeRuntime.run(request, viewer, dumps.get(), outcome ->
                Text.of(WipePanels.result(request, outcome)).send(viewer));
    }

    // ---------------------------------------------------------------- screens

    private static void showPlugins(Player viewer, WipeRequest request) {
        Map<String, Object> context = base(request);
        context.put("wipe_scope", request.whole() ? WipePanels.preset(request.preset()) : request.playerName());
        context.put("wipe_go", request.whole() ? Phrases.tr("{error}&lWIPE") : Phrases.tr("{error}&lRESET PLAYER"));
        context.put("wipe_vanilla_allowed", String.valueOf(request.preset().vanilla()));
        context.put("wipe_vanilla_state", (Supplier<String>) () -> request.vanilla()
                ? Phrases.tr("{success}included") : Phrases.tr("{muted}kept"));
        context.put("wipe_kinds_list", (Supplier<String>) () -> kindList(request));
        context.put("wipe_skipped", (Supplier<String>) () -> String.valueOf(WipePanels.skipped(request)));
        show(viewer, PLUGINS, context, () -> pluginRows(request));
    }

    private static void showTables(Player viewer, WipeRequest request, String plugin) {
        Map<String, Object> context = base(request);
        context.put(PLUGIN_KEY, plugin);
        show(viewer, TABLES, context, () -> tableRows(request, plugin));
    }

    private static void showPick(Player viewer, WipeRequest catalog) {
        show(viewer, PICK, base(catalog), () -> pickRows(catalog));
    }

    private static void showKinds(Player viewer, WipeRequest request) {
        Map<String, Object> context = base(request);
        context.put("wipe_go", request.whole() ? Phrases.tr("{error}&lWIPE") : Phrases.tr("{error}&lRESET PLAYER"));
        for (DataKind kind : ORDER) {
            String key = "wipe_k_" + kind.name().toLowerCase(Locale.ROOT);
            context.put(key + "_title", (Supplier<String>) () -> (request.preset().allowed().contains(kind)
                    ? "{primary}&l" : "{muted}&l") + WipePanels.kind(kind).toUpperCase(Locale.ROOT));
            context.put(key + "_state", (Supplier<String>) () -> !request.preset().allowed().contains(kind)
                    ? Phrases.tr("{muted}never in this wipe")
                    : request.kinds().contains(kind) ? Phrases.tr("{error}deleted") : Phrases.tr("{success}kept"));
            context.put(key + "_rows", (Supplier<String>) () -> WipePanels.rows(rowsOfKind(request, kind)));
            context.put(key + "_action", (Supplier<String>) () -> !request.preset().allowed().contains(kind)
                    ? Phrases.tr("{muted}➥ Not part of this preset")
                    : request.kinds().contains(kind) ? Phrases.tr("{warning}➥ Click to keep it")
                    : Phrases.tr("{warning}➥ Click to delete it too"));
        }
        show(viewer, KINDS, context, null);
    }

    private static Map<String, Object> base(WipeRequest request) {
        Map<String, Object> context = new HashMap<>();
        context.put(REQUEST_KEY, request);
        context.put("wipe_empty", (Supplier<String>) () -> request.tables() == null
                ? Phrases.tr("{muted}Reading every plugin's tables…") : Phrases.tr("{muted}No more plugins"));
        return context;
    }

    private static void show(Player viewer, String id, Map<String, Object> context,
                             @Nullable Supplier<List<UiEntry>> rows) {
        Plugin plugin = library;
        PluginMenus current = menus;
        if (plugin == null || current == null) return;
        Tasks.of(plugin).runAtEntity(viewer, () -> {
            if (!viewer.isOnline()) return;
            if (rows == null) current.open(viewer, id, context);
            else current.open(viewer, id, context, rows);
        });
    }

    private static WipeRequest request(UiSession session) {
        return session.context(REQUEST_KEY, WipeRequest.class)
                .orElseThrow(() -> new IllegalStateException("A wipe screen without its request"));
    }

    // ---------------------------------------------------------------- rows

    static List<UiEntry> pluginRows(WipeRequest request) {
        List<UiEntry> rows = new ArrayList<>();
        request.byPlugin().forEach((plugin, tables) -> {
            Map<DataKind, Long> deleted = request.rowsByKind(tables);
            long kept = tables.stream().filter(table -> !request.includes(table)).count();
            List<String> lines = new ArrayList<>();
            for (String line : WipePanels.kindLines(deleted)) lines.add(" {letters_black}▎ " + line);
            if (lines.isEmpty()) lines.add(" {letters_black}▎ " + Phrases.tr("{muted}nothing"));
            rows.add(UiEntry.of(new Card(plugin))
                    .with(PLUGIN_KEY, plugin)
                    .withFormatted("wipe_rows", String.join(Lines.NEWLINE, lines))
                    .withFormatted("wipe_kept", " {letters_black}▎ "
                            + Phrases.tr("{letters}{0} {letters_black}» {info}{1}", Phrases.tr("Tables kept"), kept))
                    .template(request.excluded(plugin) ? "excluded" : null)
                    .build());
        });
        return rows;
    }

    static List<UiEntry> tableRows(WipeRequest request, String plugin) {
        List<UiEntry> rows = new ArrayList<>();
        List<WipeTable> tables = request.byPlugin().getOrDefault(plugin, List.of());
        for (WipeTable table : tables) {
            boolean goes = request.includes(table);
            rows.add(UiEntry.of(table)
                    .with("wipe_table", table.table())
                    .with("wipe_icon", icon(table.kind()))
                    .withFormatted("wipe_kind", "{info}" + WipePanels.kind(table.kind()))
                    .withFormatted("wipe_count", WipePanels.rows(request.count(table)))
                    .withFormatted("wipe_owner", table.owner() == null ? Phrases.tr("{muted}none") : "{info}" + table.owner())
                    .withFormatted("wipe_status", goes ? Phrases.tr("{error}deleted") : Phrases.tr("{success}kept"))
                    .build());
        }
        return rows;
    }

    static List<UiEntry> pickRows(WipeRequest catalog) {
        List<UiEntry> rows = new ArrayList<>();
        catalog.byPlugin().forEach((plugin, tables) -> {
            long total = 0;
            boolean counting = false;
            for (WipeTable table : tables) {
                long counted = catalog.count(table);
                if (counted < 0) counting = true;
                else total += counted;
            }
            rows.add(UiEntry.of(new Card(plugin))
                    .with(PLUGIN_KEY, plugin)
                    .with("wipe_table_count", tables.size())
                    .withFormatted("wipe_count", WipePanels.rows(counting ? -1L : total))
                    .build());
        });
        return rows;
    }

    /** Rows a kind holds across the plugins still in, whether or not it is on. */
    static long rowsOfKind(WipeRequest request, DataKind kind) {
        long total = 0;
        for (Map.Entry<String, List<WipeTable>> entry : request.byPlugin().entrySet()) {
            if (request.excluded(entry.getKey())) continue;
            for (WipeTable table : entry.getValue()) {
                if (table.kind() != kind) continue;
                long counted = request.count(table);
                if (counted < 0) return -1L;
                total += counted;
            }
        }
        return request.tables() == null ? -1L : total;
    }

    private static String kindList(WipeRequest request) {
        List<String> names = request.kinds().stream().map(WipePanels::kind).toList();
        return names.isEmpty() ? Phrases.tr("{muted}nothing") : "{info}" + String.join("{letters_black}, {info}", names);
    }

    static String icon(DataKind kind) {
        return switch (kind) {
            case PROGRESS -> "EXPERIENCE_BOTTLE";
            case WORLD -> "GRASS_BLOCK";
            case CACHE -> "REDSTONE";
            case ACCOUNT -> "EMERALD";
            case HISTORY -> "WRITABLE_BOOK";
            case SETUP -> "CRAFTING_TABLE";
            case UNSPECIFIED -> "STRUCTURE_VOID";
        };
    }

    // ---------------------------------------------------------------- definitions

    // Built in code and in the server's language, like the updates screens: a
    // reload in another language rebuilds them, and a quote in a translation
    // can never break YAML.

    private static YamlConfiguration frame(String title, int size, boolean paged) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("title", title);
        yaml.set("size", size);
        yaml.set("type", paged ? "PAGINATION" : "SIMPLE");
        yaml.set("filler.global.material", "GRAY_STAINED_GLASS_PANE");
        yaml.set("filler.global.hide_tooltip", true);
        if (paged) {
            yaml.set("filler.pagination.material", "LIGHT_GRAY_STAINED_GLASS_PANE");
            yaml.set("filler.pagination.hide_tooltip", false);
            yaml.set("filler.pagination.name", "%wipe_empty%");
            yaml.set("filler.pagination.lore", List.of("",
                    Phrases.tr(" {letters_black}▎ {letters}Only plugins with tables in scope are listed."), ""));
            yaml.set("pagination.slots", "0-35");
            arrow(yaml, "previous", 48, PREVIOUS_HEAD, Phrases.tr("{error}&l← PREVIOUS PAGE"), "previous_page");
            arrow(yaml, "next", 50, NEXT_HEAD, Phrases.tr("{success}&lNEXT PAGE →"), "next_page");
        }
        return yaml;
    }

    private static void arrow(YamlConfiguration yaml, String key, int slot, String head, String name, String action) {
        String path = "pagination.navigation." + key;
        yaml.set(path + ".slot", slot);
        yaml.set(path + ".sound", "minecraft:item.book.page_turn|1.0|1.0");
        yaml.set(path + ".material", head);
        yaml.set(path + ".name", name);
        yaml.set(path + ".lore", List.of("",
                Phrases.tr(" {letters_black}▎ {letters}Page {info}%current_page%{letters_black}/{info}%total_pages%"), ""));
        yaml.set(path + ".actions", List.of(action));
    }

    private static void item(YamlConfiguration yaml, String key, int slot, String material, String name,
                             List<String> lore, List<String> actions) {
        String path = "items." + key;
        yaml.set(path + ".slot", slot);
        yaml.set(path + ".material", material);
        yaml.set(path + ".name", name);
        yaml.set(path + ".lore", lore);
        if (!actions.isEmpty()) yaml.set(path + ".actions", actions);
    }

    private static void back(YamlConfiguration yaml, int slot, String where, String target) {
        item(yaml, "back", slot, BACK_HEAD, Phrases.tr("{error}&lBACK"),
                List.of("", " {letters_black}▎ {letters}" + where, "", Phrases.tr("{warning}➥ Click to go back"), ""),
                List.of("exylialib:wipe_back " + target));
        yaml.set("items.back.sound", "minecraft:item.bundle.remove_one|1.0|0.5");
    }

    private static void go(YamlConfiguration yaml, int slot) {
        item(yaml, "go", slot, "TNT", "%wipe_go%", List.of("",
                Phrases.tr("{secondary}Information:"),
                Phrases.tr(" {letters_black}▎ {letters}Deletes what these screens list,"),
                Phrases.tr(" {letters_black}▎ {letters}after a {highlight}backup {letters}of every plugin."),
                "",
                Phrases.tr("{letters_black}You will be asked to confirm."),
                "",
                Phrases.tr("{error}➥ Click to confirm"),
                ""), List.of("exylialib:wipe_confirm"));
    }

    static YamlConfiguration home() {
        YamlConfiguration yaml = frame(Phrases.tr("{primary}&lDATA WIPE"), 27, false);
        item(yaml, "header", 4, "TNT", Phrases.tr("{primary}&lDATA WIPE"), List.of("",
                Phrases.tr("{secondary}Information:"),
                Phrases.tr(" {letters_black}▎ {letters}Deletes data by {highlight}what it is{letters},"),
                Phrases.tr(" {letters_black}▎ {letters}never by table name."),
                Phrases.tr(" {letters_black}▎ {letters}A {highlight}backup {letters}is always taken first."),
                "",
                Phrases.tr("{secondary}Advanced:"),
                Phrases.tr(" {letters_black}▎ {muted}/exylialib wipe <plugin|all> <@kind|table>"),
                Phrases.tr(" {letters_black}▎ {muted}/exylialib resetplayer <player>"),
                ""), List.of());
        card(yaml, "season", 10, "NETHER_STAR", Phrases.tr("{primary}&lNEW SEASON"),
                Phrases.tr(" {letters_black}▎ {letters}Player progress, world state"),
                Phrases.tr(" {letters_black}▎ {letters}and caches start over."));
        card(yaml, "players", 12, "PLAYER_HEAD", Phrases.tr("{primary}&lPLAYER DATA ONLY"),
                Phrases.tr(" {letters_black}▎ {letters}Every player's progress goes;"),
                Phrases.tr(" {letters_black}▎ {letters}claims, listings and builds stay."));
        card(yaml, "plugin", 14, "CHEST", Phrases.tr("{primary}&lONE PLUGIN"),
                Phrases.tr(" {letters_black}▎ {letters}Every table of one plugin,"),
                Phrases.tr(" {letters_black}▎ {letters}its setup included if you want."));
        card(yaml, "player", 16, "NAME_TAG", Phrases.tr("{primary}&lONE PLAYER"),
                Phrases.tr(" {letters_black}▎ {letters}One player's rows everywhere."),
                Phrases.tr(" {letters_black}▎ {letters}No restart needed."));
        return yaml;
    }

    private static void card(YamlConfiguration yaml, String way, int slot, String material, String name,
                             String first, String second) {
        item(yaml, way, slot, material, name, List.of("", Phrases.tr("{secondary}Information:"), first, second, "",
                Phrases.tr("{warning}➥ Click to review"), ""), List.of("exylialib:wipe_start " + way));
    }

    static YamlConfiguration plugins() {
        YamlConfiguration yaml = frame(Phrases.tr("{primary}&lWIPE {letters_black}» {highlight}%wipe_scope% {muted}%current_page%/%total_pages%"),
                54, true);
        yaml.set("pagination.item_template.material", "ENDER_CHEST");
        yaml.set("pagination.item_template.name", "{primary}&l%wipe_plugin%");
        yaml.set("pagination.item_template.lore", List.of("",
                Phrases.tr("{secondary}Deleted:"), "%wipe_rows%", "",
                Phrases.tr("{secondary}Status:"), "%wipe_kept%", "",
                Phrases.tr("{warning}➥ Left-click {letters_black}» {letters}leave it out"),
                Phrases.tr("{warning}➥ Right-click {letters_black}» {letters}see its tables"), ""));
        yaml.set("pagination.item_template.actions", List.of("left: exylialib:wipe_toggle %wipe_plugin%",
                "right: exylialib:wipe_open_tables %wipe_plugin%"));
        yaml.set("pagination.excluded_template.material", "GRAY_DYE");
        yaml.set("pagination.excluded_template.name", "{muted}&l%wipe_plugin%");
        yaml.set("pagination.excluded_template.lore", List.of("",
                Phrases.tr(" {letters_black}▎ {letters}Left out: nothing of it is deleted."), "",
                Phrases.tr("{warning}➥ Left-click {letters_black}» {letters}put it back"),
                Phrases.tr("{warning}➥ Right-click {letters_black}» {letters}see its tables"), ""));
        yaml.set("pagination.excluded_template.actions", List.of("left: exylialib:wipe_toggle %wipe_plugin%",
                "right: exylialib:wipe_open_tables %wipe_plugin%"));
        item(yaml, "kinds", 45, "HOPPER", Phrases.tr("{primary}&lWHAT IS DELETED"), List.of("",
                Phrases.tr("{secondary}Information:"),
                Phrases.tr(" {letters_black}▎ {letters}Choose the kinds of data that go."),
                "",
                Phrases.tr("{secondary}Status:"),
                Phrases.tr(" {letters_black}▎ {letters}Deleted {letters_black}» %wipe_kinds_list%"),
                Phrases.tr(" {letters_black}▎ {letters}Tables without a player {letters_black}» {info}%wipe_skipped%"),
                "",
                Phrases.tr("{warning}➥ Click to choose"),
                ""), List.of("exylialib:wipe_open_kinds"));
        item(yaml, "vanilla", 46, "GRASS_BLOCK", Phrases.tr("{primary}&lVANILLA PLAYER FILES"), List.of("",
                Phrases.tr("{secondary}Information:"),
                Phrases.tr(" {letters_black}▎ {letters}Inventories, ender chests, stats and"),
                Phrases.tr(" {letters_black}▎ {letters}advancements of the {highlight}main world{letters}."),
                "",
                Phrases.tr("{secondary}Status:"),
                Phrases.tr(" {letters_black}▎ {letters}State {letters_black}» %wipe_vanilla_state%"),
                "",
                Phrases.tr("{warning}➥ Click to toggle"),
                ""), List.of("exylialib:wipe_vanilla"));
        yaml.set("items.vanilla.condition", "%wipe_vanilla_allowed% == true");
        back(yaml, 49, Phrases.tr("Returns to the ways to wipe."), "home");
        go(yaml, 53);
        return yaml;
    }

    static YamlConfiguration tables() {
        YamlConfiguration yaml = frame(Phrases.tr("{primary}&lTABLES {letters_black}» {highlight}%wipe_plugin% {muted}%current_page%/%total_pages%"),
                54, true);
        yaml.set("pagination.item_template.material", "%wipe_icon%");
        yaml.set("pagination.item_template.name", "{primary}&l%wipe_table%");
        yaml.set("pagination.item_template.lore", List.of("",
                Phrases.tr("{secondary}Information:"),
                Phrases.tr(" {letters_black}▎ {letters}Kind {letters_black}» %wipe_kind%"),
                Phrases.tr(" {letters_black}▎ {letters}Rows {letters_black}» %wipe_count%"),
                Phrases.tr(" {letters_black}▎ {letters}Owner column {letters_black}» %wipe_owner%"),
                "",
                Phrases.tr("{secondary}Status:"),
                Phrases.tr(" {letters_black}▎ {letters}This wipe {letters_black}» %wipe_status%"),
                ""));
        back(yaml, 49, Phrases.tr("Returns to the plugins."), "plugins");
        return yaml;
    }

    static YamlConfiguration pick() {
        YamlConfiguration yaml = frame(Phrases.tr("{primary}&lONE PLUGIN {muted}%current_page%/%total_pages%"), 54, true);
        yaml.set("pagination.item_template.material", "CHEST");
        yaml.set("pagination.item_template.name", "{primary}&l%wipe_plugin%");
        yaml.set("pagination.item_template.lore", List.of("",
                Phrases.tr("{secondary}Information:"),
                Phrases.tr(" {letters_black}▎ {letters}Tables {letters_black}» {info}%wipe_table_count%"),
                Phrases.tr(" {letters_black}▎ {letters}Rows {letters_black}» %wipe_count%"),
                "",
                Phrases.tr("{warning}➥ Click to choose"),
                ""));
        yaml.set("pagination.item_template.actions", List.of("exylialib:wipe_pick %wipe_plugin%"));
        back(yaml, 49, Phrases.tr("Returns to the ways to wipe."), "home");
        return yaml;
    }

    static YamlConfiguration kinds() {
        YamlConfiguration yaml = frame(Phrases.tr("{primary}&lWHAT IS DELETED"), 27, false);
        item(yaml, "header", 4, "HOPPER", Phrases.tr("{primary}&lWHAT IS DELETED"), List.of("",
                Phrases.tr("{secondary}Information:"),
                Phrases.tr(" {letters_black}▎ {letters}Every table says what it holds."),
                Phrases.tr(" {letters_black}▎ {letters}Pick the kinds that go."),
                ""), List.of());
        int slot = 10;
        for (DataKind kind : ORDER) {
            String key = "wipe_k_" + kind.name().toLowerCase(Locale.ROOT);
            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add(Phrases.tr("{secondary}Information:"));
            lore.addAll(description(kind));
            lore.add("");
            lore.add(Phrases.tr("{secondary}Status:"));
            lore.add(" {letters_black}▎ {letters}" + Phrases.tr("State") + " {letters_black}» %" + key + "_state%");
            lore.add(" {letters_black}▎ {letters}" + Phrases.tr("Rows") + " {letters_black}» %" + key + "_rows%");
            lore.add("");
            lore.add("%" + key + "_action%");
            lore.add("");
            item(yaml, key, slot++, icon(kind), "%" + key + "_title%", lore,
                    List.of("exylialib:wipe_kind " + kind.name()));
        }
        back(yaml, 22, Phrases.tr("Returns to the previous screen."), "kinds_parent");
        go(yaml, 26);
        return yaml;
    }

    static List<String> description(DataKind kind) {
        return switch (kind) {
            case PROGRESS -> List.of(Phrases.tr(" {letters_black}▎ {letters}What a player earns while playing:"),
                    Phrases.tr(" {letters_black}▎ {letters}homes, balances, stats, ranks."));
            case WORLD -> List.of(Phrases.tr(" {letters_black}▎ {letters}Shared state of this playthrough:"),
                    Phrases.tr(" {letters_black}▎ {letters}claims, listings, graves, clans."));
            case CACHE -> List.of(Phrases.tr(" {letters_black}▎ {letters}Rebuilt by itself: cooldowns,"),
                    Phrases.tr(" {letters_black}▎ {letters}stat caches, queues."));
            case ACCOUNT -> List.of(Phrases.tr(" {letters_black}▎ {letters}Survives a season: purchases,"),
                    Phrases.tr(" {letters_black}▎ {letters}unlocks, settings, punishments."));
            case HISTORY -> List.of(Phrases.tr(" {letters_black}▎ {letters}Logs and history: matches,"),
                    Phrases.tr(" {letters_black}▎ {letters}ledgers, audits, replays."));
            case SETUP -> List.of(Phrases.tr(" {letters_black}▎ {letters}Built by staff: warps, kits,"),
                    Phrases.tr(" {letters_black}▎ {letters}arenas, crates, shop products."));
            case UNSPECIFIED -> List.of(Phrases.tr(" {letters_black}▎ {letters}Tables nobody classified yet."),
                    Phrases.tr(" {letters_black}▎ {letters}Only a whole-plugin wipe takes them."));
        };
    }
}
