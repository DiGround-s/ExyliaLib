package net.exylia.lib.effect.internal;

import net.exylia.lib.effect.Timer;
import net.exylia.lib.util.CooldownScope;
import net.exylia.lib.util.Cooldowns;

/**
 * A {@link Timer} that reads a cooldown instead of counting on its own.
 *
 * <h2>Why this is a view and not a merge</h2>
 * A cooldown and a timer look alike and are not the same thing. A cooldown is
 * an expiry instant: nothing moves it, it is compared when read, and it cannot
 * be paused because nothing is advancing it in the first place. A timer is a
 * value something else drives, which is exactly what lets it be paused, run
 * backwards, or count up forever.
 *
 * <p>Making one out of the other would cost the pause and the count-up, so
 * this reads a cooldown rather than replacing it. The cooldown stays the truth
 * — shared between plugins, surviving a restart — and the display just looks
 * at it.
 *
 * <p>Consequently {@link #advance} and {@link #extend} do nothing here: a
 * display cannot move a clock it does not own. Extending the cooldown is done
 * through {@link Cooldowns}, and this will show it.
 */
public final class CooldownTimer implements Timer {

    private final CooldownScope scope;
    private final String key;

    /**
     * What the bar measures itself against.
     *
     * <p>Taken when the timer is created rather than from the cooldown, which
     * only knows what is left, not how long it was. A bar with nothing to
     * measure against cannot fill or empty.
     */
    private final double total;

    public CooldownTimer(CooldownScope scope, String key) {
        this.scope = scope;
        this.key = key;
        this.total = Cooldowns.remainingSeconds(scope, key);
    }

    public CooldownTimer(CooldownScope scope, String key, double totalSeconds) {
        this.scope = scope;
        this.key = key;
        this.total = Math.max(0, totalSeconds);
    }

    @Override
    public void advance(long ticks) {
        // The cooldown moves itself. Nothing to do.
    }

    @Override
    public void extend(long ticks) {
        // Time is added through Cooldowns, not through the thing watching it.
    }

    @Override
    public double remaining() {
        return Cooldowns.remainingSeconds(scope, key);
    }

    @Override
    public double elapsed() {
        return Math.max(0, total - remaining());
    }

    @Override
    public long remainingTicks() {
        return Cooldowns.remaining(scope, key) / 50L;
    }

    @Override
    public long elapsedTicks() {
        return Math.max(0, (long) (total * 20) - remainingTicks());
    }

    @Override
    public double total() {
        return total;
    }

    @Override
    public float progress() {
        if (total <= 0) {
            return 0f;
        }
        float progress = (float) (remaining() / total);
        return progress < 0f ? 0f : Math.min(progress, 1f);
    }

    @Override
    public boolean finished() {
        return Cooldowns.remaining(scope, key) <= 0;
    }

    @Override
    public boolean isCountdown() {
        return true;
    }

    @Override
    public double displayed() {
        return remaining();
    }
}
