package net.exylia.lib.discord;

import net.exylia.lib.discord.internal.WebhookRuntime;
import net.exylia.lib.internal.LibrarySettings;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Entry point for Discord webhook messages.
 *
 * <pre>{@code
 * PluginWebhooks webhooks = Webhooks.of(this);
 * webhooks.send(target, config.captureStarted(), Map.of(
 *         "zone", zone.name(),                          // escaped: plain text
 *         "player_head", Webhooks.head(player.getUniqueId()),
 *         "server", Webhooks.trusted(config.serverName())));   // the owner's own markdown
 * }</pre>
 *
 * <p>Two trust tiers. {@code Webhooks.of(plugin)} is for webhooks the server
 * owner configured: a literal {@code @everyone} or {@code @here} in a template's
 * message pings. {@link PluginWebhooks#playerOwned()} is for webhooks a player
 * pasted in: those never ping everyone, whatever the template says.
 *
 * <p>No bot, no gateway, no dependency: webhooks over {@code java.net.http}.
 *
 * @since 1.245.0
 */
public final class Webhooks {

    private static final ConcurrentMap<String, PluginWebhooks> BY_PLUGIN = new ConcurrentHashMap<>();

    private Webhooks() {
        throw new AssertionError("No instances.");
    }

    /**
     * Returns the webhook view owned by a plugin, for server-owned webhooks.
     *
     * @param plugin the plugin whose messages and listeners these are
     * @return its cached view
     */
    public static @NotNull PluginWebhooks of(@NotNull Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        return BY_PLUGIN.computeIfAbsent(plugin.getName(), ignored -> new PluginWebhooks(plugin, true));
    }

    /**
     * Marks a value as the server owner's own text, inserted as written.
     *
     * <p>A value is escaped by default, because most of them come from players:
     * a name, a clan tag, a reason. Wrap one in this only when the server owner
     * wrote it — a server name from the config — and its markdown is meant to
     * render. Its Minecraft formatting is still converted, and it still cannot
     * ping anyone: pings come from the template alone.
     *
     * @param value the value; {@code null} is empty
     * @return the marked value, to put in the values map
     */
    public static @NotNull Trusted trusted(@Nullable Object value) {
        return new Trusted(value == null ? "" : String.valueOf(value));
    }

    /**
     * Returns the URL of a player's head, for an author icon or a thumbnail.
     *
     * <p>Drawn by a public head service, set in ExyliaLib's {@code config.yml}
     * under {@code webhook-head} ({@code https://mc-heads.net/avatar/%uuid%/64}
     * by default).
     *
     * @param player the player's id
     * @return the image URL
     */
    public static @NotNull String head(@NotNull UUID player) {
        String pattern = LibrarySettings.get().webhookHead();
        if (pattern == null || pattern.isBlank()) {
            pattern = LibrarySettings.DEFAULT_WEBHOOK_HEAD;
        }
        return pattern.replace("%uuid%", player.toString());
    }

    /**
     * Releases one plugin: its listeners are forgotten and its messages still
     * waiting are dropped. Called by the library one tick after the plugin's
     * {@code onDisable}, so a message sent from there still has its chance.
     */
    @ApiStatus.Internal
    public static void release(@NotNull Plugin plugin) {
        // This load only: a reload's new load may already hold a view under the same name.
        BY_PLUGIN.computeIfPresent(plugin.getName(), (name, view) -> view.plugin() == plugin ? null : view);
        WebhookRuntime runtime = WebhookRuntime.get();
        if (runtime != null) {
            runtime.release(plugin);
        }
    }

    /** Stops the module: a short, bounded flush of what is queued. */
    @ApiStatus.Internal
    public static void releaseAll() {
        BY_PLUGIN.clear();
        WebhookRuntime runtime = WebhookRuntime.get();
        if (runtime != null) {
            runtime.shutdown();
        }
    }

    /**
     * A value the server owner wrote, inserted as written; see {@link #trusted}.
     *
     * @param value the text
     * @since 1.245.0
     */
    public record Trusted(@NotNull String value) {
    }
}
