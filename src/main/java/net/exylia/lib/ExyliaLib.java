package net.exylia.lib;

import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.config.Configs;
import net.exylia.lib.effect.Effects;
import net.exylia.lib.effect.internal.EffectRuntime;
import net.exylia.lib.action.Actions;
import net.exylia.lib.command.Commands;
import net.exylia.lib.clan.internal.ClanRuntime;
import net.exylia.lib.database.Databases;
import net.exylia.lib.format.FormatSettings;
import net.exylia.lib.economy.EconomySettings;
import net.exylia.lib.economy.internal.BalanceCache;
import net.exylia.lib.economy.internal.CurrencyRegistry;
import net.exylia.lib.format.Formats;
import net.exylia.lib.format.internal.FormatPlaceholders;
import net.exylia.lib.skull.internal.SkullRuntime;
import net.exylia.lib.client.internal.ClientRuntime;
import net.exylia.lib.hologram.internal.HologramRuntime;
import net.exylia.lib.internal.ExyliaLibUpdater;
import net.exylia.lib.item.internal.ItemCache;
import net.exylia.lib.ui.Menus;
import net.exylia.lib.ui.internal.MenuListener;
import net.exylia.lib.ui.internal.MenuRuntime;
import net.exylia.lib.internal.LibCommands;
import net.exylia.lib.internal.LibrarySettings;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.placeholder.Placeholders;
import net.exylia.lib.reload.Reloads;
import net.exylia.lib.region.Regions;
import net.exylia.lib.region.internal.RegionListener;
import net.exylia.lib.region.internal.RegionRuntime;
import net.exylia.lib.region.internal.SelectionListener;
import net.exylia.lib.text.Prefixes;
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
    private ConfigFile<FormatSettings> formats;
    private ConfigFile<EconomySettings> economy;

    @Override
    public void onEnable() {
        // First, before anything that could throw. A release that fails to
        // start is exactly the one that most needs to fetch its replacement,
        // and this used to sit at the end of onEnable where a broken version
        // could never reach it.
        startUpdateCheck();

        getServer().getPluginManager().registerEvents(this, this);
        // One listener for every plugin's menus: an inventory event fires once,
        // and the window's holder says whose menu it is.
        getServer().getPluginManager().registerEvents(new MenuListener(), this);
        RegionRuntime.init(this);
        getServer().getPluginManager().registerEvents(new RegionListener(), this);
        getServer().getPluginManager().registerEvents(new SelectionListener(), this);
        loadPalette();
        loadFormats();
        loadEconomy();
        Placeholders.logger(getLogger());
        BuiltIn.register(this);
        FormatPlaceholders.register(this);
        BoardManager.init(this, SidebarLibrary.load(this, getLogger()));
        HologramRuntime.init(this);
        ClientRuntime.init(this);
        ClanRuntime.init(this);
        SkullRuntime.init(this);
        // Starts only the database lifecycle. Each consumer loads database.yml
        // when it asks for its view, and opens lazily on its first repository.
        Databases.init(this);
        Cooldowns.init(this, task -> Tasks.of(this).runAsync(task));
        // Long cooldowns are written every few minutes as well as on quit, so
        // a server that dies without a clean shutdown loses minutes rather
        // than everything.
        Tasks.of(this).runAsyncTimer(
                COOLDOWN_FLUSH_TICKS, COOLDOWN_FLUSH_TICKS, Cooldowns::flushAll);
        LibCommands.register(this);
        getLogger().info("ExyliaLib " + version() + " ready on " + Platform.current() + ".");
    }

    /**
     * Stages a newer release in the background, if there is one.
     *
     * <p>Off the main thread: this talks to the network and the server should
     * not wait on it to finish starting. The shutdown pass is the one that
     * matters for a single-restart update; this pass covers servers that were
     * killed rather than stopped, and releases that cannot finish starting.
     */
    private void startUpdateCheck() {
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
            // A static effect is drawn once and never re-parsed, so without
            // this a permanent boss bar would keep the old colours.
            EffectRuntime.invalidateAll();
            // Same reason: an item with no placeholders is rendered once and
            // copied, so its name and lore hold the previous palette.
            ItemCache.invalidateAll();
            // Plugins that kept something parsed — a menu built at startup —
            // are told, so they can rebuild it. Announced, never invoked.
            Reloads.fireLibraryReload();
        });
    }

    /**
     * Loads the shared number and date formats and keeps them applied across
     * reloads.
     *
     * <p>The same reason as the palette: a plugin says "this is money" and this
     * file decides what money looks like, so a server changes its currency
     * symbol once instead of in two thousand configuration files.
     *
     * <p>Nothing needs invalidating afterwards. Formats are read on every
     * render rather than cached into a component, so applying the new settings
     * is the whole of the reload — a board that re-sends itself for the palette
     * picks up the new symbol on the way.
     */
    private void loadFormats() {
        formats = Configs.define(this, "formats", FormatSettings.class).load();
        Formats.apply(formats.get());
        formats.onReload(Formats::apply);
    }

    /**
     * Reads {@code economy.yml} and finds whichever economies are installed.
     *
     * <p>Detection is reflective and every probe is guarded, so a server with
     * no economy plugin, or with a version whose API moved, starts normally and
     * reports that no currency is available rather than failing to enable.
     *
     * <p>The settings are applied before detection so the first fallback
     * decision already knows the order the owner chose, and re-applied on reload
     * so changing the default currency does not need a restart.
     */
    private void loadEconomy() {
        economy = Configs.define(this, "economy", EconomySettings.class).load();
        CurrencyRegistry.apply(economy.get());
        BalanceCache.apply(economy.get());
        CurrencyRegistry.init(this);
        economy.onReload(settings -> {
            CurrencyRegistry.apply(settings);
            BalanceCache.apply(settings);
        });
    }

    /**
     * Reloads ExyliaLib's own configuration.
     *
     * <p>Exposed so a plugin can refresh the shared palette without a restart.
     */
    public void reloadPalette() {
        palette.reload();
        // Both files are the library's own shared configuration, and a server
        // owner running one reload command means both. Keeping formats.yml on a
        // separate command would guarantee that the one nobody remembers is the
        // one that stays stale.
        formats.reload();
        economy.reload();
    }

    /**
     * Reads the plugin version in a way that works on every platform.
     *
     * <p>Paper deprecates {@code getDescription()} in favour of
     * {@code getPluginMeta()}, but that method does not exist on Spigot, where
     * calling it would fail at runtime. The deprecated call is the portable one.
     *
     * @return the running version
     */
    @SuppressWarnings("deprecation")
    public String version() {
        return getDescription().getVersion();
    }

    @Override
    public void onDisable() {
        // Stage any newer release now, while the server is on its way down.
        // The next start applies plugins/update/ before it loads anything, so
        // staging here is what turns updating into a single restart. Run inline
        // rather than on a thread: the JVM is about to exit and a daemon thread
        // would be killed mid-download. Nobody is playing, so the pause costs
        // nothing, and the updater's own timeouts bound it either way.
        //
        // Before the teardown below, not after: reading the settings goes
        // through Configs, which releaseAll() empties.
        ExyliaLibUpdater.checkForUpdate(this);

        EffectRuntime.stopEverything();
        EffectRuntime.releaseAll();
        // Before releasing tasks: their refresh drivers are among them.
        BoardManager.stopEverything();
        HologramRuntime.removeEverything();
        ClientRuntime.shutdown();
        ClanRuntime.shutdown();
        // Writes the texture cache before tasks go away: the save is inline.
        SkullRuntime.shutdown();
        // Writes whatever is pending before the maps are emptied.
        Cooldowns.clearEverything();
        SidebarLibrary.close();
        Menus.releaseAll();
        Regions.releaseAll();
        // After every plugin has had its own onDisable — they run before this
        // one — so a last write queued there has already been handed to the
        // pool. Before the task module, because the pool's own close is
        // synchronous and cancelling the tasks first would leave it open.
        Databases.releaseAll();
        Tasks.releaseAll();
        Configs.releaseAll();
        Placeholders.releaseAll();
        Actions.releaseAll();
        Commands.releaseAll();
        Prefixes.releaseAll();
        Reloads.releaseAll();
        Debug.releaseAll();
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
        MenuRuntime.forgetEverywhere(event.getPlayer().getUniqueId());
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
        EffectRuntime.release(pluginName);
        BoardManager.stopAll(pluginName);
        HologramRuntime.removeAll(pluginName);
        // Before the task module: closing a window cancels what its buttons
        // started, and a menu whose actions come from a dying classloader
        // must not answer another click.
        Menus.release(pluginName);
        // Reconciliation uses ExyliaLib's scheduler, but publication must still happen
        // before the dying plugin's own task scheduler is cancelled.
        Regions.release(pluginName);
        // Drops the plugin's repositories and datasource lease. A target closes
        // only after its last owning plugin releases it.
        Databases.release(pluginName);
        Tasks.release(pluginName);
        Configs.release(pluginName);
        Placeholders.unregisterAll(pluginName);
        Actions.release(pluginName);
        Commands.release(pluginName);
        Prefixes.release(pluginName);
        Reloads.release(pluginName);
        Debug.release(pluginName);
    }
}
