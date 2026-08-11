package net.exylia.lib.placeholder;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * A line of text with its placeholders already located.
 *
 * <p>Scanning a string to find {@code %...%} is work that does not change
 * between renders, so a template does it once and keeps the result. Rendering
 * then only resolves values and joins them.
 *
 * <p>This is the shape a scoreboard wants:
 *
 * <pre>{@code
 * // once, when the scoreboard is built
 * Template line = Placeholders.compile("{letters}Coins: {highlight}%eco_balance:comma%");
 *
 * // every tick, for every player
 * String rendered = line.render(player);
 * }</pre>
 *
 * <p>{@link Placeholders#apply} does the same thing with an internal cache, and
 * is the right choice for one-off messages. Hold a template when you already
 * know a line will be rendered again and again, which also keeps it out of that
 * shared cache.
 *
 * <p>Templates are immutable and safe to share between threads.
 *
 * @since 1.3.0
 */
public interface Template {

    /**
     * Renders for a player.
     *
     * @param viewer who reads the text, may be {@code null}
     * @return the finished text
     */
    @NotNull String render(@Nullable Player viewer);

    /**
     * Renders for a player, with extra values attached.
     *
     * <p>Use this to pass whatever the placeholders need that is not the player
     * itself, such as the arena a scoreboard belongs to:
     *
     * <pre>{@code
     * line.render(player, Map.of("arena", arena));
     * }</pre>
     *
     * @param viewer who reads the text, may be {@code null}
     * @param data   values resolvers can read with {@link Request#get}
     * @return the finished text
     */
    @NotNull String render(@Nullable Player viewer, @NotNull Map<String, Object> data);

    /**
     * Renders text that is about somebody other than the reader.
     *
     * @param viewer who reads the text
     * @param target who the text is about
     * @return the finished text
     */
    @NotNull String render(@Nullable Player viewer, @Nullable OfflinePlayer target);

    /**
     * Renders text with no reader, such as a console message or a log line.
     *
     * @return the finished text
     */
    @NotNull String render();

    /**
     * Renders with everything specified.
     *
     * <p>Named apart from {@code render} so that {@code render(null)} is never
     * ambiguous for a caller who simply has no player.
     *
     * @param request who is asking, about whom, and with what data
     * @return the finished text
     */
    @NotNull String renderFor(@NotNull Request request);

    /**
     * Returns the names of the placeholders in this template, in order.
     *
     * <p>For diagnostics, and for deciding whether a line needs re-rendering at
     * all.
     *
     * @return an immutable list, empty when the text has no placeholders
     */
    @NotNull List<String> placeholders();

    /**
     * Returns whether this template has any placeholders.
     *
     * <p>When {@code false}, {@link #render} always returns the same string and
     * costs nothing, so a caller can skip re-rendering entirely.
     *
     * @return {@code true} when there is at least one placeholder
     */
    boolean isDynamic();

    /**
     * Returns the text this template was compiled from.
     *
     * @return the original string
     */
    @NotNull String raw();
}
