package net.exylia.lib.skull;

import net.exylia.lib.skull.internal.SkullRuntime;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Player heads: fetched once, remembered, and never on the main thread.
 *
 * <p>Three ways in, the same three ExyliaCommons had — a base64 texture, a
 * skin URL, or a player name — plus a unique id, which is cheaper than a name
 * because it skips a lookup.
 *
 * <pre>{@code
 * // A head from a config value. Instant, nothing fetched.
 * ItemStack icon = Skulls.of(SkullSource.texture(config.icon())).item();
 *
 * // A player's head in a menu: drawn now, corrected when it lands.
 * SkullHandle handle = Skulls.of(SkullSource.player("Notch"))
 *         .name("<gold>Notch")
 *         .viewer(player)
 *         .build();
 * menu.setItem(slot, handle.item());
 * handle.onReady(head -> menu.setItem(slot, head));
 * }</pre>
 *
 * <h2>What it costs</h2>
 * A head whose texture is known costs one item allocation and no scheduling.
 * A head whose texture is not costs one HTTP request — shared with every other
 * caller asking for the same head at the same time, and remembered on disk so
 * the next restart does not repeat it.
 *
 * <p>Nothing here blocks the main thread. Methods that could wait return a
 * {@link SkullHandle} or a {@link CompletableFuture} rather than an item.
 *
 * @since 1.19.0
 */
public final class Skulls {

    private Skulls() {
    }

    /**
     * Starts building a head.
     *
     * @param source where the texture comes from
     * @return a builder
     */
    public static @NotNull SkullBuilder of(@NotNull SkullSource source) {
        return new SkullBuilder(source);
    }

    /**
     * A head from a base64 texture. Shorthand for the common config case.
     *
     * @param base64 the texture property
     * @return a builder
     */
    public static @NotNull SkullBuilder texture(@NotNull String base64) {
        return of(SkullSource.texture(base64));
    }

    /**
     * A head from a skin URL, or a bare texture hash.
     *
     * @param url the URL
     * @return a builder
     */
    public static @NotNull SkullBuilder url(@NotNull String url) {
        return of(SkullSource.url(url));
    }

    /**
     * A head belonging to a player, by name.
     *
     * @param name the player name
     * @return a builder
     */
    public static @NotNull SkullBuilder player(@NotNull String name) {
        return of(SkullSource.player(name));
    }

    /**
     * A head belonging to a player, by unique id.
     *
     * @param id the player's id
     * @return a builder
     */
    public static @NotNull SkullBuilder player(@NotNull UUID id) {
        return of(SkullSource.player(id));
    }

    /**
     * A head belonging to a player who is here.
     *
     * <p>Free: their skin arrived with them, so this never touches the
     * network.
     *
     * @param player the player
     * @return a builder
     */
    public static @NotNull SkullBuilder player(@NotNull Player player) {
        return of(SkullSource.player(player.getUniqueId()));
    }

    /**
     * Returns whether a head is ready to be shown with no waiting.
     *
     * <p>For callers deciding whether to build a menu now or warm it first.
     *
     * @param source the head
     * @return {@code true} when the texture is already known
     */
    public static boolean isCached(@NotNull SkullSource source) {
        return SkullRuntime.cached(source) != null;
    }

    /**
     * Fetches a texture without building anything.
     *
     * @param source the head
     * @return the texture, completing with {@code null} when there is none
     */
    public static @NotNull CompletableFuture<@Nullable String> texture(
            @NotNull SkullSource source) {
        return SkullRuntime.resolve(source);
    }

    /**
     * Fetches several heads ahead of time.
     *
     * <p>For the menu that has not been opened yet: warm it on join, or when
     * the data behind it changes, and every head in it is then instant. This
     * is the difference between a leaderboard that pops in and one that does
     * not.
     *
     * @param sources the heads to fetch
     * @return completes when every lookup has finished or failed
     */
    public static @NotNull CompletableFuture<Void> warm(
            @NotNull Collection<? extends SkullSource> sources) {
        CompletableFuture<?>[] all = sources.stream()
                .map(SkullRuntime::resolve)
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(all);
    }

    /**
     * Forgets one head, so the next request fetches it again.
     *
     * <p>For when a player changes their skin.
     *
     * @param source the head to forget
     */
    public static void invalidate(@NotNull SkullSource source) {
        SkullRuntime.invalidate(source);
    }

    /** Forgets every head, in memory and on disk. */
    public static void invalidateAll() {
        SkullRuntime.invalidateAll();
    }

    /**
     * A snapshot of what the module is doing, for a debug command.
     *
     * @return the current numbers
     */
    public static @NotNull Stats stats() {
        return new Stats(SkullRuntime.size(), SkullRuntime.pending(),
                SkullRuntime.isBackedOff(), SkullRuntime.backoffRemaining());
    }

    /**
     * What the module is holding.
     *
     * @param cached           textures held in memory
     * @param pending          lookups in flight
     * @param backedOff        whether Mojang lookups are paused
     * @param backoffRemaining how long the pause has left, in millis
     */
    public record Stats(long cached, int pending, boolean backedOff, long backoffRemaining) {
    }
}
