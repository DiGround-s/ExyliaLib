package net.exylia.lib.api.practicebot.event;

import net.exylia.lib.api.practicebot.BotHandle;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * A bot killed a player.
 *
 * <p>Fired when a player dies and the last entity to hurt them was a bot - its
 * swing, or a crystal it set off - rather than making every integration read
 * {@code PlayerDeathEvent} and work out for itself whether the killer was a bot.
 * A player hit by a bot and then by anything else is not the bot's kill; one hit
 * by a bot who then falls to their death is.
 *
 * <p>Called from the player's own death, on the thread that owns them, which on
 * a threaded server is not the main thread. The handle may already name a bot
 * that is gone - one that died in the same exchange - so check
 * {@link BotHandle#isAlive()} before acting on it.
 *
 * @since 1.3.0
 */
public class PracticeBotKillEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final BotHandle bot;
    private final Player victim;

    public PracticeBotKillEvent(BotHandle bot, Player victim, boolean async) {
        super(async);
        this.bot = bot;
        this.victim = victim;
    }

    /** The bot that landed the last hit. */
    public BotHandle bot() {
        return bot;
    }

    /** The player it killed. Still dying: the death event has not finished. */
    public Player victim() {
        return victim;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
