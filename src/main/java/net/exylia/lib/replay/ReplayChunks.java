package net.exylia.lib.replay;

import org.jetbrains.annotations.NotNull;

/**
 * Where the terrain of recordings is kept, apart from the recordings.
 *
 * <p>A recording that carries the ground it happened on is mostly that ground,
 * and most ground is recorded again and again: the same spawn, the same road,
 * the same arena. Each piece of it is handed here under a key made from its own
 * content, so two recordings of the same unchanged place share one copy.
 *
 * <pre>{@code
 * ReplayChunks chunks = new ReplayChunks() {
 *     public void put(String key, byte[] data) { repository.saveIfAbsent(key, data); }
 *     public byte[] get(String key) { return repository.find(key); }
 * };
 * byte[] blob = replay.toBytes(chunks);
 * Replay again = Replay.from(blob, chunks);
 * }</pre>
 *
 * <p>Both methods are called on whatever thread wrote or read the recording,
 * which should never be the server's own.
 *
 * @since 1.241.0
 */
public interface ReplayChunks {

    /**
     * Keeps one piece. A key that is already there holds the same bytes, so it
     * can be ignored.
     *
     * @param key  made from the content, safe as a primary key (40 hex digits)
     * @param data the piece
     */
    void put(@NotNull String key, byte @NotNull [] data);

    /**
     * Gives one piece back.
     *
     * @param key what {@link #put} was given
     * @return the piece, or {@code null} when it is gone; that part of the ground
     *         is then empty in the playback rather than the playback failing
     */
    byte[] get(@NotNull String key);
}
