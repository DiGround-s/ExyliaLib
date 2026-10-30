package net.exylia.lib.stage;

import net.exylia.lib.display.DisplayHandle;
import net.exylia.lib.display.DisplayKeyframe;
import net.exylia.lib.display.DisplayModel;
import net.exylia.lib.display.DisplayMotion;
import net.exylia.lib.text.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A button on a {@link Stage}: a plate with its label sized to fit, that lights up and grows a
 * little under the crosshair, and squashes when pressed.
 *
 * <pre>{@code
 * StageButton again = stage.button(label, 0, -0.9, 0.8, 0.24, 300);
 *
 * // every couple of ticks, on the viewer's thread
 * again.hover(again.under(player));
 *
 * // on a swing
 * if (again.under(player)) {
 *     again.press();
 *     playAgain();
 * }
 * }</pre>
 *
 * <p>The label is fitted to the plate from its length, so a long translation shrinks instead of
 * spilling over the next button, and a label of one or two glyphs is drawn as an icon, as tall as
 * the plate holds. Hovering and pressing play the theme's sounds to the viewer; a
 * theme with blank sounds leaves them to the caller. Used from the stage's thread, like the stage.
 *
 * @since 1.356.0
 */
public final class StageButton {

    /** How wide a character is drawn at scale 1, in blocks; generous, for wide server fonts. */
    private static final double CHAR_WIDTH = 0.18;
    private static final double MAX_LABEL = 0.75;
    /** A label of a glyph or two (+, −, an arrow) is an icon, drawn as tall as the plate holds. */
    private static final int ICON_LENGTH = 2;
    private static final double ICON_FILL = 3.6;
    private static final long HOVER_MILLIS = 120L;

    private final Stage stage;
    private final double across;
    private final double up;
    private final double width;
    private final double height;
    private final DisplayHandle plate;
    private DisplayHandle label;
    private boolean hovered;
    private boolean removed;

    StageButton(Stage stage, Component text, double across, double up, double width, double height, long delay) {
        this.stage = stage;
        this.across = across;
        this.up = up;
        this.width = width;
        this.height = height;
        this.plate = stage.show(DisplayModel.block(StageLook.block(stage.theme.buttonBlock(), Material.GRAY_CONCRETE)).light(15),
                StageLook.pop(stage.facing, width, height, 0.05, delay, stage.life), stage.plane.point(across, up, 0));
        this.label = draw(text, delay + 60);
    }

    /** Whether a hit on the stage's plane lands on it; never once it is removed. */
    public boolean contains(@Nullable StagePlane.Hit hit) {
        return !removed && hit != null && hit.inside(across, up, width, height);
    }

    /** The plane it stands on, which {@link #contains} measures hits against. @since 1.406.0 */
    public StagePlane plane() {
        return stage.plane;
    }

    /** Whether a player's crosshair is on it. Reads the player's eyes: call it on their thread. */
    public boolean under(Player player) {
        return contains(stage.plane.hit(player.getEyeLocation()));
    }

    /** Rewrites the label, refitting it. */
    public void label(Component text) {
        if (removed) return;
        stage.remove(label);
        label = draw(text, 0);
    }

    /** Lights the button and lifts it a little, or puts it back; the hover sound plays as it lights. */
    public void hover(boolean on) {
        if (on == hovered || plate == null || removed) return;
        hovered = on;
        plate.glow(on ? StageLook.rgb(stage.theme.buttonGlow(), 0xB48FD9) : -1);
        if (on) StageLook.play(stage.viewer, stage.theme.hoverSound());
        double grow = on ? 1.08 : 1.0;
        DisplayKeyframe here = plate.pose();
        if (here != null) {
            plate.moveTo(new DisplayKeyframe(0L, here.x(), here.y(), here.z(), here.rotation(),
                    (float) (width * grow), (float) (height * grow), 0.05f), HOVER_MILLIS, DisplayMotion.Easing.QUAD_OUT);
        }
    }

    /** Whether it is lit under the crosshair. */
    public boolean hovered() {
        return hovered;
    }

    /** The button is pushed: it squashes in, springs back past its size and settles, unlit. */
    public void press() {
        if (plate == null || removed) return;
        hovered = false;
        plate.glow(-1);
        StageLook.play(stage.viewer, stage.theme.pressSound());
        DisplayKeyframe here = plate.pose();
        if (here == null) return;
        plate.then(DisplayMotion.of(List.of(here,
                sized(here, 70L, 0.9, 0.78), sized(here, 190L, 1.06, 1.08), sized(here, 300L, 1.0, 1.0)), stage.life));
    }

    private DisplayKeyframe sized(DisplayKeyframe at, long millis, double across, double up) {
        return new DisplayKeyframe(millis, at.x(), at.y(), at.z(), at.rotation(),
                (float) (width * across), (float) (height * up), 0.05f);
    }

    /** Takes it down; it answers no hit afterwards. */
    public void remove() {
        removed = true;
        stage.remove(plate);
        stage.remove(label);
    }

    private DisplayHandle draw(Component text, long delay) {
        String plain = Text.of(PlainTextComponentSerializer.plainText().serialize(text)).plain();
        double fit = (width * 0.86) / Math.max(1, plain.length() * CHAR_WIDTH);
        double cap = plain.strip().length() <= ICON_LENGTH ? height * ICON_FILL : Math.min(MAX_LABEL, height * 2.4);
        double scale = Math.min(fit, cap);
        Location at = stage.plane.point(across, up - Stage.TEXT_HEIGHT * scale / 2, 0.05);
        return stage.show(Stage.textModel(text).background(0).textShadow(true),
                StageLook.pop(stage.facing, scale, scale, scale, delay, stage.life), at);
    }
}
