package net.exylia.lib;

import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.config.Configs;
import net.exylia.lib.effect.Effects;
import net.exylia.lib.effect.internal.EffectRuntime;
import net.exylia.lib.action.Actions;
import net.exylia.lib.command.Commands;
import net.exylia.lib.clan.internal.ClanRuntime;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.transfer.Transfers;
import net.exylia.lib.format.FormatSettings;
import net.exylia.lib.economy.EconomySettings;
import net.exylia.lib.economy.internal.BalanceCache;
import net.exylia.lib.economy.internal.CurrencyRegistry;
import net.exylia.lib.format.Formats;
import net.exylia.lib.input.InputSettings;
import net.exylia.lib.input.Inputs;
import net.exylia.lib.input.internal.Bedrocks;
import net.exylia.lib.input.internal.ChatTransport;
import net.exylia.lib.input.internal.DialogTransport;
import net.exylia.lib.input.internal.InputListener;
import net.exylia.lib.input.internal.InputRuntime;
import net.exylia.lib.format.internal.FormatPlaceholders;
import net.exylia.lib.skull.internal.SkullRuntime;
import net.exylia.lib.schematic.internal.SchematicRuntime;
import net.exylia.lib.client.internal.ClientRuntime;
import net.exylia.lib.nametag.internal.NametagRuntime;
import net.exylia.lib.util.combat.internal.CombatRuntime;
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
import net.exylia.lib.redis.internal.RedisRuntime;
import net.exylia.lib.reload.Reloads;
import net.exylia.lib.region.Regions;
import net.exylia.lib.region.internal.PlacedBlockListener;
import net.exylia.lib.region.internal.RegionListener;
import net.exylia.lib.region.internal.RegionRuntime;
import net.exylia.lib.region.internal.SelectionListener;
import net.exylia.lib.text.Prefixes;
import net.exylia.lib.util.Cooldowns;
import net.exylia.lib.util.preview.Previews;
import net.exylia.lib.util.teleport.Teleports;
import net.exylia.lib.util.reward.Rewards;
import net.exylia.lib.util.sequence.Sequences;
import net.exylia.lib.util.snapshot.Snapshots;
import net.exylia.lib.util.snapshot.internal.SnapshotRuntime;
import net.exylia.lib.util.wizard.Wizards;
import net.exylia.lib.util.wizard.internal.WizardRuntime;
import net.exylia.lib.placeholder.internal.BuiltIn;
import net.exylia.lib.platform.Platform;
import net.exylia.lib.scoreboard.internal.BoardManager;
import net.exylia.lib.session.Sessions;
import net.exylia.lib.scoreboard.internal.SidebarLibrary;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Colors;
import net.exylia.lib.text.Palette;
import net.exylia.lib.text.internal.TextEngine;
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
    private ConfigFile<InputSettings> input;

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
        // One listener for every plugin's questions, for the same reason: a
        // chat or inventory event fires once and the session says whose it is.
        getServer().getPluginManager().registerEvents(new InputListener(), this);
        RegionRuntime.init(this);
        getServer().getPluginManager().registerEvents(new RegionListener(), this);
        // Block ownership, for the regions that declare they need it. Dormant
        // otherwise: both handlers read one volatile flag and return.
        getServer().getPluginManager().registerEvents(new PlacedBlockListener(), this);
        getServer().getPluginManager().registerEvents(new SelectionListener(), this);
        loadPalette();
        loadFormats();
        loadEconomy();
        loadInput();
        Placeholders.logger(getLogger());
        BuiltIn.register(this);
        FormatPlaceholders.register(this);
        BoardManager.init(this, SidebarLibrary.load(this, getLogger()));
        HologramRuntime.init(this);
        ClientRuntime.init(this);
        NametagRuntime.init(this);
        MenuRuntime.init(this);
        ClanRuntime.init(this);
        CombatRuntime.init(this);
        SkullRuntime.init(this);
        // Binds FastAsyncWorldEdit if the server has it, and says so once here
        // rather than once per refused call: whether an engine exists is a fact
        // about the server, not about any one request.
        SchematicRuntime.init(this);
        net.exylia.lib.util.preview.internal.PreviewRuntime.init(this);
        net.exylia.lib.util.teleport.internal.TeleportRuntime.init(this);
        // One listener for every plugin's wizards, for the same reason menus
        // and questions have one: a block click fires once, and the run that
        // owns it is found by player rather than by plugin.
        WizardRuntime.init(this);
        // Starts only the database lifecycle. Each consumer loads database.yml
        // when it asks for its view, and opens lazily on its first repository.
        Databases.init(this);
        // Holds only this plugin reference, so that an export or an import runs
        // on the library's scheduler: a transfer scheduled on the consumer's own
        // would be cancelled halfway through a file the moment that consumer is
        // disabled.
        Transfers.init(this);
        // Registers the snapshot codec, which has to happen before anything can
        // compile a model that uses it: a codec registered afterwards never
        // reaches a model that is already compiled.
        SnapshotRuntime.init(this);
        // Only binds the debug view. The registry itself is a plain map with no
        // lifecycle: a claim exists because a plugin took one, and the module
        // has nothing to start.
        Sessions.init(this);
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
        LibrarySettings settings = LibrarySettings.load(this);
        Debug.all(settings.debug());
        // Applied before anything can parse a line, so the first item built
        // during startup is drawn in the style the owner asked for rather
        // than being cached in the other one.
        TextEngine.smallText(settings.smallText());
        SkullRuntime.fallback(settings.fallbackHead());
        Thread updateThread = new Thread(
            () -> ExyliaLibUpdater.checkForUpdate(this),
            "ExyliaLib-Updater");
        updateThread.setDaemon(true);
        updateThread.start();

        // Keep looking while the server runs. The shutdown pass is what makes
        // an update a single restart, but it only happens on a clean stop: a
        // crash, a kill -9 or a host reboot never reaches onDisable, and that
        // server would sit on an old jar until someone stopped it properly.
        //
        // Releases in between are handled by the same code, because the check
        // compares against the version now running rather than against the one
        // already staged: the newest release wins and simply overwrites it.
        int minutes = settings.updateCheckMinutes();
        if (settings.autoUpdate() && minutes > 0) {
            long ticks = minutes * 60L * 20L;
            Tasks.of(this).runAsyncTimer(
                    ticks, ticks, () -> ExyliaLibUpdater.checkForUpdate(this));
        }
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
     * Reads {@code input.yml} and starts the question runtime.
     *
     * <p>Transports are discovered reflectively, so a server without
     * PacketEvents or Floodgate simply has fewer ways to ask and falls back to
     * chat and menus rather than failing to start.
     *
     * <p>The Bedrock prefix comes from {@code config.yml} rather than from
     * here: it says which players are on Bedrock, which anything that adapts to
     * the client needs, not only the part that asks them questions.
     */
    private void loadInput() {
        input = Configs.define(this, "input", InputSettings.class).load();
        applyInput(input.get());
        input.onReload(this::applyInput);
        InputRuntime.init(this);
    }

    private void applyInput(InputSettings settings) {
        Inputs.defaultTimeout(java.time.Duration.ofSeconds(Math.max(1, settings.timeoutSeconds())));
        ChatTransport.setCancelWord(settings.cancelWord());
        DialogTransport.enabled(settings.preferDialogs());
        Bedrocks.prefix(LibrarySettings.get().bedrockPrefix());
    }

    /**
     * Reloads ExyliaLib's own configuration.
     *
     * <p>Exposed so a plugin can refresh the shared palette without a restart.
     */
    public void reloadPalette() {
        // The debug switch too: a server owner turning it on to chase a bug
        // should not have to restart to see the lines it was meant to show.
        LibrarySettings settings = LibrarySettings.reload();
        Debug.all(settings.debug());
        // Before the palette reloads, not after: switching the style changes
        // what every cached component looks like, and the palette's own
        // listener already re-sends every board, hologram, effect and item.
        // Applying it afterwards would leave all of them holding the previous
        // style until something else happened to invalidate them.
        TextEngine.smallText(settings.smallText());
        // Before ItemCache is invalidated below, so a menu rebuilt by the
        // palette listener already sees the new fallback rather than the old
        // one baked in.
        SkullRuntime.fallback(settings.fallbackHead());
        palette.reload();
        // Both files are the library's own shared configuration, and a server
        // owner running one reload command means both. Keeping formats.yml on a
        // separate command would guarantee that the one nobody remembers is the
        // one that stays stale.
        formats.reload();
        economy.reload();
        input.reload();
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
        Previews.releaseAll();
        Teleports.releaseAll();
        Sequences.releaseAll();
        // Before releasing tasks: their refresh drivers are among them.
        BoardManager.stopEverything();
        HologramRuntime.removeEverything();
        ClientRuntime.shutdown();
        NametagRuntime.shutdown();
        ClanRuntime.shutdown();
        CombatRuntime.shutdown();
        // Writes the texture cache before tasks go away: the save is inline.
        SkullRuntime.shutdown();
        // Before the task module, for the same reason a plugin's release is,
        // plus one: releasing the engine drops every loaded clipboard, and
        // those are tens of megabytes each.
        SchematicRuntime.shutdown();
        // Writes whatever is pending before the maps are emptied.
        Cooldowns.clearEverything();
        SidebarLibrary.close();
        // Before the modules a run borrows from, for the same reason its
        // per-plugin release goes first: ending one gives back a question, a
        // block selector and a menu.
        Wizards.releaseAll();
        // Before the task module: a pending question owns a timeout task, and
        // a player left staring at a form nobody will answer is worse than one
        // told the server is stopping.
        InputRuntime.shutdown();
        Inputs.releaseAll();
        Menus.releaseAll();
        Regions.releaseAll();
        // Before the database module, for the same reason a plugin's release is:
        // a pending store is somebody's repository.
        Rewards.releaseAll();
        // Same reason, and nothing else: every stored snapshot was durable the
        // moment it was taken, so a shutdown has nothing left to write.
        Snapshots.releaseAll();
        // After every plugin has had its own onDisable — they run before this
        // one — so a last write queued there has already been handed to the
        // pool. Before the task module, because the pool's own close is
        // synchronous and cancelling the tasks first would leave it open.
        Databases.releaseAll();
        // Nothing to close: a transfer in flight owns its own streams and shuts
        // them in a finally. This drops the library reference so a transfer
        // asked for after this point is refused rather than scheduled onto a
        // plugin that is gone.
        Transfers.releaseAll();
        // After the datasources, because a repository closing may still write,
        // and a write still caches. Closing the cache first would leave the
        // last writes of a shutdown invisible to the rest of the network.
        RedisRuntime.shutdown();
        Tasks.releaseAll();
        Configs.releaseAll();
        Placeholders.releaseAll();
        Actions.releaseAll();
        Commands.releaseAll();
        Prefixes.releaseAll();
        Reloads.releaseAll();
        Sessions.releaseAll();
        Debug.releaseAll();
    }

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
        NametagRuntime.forget(event.getPlayer());
        ClanRuntime.forget(event.getPlayer().getUniqueId());
        CombatRuntime.forget(event.getPlayer().getUniqueId());
        Cooldowns.forget(event.getPlayer().getUniqueId());
        MenuRuntime.forgetEverywhere(event.getPlayer().getUniqueId());
        // Before the input module: ending a run cancels the question it was
        // waiting on, and a question already forgotten cannot be cancelled.
        WizardRuntime.forget(event.getPlayer().getUniqueId());
        InputRuntime.forget(event.getPlayer().getUniqueId());
        // A tick later, not now: every plugin's own quit handling has to have
        // had its turn first, because a mode that puts a leaving player's
        // things back releases its own claim as part of doing so. What is left
        // by then is a claim somebody forgot, and dropping it is the difference
        // between a player who can rejoin and one nothing will accept.
        java.util.UUID left = event.getPlayer().getUniqueId();
        Tasks.of(this).runLater(1L, () -> Sessions.forget(left));
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
        // An outgoing metadata packet carries an entity id and nothing else, so
        // the way back to the player has to be recorded while they are here.
        NametagRuntime.register(player);
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

    /**
     * Releases a plugin's resources when it is disabled.
     *
     * <p>Runs at {@link EventPriority#MONITOR} so no other listener can still
     * be reacting to the disable when the modules go away. It does <em>not</em>
     * run after the plugin's own {@code onDisable}: both Bukkit's
     * {@code JavaPluginLoader} and Paper's {@code PaperPluginInstanceManager}
     * fire this event and only then call {@code setEnabled(false)}, which is
     * what invokes {@code onDisable}. This method is therefore the last thing
     * before the plugin's own teardown, not the first thing after it.
     *
     * <p>That ordering splits the work in two. What is released here is what
     * must not outlive the classloader: a button whose action comes from a
     * dying plugin must not answer another click, and a packet must not be
     * sent on behalf of code that is going away. What a plugin legitimately
     * uses <em>from</em> {@code onDisable} — its repositories above all, since
     * a last save is the single most common thing an {@code onDisable} does —
     * is deferred by {@link #releaseAfterDisable(String)} instead.
     *
     * @param event the disable event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        String pluginName = event.getPlugin().getName();
        // Before the task module: a display cancels its own task when stopped,
        // and doing it the other way round would leave the effect on screen.
        EffectRuntime.stopAll(pluginName);
        EffectRuntime.release(pluginName);
        // Before the sequence module: a preview owns a run and must put its
        // player back before that run is cancelled underneath it.
        Previews.release(pluginName);
        // Before the task module, for the same reason: a sequence schedules the
        // frames of its own animation, and a frame belonging to a classloader
        // that is going away must not fire.
        Sequences.release(pluginName);
        BoardManager.stopAll(pluginName);
        HologramRuntime.removeAll(pluginName);
        // Deleting a team clears its members' markers, and taking down this
        // plugin's waypoints and cooldowns is a packet too — all of them to
        // players who are still here. It has to happen while the client module
        // is still up, not on the way out with the rest of the shutdown.
        ClientRuntime.release(pluginName);
        // Same reason: putting a nametag back to normal is a packet to a player
        // who is still on the server.
        NametagRuntime.release(pluginName);
        // Before all three of the modules a run borrows from, because ending
        // one hands work back to each of them: it cancels the question it was
        // waiting on, releases the player's block selector, and schedules the
        // menu it promised to reopen. Released after any of them, a run would
        // be reaching into a module that is already gone.
        Wizards.release(pluginName);
        // Before the task module: closing a window cancels what its buttons
        // started, and a menu whose actions come from a dying classloader
        // must not answer another click.
        Menus.release(pluginName);
        // Same reason, and one more: a pending question holds a future the
        // dying plugin is waiting on. Ending it as SHUT_DOWN releases that
        // waiter; leaving it would hand a player a form whose answer has
        // nowhere left to go.
        InputRuntime.releasePlugin(pluginName);
        Inputs.release(pluginName);
        // Reconciliation uses ExyliaLib's scheduler, but publication must still happen
        // before the dying plugin's own task scheduler is cancelled.
        Regions.release(pluginName);
        // Before the task module: a pending warmup owns an entity timer
        // belonging to this plugin and must be cancelled before its scheduler
        // goes away.
        Teleports.release(pluginName);
        // Same reason, and it is the whole point of this module's cleanup: a
        // paste in flight is a chain of tasks on that plugin's own scheduler,
        // so cancelling the scheduler first would leave the stage that
        // completes the future unable to run — a caller waiting forever, which
        // is exactly the ExyliaCommons bug this module exists to fix.
        SchematicRuntime.release(pluginName);
        Tasks.release(pluginName);
        Placeholders.unregisterAll(pluginName);
        Actions.release(pluginName);
        Commands.release(pluginName);
        Prefixes.release(pluginName);
        Reloads.release(pluginName);
        // Only the record, never the handlers: undoing a hold means calling
        // into a classloader that is being dismantled. A player left mid
        // activity by a plugin that is going away is free again, which is the
        // one state the rest of the server can still act on.
        Sessions.forgetPlugin(pluginName);

        // Everything a plugin still uses from its own onDisable, which has not
        // run yet. Scheduled on the library's scheduler, which is still up:
        // the dying plugin's own was just cancelled above.
        Tasks.of(this).run(() -> releaseAfterDisable(pluginName));
    }

    /**
     * Releases the modules a plugin is still entitled to use while it shuts
     * itself down.
     *
     * <p>Runs one tick after {@link PluginDisableEvent}, which is the first
     * moment the plugin's {@code onDisable} is guaranteed to have finished —
     * the event fires before it, not after. Released from the event itself,
     * these took the plugin's database out from under its last save: a
     * {@code saveAll} issued from {@code onDisable} arrived at a target whose
     * final owner had already let go, so it came back as "the database target
     * is closing because its last plugin was disabled" and the rows were lost.
     * Silently: the write failed on the way out, when nobody is watching the
     * console and the server is seconds from exiting.
     *
     * <p>On a full server stop this task never runs, because the scheduler
     * stops with the server. That is not a leak and not a second path: the
     * whole-server teardown in {@link #onDisable()} releases the same modules,
     * and it runs after every plugin's {@code onDisable} for the same reason
     * this one does. This method exists for the case the shutdown path cannot
     * cover — a single plugin disabled while the server keeps running.
     *
     * @param pluginName the plugin that was disabled
     */
    private void releaseAfterDisable(String pluginName) {
        // Before the database module: a claim reads the plugin's pending table,
        // and a reward handed to a player whose plugin is going away must not be
        // marked as claimed by a repository that is about to close.
        Rewards.release(pluginName);
        // Before the database module for the same reason, and for no other: a
        // snapshot this plugin took is already a row, so nothing has to be
        // written on the way out. Forgetting the repository is the whole job.
        Snapshots.release(pluginName);
        // Drops the plugin's repositories and datasource lease. A target closes
        // only after its last owning plugin releases it.
        Databases.release(pluginName);
        // After the database module: resolving a datasource reads the plugin's
        // database.yml through Configs, so forgetting its files first would
        // strand a repository asked for during the plugin's own teardown.
        Configs.release(pluginName);
        // Last: everything above reports through it, so a line written while
        // the plugin lets go still names the plugin it belongs to.
        Debug.release(pluginName);
    }
}
