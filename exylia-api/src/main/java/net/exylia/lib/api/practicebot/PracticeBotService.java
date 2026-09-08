package net.exylia.lib.api.practicebot;

import org.bukkit.entity.Player;

import java.util.Collection;
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
     * <p>The bot is the caller's, and independent: it does not replace one the
     * owner already has, it is not saved to their profile, it is not reachable
     * from the plugin's own menus unless {@link BotSpec#settingsMenu()} says so,
     * and every number it fights on can be sent with it rather than read out of
     * the server's {@code config.yml} - see {@link BotTuning}.
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
     * One of the bots a player owns.
     *
     * <p>A player may own several at once - the one they spawned with
     * {@code /bot} and any number an integration spawned for them - so this
     * answers with the one they spawned themselves when there is one, and an
     * arbitrary one of the rest otherwise. Anything that has to see all of them
     * wants {@link #ownedBy(Player)}.
     *
     * @param owner the player
     * @return one of their bots, or empty
     */
    Optional<BotHandle> byOwner(Player owner);

    /**
     * Every bot a player owns.
     *
     * <p>Spawning through this service never displaces a bot the player already
     * has, so a plugin running a 2v2 can put two of them on the field and a
     * player practising in a sandbox keeps their own while a match borrows them.
     *
     * @param owner the player
     * @return their bots, newest last, empty when they have none
     */
    Collection<BotHandle> ownedBy(Player owner);

    /**
     * Every bot on the server.
     *
     * <p>A snapshot, safe to iterate: a bot dying while you walk it changes
     * nothing about the collection you were handed.
     */
    Collection<BotHandle> all();

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
