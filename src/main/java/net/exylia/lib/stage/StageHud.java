package net.exylia.lib.stage;

import net.exylia.lib.effect.Display;
import net.exylia.lib.effect.PluginEffects;
import net.exylia.lib.text.Text;
import net.exylia.lib.text.Values;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * The clock and boss bars a timed screen shows: one deadline at a time, one bar per seat.
 *
 * <pre>{@code
 * StageHud hud = new StageHud(Effects.of(this), seats);
 * hud.clock(30_000);
 * hud.bar(turn, player, messages.yourTurn(), 30_000, "PURPLE");
 *
 * // from the screen's own tick
 * if (hud.expired()) playForThem();
 * else if (hud.ticked()) stage.figures(StageHud.line(messages.timeLeft(), Values.of("time", hud.secondsLeft())));
 * ...
 * hud.stopAll();
 * }</pre>
 *
 * <p>Every timed screen counts down the same way, so the countdown, the bars and the "has a second
 * gone by" question that drives header redraws live here once. There is no task: the clock is read
 * when asked. Used from one thread at a time, like the stage it belongs to; the bars go when the
 * plugin is disabled.
 *
 * @since 1.356.0
 */
public final class StageHud {

    private final PluginEffects effects;
    private final Display[] bars;
    private final LongSupplier clock;
    private long deadline;
    private long shownSeconds = -1;

    /**
     * @param effects the plugin's effects, which own the bars
     * @param seats   how many seats can be shown a bar, numbered from 0
     */
    public StageHud(@NotNull PluginEffects effects, int seats) {
        this(effects, seats, System::currentTimeMillis);
    }

    /** With its clock handed in, for tests. */
    StageHud(PluginEffects effects, int seats, LongSupplier clock) {
        this.effects = effects;
        this.bars = new Display[seats];
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Starts a new countdown. */
    public void clock(long millis) {
        deadline = clock.getAsLong() + millis;
        shownSeconds = -1;
    }

    public boolean expired() {
        return clock.getAsLong() >= deadline;
    }

    /** The whole seconds left, rounded up: what a countdown reads. */
    public long secondsLeft() {
        return Math.max(0, (deadline - clock.getAsLong() + 999) / 1000);
    }

    /** Whether the seconds left changed since the last call: the cue to redraw a header. */
    public boolean ticked() {
        long seconds = secondsLeft();
        if (seconds == shownSeconds) return false;
        shownSeconds = seconds;
        return true;
    }

    /**
     * Shows a seat a counting-down boss bar, replacing the one it had.
     *
     * @param line   the bar's text, in Exylia text notation
     * @param colour a boss bar colour name ({@code PURPLE}, {@code RED}...)
     */
    public void bar(int seat, @Nullable Player player, String line, long millis, String colour) {
        stop(seat);
        if (player == null) return;
        bars[seat] = effects.bossBar(line)
                .countdown(millis / 1000.0)
                .colour(colour)
                .show(player);
    }

    public void stop(int seat) {
        if (bars[seat] != null) {
            bars[seat].stop();
            bars[seat] = null;
        }
    }

    public void stopAll() {
        for (int seat = 0; seat < bars.length; seat++) stop(seat);
    }

    /** Several components on one line, with a gap between them. */
    public static @NotNull Component row(Component... parts) {
        var builder = Component.text();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) builder.append(Component.text("   "));
            builder.append(parts[i]);
        }
        return builder.build();
    }

    /**
     * A message line as a component, placeholders filled: palette tokens, legacy codes and
     * MiniMessage all read. No {@code %prefix%}: nothing drawn on a stage carries one.
     *
     * @param values what it is about, or {@code null}
     */
    public static @NotNull Component line(@Nullable String line, @Nullable Values values) {
        Text text = Text.of(line == null ? "" : line);
        return (values == null ? text : values.applyTo(text)).build();
    }

    /** Several components, one per line. */
    public static @NotNull Component lines(Component... lines) {
        var builder = Component.text();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) builder.append(Component.newline());
            builder.append(lines[i]);
        }
        return builder.build();
    }
}
