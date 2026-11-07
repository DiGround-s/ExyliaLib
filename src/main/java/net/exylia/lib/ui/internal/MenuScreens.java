package net.exylia.lib.ui.internal;

import net.exylia.lib.screen.Node;
import net.exylia.lib.screen.Screen;
import net.exylia.lib.screen.ScreenEvent;
import net.exylia.lib.screen.ScreenSession;
import net.exylia.lib.screen.ScreenSettings;
import net.exylia.lib.screen.Screens;
import net.exylia.lib.screen.internal.ScreenRoutes;
import net.exylia.lib.ui.ClickKind;
import net.exylia.lib.ui.MenuStyles;
import net.exylia.lib.ui.UiDefinition;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chest menus shown on screens (MenuStyle SCREEN): the menu runs as always but its window is never opened, and
 * either the screen its id is routed to ({@code <screen routes>}) or the chest skin draws it. The skin lays the
 * slots out as the chest does, one card a slot with the item's name and lore as its tooltip, and hands every
 * click to the chest menu, so actions, pages and redraws all work as in the window.
 *
 * <p>Internal: free to change without notice.
 */
final class MenuScreens {

    /** One player's screen and the chest menu behind it. */
    private static final class Shown {
        final Plugin plugin;
        final @Nullable ScreenRoutes.Route route;
        volatile Session menu;
        volatile ScreenSession screen;

        Shown(Plugin plugin, @Nullable ScreenRoutes.Route route, Session menu) {
            this.plugin = plugin;
            this.route = route;
            this.menu = menu;
        }
    }

    private static final Map<UUID, Shown> SHOWN = new ConcurrentHashMap<>();

    private static final String PANEL = "#17131f";
    private static final String SLOT = "#221b2e";
    private static final String SLOT_HOVER = "#33294a";
    private static final String EMPTY = "#1b1525";

    private MenuScreens() {
    }

    /** Whether this open should be drawn on a screen rather than in a window. */
    static boolean wanted(Player viewer, UiDefinition definition, MenuRuntime runtime) {
        // A window the player puts items into, or one that is not a chest, keeps its window.
        if (definition.kind().type() != null || !definition.inputSlots().isEmpty()) return false;
        if (!MenuStyles.of(runtime.plugin()).screens(viewer)) return false;
        return ScreenRoutes.find(runtime.plugin(), definition.id()) != null || ScreenSettings.get().chestSkin();
    }

    /**
     * On the viewer's thread: shows a menu already marked headless, reusing the screen on show when it is the
     * same one (another menu of the skin, another route of the same screen).
     *
     * @return whether a screen shows it; {@code false} when screens cannot open, and the caller opens the window
     */
    static boolean show(Session menu) {
        Player viewer = menu.viewer();
        Plugin plugin = menu.runtime().plugin();
        ScreenRoutes.Route route = ScreenRoutes.find(plugin, menu.menuId());
        Shown now = SHOWN.get(viewer.getUniqueId());
        if (now != null && now.screen != null && now.screen.open() && now.plugin == plugin
                && (now.route == null ? route == null : route != null && now.route.markup() == route.markup())) {
            now.menu = menu;
            ScreenSession screen = now.screen;
            menu.headless(screen::redraw, route != null);
            if (route != null) {
                route.state(menu.contextValues()).forEach(screen::set);
                screen.set(ScreenRoutes.MENU, menu);
            }
            screen.redraw();
            return true;
        }
        Shown shown = new Shown(plugin, route, menu);
        Screen screen;
        if (route == null) {
            screen = Screen.of(session -> skin(shown.menu));
        } else {
            Map<String, Object> state = new LinkedHashMap<>(route.state(menu.contextValues()));
            state.put(ScreenRoutes.MENU, menu);
            screen = route.markup().screen(state);
        }
        screen.onClose(session -> closed(shown));
        // Shift goes back to the menu this one was opened from, as the window's back button would.
        screen.onBack(session -> back(shown));
        SHOWN.put(viewer.getUniqueId(), shown);
        ScreenSession opened = Screens.of(plugin).open(viewer, screen);
        if (opened == null) {
            SHOWN.remove(viewer.getUniqueId(), shown);
            return false;
        }
        shown.screen = opened;
        menu.headless(opened::redraw, route != null);
        return true;
    }

    /**
     * Shift on the screen: back to the menu opened before this one. A routed screen goes back only through menus
     * the player really came from; the skin also follows the file's parent, as the back button does.
     */
    private static boolean back(Shown shown) {
        Session menu = shown.menu;
        Player viewer = menu.viewer();
        if (shown.route != null && !menu.runtime().hasHistory(viewer)) return false;
        return menu.runtime().back(viewer);
    }

    /** Closes the screen showing {@code menu}; its close closes the menu. */
    static void close(Session menu) {
        Shown shown = SHOWN.get(menu.viewer().getUniqueId());
        if (shown != null && shown.menu == menu && shown.screen != null) {
            shown.screen.close();
        } else if (menu.runtime().sessionOf(menu.viewer()) == menu) {
            menu.runtime().closed(menu);
            MenuListener.runClose(menu, menu.viewer());
        }
    }

    /** Takes the screen away without closing a menu: one opening in a window replaces it. */
    static void drop(Player viewer) {
        Shown shown = SHOWN.remove(viewer.getUniqueId());
        if (shown != null && shown.screen != null) shown.screen.close();
    }

    /** The screen closed (Shift, damage, a teleport, quit): so does the menu it showed, unless another replaced it. */
    private static void closed(Shown shown) {
        Session menu = shown.menu;
        Player viewer = menu.viewer();
        SHOWN.remove(viewer.getUniqueId(), shown);
        if (menu.runtime().sessionOf(viewer) == menu) {
            menu.runtime().closed(menu);
            if (viewer.isOnline()) MenuListener.runClose(menu, viewer);
        }
    }

    // ------------------------------------------------------------------
    // The skin
    // ------------------------------------------------------------------

    /** The chest as cards: its title, then its rows of nine slots. */
    private static Node skin(Session menu) {
        Inventory inventory = menu.inventory();
        int rows = Math.max(1, inventory.getSize() / 9);
        double cell = Math.min(30, Math.floor(176.0 / rows) - 2);
        Node grid = Node.column().gap(2);
        for (int r = 0; r < rows; r++) {
            Node row = Node.row().gap(2);
            for (int c = 0; c < 9; c++) row.add(slot(menu, r * 9 + c, inventory.getItem(r * 9 + c), cell));
            grid.add(row);
        }
        Node panel = Node.column(Node.text(menu.currentTitle()), grid)
                .padding(8, 10).gap(6).background(PANEL).outline("{primary}", 1);
        return Node.column(panel).align(Node.Align.CENTER).justify(Node.Justify.CENTER);
    }

    private static Node slot(Session menu, int slot, @Nullable ItemStack item, double cell) {
        boolean blank = item == null || item.getType().isAir();
        // Glass panes frame a chest: drawn as the empty part of the card, keeping a tooltip they show.
        if (blank || item.getType().name().endsWith("GLASS_PANE")) {
            Node empty = Node.box().size(cell, cell).background(EMPTY);
            if (!blank) empty.tooltip(lines(item));
            return empty.id("s" + slot);
        }
        Node card = Node.item(item).size(cell, cell).padding(cell * 0.16).background(SLOT).hover(SLOT_HOVER)
                .tooltip(lines(item)).id("s" + slot);
        return card.onClick(event -> click(menu, slot, event));
    }

    /** The item's name and lore, as its tooltip in a chest would show them; nothing when it hides its tooltip. */
    private static @Nullable List<Component> lines(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return List.of(Component.translatable(item.translationKey()));
        if (meta.isHideTooltip()) return null;
        List<Component> out = new ArrayList<>();
        out.add(meta.hasDisplayName() && meta.displayName() != null ? meta.displayName()
                : Component.translatable(item.translationKey()));
        List<Component> lore = meta.lore();
        if (lore != null) out.addAll(lore);
        return out;
    }

    /** A click on a card, handed to the chest menu as the same click on that slot. */
    private static void click(Session menu, int slot, ScreenEvent event) {
        if (!menu.isOpen()) return;
        Rendered rendered = menu.renderedAt(slot);
        if (rendered == null || rendered.item() == null) return;
        boolean right = event.kind() == ScreenEvent.Kind.RIGHT;
        // Sprint held is the shift of a click on a screen, where Shift itself steps back.
        ClickKind kind = event.shift() ? (right ? ClickKind.SHIFT_RIGHT : ClickKind.SHIFT_LEFT)
                : right ? ClickKind.RIGHT : ClickKind.LEFT;
        MenuListener.handle(menu, event.viewer(), slot, kind, rendered, menu.inventory().getItem(slot));
    }
}
