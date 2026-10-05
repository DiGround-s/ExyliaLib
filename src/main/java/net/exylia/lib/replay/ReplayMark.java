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
 * <h2>The five the module draws itself</h2>
 * {@link #SWING}, {@link #HURT}, {@link #EQUIP}, {@link #BLOCK} and
 * {@link #EXPLOSION} are recognised by the playback, which swings the arm,
 * makes the body flinch, changes what it is holding, puts the arena back the
 * way it was and sets off the blast &mdash; none of it asked for. Every other
 * kind &mdash; including {@link #DEATH} and {@link #RESPAWN} &mdash; is handed
 * to whoever asked for the playback, through {@link ReplayPlayback#onMark}, and
 * means whatever that plugin decided it means.
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
     * One block in the arena became something else.
     *
     * <p>Written by {@link ReplayRecorder#block}. The playback shows it to the
     * viewer and nobody else, so the arena a replay is watched in is never
     * actually changed &mdash; which is what lets one be watched in an arena
     * somebody else is about to fight in.
     *
     * <p>Its data is a position and a block, in a shape the module reads back
     * itself, so a plugin should neither build nor read one of these by hand.
     */
    public static final String BLOCK = "block";

    /**
     * Something went off.
     *
     * <p>Written by {@link ReplayRecorder#explosion}. The blocks it took out
     * are their own {@link #BLOCK} marks; this is the flash, the smoke and the
     * bang, which without it is a crater that appears in silence.
     */
    public static final String EXPLOSION = "explosion";

    /**
     * The arena went back to how it started.
     *
     * <p>Written by {@link ReplayRecorder#reset()}. A match played over several
     * rounds pastes its arena fresh between them, and a plugin that regenerates
     * mid-match does the same &mdash; neither of which fires a single block
     * event, so without this the replay keeps every bridge and crater from the
     * round before stacked on top of the new one.
     *
     * <p>The playback puts every block it has drawn back and starts collecting
     * again from here, which is also what a seek across one does.
     */
    public static final String RESET = "reset";

    /**
     * One player hit something.
     *
     * <p>Written by the module from the server's own damage event, against the
     * attacker. It carries who was hit and how &mdash; a critical, a sweep, a
     * sprint knockback, a full or a weak swing, a hit taken on a shield &mdash;
     * which is what the playback turns into the same sounds and particles the
     * game itself makes for that hit.
     *
     * @since 1.241.0
     */
    public static final String ATTACK = "attack";

    /**
     * A totem of undying went off. Drawn by the module as the game draws it.
     *
     * @since 1.241.0
     */
    public static final String TOTEM = "totem";

    /**
     * Somebody was teleported rather than walking there: a pearl, a command, a
     * portal. Its text is the cause, as Bukkit names it.
     *
     * @since 1.241.0
     */
    public static final String TELEPORT = "teleport";

    /**
     * Somebody said something in chat. Its text is the message, as plain text.
     *
     * <p>Recorded only by the black box; handed to the plugin, which decides
     * whether and how a viewer reads it.
     *
     * @since 1.241.0
     */
    public static final String CHAT = "chat";

    /**
     * Somebody got on something: a horse, a boat, a minecart, another player.
     * Drawn by the module.
     *
     * @since 1.241.0
     */
    public static final String MOUNT = "mount";

    /** Somebody got off. Drawn by the module. @since 1.241.0 */
    public static final String DISMOUNT = "dismount";

    /**
     * Somebody picked an item up off the ground. Drawn by the module as the item
     * flying into them.
     *
     * @since 1.241.0
     */
    public static final String PICKUP = "pickup";

    /**
     * A block is being mined: the cracks on it. Drawn by the module.
     *
     * @since 1.241.0
     */
    public static final String BREAKING = "breaking";

    /**
     * Somebody's shield was knocked out by an axe. Drawn by the module.
     *
     * @since 1.241.0
     */
    public static final String SHIELD_DISABLED = "shield_disabled";

    /**
     * Somebody's ping, in milliseconds, as text. Written once a second by the
     * black box and by a recorder, for every player they follow, so a replay
     * can say whether the person who died was lagging.
     *
     * @since 1.241.0
     */
    public static final String PING = "ping";

    /**
     * How the server was doing: {@code "tps;mspt"} as text, written once a
     * second by the black box. Belongs to nobody.
     *
     * @since 1.241.0
     */
    public static final String SERVER = "server";

    /**
     * Somebody left the server. Its text is why, as Paper names it:
     * {@code DISCONNECTED}, {@code KICKED}, {@code TIMED_OUT},
     * {@code ERRONEOUS_STATE}.
     *
     * @since 1.241.0
     */
    public static final String QUIT = "quit";

    /**
     * A sound the server played there, exactly as it sent it.
     *
     * <p>Written by the library from the server's own sound packets, so a pearl
     * sounds like a pearl and a wind charge like a wind charge. A place, with
     * {@code key|category|volume|pitch} beside it.
     *
     * @since 1.243.0
     */
    public static final String SOUND = "sound";

    /**
     * An explosion as the server sent it: its own particle and its own sound.
     * A place, with {@code particle|sound} beside it. A recording that has
     * these draws them instead of guessing from {@link #EXPLOSION}.
     *
     * @since 1.243.0
     */
    public static final String BLAST = "blast";

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

    /**
     * The other actor a module mark names: who an {@link #ATTACK} hit, what a
     * {@link #MOUNT} got on, which item a {@link #PICKUP} took.
     *
     * @return their id, or {@code null} for any other kind
     * @since 1.241.0
     */
    public @Nullable UUID other() {
        return switch (kind) {
            case ATTACK -> net.exylia.lib.replay.internal.MarkData.attackVictim(data);
            case MOUNT, PICKUP -> net.exylia.lib.replay.internal.MarkData.other(data);
            default -> null;
        };
    }
}
