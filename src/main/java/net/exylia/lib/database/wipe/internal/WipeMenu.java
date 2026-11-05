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
import net.exylia.lib.text.Lore;
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

    /** An action line in these cards' tone. */
    private static final String ACT = Lore.Tone.PRIMARY.colour() + "» ";

    /** The tone of the wipe screens' own cards. */
    private static final Lore.Tone TONE = Lore.Tone.PRIMARY;

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
        context.put("wipe_go", goName(request));
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
        context.put("wipe_go", goName(request));
        for (DataKind kind : ORDER) {
            String key = "wipe_k_" + kind.name().toLowerCase(Locale.ROOT);
            context.put(key + "_title", (Supplier<String>) () -> request.preset().allowed().contains(kind)
                    ? Lore.name(TONE, Phrases.tr("Kind"), WipePanels.kind(kind))
                    : "{letters}" + Phrases.tr("Kind") + " {letters_black}▸ {muted}" + WipePanels.kind(kind));
            context.put(key + "_state", (Supplier<String>) () -> !request.preset().allowed().contains(kind)
                    ? Phrases.tr("{muted}never in this wipe")
                    : request.kinds().contains(kind) ? Phrases.tr("{error}deleted") : Phrases.tr("{success}kept"));
            context.put(key + "_rows", (Supplier<String>) () -> WipePanels.rows(rowsOfKind(request, kind)));
            context.put(key + "_action", (Supplier<String>) () -> !request.preset().allowed().contains(kind)
                    ? "{muted}» " + Phrases.tr("Not part of this preset")
                    : request.kinds().contains(kind) ? ACT + Phrases.tr("Click to keep it")
                    : "{error}» " + Phrases.tr("Click to delete it too"));
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
            deleted.forEach((kind, count) -> lines.add("  {letters_black}· " + Phrases.tr("{letters}{0}{letters_black}: {1} {letters}rows",
                    WipePanels.kind(kind), WipePanels.rows(count))));
            if (lines.isEmpty()) lines.add(" " + Phrases.tr("{muted}nothing"));
            rows.add(UiEntry.of(new Card(plugin))
                    .with(PLUGIN_KEY, plugin)
                    .withFormatted("wipe_rows", String.join(Lines.NEWLINE, lines))
                    .withFormatted("wipe_kept", "  {letters_black}· "
                            + Phrases.tr("{letters}{0}{letters_black}: {info}{1}", Phrases.tr("Tables kept"), kept))
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
            yaml.set("filler.pagination.lore", List.of(
                    Phrases.tr("{letters_black}Only plugins with tables in scope are listed.")));
            yaml.set("pagination.slots", "0-35");
            arrow(yaml, "previous", 48, PREVIOUS_HEAD,
                    Lore.name(Lore.Tone.ERROR, Phrases.tr("Page"), "← " + Phrases.tr("Previous")), "previous_page");
            arrow(yaml, "next", 50, NEXT_HEAD,
                    Lore.name(Lore.Tone.SUCCESS, Phrases.tr("Page"), Phrases.tr("Next") + " →"), "next_page");
        }
        return yaml;
    }

    private static void arrow(YamlConfiguration yaml, String key, int slot, String head, String name, String action) {
        String path = "pagination.navigation." + key;
        yaml.set(path + ".slot", slot);
        yaml.set(path + ".sound", "minecraft:item.book.page_turn|1.0|1.0");
        yaml.set(path + ".material", head);
        yaml.set(path + ".name", name);
        yaml.set(path + ".lore", List.of(
                Phrases.tr("  {letters_black}· {letters}Page{letters_black}: {info}%current_page%{letters_black}/{info}%total_pages%")));
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

    private static void back(YamlConfiguration yaml, int slot, String parent, String target) {
        item(yaml, "back", slot, BACK_HEAD, Lore.name(Lore.Tone.ERROR, Phrases.tr("Back"), parent),
                List.of("{error}» " + Phrases.tr("Click to go back")),
                List.of("exylialib:wipe_back " + target));
        yaml.set("items.back.sound", "minecraft:item.bundle.remove_one|1.0|0.5");
    }

    /** The name of the button that runs the wipe. */
    static String goName(WipeRequest request) {
        return Lore.name(Lore.Tone.ERROR, Phrases.tr("Data wipe"),
                request.whole() ? Phrases.tr("Wipe now") : Phrases.tr("Reset player"));
    }

    private static void go(YamlConfiguration yaml, int slot) {
        item(yaml, "go", slot, "TNT", "%wipe_go%", Lore.of(Lore.Tone.ERROR).subtitle(Phrases.tr("Danger zone"))
                .section(Phrases.tr("Information"))
                .line(Phrases.tr(" {letters}Deletes what these screens list,"))
                .line(Phrases.tr(" {letters}after a {highlight}backup {letters}of every plugin."))
                .action(Phrases.tr("Click to confirm"))
                .note(Phrases.tr("You will be asked to confirm."))
                .lines(), List.of("exylialib:wipe_confirm"));
    }

    static YamlConfiguration home() {
        YamlConfiguration yaml = frame(Phrases.tr("{primary}&lDATA WIPE"), 27, false);
        item(yaml, "header", 4, "TNT", Lore.name(TONE, Phrases.tr("Data wipe")), Lore.of(TONE)
                .subtitle(Phrases.tr("Wipes and resets"))
                .section(Phrases.tr("Information"))
                .line(Phrases.tr(" {letters}Deletes data by {highlight}what it is{letters},"))
                .line(Phrases.tr(" {letters}never by table name."))
                .line(Phrases.tr(" {letters}A {highlight}backup {letters}is always taken first."))
                .section("✦", Phrases.tr("Advanced"))
                .line(Phrases.tr(" {muted}/exylialib wipe <plugin|all> <@kind|table>"))
                .line(Phrases.tr(" {muted}/exylialib resetplayer <player>"))
                .lines(), List.of());
        card(yaml, "season", 10, "NETHER_STAR", Phrases.tr("New season"),
                Phrases.tr(" {letters}Player progress, world state"),
                Phrases.tr(" {letters}and caches start over."));
        card(yaml, "players", 12, "PLAYER_HEAD", Phrases.tr("Player data only"),
                Phrases.tr(" {letters}Every player's progress goes;"),
                Phrases.tr(" {letters}claims, listings and builds stay."));
        card(yaml, "plugin", 14, "CHEST", Phrases.tr("One plugin"),
                Phrases.tr(" {letters}Every table of one plugin,"),
                Phrases.tr(" {letters}its setup included if you want."));
        card(yaml, "player", 16, "NAME_TAG", Phrases.tr("One player"),
                Phrases.tr(" {letters}One player's rows everywhere."),
                Phrases.tr(" {letters}No restart needed."));
        return yaml;
    }

    private static void card(YamlConfiguration yaml, String way, int slot, String material, String name,
                             String first, String second) {
        item(yaml, way, slot, material, Lore.name(Lore.Tone.WARNING, Phrases.tr("Wipe"), name),
                Lore.of(Lore.Tone.WARNING).subtitle(Phrases.tr("Way to wipe"))
                        .section(Phrases.tr("Information")).line(first).line(second)
                        .action(Phrases.tr("Click to review")).lines(),
                List.of("exylialib:wipe_start " + way));
    }

    static YamlConfiguration plugins() {
        YamlConfiguration yaml = frame(Phrases.tr("{primary}&lWIPE {letters_black}» {highlight}%wipe_scope% {muted}%current_page%/%total_pages%"),
                54, true);
        yaml.set("pagination.item_template.material", "ENDER_CHEST");
        yaml.set("pagination.item_template.name", Lore.name(TONE, Phrases.tr("Plugin"), "%wipe_plugin%"));
        yaml.set("pagination.item_template.lore", Lore.of(TONE).subtitle(Phrases.tr("Plugin in this wipe"))
                .section("✦", Phrases.tr("Deleted")).line("%wipe_rows%")
                .section("✦", Phrases.tr("Status")).line("%wipe_kept%")
                .click(Phrases.tr("Left-click"), Phrases.tr("leave it out"))
                .click(Phrases.tr("Right-click"), Phrases.tr("see its tables"))
                .lines());
        yaml.set("pagination.item_template.actions", List.of("left: exylialib:wipe_toggle %wipe_plugin%",
                "right: exylialib:wipe_open_tables %wipe_plugin%"));
        yaml.set("pagination.excluded_template.material", "GRAY_DYE");
        yaml.set("pagination.excluded_template.name", "{letters}" + Phrases.tr("Plugin") + " {letters_black}▸ {muted}%wipe_plugin%");
        yaml.set("pagination.excluded_template.lore", Lore.of(TONE).subtitle(Phrases.tr("Left out of this wipe"))
                .section(Phrases.tr("Information"))
                .line(Phrases.tr(" {letters}Left out: nothing of it is deleted."))
                .click(Phrases.tr("Left-click"), Phrases.tr("put it back"))
                .click(Phrases.tr("Right-click"), Phrases.tr("see its tables"))
                .lines());
        yaml.set("pagination.excluded_template.actions", List.of("left: exylialib:wipe_toggle %wipe_plugin%",
                "right: exylialib:wipe_open_tables %wipe_plugin%"));
        item(yaml, "kinds", 45, "HOPPER", Lore.name(TONE, Phrases.tr("Wipe"), Phrases.tr("What is deleted")),
                Lore.of(TONE).subtitle(Phrases.tr("Wipe setting"))
                        .section(Phrases.tr("Information"))
                        .line(Phrases.tr(" {letters}Choose the kinds of data that go."))
                        .section("✦", Phrases.tr("Status"))
                        .line(Phrases.tr("  {letters_black}· {error}{icon:hopper} {letters}Deleted{letters_black}: %wipe_kinds_list%"))
                        .line(Phrases.tr("  {letters_black}· {letters}Tables without a player{letters_black}: {info}%wipe_skipped%"))
                        .action(Phrases.tr("Click to choose"))
                        .lines(), List.of("exylialib:wipe_open_kinds"));
        item(yaml, "vanilla", 46, "GRASS_BLOCK", Lore.name(TONE, Phrases.tr("Wipe"), Phrases.tr("Vanilla player files")),
                Lore.of(TONE).subtitle(Phrases.tr("Wipe setting"))
                        .section(Phrases.tr("Information"))
                        .line(Phrases.tr(" {letters}Inventories, ender chests, stats and"))
                        .line(Phrases.tr(" {letters}advancements of the {highlight}main world{letters}."))
                        .section("✦", Phrases.tr("Status"))
                        .line(Phrases.tr("  {letters_black}· {info}{icon:redstone} {letters}State{letters_black}: %wipe_vanilla_state%"))
                        .action(Phrases.tr("Click to toggle"))
                        .lines(), List.of("exylialib:wipe_vanilla"));
        yaml.set("items.vanilla.condition", "%wipe_vanilla_allowed% == true");
        back(yaml, 49, Phrases.tr("Ways to wipe"), "home");
        go(yaml, 53);
        return yaml;
    }

    static YamlConfiguration tables() {
        YamlConfiguration yaml = frame(Phrases.tr("{primary}&lTABLES {letters_black}» {highlight}%wipe_plugin% {muted}%current_page%/%total_pages%"),
                54, true);
        yaml.set("pagination.item_template.material", "%wipe_icon%");
        yaml.set("pagination.item_template.name", Lore.name(Lore.Tone.INFO, Phrases.tr("Table"), "%wipe_table%"));
        yaml.set("pagination.item_template.lore", Lore.of(Lore.Tone.INFO).subtitle(Phrases.tr("Database table"))
                .section("✦", Phrases.tr("Status"))
                .line(Phrases.tr("  {letters_black}· {info}{icon:tag} {letters}Kind{letters_black}: %wipe_kind%"))
                .line(Phrases.tr("  {letters_black}· {info}{icon:iron} {letters}Rows{letters_black}: %wipe_count%"))
                .line(Phrases.tr("  {letters_black}· {info}{icon:key} {letters}Owner column{letters_black}: %wipe_owner%"))
                .line(Phrases.tr("  {letters_black}· {info}{icon:redstone} {letters}This wipe{letters_black}: %wipe_status%"))
                .lines());
        back(yaml, 49, Phrases.tr("Plugins"), "plugins");
        return yaml;
    }

    static YamlConfiguration pick() {
        YamlConfiguration yaml = frame(Phrases.tr("{primary}&lONE PLUGIN {muted}%current_page%/%total_pages%"), 54, true);
        yaml.set("pagination.item_template.material", "CHEST");
        yaml.set("pagination.item_template.name", Lore.name(Lore.Tone.INFO, Phrases.tr("Plugin"), "%wipe_plugin%"));
        yaml.set("pagination.item_template.lore", Lore.of(Lore.Tone.INFO).subtitle(Phrases.tr("Plugin with data"))
                .section("✦", Phrases.tr("Status"))
                .line(Phrases.tr("  {letters_black}· {info}{icon:book} {letters}Tables{letters_black}: {info}%wipe_table_count%"))
                .line(Phrases.tr("  {letters_black}· {info}{icon:iron} {letters}Rows{letters_black}: %wipe_count%"))
                .action(Phrases.tr("Click to choose"))
                .lines());
        yaml.set("pagination.item_template.actions", List.of("exylialib:wipe_pick %wipe_plugin%"));
        back(yaml, 49, Phrases.tr("Ways to wipe"), "home");
        return yaml;
    }

    static YamlConfiguration kinds() {
        YamlConfiguration yaml = frame(Phrases.tr("{primary}&lWHAT IS DELETED"), 27, false);
        item(yaml, "header", 4, "HOPPER", Lore.name(TONE, Phrases.tr("What is deleted")), Lore.of(TONE)
                .subtitle(Phrases.tr("Wipe setting"))
                .section(Phrases.tr("Information"))
                .line(Phrases.tr(" {letters}Every table says what it holds."))
                .line(Phrases.tr(" {letters}Pick the kinds that go."))
                .lines(), List.of());
        int slot = 10;
        for (DataKind kind : ORDER) {
            String key = "wipe_k_" + kind.name().toLowerCase(Locale.ROOT);
            Lore lore = Lore.of(TONE).subtitle(Phrases.tr("Kind of data")).section(Phrases.tr("Information"));
            description(kind).forEach(lore::line);
            lore.section("✦", Phrases.tr("Status"))
                    .stat("redstone", "info", Phrases.tr("State"), "%" + key + "_state%")
                    .stat("iron", "info", Phrases.tr("Rows"), "%" + key + "_rows%")
                    .line("")
                    .line("%" + key + "_action%");
            item(yaml, key, slot++, icon(kind), "%" + key + "_title%", lore.lines(),
                    List.of("exylialib:wipe_kind " + kind.name()));
        }
        back(yaml, 22, Phrases.tr("Previous screen"), "kinds_parent");
        go(yaml, 26);
        return yaml;
    }

    static List<String> description(DataKind kind) {
        return switch (kind) {
            case PROGRESS -> List.of(Phrases.tr(" {letters}What a player earns while playing:"),
                    Phrases.tr(" {letters}homes, balances, stats, ranks."));
            case WORLD -> List.of(Phrases.tr(" {letters}Shared state of this playthrough:"),
                    Phrases.tr(" {letters}claims, listings, graves, clans."));
            case CACHE -> List.of(Phrases.tr(" {letters}Rebuilt by itself: cooldowns,"),
                    Phrases.tr(" {letters}stat caches, queues."));
            case ACCOUNT -> List.of(Phrases.tr(" {letters}Survives a season: purchases,"),
                    Phrases.tr(" {letters}unlocks, settings, punishments."));
            case HISTORY -> List.of(Phrases.tr(" {letters}Logs and history: matches,"),
                    Phrases.tr(" {letters}ledgers, audits, replays."));
            case SETUP -> List.of(Phrases.tr(" {letters}Built by staff: warps, kits,"),
                    Phrases.tr(" {letters}arenas, crates, shop products."));
            case UNSPECIFIED -> List.of(Phrases.tr(" {letters}Tables nobody classified yet."),
                    Phrases.tr(" {letters}Only a whole-plugin wipe takes them."));
        };
    }
}
