package net.exylia.lib.screen.kit;

import net.exylia.lib.screen.Motion;
import net.exylia.lib.screen.ScreenScene;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleUnaryOperator;

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
        double from = angle;
        double wide = radius;
        // Out-cubic: it spins down like something heavy, and the last steps barely move.
        return spin(f -> from + (to - from) * (1 - Math.pow(1 - f, 3)), f -> wide + (shrinkTo - wide) * f * f,
                ticks, stepTicks, eachStep, landed);
    }

    /**
     * Runs it along a path of the caller's: for a game whose own settings say how it brakes and where it stops.
     *
     * @param angle  the angle at each share of the time, 0 to 1
     * @param radius the radius at each share of the time
     * @return the timeline, to cancel it with the round
     * @since 1.543.0
     */
    public @NotNull ScreenScene.Timeline spin(@NotNull DoubleUnaryOperator angle, @NotNull DoubleUnaryOperator radius,
                                              int ticks, int stepTicks, DoubleConsumer eachStep, Runnable landed) {
        if (spinning != null) spinning.cancel();
        ScreenScene.Timeline run = scene.timeline();
        if (scene.reducedMotion()) {
            this.angle = angle.applyAsDouble(1);
            this.radius = radius.applyAsDouble(1);
            members.forEach(member -> place(member, 0));
            if (landed != null) run.at(1, landed);
            return spinning = run.play();
        }
        int steps = Math.max(1, ticks / Math.max(1, stepTicks));
        for (int step = 1; step <= steps; step++) {
            double f = step / (double) steps;
            double at = angle.applyAsDouble(f);
            double r = radius.applyAsDouble(f);
            int tick = (step - 1) * stepTicks;
            run.at(tick, () -> {
                this.angle = at;
                this.radius = r;
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
