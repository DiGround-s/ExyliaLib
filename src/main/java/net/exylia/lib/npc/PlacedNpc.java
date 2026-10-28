package net.exylia.lib.npc;

import net.exylia.lib.interaction.Click;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * An NPC that stands somewhere until it is removed, seen by whoever comes near.
 *
 * <pre>{@code
 * PlacedNpc guide = Npcs.of(this).place("guide", spec, location);
 * guide.onClick((player, click) -> player.sendMessage("Hello!"));
 * }</pre>
 *
 * <p>Packets only, like every NPC: the server never holds an entity for it. It is sent
 * to a player who walks within its view distance and taken back when they leave, and it
 * is gone from every client when it is removed or its plugin is disabled.
 *
 * <p>Every method is safe from any thread.
 *
 * @since 1.291.0
 */
public interface PlacedNpc {

    /** The id it was placed under, unique within its plugin. */
    @NotNull String id();

    /** Where it stands; a copy. */
    @NotNull Location location();

    /** What it looks like now. */
    @NotNull NpcSpec spec();

    /**
     * What happens when a viewer clicks it.
     *
     * <p>Run on the clicking player's thread. A click that arrives within a fifth of a
     * second of that player's previous one is dropped, which is how one press that the
     * client sends twice counts once.
     *
     * <p>One handler, the owner's. Another plugin that wants to know about clicks listens
     * to {@link PlacedNpcClickEvent}, fired for every counted click just before this.
     *
     * @param handler the handler, or {@code null} for none
     */
    void onClick(@Nullable BiConsumer<Player, Click> handler);

    /**
     * Who may see it, on top of the view distance.
     *
     * <p>Asked on every visibility pass, off the main thread: keep it to cheap reads.
     *
     * @param filter the condition, or {@code null} for everybody
     */
    void visibleIf(@Nullable Predicate<Player> filter);

    /**
     * Puts it somewhere else, facing the location's yaw and pitch.
     *
     * <p>Teleported for its viewers in the same world; to another world it is taken
     * away and sent again by the next pass.
     *
     * @param to where it goes
     */
    void moveTo(@NotNull Location to);

    /**
     * Changes what it looks like.
     *
     * <p>Equipment and pose are sent as they are; a new skin, size or outline gives
     * every viewer the NPC again. Look and distances apply from the next pass.
     *
     * @param spec the new spec
     */
    void update(@NotNull NpcSpec spec);

    /** Swings its main arm for everybody who sees it. */
    void swing();

    /** Makes it flinch for everybody who sees it. */
    void hurt();

    /**
     * Plays an animation for everybody who sees it.
     *
     * <pre>{@code
     * guide.play(NpcAnimation.parse("wave"));
     * }</pre>
     *
     * <p>Returns at once; the moves are sent at their moments, off the main thread. Two
     * animations at once both play; one that crouched always ends in the spec's pose.
     *
     * @param animation what it does
     * @since 1.300.0
     */
    default void play(@NotNull NpcAnimation animation) {
    }

    /**
     * Plays an animation for one viewer only, which nobody else sees.
     *
     * <p>Nothing happens when that player does not have it on screen.
     *
     * @param animation what it does
     * @param viewer    who sees it
     * @since 1.300.0
     */
    default void play(@NotNull NpcAnimation animation, @NotNull Player viewer) {
    }

    /**
     * Performs a whole-body animation for everybody near enough to see it.
     *
     * <pre>{@code
     * guide.perform(NpcPerformance.parse("anim:wave"));
     * }</pre>
     *
     * <p>While it plays the NPC is invisible to those viewers (it keeps its hitbox, so
     * clicks still reach it) and a ragdoll body wearing its skin stands in its place,
     * facing the same way; the NPC comes back the moment the body stands in its last
     * pose. A mirrored skin is drawn as the default skin here, since one body is shared.
     *
     * <p>One at a time: while one plays, another is skipped. Skipped too when nobody is
     * within {@link NpcAnimations#performRange()}, for a sitting NPC, without
     * PacketEvents, and for an {@code emote:} the plugin's resolver does not know.
     * Moving, changing, removing or replacing the NPC ends it at once.
     *
     * @param performance what it performs
     * @return whether it started
     * @since 1.302.0
     */
    default boolean perform(@NotNull NpcPerformance performance) {
        return false;
    }

    /**
     * Performs a whole-body animation for one viewer only; everybody else keeps seeing
     * the NPC as it was. A mirrored skin is drawn as that viewer's own.
     *
     * <p>Counts as the NPC's one performance: while it plays, nothing else starts.
     *
     * @param performance what it performs
     * @param viewer      who sees it
     * @return whether it started
     * @since 1.302.0
     */
    default boolean perform(@NotNull NpcPerformance performance, @NotNull Player viewer) {
        return false;
    }

    /**
     * Whether a performance is playing.
     *
     * @since 1.302.0
     */
    default boolean isPerforming() {
        return false;
    }

    /**
     * Gives one viewer an outline of their own, which nobody else sees.
     *
     * <pre>{@code
     * guide.glow(player, 0xFFD700);   // only this player sees it outlined in gold
     * }</pre>
     *
     * <p>Wins over {@link NpcSpec#glow()} for that viewer until {@link #resetGlow}, also
     * across {@link #update}; a negative colour takes the spec's outline away for them.
     * Sent at once to a viewer who has it on screen, and with the next spawn otherwise.
     * Forgotten when the player leaves. The colour is the nearest of the sixteen a team
     * can outline in.
     *
     * @param viewer who sees it
     * @param rgb    {@code 0xRRGGBB}, or negative for no outline
     * @since 1.392.0
     */
    default void glow(@NotNull Player viewer, int rgb) {
    }

    /**
     * Puts one viewer back on the spec's outline.
     *
     * @param viewer who had their own
     * @since 1.392.0
     */
    default void resetGlow(@NotNull Player viewer) {
    }

    /** Whether a player has it on screen. */
    boolean isViewing(@NotNull Player player);

    /** How many players have it on screen. */
    int viewerCount();

    /** Takes it off every screen and forgets it. Safe to call twice. */
    void remove();

    /** Whether it has been removed, or replaced by another placed under the same id. */
    boolean removed();
}
