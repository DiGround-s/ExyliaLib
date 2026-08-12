package net.exylia.lib;

import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.config.Configs;
import net.exylia.lib.effect.Effects;
import net.exylia.lib.effect.internal.EffectRuntime;
import net.exylia.lib.clan.internal.ClanRuntime;
import net.exylia.lib.client.internal.ClientRuntime;
import net.exylia.lib.hologram.internal.HologramRuntime;
import net.exylia.lib.internal.ExyliaLibUpdater;
import net.exylia.lib.internal.LibrarySettings;
import net.exylia.lib.placeholder.Placeholders;
import net.exylia.lib.util.Cooldowns;
import net.exylia.lib.placeholder.internal.BuiltIn;
import net.exylia.lib.platform.Platform;
import net.exylia.lib.scoreboard.internal.BoardManager;
import net.exylia.lib.scoreboard.internal.SidebarLibrary;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Colors;
import net.exylia.lib.text.Palette;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
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

    /**
     * How long to wait before asking what client a player runs.
     *
     * <p>Modified clients announce themselves a moment after joining, so a
     * question asked immediately gets "vanilla" and that answer would be
     * remembered for the whole session.
     */
    private static final long CLIENT_HANDSHAKE_TICKS = 20L;

    /**
     * How often long cooldowns are written out: five minutes.
     *
     * <p>Matched to the threshold that makes a cooldown persistent in the
     * first place, so nothing worth saving can be lost by more than the
     * interval that decided it was worth saving.
     */
    private static final long COOLDOWN_FLUSH_TICKS = 20L * 60L * 5L;

    private ConfigFile<Palette> palette;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        loadPalette();
        Placeholders.logger(getLogger());
        BuiltIn.register(this);
        BoardManager.init(this, SidebarLibrary.load(this, getLogger()));
        HologramRuntime.init(this);
        ClientRuntime.init(this);
        ClanRuntime.init(this);
        Cooldowns.init(this, task -> Tasks.of(this).runAsync(task));
        // Long cooldowns are written every few minutes as well as on quit, so
        // a server that dies without a clean shutdown loses minutes rather
        // than everything.
        Tasks.of(this).runAsyncTimer(
                COOLDOWN_FLUSH_TICKS, COOLDOWN_FLUSH_TICKS, Cooldowns::flushAll);

        getLogger().info("ExyliaLib " + version() + " ready on " + Platform.current() + ".");

        // Check for updates asynchronously — never block the main thread.
        LibrarySettings.load(this);
        Thread updateThread = new Thread(
            () -> ExyliaLibUpdater.checkForUpdate(this),
            "ExyliaLib-Updater");
        updateThread.setDaemon(true);
        updateThread.start();
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
        palette.onReload(values -> {
            Colors.apply(values);
            // The text of a board is unchanged, but what it parses into is not.
            BoardManager.invalidateAll();
            HologramRuntime.invalidateAll();
        });
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
        EffectRuntime.stopEverything();
        // Before releasing tasks: their refresh drivers are among them.
        BoardManager.stopEverything();
        HologramRuntime.removeEverything();
        ClientRuntime.shutdown();
        ClanRuntime.shutdown();
        // Writes whatever is pending before the maps are emptied.
        Cooldowns.clearEverything();
        SidebarLibrary.close();
        Tasks.releaseAll();
        Configs.releaseAll();
        Placeholders.releaseAll();
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
    /**
     * Stops a leaving player's effects.
     *
     * <p>Without this they would leak. The task module stops an entity timer
     * once the entity is gone, which means a display driven by one is never
     * told that its viewer left: its task is cancelled, but the display itself
     * stays registered and is never cleaned up.
     *
     * @param event the quit event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Effects.stopFor(event.getPlayer());
        BoardManager.stopFor(event.getPlayer());
        HologramRuntime.forget(event.getPlayer());
        ClientRuntime.forget(event.getPlayer());
        ClanRuntime.forget(event.getPlayer().getUniqueId());
        Cooldowns.forget(event.getPlayer().getUniqueId());
    }

    /**
     * Re-sends a player's board after a world change.
     *
     * <p>The client can lose the sidebar objective when it switches worlds, and
     * another plugin is free to claim it in between.
     *
     * @param event the world change event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        BoardManager.reinit(event.getPlayer());
        // Feather drops waypoints along with the world they belonged to.
        ClientRuntime.resend(event.getPlayer(), true);
    }

    /**
     * Puts back what a player's modified client forgot while they were away.
     *
     * <p>Delayed on purpose: a modified client announces itself a moment after
     * joining, so asking immediately would find a vanilla player and remember
     * that answer for the whole session.
     *
     * @param event the join event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        org.bukkit.entity.Player player = event.getPlayer();
        // Reading a file is not something the main thread should wait for.
        java.util.UUID id = player.getUniqueId();
        Tasks.of(this).runAsync(() -> Cooldowns.load(id));
        // An entity timer dies with its entity, so a player who leaves during
        // the wait costs nothing and needs no online check of its own.
        Tasks.of(this).runAtEntityLater(player, CLIENT_HANDSHAKE_TICKS, () -> {
            ClientRuntime.forget(player);
            ClientRuntime.resend(player, false);
        });
    }

    /**
     * Re-sends a player's board after a respawn, for the same reason as a
     * world change.
     *
     * @param event the respawn event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        BoardManager.reinit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        String pluginName = event.getPlugin().getName();
        // Before the task module: a display cancels its own task when stopped,
        // and doing it the other way round would leave the effect on screen.
        EffectRuntime.stopAll(pluginName);
        BoardManager.stopAll(pluginName);
        HologramRuntime.removeAll(pluginName);
        Tasks.release(pluginName);
        Configs.release(pluginName);
        Placeholders.unregisterAll(pluginName);
    }
}
