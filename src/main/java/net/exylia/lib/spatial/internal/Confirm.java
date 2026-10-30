package net.exylia.lib.spatial.internal;

import org.jetbrains.annotations.Nullable;

/**
 * A click that asks before it acts, kept in place: the first {@link #confirm} of a key arms it, a
 * second of the same key within {@link #WINDOW_MILLIS} answers {@code true} and disarms. Anything else
 * the player does disarms it ({@link #disarm}), and so does waiting.
 *
 * <p>Keys name what is confirmed: {@code "<action>"} or {@code "<action>:<subject>"}. A different
 * subject under the same action re-arms rather than confirms, so a price that changed between the two
 * clicks is asked again.
 *
 * <p>Internal: free to change without notice.
 */
public final class Confirm {

    /** How long an armed key waits for its second click. */
    public static final long WINDOW_MILLIS = 6_000L;

    private @Nullable String armed;
    private long until;

    /** {@code true} when {@code key} was armed and still waits: the second click. Otherwise arms it. */
    public boolean confirm(String key, long now) {
        if (key.equals(armed(now))) {
            armed = null;
            return true;
        }
        armed = key;
        until = now + WINDOW_MILLIS;
        return false;
    }

    /** Whether {@code key}, or a subject under it ({@code key:...}), is armed. */
    public boolean confirming(String key, long now) {
        String current = armed(now);
        return current != null && (current.equals(key) || current.startsWith(key + ":"));
    }

    /** The armed key, or {@code null} once it has waited too long. */
    public @Nullable String armed(long now) {
        if (armed != null && now >= until) armed = null;
        return armed;
    }

    /** Whether an armed key ran out since it was armed: the caller redraws its words. */
    public boolean expired(long now) {
        if (armed == null || now < until) return false;
        armed = null;
        return true;
    }

    /** Drops what was armed; {@code true} when something was. */
    public boolean disarm() {
        boolean was = armed != null;
        armed = null;
        return was;
    }
}
