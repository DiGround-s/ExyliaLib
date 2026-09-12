package net.exylia.lib.player;

import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * A player a command was asked about, before anybody has been found.
 *
 * <p>The command parameter to declare when the target may be somebody this
 * server has never seen: a name from the proxy's player list, or one only
 * Mojang can put an id to. It always parses — what the sender typed is a
 * name, not yet an answer — and the work of turning it into an
 * {@link ExyliaPlayer} happens in the handler, where it is allowed to take a
 * moment.
 *
 * <p>Declare {@link ExyliaPlayer} instead when the target has to be somebody
 * this server already knows, which is the common case and the cheaper one:
 * it resolves while the command is being parsed, from memory, and a name
 * nobody knows is reported before the handler runs at all.
 *
 * <pre>{@code
 * @Command("punish")
 * public void punish(Player staff, PlayerTarget target, String reason) {
 *     target.then(staff, found -> punishments.apply(found.id(), found.name(), reason));
 * }
 * }</pre>
 *
 * @param typed what the sender typed: a name, or a uuid as text
 * @since 1.146.0
 */
public record PlayerTarget(@NotNull String typed) {

    public PlayerTarget {
        if (typed == null || typed.isBlank()) {
            throw new IllegalArgumentException("A player target needs something to look up.");
        }
        typed = typed.trim();
    }

    /** What the sender typed, which is the name to put in a message. */
    public @NotNull String name() {
        return typed;
    }

    /**
     * Whoever this names, if the cheap tiers already know.
     *
     * <p>Synchronous and free: online here, the directory, the server's user
     * cache. Worth asking before {@link #resolve()} when the answer changes
     * what the command does rather than what it says.
     *
     * @return their address, or {@code null} when a lookup is needed
     */
    public @Nullable ExyliaPlayer cached() {
        return ExyliaPlayers.cached(typed);
    }

    /**
     * Whoever this names, going out to the network if needed.
     *
     * <p>Completes off the server thread; {@link #then} is the version that
     * hops back.
     *
     * @return their address, or empty when nobody answers to it
     */
    public @NotNull CompletableFuture<Optional<ExyliaPlayer>> resolve() {
        return ExyliaPlayers.resolve(typed);
    }

    /**
     * Resolves the target and runs an action on the server thread, telling
     * the sender itself when nobody answers.
     *
     * <p>The whole point of the type. Every plugin that took a name and
     * looked it up wrote these eight lines, and two of them sent the
     * not-found line from the Redis thread.
     *
     * @param sender who asked, and who is told when nobody is found
     * @param action what to do with the target
     */
    public void then(@NotNull CommandSender sender, @NotNull Consumer<ExyliaPlayer> action) {
        ExyliaPlayers.then(sender, typed, action);
    }

    /**
     * The same, when the plugin has its own wording for nobody found.
     *
     * @param sender   who asked
     * @param action   what to do with the target
     * @param notFound what to do when nobody answers
     */
    public void then(@NotNull CommandSender sender,
                     @NotNull Consumer<ExyliaPlayer> action,
                     @NotNull Runnable notFound) {
        ExyliaPlayers.then(sender, typed, action, notFound);
    }

    @Override
    public @NotNull String toString() {
        return typed;
    }
}
