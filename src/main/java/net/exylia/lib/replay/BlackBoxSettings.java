package net.exylia.lib.replay;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.function.Predicate;

/**
 * How much the black box keeps.
 *
 * @param seconds        how far back a capture can reach; between 10 and 300
 * @param radius         how far around each player things are recorded, in
 *                       blocks; between 8 and 96
 * @param maxEntities    how many things that are not players are kept at once,
 *                       across the whole server
 * @param terrain        whether a capture carries the ground it happened on,
 *                       which is what lets it be watched on another server or
 *                       after the place has changed
 * @param verticalMargin how far above and below where people went the ground
 *                       is kept, in blocks
 * @param maxChunks      the most chunks one capture keeps the ground of; past
 *                       this the oldest part of a long chase is left out
 * @param hidden         players who must never appear in a recording, such as
 *                       staff in vanish; read on every sample, so keep it cheap
 * @since 1.241.0
 */
public record BlackBoxSettings(int seconds, double radius, int maxEntities, boolean terrain,
                               int verticalMargin, int maxChunks,
                               @NotNull Predicate<Player> hidden) {

    public BlackBoxSettings {
        seconds = Math.clamp(seconds, 10, 300);
        radius = Math.clamp(radius, 8.0, 96.0);
        maxEntities = Math.clamp(maxEntities, 0, 20_000);
        verticalMargin = Math.clamp(verticalMargin, 8, 128);
        maxChunks = Math.clamp(maxChunks, 1, 4096);
    }

    /**
     * A minute, thirty-two blocks around everybody, with the ground: what a
     * staff team needs to decide what happened before somebody died.
     */
    public static @NotNull BlackBoxSettings defaults() {
        return new BlackBoxSettings(60, 32, 1500, true, 24, 400, player -> false);
    }

    public @NotNull BlackBoxSettings seconds(int seconds) {
        return new BlackBoxSettings(seconds, radius, maxEntities, terrain, verticalMargin, maxChunks, hidden);
    }

    public @NotNull BlackBoxSettings radius(double radius) {
        return new BlackBoxSettings(seconds, radius, maxEntities, terrain, verticalMargin, maxChunks, hidden);
    }

    public @NotNull BlackBoxSettings hidden(@NotNull Predicate<Player> hidden) {
        return new BlackBoxSettings(seconds, radius, maxEntities, terrain, verticalMargin, maxChunks, hidden);
    }

    public @NotNull BlackBoxSettings terrain(boolean terrain) {
        return new BlackBoxSettings(seconds, radius, maxEntities, terrain, verticalMargin, maxChunks, hidden);
    }

    /** The larger of two, field by field; either one's hidden players stay hidden. */
    @org.jetbrains.annotations.ApiStatus.Internal
    public @NotNull BlackBoxSettings merge(@NotNull BlackBoxSettings other) {
        Predicate<Player> mine = hidden;
        Predicate<Player> theirs = other.hidden;
        return new BlackBoxSettings(Math.max(seconds, other.seconds), Math.max(radius, other.radius),
                Math.max(maxEntities, other.maxEntities), terrain || other.terrain,
                Math.max(verticalMargin, other.verticalMargin), Math.max(maxChunks, other.maxChunks),
                player -> mine.test(player) || theirs.test(player));
    }
}
