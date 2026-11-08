package net.exylia.lib.kitroom.internal;

import net.exylia.lib.action.ActionResult;
import net.exylia.lib.action.Actions;
import net.exylia.lib.action.PluginActions;
import net.exylia.lib.kitroom.KitRoom;
import net.exylia.lib.kitroom.KitRoomCategory;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Lore;
import net.exylia.lib.text.Phrases;
import net.exylia.lib.text.Text;
import net.exylia.lib.ui.PluginMenus;
import net.exylia.lib.ui.UiEntry;
import net.exylia.lib.ui.UiKeys;
import net.exylia.lib.ui.UiSession;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The screen players take kit room items from: the selected category's items
 * in rows 0-3, its tabs in the bottom corners (the ranking layout), and any
 * click on an item hands a copy into the player's inventory.
 *
 * <p>Compiled into the library's one {@link PluginMenus}, in the server's
 * language. What the screen shows lives in one {@link State} in the menu
 * context, never in a per-player map.
 */
public final class KitRoomMenu {

    static final String ID = "kitroom";
    private static final String STATE = "kitroom_state";
    private static final String ITEMS = "items";
    private static final String TABS = "tabs";

    private static final String PREVIOUS_HEAD = "basehead-eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZGExZDU1YjNmOTg5NDEwYTM0NzUyNjUwZTI0OGM5YjZjMTc4M2E3ZWMyYWEzZmQ3Nzg3YmRjNGQwZTYzN2QzOSJ9fX0=";
    private static final String NEXT_HEAD = "basehead-eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZmE4N2UzZDk2ZTFjZmViOWNjZmIzYmE1M2EyMTdmYWY1MjQ5ZTI4NTUzM2IyNzFhMmZiMjg0YzMwZGJkOTgyOSJ9fX0=";

    private static volatile @Nullable Plugin library;
    private static volatile @Nullable PluginMenus menus;

    /** One open kit room: which room, which tabs it may show, which one it shows. */
    static final class State {
        final KitRoom room;
        final List<String> allowed;
        volatile String selected;

        State(KitRoom room, List<String> allowed, String selected) {
            this.room = room;
            this.allowed = allowed;
            this.selected = selected;
        }

        List<KitRoomCategory> tabs() {
            return room.categories(allowed);
        }

        @Nullable KitRoomCategory shown() {
            List<KitRoomCategory> tabs = tabs();
            return tabs.stream().filter(tab -> tab.id().equals(selected)).findFirst()
                    .orElse(tabs.isEmpty() ? null : tabs.getFirst());
        }
    }

    private KitRoomMenu() {
    }

    /** Registers the actions. Once, before the screen is loaded. */
    public static void init(@NotNull Plugin plugin, @NotNull String namespace) {
        library = plugin;
        PluginActions actions = Actions.of(plugin, namespace);
        actions.registerSync("kitroom_tab", (context, arguments) -> {
            UiSession session = context.require(UiKeys.SESSION);
            State state = session.context(STATE, State.class).orElse(null);
            if (state == null) return ActionResult.stop("not a kit room");
            state.selected = arguments.string(0, "");
            session.page(ITEMS, 0);
            session.entries(TABS, tabRows(state));
            session.entries(ITEMS, itemRows(state));
            return ActionResult.success();
        });
        actions.registerSync("kitroom_take", (context, arguments) -> {
            if (!(context.require(UiKeys.ENTRY) instanceof ItemStack item)) return ActionResult.stop("no item");
            Player player = context.player();
            if (!player.getInventory().addItem(item.clone()).isEmpty()) {
                Text.of(Phrases.tr("{error}✘ {letters}Your inventory is full.")).send(player);
                player.playSound(player, Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
                return ActionResult.success();
            }
            player.playSound(player, Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.4f);
            return ActionResult.success();
        });
    }

    /** Compiles the screen into the library's menus. */
    public static void load(@NotNull PluginMenus target) {
        target.load(ID, screen());
        menus = target;
    }

    public static void release() {
        menus = null;
        library = null;
    }

    /** Opens a room on its first allowed tab; any thread. */
    public static void open(@NotNull KitRoom room, @NotNull Player viewer, @NotNull List<String> allowed) {
        Plugin plugin = library;
        PluginMenus current = menus;
        if (plugin == null || current == null) return;
        State state = new State(room, allowed, "");
        Map<String, Object> context = new HashMap<>();
        context.put(STATE, state);
        context.put("kitroom_tab", (Supplier<String>) () -> {
            KitRoomCategory shown = state.shown();
            return shown == null ? Phrases.tr("Empty") : shown.name();
        });
        Tasks.of(plugin).runAtEntity(viewer, () -> {
            if (!viewer.isOnline()) return;
            current.definition(ID).ifPresent(definition -> current.openNow(viewer, definition, context,
                    Map.of(TABS, tabRows(state), ITEMS, itemRows(state))));
        });
    }

    static List<UiEntry> tabRows(State state) {
        KitRoomCategory shown = state.shown();
        return state.tabs().stream()
                .map(tab -> UiEntry.of(tab)
                        .with("kitroom_id", tab.id())
                        .with("kitroom_icon", tab.icon())
                        .with("kitroom_name", tab.name())
                        .with("kitroom_count", tab.items().length)
                        .template(shown != null && tab.id().equals(shown.id()) ? "selected" : "not_selected")
                        .build())
                .toList();
    }

    static List<UiEntry> itemRows(State state) {
        KitRoomCategory shown = state.shown();
        if (shown == null) return List.of();
        return shown.itemList().stream().map(item -> UiEntry.of(item).item(item.clone()).build()).toList();
    }

    static YamlConfiguration screen() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("title", Phrases.tr("{primary}&lKIT ROOM"));
        yaml.set("size", 54);
        yaml.set("type", "MULTI_PAGINATION");
        yaml.set("filler.global.material", "GRAY_STAINED_GLASS_PANE");
        yaml.set("filler.global.hide_tooltip", true);

        String items = "sections." + ITEMS;
        yaml.set(items + ".slots", "0-35");
        yaml.set(items + ".item_template.material", "STONE");
        yaml.set(items + ".item_template.actions", List.of("exylialib:kitroom_take"));
        yaml.set(items + ".filler.material", "LIGHT_GRAY_STAINED_GLASS_PANE");
        yaml.set(items + ".filler.hide_tooltip", false);
        yaml.set(items + ".filler.name", Phrases.tr("{muted}No more items"));
        yaml.set(items + ".filler.lore", List.of(Phrases.tr("{letters_black}Staff add them in the kit room editor.")));
        arrow(yaml, items + ".navigation.previous", 48, PREVIOUS_HEAD,
                Lore.name(Lore.Tone.ERROR, Phrases.tr("Page"), "← " + Phrases.tr("Previous")));
        arrow(yaml, items + ".navigation.next", 50, NEXT_HEAD,
                Lore.name(Lore.Tone.SUCCESS, Phrases.tr("Page"), Phrases.tr("Next") + " →"));

        String tabs = "sections." + TABS;
        // ponytail: six tabs and no paging of their own; page them if a room ever needs more.
        yaml.set(tabs + ".slots", "45-47,51-53");
        yaml.set(tabs + ".filler.material", "GRAY_STAINED_GLASS_PANE");
        yaml.set(tabs + ".filler.hide_tooltip", true);
        yaml.set(tabs + ".not_selected_template.material", "%kitroom_icon%");
        yaml.set(tabs + ".not_selected_template.name",
                Lore.name(Lore.Tone.INFO, Phrases.tr("Category"), "%kitroom_name%"));
        yaml.set(tabs + ".not_selected_template.lore", Lore.of(Lore.Tone.INFO)
                .subtitle(Phrases.tr("Kit room category"))
                .section("✦", Phrases.tr("Status"))
                .stat("hopper", "info", Phrases.tr("Items"), "%kitroom_count%")
                .action(Phrases.tr("Click to open"))
                .note(Phrases.tr("Shows its items above."))
                .lines());
        yaml.set(tabs + ".not_selected_template.actions", List.of("exylialib:kitroom_tab %kitroom_id%"));
        yaml.set(tabs + ".selected_template.material", "%kitroom_icon%");
        yaml.set(tabs + ".selected_template.glowing", true);
        yaml.set(tabs + ".selected_template.name",
                Lore.name(Lore.Tone.SUCCESS, Phrases.tr("Category"), "%kitroom_name%"));
        yaml.set(tabs + ".selected_template.lore", Lore.of(Lore.Tone.SUCCESS)
                .subtitle(Phrases.tr("Kit room category · shown"))
                .section("✦", Phrases.tr("Status"))
                .stat("hopper", "info", Phrases.tr("Items"), "%kitroom_count%")
                .lines());

        yaml.set("items.header.slot", 49);
        yaml.set("items.header.material", "ENDER_CHEST");
        yaml.set("items.header.name", Lore.name(Lore.Tone.PRIMARY, Phrases.tr("Kit room"), "%kitroom_tab%"));
        yaml.set("items.header.lore", Lore.of(Lore.Tone.PRIMARY)
                .subtitle(Phrases.tr("Build your own kit"))
                .section(Phrases.tr("Information"))
                .line(Phrases.tr(" {letters}Click an item to take a copy;"))
                .line(Phrases.tr(" {letters}it lands in your inventory."))
                .line(Phrases.tr(" {letters}The tabs below switch category."))
                .lines());
        return yaml;
    }

    private static void arrow(YamlConfiguration yaml, String path, int slot, String head, String name) {
        yaml.set(path + ".slot", slot);
        yaml.set(path + ".sound", "minecraft:item.book.page_turn|1.0|1.0");
        yaml.set(path + ".material", head);
        yaml.set(path + ".name", name);
    }
}
