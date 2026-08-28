package net.exylia.lib.practicebot;

import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Spawning and finding practice bots.
 *
 * <p>Registered with Bukkit's {@link org.bukkit.plugin.ServicesManager} by the
 * bot plugin when it enables. Reach it through {@link PracticeBots#get()} rather
 * than looking it up directly.
 *
 * @since 1.73.0
 */
public interface PracticeBotService {

    /**
     * Spawns a bot.
     *
     * <p>Asynchronous because spawning an entity has to happen on the thread that
     * owns the region it appears in, which is rarely the caller's. The returned
     * future completes on that thread - schedule your own work from it rather
     * than assuming the main thread.
     *
     * <p>Fails with {@link BotLimitReachedException} when the server is already
     * running as many bots as it is configured to allow, and with
     * {@link IllegalStateException} when the owner logs out before it spawns.
     *
     * @param spec what to spawn
     * @return the bot, once it exists
     */
    CompletableFuture<BotHandle> spawn(BotSpec spec);

    /**
     * The bot driving a given entity, if that entity is a bot at all.
     *
     * <p>The lookup behind every "did that just happen to a bot?" question.
     *
     * @param entityId an entity id from the world
     * @return its bot, or empty
     */
    Optional<BotHandle> byEntity(UUID entityId);

    /**
     * The bot a player owns, if they have one.
     *
     * @param owner the player
     * @return their bot, or empty
     */
    Optional<BotHandle> byOwner(Player owner);

    /** How many bots exist right now. */
    int active();

    /**
     * How many may exist at once.
     *
     * <p>Every bot thinks once per tick and searches for a path of its own, so
     * this is a real ceiling rather than a formality. Worth checking before
     * offering somebody a fight you cannot start.
     *
     * @return the configured cap
     */
    int capacity();
}
