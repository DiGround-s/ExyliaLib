package net.exylia.lib.chat;

import net.exylia.lib.chat.internal.ChatRuntime;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Entry point of the chat module: who reads whose messages.
 *
 * <p>A plugin registers one rule and the library takes the receivers a rule
 * refuses off every message before the server delivers it. Nothing is
 * cancelled and nothing is re-sent, so the chat plugin's format, its hover
 * text and its console line all stay exactly as they were.
 *
 * <pre>{@code
 * // an event's chat is its own: outside is deaf to it, and it is deaf to outside
 * Chats.rule(this, (listener, speaker) -> events.share(listener, speaker));
 * }</pre>
 *
 * <h2>Every rule has to agree</h2>
 * Rules from different plugins are combined with AND, the way
 * {@link net.exylia.lib.packet.VisibilityRule} is: a message reaches a player
 * only when no plugin objects. One plugin per rule — registering again
 * replaces that plugin's rule — and a plugin's rule is dropped when it is
 * disabled.
 *
 * <h2>One player above the rules</h2>
 * {@link #bypass} takes a player out of the question entirely: they read every
 * message and every message of theirs is read, whatever any rule says. It is
 * the only way past a no, because rules agree with AND and a rule of one's own
 * can never undo another plugin's refusal. Staff reading an isolated chat is
 * what it is for.
 *
 * <h2>What is needed</h2>
 * A chat plugin that lets the server deliver the message, which is what Paper's
 * own chat and every renderer-based chat plugin do. A plugin that instead
 * cancels the event and sends the line itself has taken delivery over, and
 * nothing here can reach those copies.
 *
 * <h2>What this does not cover</h2>
 * Chat, and only chat. Join and quit lines, death messages, broadcasts and
 * private messages are not chat and are not touched. Neither is a message that
 * arrives from another server through Redis, because it never was a chat event
 * here — filter those where they are delivered, with {@link #canHear}.
 *
 * <h2>Threading</h2>
 * Rules are asked on the chat thread. Read shared state, and nothing else.
 *
 * @since 1.89.0
 */
public final class Chats {

    private Chats() {
        throw new AssertionError("No instances.");
    }

    /**
     * Registers this plugin's rule, replacing its previous one.
     *
     * @param plugin the owning plugin
     * @param rule   the rule
     */
    public static void rule(@NotNull Plugin plugin, @NotNull ChatRule rule) {
        ChatRuntime.rule(plugin.getName(), rule);
    }

    /**
     * Drops this plugin's rule, so it stops having a say.
     *
     * @param plugin the owning plugin
     */
    public static void clear(@NotNull Plugin plugin) {
        ChatRuntime.release(plugin.getName());
    }

    /**
     * Returns whether {@code listener} may read what {@code speaker} says.
     *
     * <p>The same answer the module applies to a chat message, for a plugin
     * that delivers text itself — a cross-server line, a replayed message —
     * and wants it to obey the same rules.
     *
     * @param listener the player who would read the message
     * @param speaker  the player who wrote it
     * @return {@code false} when some rule keeps them apart
     */
    public static boolean canHear(@NotNull Player listener, @NotNull Player speaker) {
        return ChatRuntime.canHear(listener, speaker);
    }

    /**
     * Puts a player above every rule, or back under them.
     *
     * <p>While bypassing, the player reads whatever anybody says and whatever
     * they say is read by everybody. Nothing is remembered across a restart,
     * and a player who quits is dropped: a plugin that wants this to survive
     * stores it itself and sets it again on join.
     *
     * @param player the player
     * @param bypass whether the rules stop applying to them
     * @since 1.96.0
     */
    public static void bypass(@NotNull Player player, boolean bypass) {
        ChatRuntime.bypass(player.getUniqueId(), bypass);
    }

    /**
     * Returns whether this player is currently above every rule.
     *
     * @param uuid the player
     * @return {@code true} while they read, and are read by, everyone
     * @since 1.96.0
     */
    public static boolean bypassing(@NotNull UUID uuid) {
        return ChatRuntime.bypassing(uuid);
    }

    /** Drops one plugin's rule. Called when the plugin disables. */
    public static void release(@NotNull String pluginName) {
        ChatRuntime.release(pluginName);
    }

    /** Drops every rule. Called when the library disables. */
    public static void releaseAll() {
        ChatRuntime.shutdown();
    }
}
