package net.exylia.lib.spatial;

import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.display.DisplayHandle;
import net.exylia.lib.stage.StagePlane;
import net.exylia.lib.tuner.Tuner;
import net.exylia.lib.tuner.TunerAxis;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Moving a menu's pieces in game: the admin looks at a panel, the carousel or the preview (outlined
 * while aimed at) and clicks it; the mouse wheel then moves it through the library's {@link Tuner}
 * (F changes axis, sneak + wheel the step, a click saves to the layout file and redraws). Sneaking
 * between two pieces leaves.
 */
final class EditMode {

    static final String CAROUSEL = "carousel";
    static final String PREVIEW = "preview";

    private final MenuSession session;
    private final ConfigFile<SpatialLayout> file;
    private final List<DisplayHandle> outline = new ArrayList<>();
    private SpatialLayout work;

    EditMode(MenuSession session, ConfigFile<SpatialLayout> file) {
        this.session = session;
        this.file = file;
        this.work = session.layout;
    }

    /** One hotspot per piece, outlined while aimed at; a click starts moving it. */
    void hotspots() {
        for (Map.Entry<String, SpatialLayout.Panel> panel : session.layout.panels().entrySet()) {
            spot(panel.getKey(), panel.getValue().width(), panel.getValue().height());
        }
        if (session.layout.carousel().present()) {
            SpatialLayout.Carousel row = session.layout.carousel();
            double width = 2 * net.exylia.lib.spatial.internal.Geometry.outermost(row.slots(), row.spacing(), row.centreGap())
                    + row.spacing();
            spot(CAROUSEL, width, 0.8);
        }
        spot(PREVIEW, 0.8, 1.9);
    }

    private void spot(String piece, double width, double height) {
        StagePlane plane = session.pieceAt(piece);
        session.pointer.add(Hotspot.rect(plane, 0, 0, width, height).priority(5)
                .onHover(on -> {
                    clearOutline();
                    if (on) outline.addAll(session.outline(plane, width, height,
                            session.colourOf(session.theme.editOutline(), 0x59A4FF)));
                })
                .onPress(click -> tune(piece)));
    }

    private void clearOutline() {
        outline.forEach(session::drop);
        outline.clear();
    }

    private void tune(String piece) {
        clearOutline();
        session.pointer.enabled(false);
        // Next tick: the swing and the interaction of the click that picked it must not reach the tuner as a save.
        session.menus.tasks().runAtEntityLater(session.viewer, 2L, () -> start(piece));
    }

    private void start(String piece) {
        session.pointer.enabled(true);
        if (session.isClosed()) return;
        work = session.layout;
        Tuner tuner = Tuner.of(session.menus.plugin(), "spatial:" + session.menu() + ":" + piece)
                .key("spatial:" + session.menu())
                .hud("{primary}&l%subject% {letters_black}» {secondary}%axis% {letters_black}» {info}%value%");
        for (TunerAxis axis : axes(piece)) {
            tuner.axis(axis.unit().equals("°") ? axis : axis.steps(0.01, 0.05, 0.25, 0.5).step(1));
        }
        tuner.onChange(tuning -> session.rebuild(work))
                .onEnd((tuning, end) -> {
                    if (end.kept()) {
                        SpatialLayout saved = work;
                        file.update(current -> saved);
                        session.rebuild(saved);
                    } else {
                        session.rebuild(file.get());
                    }
                    // The click that saved must not pick the piece under it again.
                    session.pointer.mute(400);
                })
                .start(session.viewer)
                .ifPresent(started -> session.pointer.mute(400));
    }

    /** What the wheel moves on a piece: ACROSS, UP, FORWARD, then its own (TURN, WIDTH, HEIGHT, SPACING...). */
    private List<TunerAxis> axes(String piece) {
        if (piece.equals(CAROUSEL)) {
            return List.of(
                    TunerAxis.of("ACROSS", () -> carousel(0), v -> carousel(v, Double.NaN, Double.NaN, Double.NaN, Double.NaN)),
                    TunerAxis.of("UP", () -> carousel(1), v -> carousel(Double.NaN, v, Double.NaN, Double.NaN, Double.NaN)),
                    TunerAxis.of("FORWARD", () -> carousel(2), v -> carousel(Double.NaN, Double.NaN, v, Double.NaN, Double.NaN)),
                    TunerAxis.of("SPACING", () -> work.carousel().spacing(),
                            v -> carousel(Double.NaN, Double.NaN, Double.NaN, Math.max(0.2, v), Double.NaN)),
                    TunerAxis.of("CURVE", () -> work.carousel().curve(),
                            v -> carousel(Double.NaN, Double.NaN, Double.NaN, Double.NaN, v)));
        }
        if (piece.equals(PREVIEW)) {
            return List.of(
                    TunerAxis.of("ACROSS", () -> work.preview().at().get(0), v -> preview(0, v)),
                    TunerAxis.of("UP", () -> work.preview().at().get(1), v -> preview(1, v)),
                    TunerAxis.of("FORWARD", () -> work.preview().at().get(2), v -> preview(2, v)));
        }
        return List.of(
                TunerAxis.of("ACROSS", () -> panel(piece).across(), v -> panel(piece, 0, v)),
                TunerAxis.of("UP", () -> panel(piece).up(), v -> panel(piece, 1, v)),
                TunerAxis.of("FORWARD", () -> panel(piece).forward(), v -> panel(piece, 2, v)),
                TunerAxis.degrees("TURN", () -> panel(piece).turn(), v -> panel(piece, 3, v)),
                TunerAxis.of("WIDTH", () -> panel(piece).width(), v -> panel(piece, 4, Math.max(0.2, v))),
                TunerAxis.of("HEIGHT", () -> panel(piece).height(), v -> panel(piece, 5, Math.max(0.1, v))));
    }

    private SpatialLayout.Panel panel(String name) {
        return work.panel(name);
    }

    private void panel(String name, int axis, double value) {
        SpatialLayout.Panel p = work.panel(name);
        double[] v = {p.across(), p.up(), p.forward(), p.turn(), p.width(), p.height()};
        v[axis] = Math.round(value * 1000) / 1000.0;
        work = work.withPanel(name, p.moved(v[0], v[1], v[2], v[3], v[4], v[5]));
    }

    private double carousel(int axis) {
        return work.carousel().at().get(axis);
    }

    private void carousel(double across, double up, double forward, double spacing, double curve) {
        SpatialLayout.Carousel c = work.carousel();
        work = work.withCarousel(c.moved(pick(across, c.at().get(0)), pick(up, c.at().get(1)),
                pick(forward, c.at().get(2)), pick(spacing, c.spacing()), pick(curve, c.curve())));
    }

    private void preview(int axis, double value) {
        SpatialLayout.Preview p = work.preview();
        double[] v = {p.at().get(0), p.at().get(1), p.at().get(2)};
        v[axis] = Math.round(value * 1000) / 1000.0;
        work = work.withPreview(p.moved(v[0], v[1], v[2]));
    }

    private static double pick(double value, double otherwise) {
        return Double.isNaN(value) ? otherwise : Math.round(value * 1000) / 1000.0;
    }

    void close() {
        clearOutline();
        Tuner.byKey(session.menus.plugin(), "spatial:" + session.menu()).ifPresent(tuning -> tuning.cancel());
    }
}
