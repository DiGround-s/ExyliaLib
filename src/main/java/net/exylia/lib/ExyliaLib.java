package net.exylia.lib;

import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.config.Configs;
import net.exylia.lib.platform.Platform;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Colors;
import net.exylia.lib.text.Palette;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Runtime plugin for ExyliaLib.
 *
 * <p>Consumers never touch this class. Everything is used through the static
 * entry points, such as {@link Tasks#of(org.bukkit.plugin.Plugin)}. This plugin
 * only exists so the library is loaded once by the server and so it can clean up
 * after plugins that depend on it.
 *
 * @since 1.0.0
 */
public final class ExyliaLib extends JavaPlugin implements Listener {

    private ConfigFile<Palette> palette;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        loadPalette();
        getLogger().info("ExyliaLib " + version() + " ready on " + Platform.current() + ".");
    }

    /**
     * Loads the shared colour palette and keeps it applied across reloads.
     *
     * <p>The palette lives here rather than in each plugin so a server owner
     * recolours everything from one file.
     */
    private void loadPalette() {
        palette = Configs.define(this, "colors", Palette.class).load();
        Colors.apply(palette.get());
        palette.onReload(Colors::apply);
    }

    /**
     * Reloads ExyliaLib's own configuration.
     *
     * <p>Exposed so a plugin can refresh the shared palette without a restart.
     */
    public void reloadPalette() {
        palette.reload();
    }

    /**
     * Reads the plugin version in a way that works on every platform.
     *
     * <p>Paper deprecates {@code getDescription()} in favour of
     * {@code getPluginMeta()}, but that method does not exist on Spigot, where
     * calling it would fail at runtime. The deprecated call is the portable one.
     */
    @SuppressWarnings("deprecation")
    private String version() {
        return getDescription().getVersion();
    }

    @Override
    public void onDisable() {
        Tasks.releaseAll();
        Configs.releaseAll();
    }

    /**
     * Releases a plugin's resources when it is disabled.
     *
     * <p>Runs at {@link EventPriority#MONITOR} so the plugin's own
     * {@code onDisable} has already finished and cannot schedule anything else.
     * This matters most on Folia, whose region and entity schedulers otherwise
     * keep running tasks belonging to a plugin that is already gone.
     *
     * @param event the disable event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        String pluginName = event.getPlugin().getName();
        Tasks.release(pluginName);
        Configs.release(pluginName);
    }
}
