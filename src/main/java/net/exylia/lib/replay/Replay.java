package net.exylia.lib.replay;

import net.exylia.lib.replay.internal.MotionTrack;
import net.exylia.lib.replay.internal.ReplayCodec;
import net.exylia.lib.replay.internal.TerrainSection;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * A finished recording: everybody who was in it, where each of them was on
 * every tick, and the things that happened along the way.
 *
 * <p>Immutable and detached from the server it was made on. It holds no
 * players, no world and no location &mdash; every position in it is relative to
 * the anchor the recording was made against, so the same file plays back in any
 * arena, including one pasted somewhere else entirely.
 *
 * <h2>Keeping one</h2>
 * The module does not decide where recordings live. {@link #toBytes()} gives a
 * compressed blob and {@link #from(byte[])} reads one back, which goes in a
 * column, a document, or a file:
 *
 * <pre>{@code
 * byte[] blob = replay.toBytes();
 * repository.save(new DuelReplay(duelId, blob));
 *
 * Replay again = Replay.from(row.blob());
 * replays.play(again, arena.spawn(), List.of(viewer));
 * }</pre>
 *
 * <p>A three minute duel between two players is about twenty kilobytes. What
 * makes that true is that the frames are delta-encoded before being compressed,
 * so a player standing still costs almost nothing and one sprinting costs two
 * bytes an axis.
 *
 * @since 1.175.0
 */
public final class Replay {

    /** Ticks a second, which is what the frames are sampled at. */
    private static final int TICKS_PER_SECOND = 20;

    private final UUID id;
    private final long createdAt;
    private final int frames;
    private final List<ReplayActor> actors;
    private final List<MotionTrack> tracks;
    private final List<ReplayMark> marks;
    private final List<ReplayScene> scenes;
    private final @Nullable List<TerrainSection> terrain;
    /** {@link #hits()}, worked out the first time they are asked for: the recording never changes. */
    private volatile @Nullable List<ReplayHit> hits;

    /** Built by the recorder and by the codec, never by a caller. */
    @ApiStatus.Internal
    public Replay(@NotNull UUID id, long createdAt, int frames,
                  @NotNull List<ReplayActor> actors, @NotNull List<MotionTrack> tracks,
                  @NotNull List<ReplayMark> marks, @NotNull List<ReplayScene> scenes,
                  @Nullable List<TerrainSection> terrain) {
        this.id = id;
        this.createdAt = createdAt;
        this.frames = frames;
        this.actors = List.copyOf(actors);
        this.tracks = List.copyOf(tracks);
        this.marks = List.copyOf(marks);
        this.scenes = scenes.isEmpty()
                ? List.of(new ReplayScene(0, null, 0, 0, 0)) : List.copyOf(scenes);
        this.terrain = terrain == null ? null : List.copyOf(terrain);
    }

    /**
     * Reads a recording back from what {@link #toBytes()} gave.
     *
     * @param bytes the blob
     * @return the recording
     * @throws IllegalArgumentException when the blob is not one of these, or
     *         was written by a newer version of the library than this one
     */
    public static @NotNull Replay from(byte @NotNull [] bytes) {
        return ReplayCodec.read(bytes, null);
    }

    /**
     * Reads back a recording whose terrain was written to a chunk store by
     * {@link #toBytes(ReplayChunks)}.
     *
     * <p>Reads the store on the calling thread, so call it off the server's.
     *
     * @param bytes  the blob
     * @param chunks where its terrain was put
     * @return the recording, with as much of its terrain as the store still has
     * @since 1.241.0
     */
    public static @NotNull Replay from(byte @NotNull [] bytes, @NotNull ReplayChunks chunks) {
        return ReplayCodec.read(bytes, chunks);
    }

    /**
     * Writes it out, compressed, for storing or sending.
     *
     * @return the blob
     */
    public byte @NotNull [] toBytes() {
        return ReplayCodec.write(this, null);
    }

    /**
     * Writes it out with its terrain kept apart.
     *
     * <p>The terrain is most of a black-box recording, and most of it is the
     * same ground recorded again: a spawn where people die every few minutes.
     * Each piece of it goes to the store under a key made from its own
     * content, so a piece already there is not stored a second time, and the
     * blob carries only the keys.
     *
     * <p>Writes to the store on the calling thread, so call it off the server's.
     *
     * @param chunks where the terrain goes
     * @return the blob, without the terrain in it
     * @since 1.241.0
     */
    public byte @NotNull [] toBytes(@NotNull ReplayChunks chunks) {
        return ReplayCodec.write(this, chunks);
    }

    /** Its own id, which is not any player's. */
    public @NotNull UUID id() {
        return id;
    }

    /** When it was recorded, in milliseconds since the epoch. */
    public long createdAt() {
        return createdAt;
    }

    /** How many ticks long it is. */
    public int frames() {
        return frames;
    }

    /** How long it runs for, in milliseconds. */
    public long durationMillis() {
        return frames * 1000L / TICKS_PER_SECOND;
    }

    /** Everybody who was recorded, in the order they were followed. */
    public @NotNull List<ReplayActor> actors() {
        return actors;
    }

    /**
     * The places it happened in, in order. A duel has one; a recording that
     * followed somebody through a teleport has one for each side of it.
     *
     * @since 1.241.0
     */
    public @NotNull List<ReplayScene> scenes() {
        return scenes;
    }

    /**
     * Which scene a tick belongs to.
     *
     * @param tick the tick
     * @return its index in {@link #scenes()}
     * @since 1.241.0
     */
    public int sceneAt(int tick) {
        int found = 0;
        for (int index = 1; index < scenes.size(); index++) {
            if (scenes.get(index).fromTick() <= tick) found = index;
            else break;
        }
        return found;
    }

    /**
     * Whether it carries the ground it happened on, which is what lets it be
     * played back somewhere that ground is not.
     *
     * @since 1.241.0
     */
    public boolean hasTerrain() {
        return terrain != null && !terrain.isEmpty();
    }

    /** The ground, by section; {@code null} when it carries none. */
    @ApiStatus.Internal
    public @Nullable List<TerrainSection> terrain() {
        return terrain;
    }

    /** Everything that happened, in the order it happened. */
    public @NotNull List<ReplayMark> marks() {
        return marks;
    }

    /**
     * Finds somebody by their UUID.
     *
     * @param actor whose
     * @return their identity, or {@code null} when they are not in it
     */
    public @Nullable ReplayActor actor(@NotNull UUID actor) {
        int index = indexOf(actor);
        return index < 0 ? null : actors.get(index);
    }

    /**
     * Where somebody was on one tick.
     *
     * <p>Relative to the anchor, so putting it somewhere is adding it to one.
     * This is what a first-person camera reads: teleport the viewer to
     * {@code anchor.add(frame.x(), frame.y(), frame.z())} each tick, with the
     * frame's own yaw and pitch, and they are looking out of the recording.
     *
     * @param tick  which tick, from zero
     * @param actor whose
     * @return their frame, or {@link ReplayFrame#ABSENT} when they were not
     *         there or the tick is outside the recording
     */
    public @NotNull ReplayFrame at(int tick, @NotNull UUID actor) {
        int index = indexOf(actor);
        if (index < 0 || tick < 0 || tick >= frames) {
            return ReplayFrame.ABSENT;
        }
        return tracks.get(index).frame(tick);
    }

    /**
     * The newest mark of one kind on or before a tick: what somebody's ping
     * was, how the server was doing, at that moment of the recording.
     *
     * @param kind  which kind
     * @param actor whose, or {@code null} for a mark that belongs to nobody
     * @param tick  the tick
     * @return the mark, or {@code null} when there is none yet
     * @since 1.241.0
     */
    public @Nullable ReplayMark last(@NotNull String kind, @Nullable UUID actor, int tick) {
        int low = 0;
        int high = marks.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (marks.get(middle).tick() <= tick) low = middle + 1;
            else high = middle;
        }
        for (int index = low - 1; index >= 0; index--) {
            ReplayMark mark = marks.get(index);
            if (mark.kind().equals(kind) && java.util.Objects.equals(mark.actor(), actor)) return mark;
        }
        return null;
    }

    /**
     * Every attack in it, in order, with what it looked like from both ends:
     * reach by the server's positions and by the attacker's own screen, how far
     * off the look was, the turn just before it, the seen lag.
     *
     * <p>A black box capture carries these worked out, with the look the
     * client sent with each attack. Anything else (an older recording, a
     * recorder's) is worked out here from its tracks, with the sampled look
     * and the ping's estimate of the lag, which is coarser and says so.
     *
     * @return the hits, attacks the server never counted included
     * @since 1.403.0
     */
    public @NotNull List<ReplayHit> hits() {
        List<ReplayHit> known = hits;
        if (known == null) {
            known = List.copyOf(net.exylia.lib.replay.internal.HitAnalysis.hits(this));
            hits = known;
        }
        return known;
    }

    /**
     * The hit one {@link ReplayMark#ATTACK} mark of this recording is: what a
     * playback listener reads when it wants to say something about the attack
     * it was just handed.
     *
     * @param attack the mark
     * @return its hit, or {@code null} for a mark that is not an attack of this
     *         recording
     * @since 1.404.0
     */
    public @Nullable ReplayHit hit(@NotNull ReplayMark attack) {
        if (!ReplayMark.ATTACK.equals(attack.kind()) || attack.actor() == null) return null;
        UUID victim = attack.other();
        if (victim == null) return null;
        // The hits are the attack marks in order, one each: the mark's place
        // among them is its hit, and two on the same tick stay apart.
        // A mark that is not this recording's own instance is the first of its tick.
        int nth = 0;
        int before = 0;
        for (ReplayMark mark : marks) {
            if (mark.tick() > attack.tick()) break;
            if (mark == attack) {
                nth = before;
                break;
            }
            if (mark.tick() == attack.tick() && mark.kind().equals(ReplayMark.ATTACK)
                    && attack.actor().equals(mark.actor()) && victim.equals(mark.other())) before++;
        }
        for (ReplayHit hit : hits()) {
            if (hit.tick() > attack.tick()) break;
            if (hit.tick() == attack.tick() && hit.attacker().equals(attack.actor()) && hit.target().equals(victim)
                    && nth-- == 0) return hit;
        }
        return null;
    }

    /**
     * How many hits the server counted from one attacker on one target up to
     * and including this one, and the damage they did.
     *
     * <p>Worked out from the recording rather than kept by whoever asks, so it
     * is right after any seek, backwards or forwards. An attack the server
     * never counted adds nothing, and is told the tally before it.
     *
     * @param hit one of {@link #hits()}
     * @return the tally
     * @since 1.404.0
     */
    public @NotNull ReplayHit.Tally tally(@NotNull ReplayHit hit) {
        int number = 0;
        double damage = 0;
        for (ReplayHit other : hits()) {
            if (other.tick() > hit.tick()) break;
            boolean counted = other.confirmed() && other.attacker().equals(hit.attacker())
                    && other.target().equals(hit.target());
            if (counted) {
                number++;
                if (!Double.isNaN(other.damage())) damage += other.damage();
            }
            if (other == hit) break;
        }
        return new ReplayHit.Tally(number, damage);
    }

    /**
     * Every anticheat flag in it, in order: who, when, which check, what it
     * said and how many times in that second.
     *
     * @return the flags; empty for a recording made without any
     * @since 1.404.0
     */
    public @NotNull List<ReplayFlag> flags() {
        List<ReplayFlag> flags = new java.util.ArrayList<>();
        for (ReplayMark mark : marks) {
            ReplayFlag flag = ReplayFlag.of(mark);
            if (flag != null) flags.add(flag);
        }
        return flags;
    }

    /** The tracks, in the same order as {@link #actors()}. */
    @ApiStatus.Internal
    public @NotNull List<MotionTrack> tracks() {
        return tracks;
    }

    private int indexOf(UUID actor) {
        for (int index = 0; index < actors.size(); index++) {
            if (actors.get(index).id().equals(actor)) {
                return index;
            }
        }
        return -1;
    }
}
