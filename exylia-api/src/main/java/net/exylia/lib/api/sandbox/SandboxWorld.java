package net.exylia.lib.api.sandbox;

import org.jetbrains.annotations.NotNull;

/**
 * One sandbox world, as it was when you asked.
 *
 * <p>A snapshot, not a live view: a world's status changes while it generates
 * and its settings change when an admin edits them, so the values here are the
 * ones that were current at the moment of the lookup.
 *
 * <p>{@code worldName} rather than a {@link org.bukkit.World}: a world that is
 * configured is not necessarily loaded, and handing out a live handle that may
 * be {@code null} half the time only moves the check to the caller. Resolve it
 * with {@link org.bukkit.Bukkit#getWorld(String)} when {@link #loaded()} says
 * there is something to resolve.
 *
 * @param id          the world id, as the admin typed it
 * @param displayName what a menu shows
 * @param worldName   the Bukkit world name, whether or not it is loaded
 * @param biome       the biome the world was generated with
 * @param borderSize  the world border's diameter in blocks
 * @param type        where its terrain came from
 * @param status      whether it can be played in
 * @param loaded      whether the server has it loaded right now
 * @since 1.0.0
 */
public record SandboxWorld(
        @NotNull String id,
        @NotNull String displayName,
        @NotNull String worldName,
        @NotNull String biome,
        int borderSize,
        @NotNull WorldType type,
        @NotNull WorldStatus status,
        boolean loaded) {

    /**
     * Whether a player could be sent here now.
     *
     * @return {@code true} when the world is ready and loaded
     */
    public boolean isPlayable() {
        return loaded && status == WorldStatus.READY;
    }
}
