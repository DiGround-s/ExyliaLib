package net.exylia.lib.practicebot.event;

import net.exylia.lib.practicebot.BotHandle;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * A bot died.
 *
 * <p>Fired instead of making every integration watch entity deaths and work out
 * for itself which corpses were bots. By the time this runs the bot is already
 * gone; the handle is here to say which one it was and who it belonged to, not
 * to be kept.
 *
 * <p>Called on the thread the bot was running on, which on a threaded server is
 * not the main thread. Schedule anything that touches the wider world.
 *
 * @since 1.73.0
 */
public class PracticeBotDeathEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final BotHandle bot;
    private final Player lastTarget;

    public PracticeBotDeathEvent(BotHandle bot, Player lastTarget, boolean async) {
        super(async);
        this.bot = bot;
        this.lastTarget = lastTarget;
    }

    /** The bot that died. Already removed: only its identity is still good. */
    public BotHandle bot() {
        return bot;
    }

    /** Who it was fighting when it died. */
    public Player lastTarget() {
        return lastTarget;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
