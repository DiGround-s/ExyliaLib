package net.exylia.lib.screen.internal;

import net.exylia.lib.screen.Motion;
import net.exylia.lib.screen.Node;
import net.exylia.lib.screen.ScreenEvent;
import net.exylia.lib.text.Phrases;
import net.exylia.lib.text.Text;
import net.exylia.lib.ui.MenuStyle;
import net.exylia.lib.ui.MenuStyles;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The in-screen settings a player reaches from the button every screen draws at its bottom right, or with
 * {@link LiveScreen#SETTINGS_KEYS}: how large screens are, how the cursor moves, how much they move, how menus open.
 * Everything is kept per player by {@link ScreenRuntime}.
 *
 * <p>Internal: free to change without notice.
 */
final class SettingsPanel {

    private static final double LABEL = 0.8;

    private SettingsPanel() {
    }

    static Node of(LiveScreen live) {
        Player player = live.viewer();
        UUID id = player.getUniqueId();
        Node panel = Node.column().id("~settings").padding(10, 14).gap(5).align(Node.Align.STRETCH)
                .background("{primary}20").outline("{primary}60", 1).overlay(true)
                .onDismiss(session -> live.settings(false))
                .enter(Motion.Tween.of(Motion.Transform.scale(0.94), 4, Motion.Ease.OUT))
                .leave(Motion.Tween.of(Motion.Transform.scale(0.96), 2, Motion.Ease.IN));
        panel.add(words("{primary}&l" + Phrases.tr("Screen settings")).textAlign(Node.Align.CENTER));

        int size = ScreenRuntime.size(id);
        panel.add(row("Size",
                stepper("size", size + "%",
                        event -> ScreenRuntime.size(player, size - ScreenRuntime.SIZE_STEP),
                        event -> ScreenRuntime.size(player, size + ScreenRuntime.SIZE_STEP))));

        double speed = ScreenRuntime.sensitivity(id);
        panel.add(row("Cursor speed",
                stepper("speed", String.format(Locale.ROOT, "%.1f", speed),
                        event -> ScreenRuntime.sensitivity(player, Math.round((speed - 0.1) * 10) / 10.0),
                        event -> ScreenRuntime.sensitivity(player, Math.round((speed + 0.1) * 10) / 10.0))));

        CursorLead.Mode cursor = ScreenRuntime.cursorMode(id);
        panel.add(row("Cursor", button("cursor", "{info}" + Phrases.tr(cursor.label),
                event -> ScreenRuntime.cursorMode(player, cursor.next())).width(70)
                .tooltip("{letters}" + Phrases.tr(cursor.about))));

        boolean assist = ScreenRuntime.cursorAssist(id);
        panel.add(row("Cursor assist", button("assist", (assist ? "{success}" : "{letters_black}")
                        + Phrases.tr(assist ? "On" : "Off"), event -> ScreenRuntime.cursorAssist(player, !assist)).width(70)
                .tooltip("{letters}" + Phrases.tr("Makes buttons easier to hit, never on a game board"))));

        boolean reduced = ScreenRuntime.reducedMotionChosen(id);
        panel.add(row("Reduced motion", button("motion", (reduced ? "{success}" : "{letters_black}")
                        + Phrases.tr(reduced ? "On" : "Off"), event -> ScreenRuntime.reducedMotion(player, !reduced)).width(70)));

        if (MenuStyles.choosable()) {
            MenuStyle style = MenuStyles.choice(player);
            panel.add(row("Menu style", button("style", "{info}" + styleName(style),
                    event -> MenuStyles.choose(player, nextStyle(style))).width(70)));
        }

        panel.add(Node.row(
                button("reset", "{error}" + Phrases.tr("Reset"), event -> ScreenRuntime.reset(player)).grow(1).assist(false),
                button("done", "{letters}" + Phrases.tr("Done"), event -> live.settings(false)).background("{primary}").grow(1))
                .gap(4));
        panel.add(words("{letters_black}" + Phrases.tr("Ctrl + Space opens this from anywhere")).scale(0.7)
                .textAlign(Node.Align.CENTER));
        return panel;
    }

    /** The small button at a screen's bottom right. */
    static Node button(LiveScreen live) {
        Node icon;
        try {
            icon = Node.item(new org.bukkit.inventory.ItemStack(org.bukkit.Material.COMPARATOR));
        } catch (RuntimeException | LinkageError noServer) {
            // No item registry outside a server (a unit test).
            icon = words("{letters}*");
        }
        return icon.id("~gear").padding(3)
                .hover("{primary}40").outline("{primary}30", 1).tooltip("{letters}" + Phrases.tr("Screen settings"))
                .onClick(event -> live.settings(true));
    }

    private static MenuStyle nextStyle(MenuStyle style) {
        if (style == null) return MenuStyle.CLASSIC;
        return style == MenuStyle.CLASSIC ? MenuStyle.SCREEN : null;
    }

    private static String styleName(MenuStyle style) {
        if (style == null) return Phrases.tr("Server's choice");
        return Phrases.tr(style == MenuStyle.CLASSIC ? "Chest windows" : "Screens");
    }

    private static Node row(String label, Node control) {
        return Node.row(words("{letters}" + Phrases.tr(label)).scale(LABEL).grow(1).textAlign(Node.Align.START), control)
                .gap(14).align(Node.Align.CENTER);
    }

    private static Node stepper(String id, String value, Consumer<ScreenEvent> less, Consumer<ScreenEvent> more) {
        return Node.row(button(id + "-", "{letters}-", less).width(16), words("{highlight}" + value).scale(LABEL).width(38)
                .textAlign(Node.Align.CENTER), button(id + "+", "{letters}+", more).width(16))
                .align(Node.Align.CENTER);
    }

    private static Node button(String id, String label, Consumer<ScreenEvent> click) {
        return words(label).scale(LABEL).id("~set." + id).padding(2, 5).textAlign(Node.Align.CENTER)
                .background("{letters_black}20").hover("{primary}50")
                .active(null, Motion.Transform.scale(0.95)).onClick(click);
    }

    private static Node words(String text) {
        return Node.text(Text.of(text).build());
    }
}
