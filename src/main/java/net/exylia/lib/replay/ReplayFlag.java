package net.exylia.lib.replay;

import net.exylia.lib.replay.internal.MarkData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * An anticheat flagged somebody, on one moment of a recording.
 *
 * <p>Written by {@link Replays#flag} into the black box, and by the module
 * itself for Grim, Vulcan, Spartan and Matrix. Flags of the same check on the
 * same player within a second are one of these with a {@link #count}, so a
 * check that fires every tick for a second is one line, not twenty.
 *
 * @param player who was flagged
 * @param tick   the tick of the recording the first of them landed on
 * @param check  the check, as the anticheat names it
 * @param detail what the anticheat said beside it, or {@code null}
 * @param count  how many times it went off in that second, at least one
 * @since 1.404.0
 */
public record ReplayFlag(@NotNull UUID player, int tick, @NotNull String check, @Nullable String detail,
                         int count) {

    /**
     * Reads a {@link ReplayMark#FLAG} mark, the way a playback hands one to
     * {@link ReplayPlayback#onMark}.
     *
     * @param mark the mark
     * @return the flag, or {@code null} for a mark of any other kind
     */
    public static @Nullable ReplayFlag of(@NotNull ReplayMark mark) {
        if (!ReplayMark.FLAG.equals(mark.kind()) || mark.actor() == null) return null;
        String[] read = MarkData.flag(mark.data());
        if (read == null) return null;
        return new ReplayFlag(mark.actor(), mark.tick(), read[0], read[1], Integer.parseInt(read[2]));
    }
}
