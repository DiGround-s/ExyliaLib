package net.exylia.lib.metrics.internal;

import com.destroystokyo.paper.event.server.ServerExceptionEvent;
import com.destroystokyo.paper.exception.ServerCommandException;
import com.destroystokyo.paper.exception.ServerException;
import com.destroystokyo.paper.exception.ServerPluginEnableDisableException;
import com.destroystokyo.paper.exception.ServerPluginException;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * What Paper reports as thrown by a plugin: listeners, commands, Bukkit tasks,
 * plugin messages, and enabling or disabling.
 *
 * <p>A class of its own because the event is Paper's: on Spigot it does not
 * exist, and registering a listener that names it would fail the whole
 * registration. {@link MetricsRuntime} registers this only on Paper.
 *
 * <p>Tasks scheduled through the library never arrive here — its own wrapper
 * catches them first and reports them itself — so nothing is counted twice.
 */
final class ServerErrors implements Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerException(ServerExceptionEvent event) {
        try {
            ServerException exception = event.getException();
            Plugin plugin = null;
            String phase = "runtime";
            if (exception instanceof ServerPluginException pluginException) {
                plugin = pluginException.getResponsiblePlugin();
                if (exception instanceof ServerPluginEnableDisableException) {
                    String message = String.valueOf(exception.getMessage());
                    phase = message.contains("disabl") ? "disable" : "enable";
                }
            } else if (exception instanceof ServerCommandException commandException
                    && commandException.getCommand() instanceof PluginIdentifiableCommand command) {
                plugin = command.getPlugin();
            }
            // Paper's wrapper only says where it was caught; what went wrong is
            // its cause, which carries any further wrapper and the root cause.
            Throwable error = exception.getCause() != null ? exception.getCause() : exception;
            MetricsRuntime.error(plugin, phase, error);
        } catch (Throwable ignored) {
            // Metrics must never become the next exception.
        }
    }
}
