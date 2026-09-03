package net.exylia.lib.packet;

import org.jetbrains.annotations.NotNull;

/**
 * Lines the server sends that a viewer must not read.
 *
 * <p>For what no event can reach: a plugin that writes its own join and quit
 * lines rather than setting the event's, a broadcast sent straight to
 * everyone. Those never pass through an API anybody can cancel, so they are
 * caught on the way out.
 *
 * <pre>{@code
 * // nobody without the permission reads a vanished player's quit line,
 * // whichever plugin wrote it
 * Packets.of(this).messages().rule((viewer, line) ->
 *         viewer.hasPermission("staff.see") || !isAboutSomebodyHidden(line));
 * }</pre>
 *
 * <h2>Every rule has to agree</h2>
 * One rule per plugin, combined with AND: a line is sent when no plugin
 * objects. A plugin's rule goes when it is disabled.
 *
 * <h2>What this covers</h2>
 * System messages, which is what a broadcast and a join or quit line are.
 * Player chat has its own module, {@link net.exylia.lib.chat.Chats}, which
 * works on the event and keeps the chat plugin's formatting intact — use that
 * for chat rather than this.
 *
 * @since 1.99.0
 */
public interface Messages {

    /**
     * Registers this plugin's rule, replacing its previous one.
     *
     * @param rule the rule
     */
    void rule(@NotNull MessageRule rule);

    /** Drops this plugin's rule, so every line passes again. */
    void clearRule();
}
