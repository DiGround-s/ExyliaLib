package net.exylia.lib.stage.kit;

import net.exylia.lib.display.DisplayHandle;
import net.exylia.lib.display.DisplayKeyframe;
import net.exylia.lib.display.DisplayModel;
import net.exylia.lib.display.DisplayMotion;
import net.exylia.lib.display.Rotation;
import net.exylia.lib.effect.Effects;
import net.exylia.lib.stage.Stage;
import net.exylia.lib.stage.StageLook;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The table moves every card and dice screen shares: a label stamped onto the table, tokens thrown on
 * an arc, a sound pitched up as tension builds, and a drumroll that speeds up before a reveal.
 *
 * <p>Everything here is keyframed and handed to the client once, so it moves at the viewer's own
 * frame rate rather than the server's tick.
 *
 * <pre>{@code
 * StageFx.stamp(stage, StageHud.line("{highlight}&l21", null), stage.plane().point(0.6, -0.2, 0.05), 0.4, 0, 0);
 * StageFx.fling(stage, new ItemStack(Material.AMETHYST_SHARD), -0.8, -0.5, 0, 0, 5, 0.25, 0, 450, true);
 * }</pre>
 *
 * @since 1.356.0
 */
public final class StageFx {

    private StageFx() {
        throw new AssertionError("No instances.");
    }

    /**
     * Plays a sound written as {@code NAME|volume|pitch} with its pitch multiplied by {@code factor}:
     * the same click a little higher each step, which is what makes a run of them climb.
     */
    public static void play(Player player, String line, double factor) {
        if (player == null || line == null || line.isBlank()) return;
        Effects.soundFrom(line).pitch(Math.clamp(StageLook.pitch(line) * factor, 0.5, 2.0)).show(player);
    }

    /** A burst of particles at a point of a board, seen by this view's player only. */
    public static void burst(Stage view, String particle, Location at, int count, double spread) {
        if (particle == null || particle.isBlank() || count <= 0) return;
        Effects.particle(particle).at(at).count(count).spread(spread, spread, spread * 0.3).speed(0.05).show(view.viewer());
    }

    /**
     * A small label stamped onto the table: it appears large and close, slams down to its size with a
     * small overshoot, and stays. Only into free table: text never wins over the glow outlines of cards
     * and pieces under it. A final verdict goes on {@link Stage#verdict} instead.
     *
     * @param at    where its centre lands
     * @param scale its size once it lands
     * @param delay how long it waits before it comes
     */
    public static DisplayHandle stamp(Stage view, Component text, Location at, double scale, long delay, int background) {
        DisplayModel model = DisplayModel.text(text).billboard("FIXED").light(15).background(background).textShadow(true);
        double height = Stage.TEXT_HEIGHT;
        List<DisplayKeyframe> keys = new ArrayList<>();
        // The text grows from its foot, so every size is lowered by half its growth to keep its middle still.
        keys.add(centred(view, 0, scale, 0.01, 0.3));
        keys.add(centred(view, delay, scale, 0.01, 0.3));
        keys.add(centred(view, delay + 70, scale, scale * 2.3, 0.3));
        keys.add(centred(view, delay + 210, scale, scale * 0.9, 0.0));
        keys.add(centred(view, delay + 330, scale, scale * 1.04, 0.0));
        keys.add(centred(view, delay + 420, scale, scale, 0.0));
        return view.draw(model, DisplayMotion.of(keys, view.life()), at.clone().add(0, -height * scale / 2, 0));
    }

    private static DisplayKeyframe centred(Stage view, long at, double base, double size, double forward) {
        return view.pose(at, 0, -Stage.TEXT_HEIGHT * (size - base) / 2, forward, size, size, size);
    }

    /**
     * Items thrown from one point of a board to another (tokens, counters, pieces): each pops out, flies
     * on an arc towards the player while it spins flat, lands with a little hop and either stays or sinks
     * into the table.
     *
     * @param count  how many, a beat apart
     * @param millis how long one flight takes
     * @param stay   whether they stay where they land for the rest of the game
     */
    public static void fling(Stage view, ItemStack item, double fromAcross, double fromUp, double toAcross, double toUp,
                             int count, double scale, long delay, long millis, boolean stay) {
        if (item == null) return;
        Location from = view.plane().point(fromAcross, fromUp, 0.1);
        double across = toAcross - fromAcross;
        double up = toUp - fromUp;
        long flight = Math.max(200, millis);
        for (int i = 0; i < count; i++) {
            long start = delay + i * 75L;
            // A little scatter, so a handful of them land as a handful and not as one.
            double jitterAcross = (i % 3 - 1) * 0.045;
            double jitterUp = (i / 3) * 0.035;
            double toX = across + jitterAcross;
            double toY = up + jitterUp;
            List<DisplayKeyframe> keys = new ArrayList<>();
            keys.add(flungPose(view, 0, 0, 0, 0, 0, 0.01));
            keys.add(flungPose(view, start, 0, 0, 0, 0, 0.01));
            keys.add(flungPose(view, start + 70, 0, 0.03, 0.04, 0.4, scale * 1.1));
            keys.add(flungPose(view, start + flight * 45 / 100, toX * 0.5, toY * 0.5 + 0.22, 0.2, 1.3, scale * 1.15));
            keys.add(flungPose(view, start + flight * 85 / 100, toX, toY + 0.03, 0.04, 2.1, scale));
            keys.add(flungPose(view, start + flight, toX, toY, 0, 2.4, scale * 0.92));
            keys.add(flungPose(view, start + flight + 90, toX, toY + 0.025, 0, 2.45, scale));
            keys.add(flungPose(view, start + flight + 160, toX, toY, 0, 2.5, scale));
            long life = view.life();
            if (!stay) {
                keys.add(flungPose(view, start + flight + 500, toX, toY, 0, 2.5, scale));
                keys.add(flungPose(view, start + flight + 700, toX, toY, -0.05, 2.5, 0.01));
                life = start + flight + 720;
            }
            view.draw(DisplayModel.item(item).light(15), DisplayMotion.spline(keys, life), from);
        }
    }

    /** A flung item's pose: where it is on the board, and how far it has turned flat in its own face. */
    private static DisplayKeyframe flungPose(Stage view, long at, double across, double up, double forward,
                                            double turns, double scale) {
        float[] offset = view.facing().apply(new float[]{(float) across, (float) up, (float) forward});
        Rotation spun = Rotation.around(Rotation.Axis.Z, turns * Math.PI * 2 * 0.4).then(view.facing());
        float size = (float) scale;
        return new DisplayKeyframe(at, offset[0], offset[1], offset[2], spun, size, size, size);
    }

    /**
     * A drumroll: beats that come closer together and climb in pitch over a stretch of time, polled
     * from a round's tick. The last beat lands just before the stretch ends, where the reveal goes.
     */
    public static final class Drumroll {

        private final long start;
        private final long millis;
        private final int beats;
        private int played;

        public Drumroll(long start, long millis, int beats) {
            this.start = start;
            this.millis = Math.max(1, millis);
            this.beats = Math.max(1, beats);
        }

        /**
         * The pitch factor of a beat due by {@code now}, from 0.8 to 1.6, or {@code 0} when none is.
         * Beats fall at {@code (k / beats) ^ 0.6} of the stretch: wide apart first, tight at the end.
         */
        public double poll(long now) {
            if (played >= beats) return 0;
            double due = Math.pow((double) played / beats, 0.6) * millis * 0.92;
            if (now - start < due) return 0;
            double pitch = 0.8 + 0.8 * played / Math.max(1, beats - 1);
            played++;
            return pitch;
        }
    }
}
