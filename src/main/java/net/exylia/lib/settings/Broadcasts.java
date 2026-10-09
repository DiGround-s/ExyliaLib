package net.exylia.lib.settings;

import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Server-wide announcements a player can mute, one channel at a time.
 *
 * <p>A channel is a toggle that starts on, listed under the Announcements
 * screen of {@code /settings} grouped by plugin. Every announcement sent
 * through {@link #send} reaches only the online players who left it on, and
 * the console.
 *
 * <pre>{@code
 * // onEnable
 * Broadcasts.Channel deaths = Broadcasts.channel(this, "death-messages", "SKELETON_SKULL",
 *         "Death messages", "Messages about players dying", "anywhere on the server.");
 *
 * // later, any thread
 * Broadcasts.send(deaths, Text.from(this, messages.death()).with("player", victim.getName()));
 * }</pre>
 *
 * <p>The text is built once and sent to everybody, so it is never parsed per
 * recipient. {@link #send(Channel, String)} builds it with
 * {@link Text#from(Plugin, String)}, so {@code %prefix%} is the plugin's prefix,
 * as every chat line of an Exylia plugin opens with.
 *
 * @since 1.261.0
 */
public final class Broadcasts {

    /**
     * A registered channel.
     *
     * @param plugin the plugin that announces on it
     * @param key    its key, which is also its setting's key
     */
    public record Channel(@NotNull Plugin plugin, @NotNull String key) {
    }

    private Broadcasts() {
        throw new AssertionError("No instances.");
    }

    /**
     * Registers a channel, or replaces the one with the same key.
     *
     * @param plugin      the plugin that announces on it
     * @param key         its key, lower case
     * @param icon        what its button is drawn as
     * @param name        its name, plain text
     * @param description its description, one lore line each
     * @return the channel to send on
     */
    public static @NotNull Channel channel(@NotNull Plugin plugin, @NotNull String key, @NotNull String icon,
                                           @NotNull String name, @NotNull String... description) {
        return channel(plugin, key, icon, name, List.of(description), null, null);
    }

    /**
     * Registers a channel whose announcements only exist while a feature is on;
     * while it is off the channel is shown disabled rather than offered.
     *
     * @param plugin      the plugin that announces on it
     * @param key         its key, lower case
     * @param icon        what its button is drawn as
     * @param name        its name, plain text
     * @param description its description, one lore line each
     * @param available   whether the feature is on; memory only, any thread
     * @param reason      why it is off, or {@code null} for the menu's own wording
     * @return the channel to send on
     */
    public static @NotNull Channel channel(@NotNull Plugin plugin, @NotNull String key, @NotNull String icon,
                                           @NotNull String name, @NotNull List<String> description,
                                           @Nullable BooleanSupplier available, @Nullable String reason) {
        Setting.Builder builder = Setting.toggle(key, true)
                .category(Setting.ANNOUNCEMENTS)
                .icon(icon)
                .name(name)
                .description(description);
        if (available != null) {
            builder.available(available, reason);
        }
        Settings.register(plugin, builder.build());
        return new Channel(plugin, key);
    }

    /**
     * Whether a player hears a channel.
     *
     * @return {@code true} unless they muted it
     */
    public static boolean enabled(@NotNull Player player, @NotNull Channel channel) {
        return Settings.enabled(player, channel.plugin(), channel.key());
    }

    /**
     * Sends a message to everybody who hears the channel, and to the console.
     *
     * @param channel the channel
     * @param message the raw message; {@code %prefix%} is the plugin's prefix. Blank sends nothing.
     */
    public static void send(@NotNull Channel channel, @Nullable String message) {
        if (message == null || message.isBlank()) return;
        send(channel, Text.from(channel.plugin(), message));
    }

    /**
     * Sends a text to everybody who hears the channel, and to the console.
     *
     * <p>Any thread: each player gets it on the thread that owns them, which is
     * what Folia needs and costs nothing elsewhere.
     *
     * @param channel the channel
     * @param text    what to send, built once for everybody
     */
    public static void send(@NotNull Channel channel, @NotNull Text text) {
        TaskScheduler tasks = Tasks.of(channel.plugin());
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!enabled(online, channel)) continue;
            if (tasks.isOwnedBy(online)) {
                text.send(online);
            } else {
                tasks.runAtEntity(online, () -> text.send(online));
            }
        }
        text.send(Bukkit.getConsoleSender());
    }
}
