package net.exylia.lib.internal;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredListener;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;

/**
 * Registers the permissions a plugin's own {@code plugin.yml} declares when the
 * server does not know them yet.
 *
 * <p>The server reads permissions from the jar it loaded, which for a premium
 * plugin is the Lukittu loader. The loader downloads the newest plugin on its
 * own but its {@code plugin.yml} stays whatever the server owner installed, so
 * a node added in an update is undeclared there, and an undeclared node is
 * op-only: a player feature meant for everybody ({@code default: true}) is
 * refused to every player. The plugin's code does reach the server, so its
 * bundled {@code plugin.yml} is read from the classes it runs and every node the
 * server lacks is added with its declared default. A node the server already
 * has is left alone.
 */
public final class DeclaredPermissions implements Listener {

    private final Plugin library;

    public DeclaredPermissions(Plugin library) {
        this.library = library;
    }

    /** Covers the plugins enabled before the library; later ones arrive through the event. */
    public void sweep() {
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            if (plugin.isEnabled()) declare(plugin);
        }
    }

    @EventHandler
    public void onEnable(PluginEnableEvent event) {
        declare(event.getPlugin());
    }

    private void declare(Plugin plugin) {
        // The inner plugin's classes come from their own class loader, which only
        // shows through what it registered: its listeners.
        // ponytail: listeners only; a plugin that registers none keeps the loader's list.
        Set<ClassLoader> loaders = new HashSet<>();
        loaders.add(plugin.getClass().getClassLoader());
        for (RegisteredListener listener : org.bukkit.event.HandlerList.getRegisteredListeners(plugin)) {
            loaders.add(listener.getListener().getClass().getClassLoader());
        }
        PluginManager manager = Bukkit.getPluginManager();
        int added = 0;
        for (ClassLoader loader : loaders) {
            // getResourceAsStream, not getResources: Lukittu's loader serves the
            // plugin's files from memory through this method only.
            try (InputStream in = loader.getResourceAsStream("plugin.yml")) {
                if (in == null) continue;
                PluginDescriptionFile description = new PluginDescriptionFile(in);
                if (!description.getName().equals(plugin.getName())) continue;
                for (Permission permission : description.getPermissions()) {
                    if (manager.getPermission(permission.getName()) != null) continue;
                    manager.addPermission(permission);
                    added++;
                }
            } catch (Exception e) {
                library.getLogger().log(Level.FINE, "Could not read the permissions of " + plugin.getName(), e);
            }
        }
        if (added > 0) {
            library.getLogger().info("Declared " + added + " permission(s) that " + plugin.getName()
                + " added since its loader jar was installed.");
        }
    }
}
