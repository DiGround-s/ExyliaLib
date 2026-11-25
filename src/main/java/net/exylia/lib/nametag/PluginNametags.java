package net.exylia.lib.nametag;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Collection;
import java.util.function.Function;

/**
 * One plugin's nametags.
 *
 * <p>Obtained from {@link Nametags#of(org.bukkit.plugin.Plugin)}. Everything
 * painted through it is undone when the plugin is disabled.
 *
 * <h2>Threading</h2>
 * Every method is safe from any thread.
 *
 * @since 1.36.0
 */
public interface PluginNametags {

    /**
     * Paints a player a certain way, for one viewer only.
     *
     * <p>Painting the same pair twice replaces the style rather than stacking:
     * a player has one look per viewer.
     *
     * @param viewer who sees it
     * @param target who is painted
     * @param style  how they look
     */
    void paint(@NotNull Player viewer, @NotNull Player target, @NotNull NametagStyle style);

    /**
     * Paints several players the same way, for one viewer.
     *
     * <p>Cheaper than a loop: they share one team packet instead of one each.
     *
     * @param viewer  who sees it
     * @param targets who are painted
     * @param style   how they look
     */
    void paint(@NotNull Player viewer, @NotNull Collection<? extends Player> targets,
               @NotNull NametagStyle style);

    /**
     * Paints every member of a group for each other.
     *
     * <p>The usual case: a clan, a party or a team where everyone should see
     * everyone else the same way. Nobody is painted for themselves — a player
     * does not see their own nametag.
     *
     * @param group the players
     * @param style how they look to each other
     */
    void paintEachOther(@NotNull Collection<? extends Player> group, @NotNull NametagStyle style);

    /**
     * Puts one player back to normal, for one viewer.
     *
     * @param viewer who sees it
     * @param target who goes back to normal
     */
    void reset(@NotNull Player viewer, @NotNull Player target);

    /**
     * Puts everything a viewer was shown back to normal.
     *
     * @param viewer who sees it
     */
    void resetAll(@NotNull Player viewer);

    /**
     * Puts a player back to normal for everyone who was shown them.
     *
     * <p>For a player who left a clan, died, or changed side: the viewers are
     * whoever this plugin painted them for, which it no longer has to track.
     *
     * @param target who goes back to normal
     */
    void resetEverywhere(@NotNull Player target);

    /**
     * Returns how a viewer currently sees a player.
     *
     * @param viewer who sees it
     * @param target who is painted
     * @return the style, or {@code null} when this plugin painted nothing
     */
    NametagStyle styleOf(@NotNull Player viewer, @NotNull Player target);

    /**
     * Puts everything this plugin painted back to normal.
     *
     * <p>Done automatically when the plugin is disabled.
     */
    void clear();

    /**
     * Paints every online player for everyone else from one rule, the way a
     * nametag plugin does: a rank prefix, a name colour, a suffix. The rule
     * receives the player being drawn and is re-read every {@code every}, which
     * also covers joins and rank changes; a pair already drawn that way sends
     * nothing.
     *
     * <p>It is a base layer: anything painted with {@link #paint} by any plugin
     * (a bounty, a clan, a duel) wins over it for that pair, and the rule takes
     * the pair back once that painting is reset. A rule returning {@code null}
     * leaves the player unpainted. Calling it again replaces the rule; a
     * {@code null} rule stops it and resets what it painted, as does
     * {@link #clear()}.
     *
     * <p>The rule runs on the target's entity thread, so it may read the player
     * and resolve placeholders.
     *
     * @param rule  how a player looks to everyone else, or {@code null} to stop
     * @param every how often it is re-read; one second is plenty
     * @since 1.659.0
     */
    void everyone(@Nullable Function<Player, NametagStyle> rule, @NotNull Duration every);

    /**
     * {@link #everyone(Function, Duration)} re-read once a second.
     *
     * @param rule how a player looks to everyone else, or {@code null} to stop
     * @since 1.659.0
     */
    default void everyone(@Nullable Function<Player, NametagStyle> rule) {
        everyone(rule, Duration.ofSeconds(1));
    }
}
