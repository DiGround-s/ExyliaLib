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

    /** Whether a player has it on screen. */
    boolean isViewing(@NotNull Player player);

    /** How many players have it on screen. */
    int viewerCount();

    /** Takes it off every screen and forgets it. Safe to call twice. */
    void remove();

    /** Whether it has been removed, or replaced by another placed under the same id. */
    boolean removed();
}
