package net.exylia.lib.screen.internal;

import net.exylia.lib.screen.Motion;
import net.exylia.lib.screen.Node;
import net.exylia.lib.screen.Screen;
import net.exylia.lib.screen.ScreenEvent;
import net.exylia.lib.text.Phrases;
import net.exylia.lib.text.Text;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Fits screens and the HUD to a player's window. The client never tells the server its field of view or window shape,
 * so the player shows it: lines are drawn where the window's edges would be at the FOV they pick, and they move them
 * onto the real edges (height, then width, the mouse wheel or the buttons),
 * then say whether sprinting zooms their view out. The result is one network-wide pair of preferences
 * ({@link ScreenPreferences#fov}, {@link ScreenPreferences#aspect}) that sizes every screen and places the HUD, and
 * their FOV Effects slider ({@link ScreenPreferences#fovEffects}), which keeps the HUD still while they sprint.
 *
 * <p>Asked once, on a player's first screen or the first time their HUD shows, whichever comes first; again from the
 * screen settings. Drawn over a camera-view screen only: a wheel screen keeps the player's own FOV effects.
 *
 * <p>Internal: free to change without notice.
 */
public final class FitPanel {

    /** The name of the screen opened just to fit, when the HUD asks first. */
    static final String SCREEN = "~fit";
    private static final double LABEL = 0.8, CORNER = 18, LINE = 2;
    private static final String[][] SHAPES = {{"16:9", "1.78"}, {"16:10", "1.6"}, {"21:9", "2.33"}, {"4:3", "1.33"}};

    private FitPanel() {
    }

    /**
     * Opens the fit screen for a player whose HUD is about to show, when they were never asked and nothing they are
     * doing would be cut short: on the player's thread. {@code false} while it waits for a quiet moment.
     */
    public static boolean offer(Plugin lib, Player player) {
        if (lib == null) return true;
        if (ScreenRuntime.isOpen(player.getUniqueId()) || player.isInsideVehicle() || !player.isOnGround()
                || player.getNoDamageTicks() > 0 || player.getFallDistance() > 0) return false;
        if (ScreenPreferences.askFit(player)) ScreenRuntime.open(lib, player, Screen.of(session -> Node.column()).name(SCREEN));
        return true;
    }

    static Node of(LiveScreen live) {
        Player player = live.viewer();
        UUID id = player.getUniqueId();
        int step = live.fitStep();
        boolean height = step == 1, width = step == 2;
        double fov = ScreenPreferences.fov(id), aspect = ScreenPreferences.aspect(id);
        double w = live.layoutWidth(), h = live.layoutHeight();
        double hy = live.fitHalfHeight(), hx = hy * aspect;
        double left = w / 2 - hx, right = w / 2 + hx, top = h / 2 - hy, bottom = h / 2 + hy;

        Node frame = Node.column().id("~fit").size(w, h).justify(Node.Justify.CENTER).align(Node.Align.CENTER)
                .overlay(true);
        // The wheel anywhere moves the lines: one notch a step, the buttons stay for the fine-grained.
        if (height) frame.onScroll(event -> ScreenPreferences.view(player, fov + event.steps(), aspect, false));
        if (width) frame.onScroll(event -> ScreenPreferences.view(player, fov, aspect + event.steps() * 0.01, false));
        // The window's edges at the chosen view: the pair being fitted as full lines, the corners always.
        String edge = "{primary}";
        if (height) {
            frame.add(bar(left, top, 2 * hx, LINE, edge), bar(left, bottom - LINE, 2 * hx, LINE, edge));
        } else if (width) {
            frame.add(bar(left, top, LINE, 2 * hy, edge), bar(right - LINE, top, LINE, 2 * hy, edge));
        }
        if (step < 3) {
            frame.add(bar(left, top, CORNER, LINE, edge), bar(left, top, LINE, CORNER, edge),
                    bar(right - CORNER, top, CORNER, LINE, edge), bar(right - LINE, top, LINE, CORNER, edge),
                    bar(left, bottom - LINE, CORNER, LINE, edge), bar(left, bottom - CORNER, LINE, CORNER, edge),
                    bar(right - CORNER, bottom - LINE, CORNER, LINE, edge), bar(right - LINE, bottom - CORNER, LINE, CORNER, edge));
        }

        Node card = Node.column().padding(10, 14).gap(5).align(Node.Align.CENTER).width(220)
                .background("{primary}20").outline("{primary}60", 1)
                .enter(Motion.Tween.of(Motion.Transform.scale(0.94), 4, Motion.Ease.OUT));
        card.add(words("{primary}&l" + Phrases.tr("Fit to your window")));
        card.add(words("{letters_black}" + Phrases.tr(height ? "Step 1 of 3 · Height"
                : width ? "Step 2 of 3 · Width" : "Step 3 of 3 · Sprinting")).scale(0.75));
        card.add(words("{letters}" + Phrases.tr(height
                ? "Scroll your mouse wheel until the lines touch the top and bottom of your window."
                : width ? "Pick your screen's shape, or scroll until the lines touch the left and right of your window."
                : "When you sprint, does your view zoom out a little?")).scale(LABEL).wrap(190)
                .textAlign(Node.Align.CENTER));
        Node next;
        if (height) {
            card.add(stepper("fov", "{highlight}" + Phrases.tr("FOV") + " " + Math.round(fov), new double[]{-5, -1, 1, 5},
                    by -> ScreenPreferences.view(player, fov + by, aspect, false)));
            next = button("next", "{letters}" + Phrases.tr("Next"), event -> live.fit(2));
        } else if (width) {
            Node shapes = Node.row().gap(3);
            for (String[] shape : SHAPES) {
                double value = Double.parseDouble(shape[1]);
                shapes.add(button("shape" + shape[0], (Math.abs(value - aspect) < 0.005 ? "{success}" : "{letters}") + shape[0],
                        event -> ScreenPreferences.view(player, fov, value, false)));
            }
            card.add(shapes);
            card.add(stepper("aspect", "{highlight}" + String.format(Locale.ROOT, "%.2f", aspect),
                    new double[]{-0.1, -0.01, 0.01, 0.1}, by -> ScreenPreferences.view(player, fov, aspect + by, false)));
            next = button("next", "{letters}" + Phrases.tr("Next"), event -> live.fit(3));
        } else {
            // Vanilla zooms out while sprinting unless FOV Effects was turned down: the two answers nearly everyone has.
            // A value in between stays in the screen settings.
            boolean still = ScreenPreferences.fovEffects(id) < 0.5;
            card.add(Node.row(
                    button("zooms", (still ? "{letters}" : "{success}") + Phrases.tr("Yes, it zooms out"),
                            event -> ScreenPreferences.fovEffects(player, 100)).grow(1),
                    button("still", (still ? "{success}" : "{letters}") + Phrases.tr("No, it stays still"),
                            event -> ScreenPreferences.fovEffects(player, 0)).grow(1))
                    .gap(4).align(Node.Align.STRETCH).width(190));
            card.add(words("{letters_black}" + Phrases.tr("It does unless you lowered FOV Effects in your video settings."))
                    .scale(0.65).wrap(190).textAlign(Node.Align.CENTER));
            next = button("done", "{letters}" + Phrases.tr("Done"), event -> {
                ScreenPreferences.view(player, fov, aspect, true);
                live.fit(0);
            });
        }
        card.add(Node.row(
                button(height ? "later" : "back", "{letters}" + Phrases.tr(height ? "Later" : "Back"),
                        event -> live.fit(step - 1)).grow(1),
                next.background("{primary}").grow(1))
                .gap(4).align(Node.Align.STRETCH).width(190));
        card.add(words("{letters_black}" + Phrases.tr("Screens and the HUD both use this. Changed your FOV or window? Fit again from the screen settings."))
                .scale(0.65).wrap(190).textAlign(Node.Align.CENTER));
        frame.add(card);
        return frame;
    }

    private static Node bar(double x, double y, double w, double h, String colour) {
        return Node.box().absolute(y, Double.NaN, Double.NaN, x).size(w, h).background(colour);
    }

    private static Node stepper(String id, String value, double[] steps, Consumer<Double> by) {
        Node row = Node.row().gap(3).align(Node.Align.CENTER);
        for (int i = 0; i < steps.length; i++) {
            double step = steps[i];
            if (i == steps.length / 2) row.add(words(value).scale(LABEL).width(52).textAlign(Node.Align.CENTER));
            String label = (step > 0 ? "+" : "-") + (Math.abs(step) >= 1 ? Long.toString(Math.round(Math.abs(step)))
                    : String.format(Locale.ROOT, "%.2f", Math.abs(step)));
            row.add(button(id + i, "{letters}" + label, event -> by.accept(step)).width(Math.abs(step) >= 1 ? 18 : 24));
        }
        return row;
    }

    private static Node button(String id, String label, Consumer<ScreenEvent> click) {
        return words(label).scale(LABEL).id("~fit." + id).padding(2, 5).textAlign(Node.Align.CENTER)
                .background("{letters_black}20").hover("{primary}50")
                .active(null, Motion.Transform.scale(0.95)).onClick(click);
    }

    private static Node words(String text) {
        return Node.text(Text.of(text).build());
    }
}
