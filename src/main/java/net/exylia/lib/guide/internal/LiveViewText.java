package net.exylia.lib.guide.internal;

import net.exylia.lib.camera.internal.CameraRuntime;
import net.exylia.lib.guide.GuideText;
import net.exylia.lib.guide.ViewTextStyle;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * A text that rides the player's own view, like a cinematic subtitle in
 * normal play.
 *
 * <h2>Why it rides the player</h2>
 * The same trick as a world shot's subtitle: a text display seated on the
 * entity the client renders from, with a {@code CENTER} billboard and a
 * translation in the view's own axes. The client positions a passenger from
 * its vehicle every frame, so the panel stays exactly where it was put on the
 * screen however fast the view turns, and turning costs no packet at all.
 * Sending it a position instead is a panel trailing the view on every turn.
 *
 * <h2>The seat is not the eye</h2>
 * A passenger sits on top of the player's hitbox, which is a fifth of a block
 * above their eyes in every pose (standing 1.8 / 1.62, sneaking 1.5 / 1.27,
 * swimming 0.6 / 0.4). The billboard turns that offset with the view, so it is
 * taken back off for the current pitch: re-sent when the pitch moves a few
 * degrees or the pose changes, over two ticks so it never jumps. In between
 * the panel is off by at most a hundredth of a block.
 *
 * <h2>When it steps aside</h2>
 * While a camera shot is playing (the shot's own subtitle takes over), while
 * dead, and in spectator mode, where the client may be looking through
 * somebody else. It comes back on the next draw after.
 */
final class LiveViewText extends Piece implements GuideText {

    /** Pitch, in degrees, before the seat offset is worked out again. */
    private static final double PITCH_STEP = 4;
    /** How long the client takes to move it to a new offset, in ticks. */
    private static final int SETTLE_TICKS = 2;

    private final ViewTextStyle style;

    private volatile Component text;
    private Component sent;
    private int id = -1;
    private double sentPitch;
    private double sentSeat;

    LiveViewText(ViewerSession session, Component text, ViewTextStyle style) {
        super(session);
        this.text = text;
        this.style = style;
    }

    @Override
    public void text(@NotNull Component replacement) {
        this.text = replacement;
    }

    @Override
    public @NotNull Component text() {
        return text;
    }

    @Override
    void draw(Player player, long tick) {
        if (stepsAside(player)) {
            clear(player);
            return;
        }
        double pitch = player.getLocation().getPitch();
        double seat = Math.max(0, player.getHeight() - player.getEyeHeight());
        if (id < 0) {
            id = GuidePackets.newId();
            sent = text;
            remember(pitch, seat);
            GuidePackets.spawnViewText(player, id, sent, style, translation(pitch, seat));
            return;
        }
        if (Math.abs(pitch - sentPitch) >= PITCH_STEP || Math.abs(seat - sentSeat) > 0.01) {
            remember(pitch, seat);
            GuidePackets.viewTranslate(player, id, translation(pitch, seat), SETTLE_TICKS);
        }
        if (!text.equals(sent)) {
            sent = text;
            GuidePackets.text(player, id, sent);
        }
    }

    private float[] translation(double pitch, double seat) {
        return GuideGeometry.viewTranslation(style.screenY(), pitch, seat, GuidePackets.VIEW_DISTANCE);
    }

    private void remember(double pitch, double seat) {
        sentPitch = pitch;
        sentSeat = seat;
    }

    private static boolean stepsAside(Player player) {
        if (player.isDead() || player.getGameMode() == GameMode.SPECTATOR) {
            return true;
        }
        try {
            return CameraRuntime.isFilming(player);
        } catch (Throwable unavailable) {
            return false;
        }
    }

    @Override
    void clear(Player player) {
        if (id >= 0) {
            GuidePackets.destroyViewText(player, id);
            id = -1;
        }
    }

    @Override
    void forget() {
        if (id >= 0) {
            GuidePackets.forgetViewText(session.player().getUniqueId(), id);
        }
        id = -1;
    }
}
