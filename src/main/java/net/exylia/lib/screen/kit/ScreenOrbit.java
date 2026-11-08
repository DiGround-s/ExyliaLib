package net.exylia.lib.screen.kit;

import net.exylia.lib.screen.Motion;
import net.exylia.lib.screen.ScreenScene;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;

/**
 * Sprites going round a centre and slowing to a chosen angle: a wheel's segments, or a roulette's ball round a fixed
 * ring of numbers. The sprites stay upright, so words and items on them stay readable at any angle. An angle is in
 * degrees, 0 at the top, growing clockwise.
 *
 * <pre>{@code
 * ScreenOrbit wheel = new ScreenOrbit(board, 95, 95, 70);
 * for (int i = 0; i < 16; i++) wheel.add(board.sprite("seg" + i).rect(28, 18, colour(i)), i * 22.5, 28, 18);
 * wheel.spin(wheel.landing(winner * 22.5, 3), 70, 3, 70, angle -> tick(angle), this::landed);
 * }</pre>
 *
 * Each step is one transform per display of every sprite on it, a step every {@code stepTicks}: mind the screen's motion
 * ceiling (a ball is one, a wheel of 16 labelled segments is 32).
 *
 * @since 1.532.0
 */
public final class ScreenOrbit {

    private record Member(ScreenScene.Sprite sprite, double offset, double width, double height) {
    }

    private final ScreenScene scene;
    private final double centreX, centreY;
    private final List<Member> members = new ArrayList<>();
    private double radius;
    private double angle;
    private ScreenScene.Timeline spinning;

    public ScreenOrbit(@NotNull ScreenScene scene, double centreX, double centreY, double radius) {
        this.scene = scene;
        this.centreX = centreX;
        this.centreY = centreY;
        this.radius = radius;
    }

    /** Puts a sprite on the orbit, {@code offset} degrees round from its first place, sized {@code width} by {@code height}. */
    public @NotNull ScreenOrbit add(@NotNull ScreenScene.Sprite sprite, double offset, double width, double height) {
        Member member = new Member(sprite, offset, width, height);
        members.add(member);
        place(member, 0);
        return this;
    }

    /** Where the orbit stands now. */
    public double angle() {
        return angle;
    }

    /** Sets it at {@code angle} at once. */
    public void set(double angle) {
        if (spinning != null) spinning.cancel();
        this.angle = angle;
        members.forEach(member -> place(member, 0));
    }

    /**
     * The angle that brings a sprite at {@code offset} to the top after {@code turns} more whole turns: pass it to
     * {@link #spin}.
     */
    public double landing(double offset, int turns) {
        double wanted = -offset;
        double now = angle % 360;
        double more = ((wanted - now) % 360 + 360) % 360;
        return angle + more + 360.0 * Math.max(0, turns);
    }

    /**
     * Turns it to {@code to}, fast at first and slowing to a stop, over {@code ticks}.
     *
     * @param stepTicks  ticks between two transforms: longer is cheaper, and at speed cuts the circle's corners more
     * @param shrinkTo   the radius it ends on, for a ball dropping into its pocket; pass the radius to keep it
     * @param eachStep   told the angle of each step (a tick as a segment passes), {@code null} for nothing
     * @param landed     runs as it stops, {@code null} for nothing
     * @return the timeline, to cancel it with the round
     */
    public @NotNull ScreenScene.Timeline spin(double to, int ticks, int stepTicks, double shrinkTo, DoubleConsumer eachStep, Runnable landed) {
        if (spinning != null) spinning.cancel();
        ScreenScene.Timeline run = scene.timeline();
        if (scene.reducedMotion()) {
            angle = to;
            radius = shrinkTo;
            members.forEach(member -> place(member, 0));
            if (landed != null) run.at(1, landed);
            return spinning = run.play();
        }
        double from = angle;
        double wide = radius;
        int steps = Math.max(1, ticks / Math.max(1, stepTicks));
        for (int step = 1; step <= steps; step++) {
            double f = step / (double) steps;
            // Out-cubic: it spins down like something heavy, and the last steps barely move.
            double eased = 1 - Math.pow(1 - f, 3);
            double at = from + (to - from) * eased;
            double r = wide + (shrinkTo - wide) * f * f;
            int tick = (step - 1) * stepTicks;
            run.at(tick, () -> {
                angle = at;
                radius = r;
                members.forEach(member -> place(member, stepTicks));
                if (eachStep != null) eachStep.accept(at);
            });
        }
        if (landed != null) run.at(steps * stepTicks, landed);
        return spinning = run.play();
    }

    private void place(Member member, int ticks) {
        double turn = Math.toRadians(angle + member.offset());
        member.sprite().size(member.width(), member.height()).move(centreX + Math.sin(turn) * radius - member.width() / 2,
                centreY - Math.cos(turn) * radius - member.height() / 2, ticks, Motion.Ease.LINEAR);
    }
}
