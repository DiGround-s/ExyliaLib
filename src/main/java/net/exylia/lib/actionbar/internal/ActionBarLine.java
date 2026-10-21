package net.exylia.lib.actionbar.internal;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * What one player's action bar last showed, and whether a new line has to be
 * sent.
 *
 * <p>The client fades an action bar after about two seconds, so an unchanged
 * line is sent again once a second; a changed one is sent at once. Going from
 * something to nothing sends one empty line, and nothing after that.
 */
final class ActionBarLine {

    static final long RESEND_AFTER_MS = 1000L;

    private Component last;
    private long sentAt;
    private boolean cleared = true;

    /**
     * Joins the parts shown this refresh.
     *
     * @param parts     every segment's text, in order, without the empty ones
     * @param separator what goes between two of them
     * @return the line, or {@code null} when there is nothing to show
     */
    static @Nullable Component compose(@NotNull List<Component> parts, @NotNull Component separator) {
        if (parts.isEmpty()) {
            return null;
        }
        if (parts.size() == 1) {
            return parts.getFirst();
        }
        return Component.join(JoinConfiguration.separator(separator), parts);
    }

    /**
     * Decides what to send.
     *
     * @param line the line composed this refresh, {@code null} for nothing
     * @param now  the current time in milliseconds
     * @return what to send, {@link Component#empty()} to clear, or {@code null} to send nothing
     */
    @Nullable Component next(@Nullable Component line, long now) {
        if (line == null) {
            if (cleared) {
                return null;
            }
            cleared = true;
            last = null;
            return Component.empty();
        }
        if (line.equals(last) && now - sentAt < RESEND_AFTER_MS) {
            return null;
        }
        cleared = false;
        last = line;
        sentAt = now;
        return line;
    }

    boolean cleared() {
        return cleared;
    }
}
