package net.exylia.lib.ui.internal;

import net.exylia.lib.screen.Node;
import net.exylia.lib.screen.Screen;
import net.exylia.lib.screen.ScreenEvent;
import net.exylia.lib.screen.ScreenSession;
import net.exylia.lib.screen.ScreenSettings;
import net.exylia.lib.screen.Screens;
import net.exylia.lib.screen.internal.ScreenRoutes;
import net.exylia.lib.ui.ClickBindings;
import net.exylia.lib.ui.ClickKind;
import net.exylia.lib.ui.MenuStyles;
import net.exylia.lib.ui.UiDefinition;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.Style;
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
 * either the screen its id is routed to ({@code <screen routes>}) or the chest skin draws it. The skin keeps the
 * chest's slot grid but draws only its items, as cards (icon, name, first lore line) with the whole lore as their
 * tooltip, its title as a header and its last row as a bar, and hands every click to the chest menu, so actions,
 * pages and redraws all work as in the window.
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
    private static final String CARD = "#201a2b";
    private static final String CARD_HOVER = "#2e2541";

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
        // The back action goes to the menu this one was opened from, as the window's back button would.
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
     * The back action on the screen: back to the menu opened before this one. A routed screen goes back only through menus
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

    /** The layout area the skin fills, in screen pixels (the screen's default). */
    private static final double AREA_W = Screen.WIDTH - 28;
    private static final double AREA_H = Screen.HEIGHT - 20;
    private static final double GAP = 3;
    private static final double PAD = 8;
    private static final double TITLE = 9;
    private static final double BAR = 16;

    /**
     * The chest as a screen: its title, its items as cards where the chest has them (frames and empty slots left
     * out, so the cards stand on the panel rather than in a grid of cells), and its last row as a bar of buttons
     * when it holds the navigation (back, pages), as every Exylia layout does.
     */
    private static Node skin(Session menu) {
        Inventory inventory = menu.inventory();
        int rows = Math.max(1, inventory.getSize() / 9);
        boolean bar = rows >= 3 && rowShown(inventory, rows - 1);
        int bodyRows = bar ? rows - 1 : rows;
        List<Integer> shownRows = new ArrayList<>();
        int minCol = 9;
        int maxCol = -1;
        for (int r = 0; r < bodyRows; r++) {
            for (int c = 0; c < 9; c++) {
                if (!shown(inventory.getItem(r * 9 + c))) continue;
                if (shownRows.isEmpty() || shownRows.get(shownRows.size() - 1) != r) shownRows.add(r);
                minCol = Math.min(minCol, c);
                maxCol = Math.max(maxCol, c);
            }
        }
        Node panel = Node.column(Node.text(menu.currentTitle()).scale(0.9)).padding(PAD, PAD + 2).gap(6)
                .background(PANEL).outline("{primary}", 1);
        Node barRows = bar ? bar(menu, inventory, (rows - 1) * 9) : null;
        double barH = bar ? (barRows.children().size() > 1 ? 2 * BAR + GAP : BAR) + 7 : 0;
        if (shownRows.isEmpty()) {
            panel.add(emptyState(inventory, bodyRows));
        } else {
            int cols = maxCol - minCol + 1;
            double w = clamp(Math.floor((AREA_W - 2 * PAD - (cols - 1) * GAP) / cols), 34, 72);
            double h = clamp(Math.floor((AREA_H - 2 * PAD - TITLE - 6 - barH - (shownRows.size() - 1) * GAP)
                    / shownRows.size()), 22, 48);
            Node grid = Node.column().gap(GAP).align(Node.Align.CENTER);
            for (int r : shownRows) {
                Node row = Node.row().gap(GAP);
                for (int c = minCol; c <= maxCol; c++) {
                    int slot = r * 9 + c;
                    ItemStack item = inventory.getItem(slot);
                    row.add(shown(item) ? card(menu, slot, item, w, h) : Node.box().size(w, h));
                }
                grid.add(row);
            }
            panel.add(grid);
        }
        if (bar) {
            panel.add(Node.box().height(1).background("{primary}30"));
            panel.add(barRows);
        }
        return Node.column(panel).align(Node.Align.CENTER).justify(Node.Justify.CENTER);
    }

    /** Whether an item is drawn as a card: frames (glass panes) and empty slots are not. */
    private static boolean shown(@Nullable ItemStack item) {
        return item != null && !item.getType().isAir() && !item.getType().name().endsWith("GLASS_PANE");
    }

    private static boolean rowShown(Inventory inventory, int row) {
        for (int c = 0; c < 9; c++) if (shown(inventory.getItem(row * 9 + c))) return true;
        return false;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** A card: the item, its name without its context, and its first lore line when there is room. */
    private static Node card(Session menu, int slot, ItemStack item, double w, double h) {
        List<Component> lines = lines(item);
        boolean live = clickable(menu, slot);
        // Every card keeps its name (a six-row chest leaves cards about 27 pixels tall): the icon gives way first.
        double icon = clamp(h - (lines != null ? 13 : 6), 10, h >= 40 ? 20 : 18);
        Node card = Node.column(Node.item(item).size(icon, icon)).size(w, h).padding(2, 3).gap(1)
                .align(Node.Align.CENTER).justify(Node.Justify.CENTER).background(CARD).tooltip(lines).id("s" + slot);
        if (lines != null) {
            card.add(Node.text(subject(lines.get(0))).scale(0.6).textAlign(Node.Align.CENTER));
            if (h >= 40 && lines.size() > 1) card.add(Node.text(lines.get(1)).scale(0.5).textAlign(Node.Align.CENTER));
        }
        if (!live) return card;
        return card.hover(CARD_HOVER).hoverOutline("{primary}").onClick(event -> click(menu, slot, event));
    }

    /**
     * The last row as a bar: its buttons kept left, centre and right, as the chest has them. Buttons that do not fit
     * across the screen wrap onto two centred rows of about the same width.
     */
    private static Node bar(Session menu, Inventory inventory, int first) {
        List<Integer> slots = new ArrayList<>();
        for (int c = 0; c < 9; c++) if (shown(inventory.getItem(first + c))) slots.add(first + c);
        double[] widths = slots.stream().mapToDouble(slot -> buttonWidth(inventory.getItem(slot))).toArray();
        int wrap = barBreak(widths, AREA_W - 2 * (PAD + 2), GAP);
        Node out = Node.column().gap(GAP);
        if (wrap < slots.size()) {
            Node top = Node.row().gap(GAP).justify(Node.Justify.CENTER);
            Node bottom = Node.row().gap(GAP).justify(Node.Justify.CENTER);
            for (int i = 0; i < slots.size(); i++) {
                (i < wrap ? top : bottom).add(button(menu, slots.get(i), inventory.getItem(slots.get(i))));
            }
            return out.add(top).add(bottom);
        }
        Node left = Node.row().gap(GAP).grow(1).justify(Node.Justify.START);
        Node centre = Node.row().gap(GAP).grow(1).justify(Node.Justify.CENTER);
        Node right = Node.row().gap(GAP).grow(1).justify(Node.Justify.END);
        for (int slot : slots) {
            int c = slot - first;
            (c < 3 ? left : c < 6 ? centre : right).add(button(menu, slot, inventory.getItem(slot)));
        }
        return out.add(Node.row(left, centre, right).height(BAR));
    }

    /**
     * Where a bar's second row starts: past the end when every button fits on one row, otherwise the break that
     * leaves the wider row narrowest.
     */
    static int barBreak(double[] widths, double room, double gap) {
        double[] upTo = new double[widths.length + 1];
        for (int i = 0; i < widths.length; i++) upTo[i + 1] = upTo[i] + widths[i] + gap;
        if (upTo[widths.length] - gap <= room) return widths.length;
        int best = 1;
        for (int i = 1; i < widths.length; i++) {
            double wider = Math.max(upTo[i], upTo[widths.length] - upTo[i]);
            if (wider < Math.max(upTo[best], upTo[widths.length] - upTo[best])) best = i;
        }
        return best;
    }

    /** What {@link #button} takes across: padding, icon, gap and name. */
    private static double buttonWidth(ItemStack item) {
        List<Component> lines = lines(item);
        return 23 + (lines == null ? 0 : net.exylia.lib.text.Centering.pixelWidth(subject(lines.get(0))) * 0.6);
    }

    private static Node button(Session menu, int slot, ItemStack item) {
        List<Component> lines = lines(item);
        Node button = Node.row(Node.item(item).size(10, 10)).height(BAR).padding(3, 5).gap(3)
                .align(Node.Align.CENTER).background(CARD).tooltip(lines).id("s" + slot);
        if (lines != null) button.add(Node.text(subject(lines.get(0))).scale(0.6));
        if (!clickable(menu, slot)) return button;
        return button.hover(CARD_HOVER).hoverOutline("{primary}").onClick(event -> click(menu, slot, event));
    }

    /** A list with nothing in it: what its empty slots say ("No more kits"), or nothing. */
    private static Node emptyState(Inventory inventory, int bodyRows) {
        for (int slot = 0; slot < bodyRows * 9; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) continue;
            List<Component> lines = lines(item);
            if (lines == null) continue;
            Node out = Node.column().gap(2).align(Node.Align.CENTER).padding(24, 0);
            for (Component line : lines) out.add(Node.text(line).scale(0.7).textAlign(Node.Align.CENTER));
            return out;
        }
        return Node.box().height(40);
    }

    /** Whether a click on the slot does anything. */
    private static boolean clickable(Session menu, int slot) {
        Rendered rendered = menu.renderedAt(slot);
        return rendered != null && rendered.item() != null && !rendered.item().bindings().isEmpty();
    }

    /**
     * An item name without its context: {@code Mine ▸ Reset} becomes {@code Reset}, the part a card has room for
     * (the tooltip keeps the whole name). A name without the marker is kept whole.
     */
    static Component subject(Component name) {
        List<Component> parts = new ArrayList<>();
        flatten(name, Style.empty(), parts);
        for (int i = 0; i < parts.size(); i++) {
            if (!(parts.get(i) instanceof TextComponent text)) continue;
            int at = text.content().indexOf('\u25b8');
            if (at < 0) continue;
            List<Component> tail = new ArrayList<>();
            String rest = text.content().substring(at + 1);
            if (!rest.isEmpty()) tail.add(text.content(rest));
            tail.addAll(parts.subList(i + 1, parts.size()));
            // The space after the marker belongs to it.
            while (!tail.isEmpty() && tail.get(0) instanceof TextComponent lead && lead.content().isBlank()) {
                tail.remove(0);
            }
            if (!tail.isEmpty() && tail.get(0) instanceof TextComponent lead) {
                tail.set(0, lead.content(lead.content().stripLeading()));
            }
            return tail.isEmpty() ? name : Component.empty().children(tail);
        }
        return name;
    }

    /** A component as its runs of text, each with the style it is drawn in. */
    private static void flatten(Component component, Style parent, List<Component> out) {
        Style style = component.style().merge(parent, Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
        if (component instanceof TextComponent text) {
            if (!text.content().isEmpty()) out.add(Component.text(text.content(), style));
        } else {
            out.add(component.children(List.of()).style(style));
        }
        for (Component child : component.children()) flatten(child, style, out);
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
        ClickKind kind = kindOf(event.kind() == ScreenEvent.Kind.RIGHT, event.shift(), rendered.item().bindings());
        net.exylia.lib.screen.internal.ScreenRuntime.asChest(event.viewer().getUniqueId(),
                () -> MenuListener.handle(menu, event.viewer(), slot, kind, rendered, menu.inventory().getItem(slot)));
    }

    /**
     * The chest click a screen click stands for. Sprint held is the shift of a click on a screen, where Shift itself
     * closes it; a client with toggle sprint holds it all the time, so a shift click on a button that binds none
     * is the plain click, never a dead one.
     */
    static ClickKind kindOf(boolean right, boolean shift, ClickBindings bindings) {
        ClickKind plain = right ? ClickKind.RIGHT : ClickKind.LEFT;
        if (!shift) return plain;
        ClickKind shifted = right ? ClickKind.SHIFT_RIGHT : ClickKind.SHIFT_LEFT;
        return bindings.bound(shifted) ? shifted : plain;
    }
}
