package net.exylia.lib.display;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/**
 * A display that is currently being shown.
 *
 * <p>Held only by code that might want it gone early &mdash; a preview the
 * player closed, an effect on an entity that stopped existing. A display that
 * is left alone removes itself when its motion ends, so most callers throw the
 * handle away.
 *
 * <h2>Changing it while it plays (since 1.272.0)</h2>
 * What it draws can change without a respawn &mdash; {@link #showing},
 * {@link #text}, {@link #glow}, {@link #light} or a whole {@link #restyle} are
 * metadata only, so the client keeps drawing whatever pose it was heading
 * for. Where it goes can change too: {@link #then} plays another motion from
 * the pose on screen at that instant, and {@link #moveTo} glides to one pose.
 * That is what lets a reel spin freely and then decelerate onto a stop the
 * server picked once the result is known:
 *
 * <pre>{@code
 * DisplayKeyframe here = reel.pose();
 * reel.then(DisplayMotion.builder().life(2500)
 *         .from(here.x(), here.y(), here.z()).to(here.x(), here.y(), here.z())
 *         .rotation(here.rotation()).spin(Rotation.Axis.Z, turnsLeft)
 *         .ease(DisplayMotion.Easing.QUART_OUT)
 *         .build());
 * }</pre>
 *
 * <p>All of these are safe from any thread. A handle that cannot do them (one a
 * test wrote, or one from before 1.272.0) ignores them.
 *
 * @since 1.85.0
 */
public interface DisplayHandle {

    /**
     * Removes it now, rather than when its life is up.
     *
     * <p>Safe from any thread and safe to call twice.
     */
    void remove();

    /** Whether it is still on somebody's screen. */
    boolean isShowing();

    /**
     * Takes it back to where it started, smoothly, and then removes it.
     *
     * <p>For something cut off in the middle of its motion that should not
     * simply vanish: a body stopped halfway through a dance stands back up in
     * the time given, and is then gone. Safe from any thread; a display that
     * cannot do it is removed at once.
     *
     * @param millis how long the way back takes
     * @since 1.262.0
     */
    default void settle(long millis) {
        remove();
    }

    /**
     * What it draws now, or {@code null} when it is gone or cannot say.
     *
     * @since 1.272.0
     */
    default @Nullable DisplayModel model() {
        return null;
    }

    /**
     * Draws another model from now on, without a respawn.
     *
     * <p>Sent as metadata, so the motion carries on untouched. The kind must be
     * the same &mdash; an item display cannot become a block &mdash; and a
     * model of another kind is ignored.
     *
     * @param model what it draws from now on
     * @since 1.272.0
     */
    default void restyle(@NotNull DisplayModel model) {
    }

    /**
     * Shows another item, for an item display. The cosmic orb's next face.
     *
     * @param item what it shows from now on
     * @since 1.272.0
     */
    default void showing(@NotNull ItemStack item) {
        DisplayModel now = model();
        if (now != null && now.kind() == DisplayModel.Kind.ITEM) {
            restyle(now.showing(item));
        }
    }

    /**
     * Shows another line, for a text display.
     *
     * @param text the line from now on, for everyone
     * @since 1.272.0
     */
    default void text(@NotNull Component text) {
        DisplayModel now = model();
        if (now != null && now.kind() == DisplayModel.Kind.TEXT) {
            restyle(now.withText(text));
        }
    }

    /**
     * Shows another line per viewer, for a text display; see
     * {@link DisplayModel#text(Function)}.
     *
     * @param text the line for one viewer
     * @since 1.272.0
     */
    default void text(@NotNull Function<Player, Component> text) {
        DisplayModel now = model();
        if (now != null && now.kind() == DisplayModel.Kind.TEXT) {
            restyle(now.withText(text));
        }
    }

    /**
     * Turns the outline on in a colour, or off.
     *
     * @param rgb {@code 0xRRGGBB}, or a negative number for no outline
     * @since 1.272.0
     */
    default void glow(int rgb) {
        DisplayModel now = model();
        if (now != null) {
            restyle(now.glow(rgb));
        }
    }

    /**
     * Fixes its light level, or gives it back to the world.
     *
     * @param level 0 to 15, or a negative number for the world's light
     * @since 1.272.0
     */
    default void light(int level) {
        DisplayModel now = model();
        if (now != null) {
            restyle(now.light(level));
        }
    }

    /**
     * Where it is on screen right now, between the two poses the client is
     * drawing between.
     *
     * <p>Offsets are relative to where it was spawned, like every pose; the
     * time is how long it has been playing its current motion. Exact to the
     * pose the client was told, not to the frame it last drew: a client a
     * tick behind is a tick behind.
     *
     * @return the pose, or {@code null} when it is gone or cannot say
     * @since 1.272.0
     */
    default @Nullable DisplayKeyframe pose() {
        return null;
    }

    /**
     * Throws away what is left of its motion and plays another from where it
     * is right now.
     *
     * <p>The new motion's first pose is replaced by {@link #pose()}, so the
     * client glides on from exactly where it was; build the motion from that
     * pose and nothing jumps. Its clock, loop and life start now: the display
     * lasts the new motion's life from this moment.
     *
     * @param next the motion to play from here
     * @since 1.272.0
     */
    default void then(@NotNull DisplayMotion next) {
    }

    /**
     * Glides from where it is to one pose.
     *
     * <p>{@link #then} with a motion from {@link #pose()} to {@code target},
     * except the life left is kept when it is longer than the glide, so a reel
     * that lands stays on screen as long as it was going to. The rotation takes
     * the shorter arc; for a wheel that must turn several more times, build the
     * motion with a spin and use {@link #then}.
     *
     * @param target where it ends; its time is ignored
     * @param millis how long the glide takes
     * @param easing how the glide is spread over that time
     * @since 1.272.0
     */
    default void moveTo(@NotNull DisplayKeyframe target, long millis,
                        @NotNull DisplayMotion.Easing easing) {
    }
}
