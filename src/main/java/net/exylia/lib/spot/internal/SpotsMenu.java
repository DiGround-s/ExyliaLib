package net.exylia.lib.spot.internal;

import net.exylia.lib.action.ActionResult;
import net.exylia.lib.action.Actions;
import net.exylia.lib.action.PluginActions;
import net.exylia.lib.input.Inputs;
import net.exylia.lib.input.PluginInputs;
import net.exylia.lib.spot.ClickSpots;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Lore;
import net.exylia.lib.text.Phrases;
import net.exylia.lib.text.Text;
import net.exylia.lib.ui.PluginMenus;
import net.exylia.lib.ui.UiEntry;
import net.exylia.lib.ui.UiKeys;
import net.exylia.lib.util.teleport.Teleports;
import net.exylia.lib.util.wizard.WizardTarget;
import net.exylia.lib.util.wizard.Wizards;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The admin screens of {@link ClickSpots}: the list, the kind picker and one
 * spot's detail, compiled into the library's {@link PluginMenus} in the
 * server's language, like the wipe screens.
 */
public final class SpotsMenu {

    static final String LIST = "spots";
    static final String PICK = "spots_pick";
    static final String DETAIL = "spots_detail";

    private static final String OWNER_KEY = "spot_owner";
    private static final String ID_KEY = "spot_id";
    private static final Lore.Tone TONE = Lore.Tone.PRIMARY;

    private static final String BACK_HEAD = "basehead-eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMjIzZmI2NzQyOTcxNmIyMWJjNmU4ZTdkNjY5Y2VkZGY2NWIxM2UwNzkwYTVjZTU1YjJlMDc3YjgyZDE5ZTEyNCJ9fX0=";
    private static final String PREVIOUS_HEAD = "basehead-eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZGExZDU1YjNmOTg5NDEwYTM0NzUyNjUwZTI0OGM5YjZjMTc4M2E3ZWMyYWEzZmQ3Nzg3YmRjNGQwZTYzN2QzOSJ9fX0=";
    private static final String NEXT_HEAD = "basehead-eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZmE4N2UzZDk2ZTFjZmViOWNjZmIzYmE1M2EyMTdmYWY1MjQ5ZTI4NTUzM2IyNzFhMmZiMjg0YzMwZGJkOTgyOSJ9fX0=";

    private static volatile @Nullable Plugin library;
    private static volatile @Nullable PluginMenus menus;
    /** Where each viewer's list goes back to: a runnable cannot ride in an action line. */
    private static final Map<UUID, Runnable> BACKS = new ConcurrentHashMap<>();

    private SpotsMenu() {
    }

    public static void init(@NotNull Plugin plugin, @NotNull String namespace) {
        library = plugin;
        PluginActions actions = Actions.of(plugin, namespace);
        action(actions, "spots_open", (viewer, spots, args) -> detail(viewer, spots, args.get(1)));
        action(actions, "spots_new", (viewer, spots, args) -> pick(viewer, spots, null));
        action(actions, "spots_kind", (viewer, spots, args) -> pick(viewer, spots, args.get(1)));
        action(actions, "spots_pick", (viewer, spots, args) -> {
            String kind = args.get(1);
            String changing = args.size() > 2 ? args.get(2) : "-";
            if (changing.equals("-")) {
                create(viewer, spots, kind, null);
            } else {
                spots.kind(changing, kind);
                detail(viewer, spots, changing);
            }
        });
        action(actions, "spots_move", (viewer, spots, args) -> move(viewer, spots, args.get(1)));
        action(actions, "spots_hologram", (viewer, spots, args) -> spots.get(args.get(1)).ifPresent(spot -> {
            spots.hologram(spot.id(), !spot.hologram());
            detail(viewer, spots, spot.id());
        }));
        action(actions, "spots_tp", (viewer, spots, args) -> spots.get(args.get(1)).ifPresent(spot -> {
            Location at = spot.location();
            if (at == null) return;
            viewer.closeInventory();
            Location stand = at.clone().add(0.5, spot.isBlock() ? 1 : 0, 0.5);
            stand.setYaw(viewer.getLocation().getYaw());
            Teleports.of(spots.plugin()).to(viewer, stand).warmup(0).start();
        }));
        action(actions, "spots_remove", (viewer, spots, args) -> remove(viewer, spots, args.get(1)));
        action(actions, "spots_back", (viewer, spots, args) -> {
            String to = args.get(1);
            if (to.equals("-")) {
                Runnable back = BACKS.get(viewer.getUniqueId());
                if (back != null) back.run();
                else viewer.closeInventory();
            } else if (to.equals("list")) {
                list(viewer, spots, BACKS.get(viewer.getUniqueId()));
            } else {
                detail(viewer, spots, to);
            }
        });
    }

    public static void load(@NotNull PluginMenus target) {
        target.load(LIST, listScreen());
        target.load(PICK, pickScreen());
        target.load(DETAIL, detailScreen());
        menus = target;
    }

    public static void release() {
        menus = null;
        library = null;
        BACKS.clear();
    }

    @FunctionalInterface
    private interface Handler {
        void run(Player viewer, ClickSpots spots, List<String> args);
    }

    /** Every action names its owner plugin first; the owner's own permission guards it. */
    private static void action(PluginActions actions, String id, Handler handler) {
        actions.registerSync(id, (context, arguments) -> {
            Player viewer = context.player();
            List<String> args = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                String value = arguments.string(i, "");
                if (value.isEmpty()) break;
                args.add(value);
            }
            if (args.isEmpty()) return ActionResult.stop("no owner");
            Optional<ClickSpots> spots = ClickSpots.of(args.get(0));
            if (spots.isEmpty()) return ActionResult.stop("unknown owner");
            if (!viewer.hasPermission(spots.get().permission())) return ActionResult.stop("no permission");
            while (args.size() < 2) args.add("-");
            handler.run(viewer, spots.get(), args);
            return ActionResult.success();
        });
    }

    // ---------------------------------------------------------------- flows

    public static void list(@NotNull Player viewer, @NotNull ClickSpots spots, @Nullable Runnable back) {
        if (back != null) BACKS.put(viewer.getUniqueId(), back);
        else BACKS.remove(viewer.getUniqueId());
        Map<String, Object> context = base(spots);
        context.put("spot_has_back", String.valueOf(back != null));
        show(viewer, LIST, context, () -> rows(spots));
    }

    public static void detail(@NotNull Player viewer, @NotNull ClickSpots spots, @NotNull String id) {
        Optional<ClickSpots.Spot> found = spots.get(id);
        if (found.isEmpty()) {
            Text.of(Phrases.tr("{error}✘ {letters}There is no table {highlight}{0}{letters}.", id)).send(viewer);
            return;
        }
        ClickSpots.Spot spot = found.get();
        Map<String, Object> context = base(spots);
        context.put(ID_KEY, id);
        ClickSpots.Kind kind = spots.kindOf(spot);
        context.put("spot_icon", kind == null ? "BARRIER" : kind.icon());
        context.put("spot_kind", kind == null ? "{error}" + spot.kind() : kind.name());
        context.put("spot_where", where(spot));
        context.put("spot_anchor", anchor(spot));
        context.put("spot_players", (Supplier<String>) () -> String.valueOf(spots.hooks().playersAt(spot)));
        context.put("spot_hologram", spot.hologram()
                ? "{success}{icon:on} " + Phrases.tr("On") : "{error}{icon:off} " + Phrases.tr("Off"));
        show(viewer, DETAIL, context, null);
    }

    /** The kind picker; {@code changing} is the spot whose kind it changes, or {@code null} for a new one. */
    static void pick(Player viewer, ClickSpots spots, @Nullable String changing) {
        Map<String, Object> context = base(spots);
        context.put("spot_changing", changing == null ? "-" : changing);
        context.put("spot_pick_back", changing == null ? "list" : changing);
        List<UiEntry> rows = new ArrayList<>();
        for (ClickSpots.Kind kind : spots.hooks().kinds()) {
            rows.add(UiEntry.of(kind).with("spot_kind_id", kind.id()).with("spot_icon", kind.icon())
                    .withFormatted("spot_kind", kind.name()).build());
        }
        show(viewer, PICK, context, () -> rows);
    }

    public static void create(@NotNull Player viewer, @NotNull ClickSpots spots, @NotNull String kind,
                              @Nullable String id) {
        place(viewer, spots, target -> {
            Optional<ClickSpots.Spot> taken = occupant(spots, target, null);
            if (taken.isPresent()) {
                // One spot per block, entity or NPC: a second would steal the first one's click.
                Text.of(Phrases.tr(TAKEN, taken.get().id())).send(viewer);
                detail(viewer, spots, taken.get().id());
                return;
            }
            ClickSpots.Spot spot = spots.create(kind, target, id);
            Text.of(Phrases.tr("{success}✔ {letters}Table {highlight}{0} {letters}placed. Players click it to play.",
                    spot.id())).send(viewer);
            detail(viewer, spots, spot.id());
        }, () -> list(viewer, spots, BACKS.get(viewer.getUniqueId())));
    }

    static void move(Player viewer, ClickSpots spots, String id) {
        place(viewer, spots, target -> {
            Optional<ClickSpots.Spot> taken = occupant(spots, target, id);
            if (taken.isPresent()) Text.of(Phrases.tr(TAKEN, taken.get().id())).send(viewer);
            else spots.move(id, target);
            detail(viewer, spots, id);
        }, () -> detail(viewer, spots, id));
    }

    private static final String TAKEN = "{warning}! {letters}Table {highlight}{0} {letters}is already there.";

    /** The spot already on what was clicked, other than {@code except}. */
    static Optional<ClickSpots.Spot> occupant(ClickSpots spots, WizardTarget target, @Nullable String except) {
        Location at = target.location();
        String world = at.getWorld() == null ? "" : at.getWorld().getName();
        return spots.all().stream().filter(spot -> !spot.id().equals(except)).filter(spot -> target.npc() != null
                ? target.npc().equals(spot.npc())
                : target.entity() != null ? target.entity().equals(spot.entity())
                : spot.isBlock() && spot.world().equals(world) && spot.x() == at.getBlockX()
                        && spot.y() == at.getBlockY() && spot.z() == at.getBlockZ()).findFirst();
    }

    private static void place(Player viewer, ClickSpots spots, Consumer<WizardTarget> placed, Runnable cancelled) {
        viewer.closeInventory();
        Wizards.of(spots.plugin()).askTarget(viewer, Phrases.tr("{primary}&lPLACE TABLE"),
                Phrases.tr("{letters}Click the {highlight}block {letters}or the {highlight}NPC {letters}where the table goes."),
                placed, cancelled);
    }

    public static void remove(@NotNull Player viewer, @NotNull ClickSpots spots, @NotNull String id) {
        Plugin plugin = library;
        if (plugin == null || spots.get(id).isEmpty()) return;
        viewer.closeInventory();
        PluginInputs inputs = Inputs.of(plugin);
        Runnable back = () -> detail(viewer, spots, id);
        inputs.answered(viewer, inputs.confirm(viewer, Phrases.tr("{error}Remove the table {0}?", id)).dangerous()
                .open(), yes -> {
            if (!yes) {
                back.run();
                return;
            }
            spots.remove(id);
            Text.of(Phrases.tr("{success}✔ {letters}Table {highlight}{0} {letters}removed.", id)).send(viewer);
            list(viewer, spots, BACKS.get(viewer.getUniqueId()));
        }, back);
    }

    // ---------------------------------------------------------------- data

    private static Map<String, Object> base(ClickSpots spots) {
        Map<String, Object> context = new HashMap<>();
        context.put(OWNER_KEY, spots.plugin().getName());
        return context;
    }

    static List<UiEntry> rows(ClickSpots spots) {
        List<UiEntry> rows = new ArrayList<>();
        for (ClickSpots.Spot spot : spots.all()) {
            ClickSpots.Kind kind = spots.kindOf(spot);
            rows.add(UiEntry.of(spot)
                    .with(ID_KEY, spot.id())
                    .with("spot_icon", kind == null ? "BARRIER" : kind.icon())
                    .withFormatted("spot_kind", kind == null ? "{error}" + spot.kind() : kind.name())
                    .withFormatted("spot_where", where(spot))
                    .withFormatted("spot_anchor", anchor(spot))
                    .withFormatted("spot_players", (Supplier<String>) () -> String.valueOf(spots.hooks().playersAt(spot)))
                    .build());
        }
        return rows;
    }

    static String where(ClickSpots.Spot spot) {
        return spot.world() + " " + spot.x() + ", " + spot.y() + ", " + spot.z();
    }

    static String anchor(ClickSpots.Spot spot) {
        return switch (spot.anchor()) {
            case "npc" -> Phrases.tr("NPC");
            case "entity" -> Phrases.tr("Entity");
            default -> Phrases.tr("Block");
        };
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

    // ---------------------------------------------------------------- definitions

    private static YamlConfiguration frame(String title, int size, boolean paged, String empty, String hint) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("title", title);
        yaml.set("size", size);
        yaml.set("type", paged ? "PAGINATION" : "SIMPLE");
        yaml.set("filler.global.material", "GRAY_STAINED_GLASS_PANE");
        yaml.set("filler.global.hide_tooltip", true);
        if (paged) {
            yaml.set("filler.pagination.material", "LIGHT_GRAY_STAINED_GLASS_PANE");
            yaml.set("filler.pagination.hide_tooltip", false);
            yaml.set("filler.pagination.name", empty);
            yaml.set("filler.pagination.lore", List.of(hint));
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
                List.of("{error}{icon:click} " + Phrases.tr("Click to go back")),
                List.of("exylialib:spots_back %spot_owner% " + target));
        yaml.set("items.back.sound", "minecraft:item.bundle.remove_one|1.0|0.5");
    }

    private static Lore status(Lore lore) {
        return lore.section("✦", Phrases.tr("Status"))
                .stat("compass", "info", Phrases.tr("Where"), "%spot_where%")
                .stat("tag", "info", Phrases.tr("Placed on"), "%spot_anchor%")
                .stat("heart", "success", Phrases.tr("Playing now"), "%spot_players%");
    }

    static YamlConfiguration listScreen() {
        YamlConfiguration yaml = frame(Phrases.tr("{primary}&lTABLES {letters_black}» {highlight}%spot_owner% {muted}%current_page%/%total_pages%"),
                54, true, Phrases.tr("{muted}No more tables"),
                Phrases.tr("{letters_black}Place one with the button below."));
        yaml.set("pagination.item_template.material", "%spot_icon%");
        yaml.set("pagination.item_template.name", Lore.name(Lore.Tone.INFO, Phrases.tr("Table"), "%spot_kind%"));
        yaml.set("pagination.item_template.lore", status(Lore.of(Lore.Tone.INFO)
                .subtitle(Phrases.tr("World table · %spot_id%")))
                .action(Phrases.tr("Click to edit"))
                .note(Phrases.tr("Move, change game, hologram, remove."))
                .lines());
        yaml.set("pagination.item_template.actions", List.of("exylialib:spots_open %spot_owner% %spot_id%"));
        item(yaml, "create", 45, "LIME_DYE", Lore.name(Lore.Tone.SUCCESS, Phrases.tr("Table"), Phrases.tr("New table")),
                Lore.of(Lore.Tone.SUCCESS).subtitle(Phrases.tr("World table"))
                        .section(Phrases.tr("Information"))
                        .text(Phrases.tr("Pick the game, then click the"))
                        .text(Phrases.tr("block or NPC where it goes."))
                        .action(Phrases.tr("Click to place one"))
                        .note(Phrases.tr("Players click it to open the game."))
                        .lines(), List.of("exylialib:spots_new %spot_owner%"));
        back(yaml, 49, Phrases.tr("Admin"), "-");
        yaml.set("items.back.condition", "%spot_has_back% == true");
        return yaml;
    }

    static YamlConfiguration pickScreen() {
        YamlConfiguration yaml = frame(Phrases.tr("{primary}&lTABLE {letters_black}» {highlight}Pick a game"),
                54, true, Phrases.tr("{muted}No more games"),
                Phrases.tr("{letters_black}Every game of this plugin is here."));
        yaml.set("pagination.item_template.material", "%spot_icon%");
        yaml.set("pagination.item_template.name", Lore.name(Lore.Tone.INFO, Phrases.tr("Game"), "%spot_kind%"));
        yaml.set("pagination.item_template.lore", Lore.of(Lore.Tone.INFO).subtitle(Phrases.tr("Table game"))
                .action(Phrases.tr("Click to choose"))
                .note(Phrases.tr("Then click where the table goes."))
                .lines());
        yaml.set("pagination.item_template.actions",
                List.of("exylialib:spots_pick %spot_owner% %spot_kind_id% %spot_changing%"));
        back(yaml, 49, Phrases.tr("Tables"), "%spot_pick_back%");
        return yaml;
    }

    static YamlConfiguration detailScreen() {
        YamlConfiguration yaml = frame(Phrases.tr("{primary}&lTABLE {letters_black}» {highlight}%spot_id%"), 27, false,
                "", "");
        String args = " %spot_owner% %spot_id%";
        item(yaml, "header", 4, "%spot_icon%", Lore.name(TONE, Phrases.tr("Table"), "%spot_kind%"),
                status(Lore.of(TONE).subtitle(Phrases.tr("World table"))
                        .section(Phrases.tr("Information"))
                        .text(Phrases.tr("A click here opens the game.")))
                        .lines(), List.of());
        item(yaml, "move", 12, "COMPASS", Lore.name(TONE, Phrases.tr("Table"), Phrases.tr("Move")),
                Lore.of(TONE).subtitle(Phrases.tr("Table setting"))
                        .section(Phrases.tr("Information"))
                        .text(Phrases.tr("Click another block or NPC"))
                        .text(Phrases.tr("and the table goes there."))
                        .action(Phrases.tr("Click to move"))
                        .lines(), List.of("exylialib:spots_move" + args));
        item(yaml, "kind", 13, "%spot_icon%", Lore.name(TONE, Phrases.tr("Table"), Phrases.tr("Game")),
                Lore.of(TONE).subtitle(Phrases.tr("Table setting"))
                        .section("✦", Phrases.tr("Status"))
                        .stat("star", "info", Phrases.tr("Game"), "%spot_kind%")
                        .action(Phrases.tr("Click to change"))
                        .lines(), List.of("exylialib:spots_kind" + args));
        item(yaml, "hologram", 14, "NAME_TAG", Lore.name(TONE, Phrases.tr("Table"), Phrases.tr("Hologram")),
                Lore.of(TONE).subtitle(Phrases.tr("Table setting"))
                        .section(Phrases.tr("Information"))
                        .text(Phrases.tr("The game's name and who plays,"))
                        .text(Phrases.tr("floating over the table."))
                        .section("✦", Phrases.tr("Status"))
                        .line("  {letters_black}· {letters}" + Phrases.tr("State") + "{letters_black}: %spot_hologram%")
                        .action(Phrases.tr("Click to toggle"))
                        .lines(), List.of("exylialib:spots_hologram" + args));
        item(yaml, "teleport", 18, "ENDER_PEARL", Lore.name(TONE, Phrases.tr("Table"), Phrases.tr("Teleport")),
                Lore.of(TONE).subtitle(Phrases.tr("Table action"))
                        .action(Phrases.tr("Click to go there"))
                        .lines(), List.of("exylialib:spots_tp" + args));
        item(yaml, "remove", 26, "BARRIER", Lore.name(Lore.Tone.ERROR, Phrases.tr("Table"), Phrases.tr("Remove")),
                Lore.of(Lore.Tone.ERROR).subtitle(Phrases.tr("Danger zone"))
                        .section(Phrases.tr("Information"))
                        .text(Phrases.tr("Takes the table out of the world."))
                        .text(Phrases.tr("Games playing now are not stopped."))
                        .action(Phrases.tr("Click to remove"))
                        .note(Phrases.tr("You will be asked to confirm."))
                        .lines(), List.of("exylialib:spots_remove" + args));
        back(yaml, 22, Phrases.tr("Tables"), "list");
        return yaml;
    }
}
