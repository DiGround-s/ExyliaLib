package net.exylia.lib.chat;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Decides whether one player may read another's chat message.
 *
 * <p>Asked once per receiver while a message is on its way out, so it must be
 * cheap and must not touch the world: it runs on the chat thread, which is not
 * the main thread.
 *
 * <p>The question is asymmetric on purpose. A rule is free to let a spectator
 * read the players of a match while the players read nothing back.
 *
 * @since 1.89.0
 */
@FunctionalInterface
public interface ChatRule {

    /**
     * Returns whether {@code listener} may read what {@code speaker} said.
     *
     * <p>Never asked about a speaker reading themselves: whoever writes a
     * message always sees it.
     *
     * @param listener the player who would read the message
     * @param speaker  the player who wrote it
     * @return {@code false} to keep the message from this listener
     */
    boolean canHear(@NotNull Player listener, @NotNull Player speaker);
}
