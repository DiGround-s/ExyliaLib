package net.exylia.lib.api.chatcosmetics;

import net.exylia.lib.api.ExyliaAPI;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * The chat ExyliaChatCosmetics runs: channels, moderation and what players set
 * for themselves.
 *
 * <pre>{@code
 * ExyliaAPI.get(ChatService.class)
 *          .filter(ChatService::isEnabled)
 *          .ifPresent(chat -> chat.send("global", Component.text("Restarting in 5 minutes.")));
 * }</pre>
 *
 * <p>Registered with Bukkit's {@link org.bukkit.plugin.ServicesManager} when
 * ExyliaChatCosmetics enables. Reach it through {@link ExyliaAPI#get(Class)}.
 *
 * <h2>The chat is a module, and it can be off</h2>
 * The cosmetics work whether or not this server handles its own chat, so the
 * chat is a module that a config key switches on and a reload can switch off
 * again under you. The service stays registered either way: every method here
 * answers as if there were no chat when the module is off — empty, {@code false},
 * zero — so ask {@link #isEnabled()} once rather than treating those answers as
 * facts about the server.
 *
 * <h2>Threads</h2>
 * Everything here reads from memory unless it is marked otherwise, so a
 * listener on the chat thread may call it. {@link #speak(Player, String, String)}
 * runs the whole pipeline on a thread of its own and returns immediately.
 *
 * @since 1.0.0
 */
public interface ChatService {

    /**
     * Whether this server's chat is handled by this plugin right now.
     *
     * @return {@code true} when the chat module is running
     */
    boolean isEnabled();

    // ── Channels ───────────────────────────────────────────────────────────

    /**
     * Every channel the config file declares.
     *
     * @return the channels, empty when the module is off
     */
    @NotNull
    @Unmodifiable
    Collection<ChatChannel> channels();

    /**
     * One channel by its id.
     *
     * @param id the channel id
     * @return the channel, or empty when there is none by that id
     */
    @NotNull
    Optional<ChatChannel> channel(@NotNull String id);

    /**
     * The channel a player's plain messages go to.
     *
     * @param player the player
     * @return their channel, or empty when the module is off or they are not
     *         loaded
     */
    @NotNull
    Optional<ChatChannel> channelOf(@NotNull UUID player);

    /**
     * Moves a player's plain messages to another channel.
     *
     * <p>Fires {@link net.exylia.lib.api.chatcosmetics.event.ChannelSwitchEvent}
     * first, so another plugin can refuse the move. Call on the player's thread.
     *
     * @param player    the player
     * @param channelId the channel to move them to
     * @return {@code true} when they moved; {@code false} when the channel does
     *         not exist, a listener objected, or they are not loaded
     */
    boolean switchChannel(@NotNull Player player, @NotNull String channelId);

    // ── Sending ────────────────────────────────────────────────────────────

    /**
     * Says something in a channel on a player's behalf.
     *
     * <p>The whole pipeline runs: the gate, the filter, their cosmetics, the
     * format. A message the filter blocks is not delivered, which is the point
     * of sending it this way rather than by hand.
     *
     * @param sender    who is speaking
     * @param channelId where it goes
     * @param text      what they say, as typed
     * @return {@code true} when the channel exists; nothing is said when it
     *         does not
     */
    boolean speak(@NotNull Player sender, @NotNull String channelId, @NotNull String text);

    /**
     * A line from the server to everybody who reads a channel.
     *
     * <p>Sent as it is: no format around it, no filter, no cosmetics. For
     * announcements rather than for player messages.
     *
     * @param channelId which channel's readers
     * @param line      the line
     * @return {@code true} when the channel exists
     */
    boolean send(@NotNull String channelId, @NotNull Component line);

    // ── Moderation ─────────────────────────────────────────────────────────

    /**
     * Whether the chat is muted for everybody without the bypass permission.
     *
     * @return {@code true} when the chat is muted
     */
    boolean muted();

    /**
     * Mutes or unmutes the chat.
     *
     * <p>Announced to the players the way the plugin's own command announces
     * it, naming your plugin as who did it. Setting it to what it already is
     * does nothing. Call on the main thread.
     *
     * @param muted whether the chat should be muted
     * @param by    your plugin, named in the announcement
     */
    void mute(boolean muted, @NotNull Plugin by);

    /**
     * The points a player has earned by breaking the chat rules.
     *
     * <p>They decay: a player who has been quiet long enough reads zero again.
     *
     * @param player the player
     * @return their current points, {@code 0} when they have none
     */
    int infractionPoints(@NotNull UUID player);

    // ── What players set ───────────────────────────────────────────────────

    /**
     * Whether one player has another on their ignore list.
     *
     * @param who   whose list
     * @param other who might be on it
     * @return {@code true} when {@code who} ignores {@code other}
     */
    boolean isIgnoring(@NotNull UUID who, @NotNull UUID other);

    /**
     * Whether a player accepts private messages at all.
     *
     * @param player the player
     * @return {@code true} unless they closed their messages
     */
    boolean acceptsPrivateMessages(@NotNull UUID player);

    /**
     * Whether a player is watching everybody else's private messages.
     *
     * @param player the player
     * @return {@code true} when social spy is on for them
     */
    boolean isSocialSpying(@NotNull UUID player);
}
