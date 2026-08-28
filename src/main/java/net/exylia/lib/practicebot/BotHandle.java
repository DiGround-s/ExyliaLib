package net.exylia.lib.practicebot;

import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * One spawned bot, from the outside.
 *
 * <p>A handle, not the bot: the entity it drives, the state machine that decides
 * its swings, and everything else about how it works stay inside the plugin that
 * owns it.
 *
 * <p>A handle does not become valid again once the bot is gone. Hold it for as
 * long as the fight lasts and drop it after, and check {@link #isAlive()} rather
 * than assuming.
 *
 * @since 1.73.0
 */
public interface BotHandle {

    /**
     * The entity id of the body the bot drives.
     *
     * <p>What to compare against when something in the world happens and the
     * question is whether it happened to a bot - a death, a damage event, a
     * cleanup sweep over an arena. It is a plain UUID on purpose: the entity
     * type it belongs to is not part of this contract and has changed before.
     */
    UUID entityId();

    /** Whose bot this is. Never changes. */
    Player owner();

    /** Who it is fighting right now. Starts as the owner. */
    Player target();

    /**
     * Points it at somebody else.
     *
     * <p>Takes effect on the next tick the bot thinks. Whatever it had decided
     * about the previous opponent - the route it was walking, the combo it
     * thought it was in - is dropped, so a re-focused bot opens on its new
     * opponent rather than continuing a fight that already ended.
     *
     * @param target the player to fight; ignored when null or already the target
     */
    void setTarget(Player target);

    /** Whether the bot still exists and is fighting. */
    boolean isAlive();

    /**
     * How much health it had when it last thought.
     *
     * <p>A snapshot taken on the bot's own tick, not a live read. Entities belong
     * to the thread that owns their region, and something drawing a health bar
     * every couple of ticks from somewhere else has no business reaching into
     * one. At worst this is a tick or two stale, which no reader can see.
     *
     * @return current health, or 0 once the bot is gone
     */
    double health();

    /**
     * The most health it can have, from the same snapshot.
     *
     * @return maximum health, or 0 once the bot is gone
     */
    double maxHealth();

    /**
     * Removes it.
     *
     * <p>Safe to call twice, and safe to call on a bot that already died.
     *
     * <p>Anything that put a bot inside an arena should call this before handing
     * the arena back, not after: an arena being reset takes every entity standing
     * in it with no warning to whoever owned them.
     */
    void remove();
}
