package net.exylia.lib.replay;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Something that happened at one moment, rather than something that was true
 * for a stretch of them.
 *
 * <p>Where a player was is sampled every tick and belongs in the motion track.
 * A swing, a hit, a death, a sword being put away &mdash; those happen once,
 * and a track that carried them would spend twenty frames a second saying that
 * nothing had happened yet. They are kept here instead, as a list the playback
 * walks alongside the frames.
 *
 * <h2>The three the module draws itself</h2>
 * {@link #SWING}, {@link #HURT} and {@link #EQUIP} are recognised by the
 * playback, which swings the arm, makes the body flinch and changes what it is
 * holding without being asked. Every other kind &mdash; including
 * {@link #DEATH} and {@link #RESPAWN} &mdash; is handed to whoever asked for
 * the playback, through {@link ReplayPlayback#onMark}, and means whatever that
 * plugin decided it means.
 *
 * <p>Four of them are recorded for you: a swing, a hit, a death and a respawn
 * are read off the server's own events while a recording is running, so a
 * plugin that records a duel gets all four without writing a listener.
 *
 * @param tick  which frame of the recording it happened on
 * @param kind  what happened; one of the constants here, or a plugin's own
 * @param actor who it happened to, or {@code null} when it belongs to nobody
 * @param data  whatever the kind carries, or {@code null}
 * @since 1.175.0
 */
public record ReplayMark(int tick, @NotNull String kind, @Nullable UUID actor,
                         byte @Nullable [] data) {

    /** An arm swung, the way a player swinging at something does. */
    public static final String SWING = "swing";

    /** A hit landed, and the body flinches. */
    public static final String HURT = "hurt";

    /**
     * Somebody died.
     *
     * <p>Written by the module, drawn by nobody: what a death should look like
     * is the plugin's decision &mdash; a ragdoll, an end to the playback, a
     * line on the screen &mdash; and the body itself keeps being recorded for
     * as long as the recording followed them.
     */
    public static final String DEATH = "death";

    /** Somebody came back, after a {@link #DEATH}. */
    public static final String RESPAWN = "respawn";

    /**
     * What somebody is wearing or holding changed.
     *
     * <p>Written by the recorder itself, which compares the six slots every
     * tick and only writes one when something actually moved. Its data is a
     * slot and an item, written in a shape the module reads back itself, so a
     * plugin should neither build nor read one of these by hand.
     */
    public static final String EQUIP = "equip";

    /**
     * One with a line of text behind it, for a plugin's own kinds.
     *
     * @param tick  which frame
     * @param kind  what happened
     * @param actor who it happened to, or {@code null}
     * @param text  the line
     * @return the mark
     */
    public static @NotNull ReplayMark of(int tick, @NotNull String kind, @Nullable UUID actor,
                                         @NotNull String text) {
        return new ReplayMark(tick, kind, actor, text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * What {@link #of} put there.
     *
     * @return the line, or {@code null} when this mark carries no data
     */
    public @Nullable String text() {
        return data == null ? null : new String(data, StandardCharsets.UTF_8);
    }
}
