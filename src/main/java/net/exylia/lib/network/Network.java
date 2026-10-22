package net.exylia.lib.network;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Plugin-to-plugin messages addressed to one server of the network, or to the
 * server a player is on, and the wait for players a proxy is sending here.
 *
 * <p>Rides on the plugin's {@link net.exylia.lib.redis.Channels channels}, so it
 * needs exactly what they need: {@code database.redis} enabled in the plugin's
 * {@code database.yml}. Resolving a player's server goes through the
 * {@link net.exylia.lib.proxy.Proxy proxy bridge}.
 *
 * <pre>{@code
 * PluginNetwork network = Network.of(this);
 * network.on("duel-request", message -> Tasks.of(this).run(() -> show(message.payload())));
 * network.send(targetId, "duel-request", requester + "|" + kit).thenAccept(delivery -> ...);
 * }</pre>
 *
 * @since 1.347.0
 */
public final class Network {

    private Network() {
        throw new AssertionError("No instances.");
    }

    /**
     * The plugin's view of the network. Cheap: the channels behind it are
     * cached and closed with the plugin.
     *
     * @param plugin the plugin whose channels carry the messages
     * @return a view over the plugin's channels
     */
    public static @NotNull PluginNetwork of(@NotNull Plugin plugin) {
        return new PluginNetwork(plugin);
    }
}
