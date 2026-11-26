package net.exylia.lib.screen.internal;

import net.exylia.lib.screen.Motion;
import net.exylia.lib.screen.Node;
import net.exylia.lib.screen.Screen;
import net.exylia.lib.screen.ScreenEvent;
import net.exylia.lib.text.Phrases;
import net.exylia.lib.text.Text;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Fits screens and the HUD to a player's window. The client never tells the server its field of view or window shape,
 * so the player shows it with one click: they point the arrow at their window's top-left corner, whose direction from
 * the camera gives both (see {@link #measured}). The result is one network-wide pair of preferences
 * ({@link ScreenPreferences#fov}, {@link ScreenPreferences#aspect}) that sizes every screen and places the HUD; a
 * second step draws the window's corners where they were measured, so a wrong click shows at once.
 *
 * <p>The FOV Effects slider ({@link ScreenPreferences#fovEffects}) is not asked: few players know it, and the vanilla
 * default is kept until they change it in their settings.
 *
 * <p>Asked once, on a player's first screen or the first time their HUD shows, whichever comes first; again from the
 * screen settings. Drawn over a camera-view screen only: a wheel screen keeps the player's own FOV effects.
 *
 * <p>Internal: free to change without notice.
 */
public final class FitPanel {

    /** The name of the screen opened just to fit, when the HUD asks first. */
    static final String SCREEN = "~fit";
    /** How far the arrow may go while pointing, as tangents: half the height of FOV 110, and 21:9 at that. */
    static final double REACH = Math.tan(Math.toRadians(ScreenPreferences.MAX_FOV / 2.0)), REACH_ASPECT = 2.4;
    private static final double LABEL = 0.8, CORNER = 18, LINE = 2, SLACK = 4;
    private static final String[][] SHAPES = {{"16:9", "1.78"}, {"16:10", "1.6"}, {"21:9", "2.37"}, {"4:3", "1.33"},
            {"32:9", "3.56"}, {"5:4", "1.25"}};

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

    /**
     * The FOV and shape of a window whose corner lies {@code tanX}, {@code tanY} from the middle of the view, as
     * tangents of the angles to it: Minecraft's FOV is the vertical one, so its half is {@code atan(|tanY|)}, and the
     * shape is the width over the height. {@code null} for a point no window has a corner at (too near the middle,
     * or beside it rather than in a corner); a FOV a few degrees past the slider's ends is taken as its end.
     */
    static double[] measured(double tanX, double tanY) {
        double fov = 2 * Math.toDegrees(Math.atan(Math.abs(tanY)));
        if (fov < ScreenPreferences.MIN_FOV - SLACK || fov > ScreenPreferences.MAX_FOV + SLACK) return null;
        double aspect = Math.abs(tanX) / Math.abs(tanY);
        if (aspect < ScreenPreferences.MIN_ASPECT || aspect > ScreenPreferences.MAX_ASPECT) return null;
        fov = Math.max(ScreenPreferences.MIN_FOV, Math.min(ScreenPreferences.MAX_FOV, fov));
        return new double[]{Math.round(fov), Math.round(aspect * 100) / 100.0};
    }

    /** A window shape as people name it, {@code 16:9}, or its ratio to one when it is none of the common ones. */
    static String shape(double aspect) {
        for (String[] shape : SHAPES) {
            if (Math.abs(Double.parseDouble(shape[1]) - aspect) <= 0.04) return shape[0];
        }
        return String.format(Locale.ROOT, "%.2f:1", aspect);
    }

    static Node of(LiveScreen live) {
        Player player = live.viewer();
        UUID id = player.getUniqueId();
        boolean pointing = live.fitStep() == 1;
        double fov = ScreenPreferences.fov(id), aspect = ScreenPreferences.aspect(id);
        double w = live.layoutWidth(), h = live.layoutHeight();

        Node frame = Node.column().id("~fit").size(w, h).justify(Node.Justify.CENTER).align(Node.Align.CENTER)
                .overlay(true);
        Node card = Node.column().padding(10, 14).gap(5).align(Node.Align.CENTER).width(220)
                .background("{primary}20").outline("{primary}60", 1)
                .enter(Motion.Tween.of(Motion.Transform.scale(0.94), 4, Motion.Ease.OUT));
        if (pointing) {
            card.add(words("{primary}&l" + Phrases.tr("Fit to your window")));
            card.add(picture());
            card.add(words("{letters}" + Phrases.tr("Move the arrow to the top-left corner of your screen and click."))
                    .scale(LABEL).wrap(190).textAlign(Node.Align.CENTER));
            card.add(Node.row(button("later", "{letters}" + Phrases.tr("Later"), event -> live.fit(0)).grow(1))
                    .align(Node.Align.STRETCH).width(120));
        } else {
            // The window's corners where the click put them: on the real ones, the fit is right.
            double hy = live.fitHalfHeight(), hx = hy * aspect;
            double left = w / 2 - hx, right = w / 2 + hx, top = h / 2 - hy, bottom = h / 2 + hy;
            String edge = "{success}";
            frame.add(bar(left, top, CORNER, LINE, edge), bar(left, top, LINE, CORNER, edge),
                    bar(right - CORNER, top, CORNER, LINE, edge), bar(right - LINE, top, LINE, CORNER, edge),
                    bar(left, bottom - LINE, CORNER, LINE, edge), bar(left, bottom - CORNER, LINE, CORNER, edge),
                    bar(right - CORNER, bottom - LINE, CORNER, LINE, edge), bar(right - LINE, bottom - CORNER, LINE, CORNER, edge));
            card.add(words("{success}&l" + Phrases.tr("Fitted to your window")));
            card.add(words("{letters}" + Phrases.tr("FOV") + " {highlight}" + Math.round(fov) + " {letters_black}· {letters}"
                    + Phrases.tr("Screen") + " {highlight}" + shape(aspect)).scale(LABEL));
            card.add(words("{letters_black}" + Phrases.tr("The green marks should sit on your screen's corners. If they do not, measure again."))
                    .scale(0.7).wrap(190).textAlign(Node.Align.CENTER));
            card.add(Node.row(
                    button("again", "{letters}" + Phrases.tr("Measure again"), event -> live.fit(1)).grow(1),
                    button("done", "{letters}" + Phrases.tr("Done"), event -> live.fit(0)).background("{primary}").grow(1))
                    .gap(4).align(Node.Align.STRETCH).width(190));
        }
        card.add(words("{letters_black}" + Phrases.tr("Screens and the HUD both use this. Changed your FOV or window? Fit again from the screen settings."))
                .scale(0.65).wrap(190).textAlign(Node.Align.CENTER));
        frame.add(card);
        return frame;
    }

    /**
     * A small window with the arrow gliding into its top-left corner, again and again: the whole task in one look.
     * Under reduced motion it holds its first frame, the arrow already in the corner.
     */
    private static Node picture() {
        double width = 72, height = 40;
        Node window = Node.box().size(width, height).background("{letters_black}18").outline("{letters_black}50", 1);
        window.add(Node.box().absolute(0, Double.NaN, Double.NaN, 0).size(8, 1).background("{primary}"),
                Node.box().absolute(0, Double.NaN, Double.NaN, 0).size(1, 8).background("{primary}"));
        Motion.Transform middle = Motion.Transform.translate(width / 2 - 4, height / 2 - 5);
        Node arrow = LiveScreen.arrowImage().size(7, 11).absolute(1, Double.NaN, Double.NaN, 1)
                .animation(new Motion.Keyframes(List.of(
                        new Motion.Frame(0, Motion.Transform.NONE),
                        new Motion.Frame(0.25, Motion.Transform.NONE),
                        new Motion.Frame(0.3, middle),
                        new Motion.Frame(0.85, Motion.Transform.NONE),
                        new Motion.Frame(1, Motion.Transform.NONE)), 50, Motion.Ease.OUT, true));
        window.add(arrow);
        return window;
    }

    private static Node bar(double x, double y, double w, double h, String colour) {
        return Node.box().absolute(y, Double.NaN, Double.NaN, x).size(w, h).background(colour);
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
