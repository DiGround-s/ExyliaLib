package net.exylia.lib.economy.internal;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Notices an economy that arrives after this library did.
 *
 * <h2>Why detection at enable is not enough</h2>
 * {@link CurrencyRegistry#detect} asks Vault's {@code ServicesManager} for an
 * economy, and that service is registered by whichever economy plugin the
 * server runs — EssentialsX, CMI, a custom one — not by Vault itself. A
 * {@code softdepend} can name Vault, but it cannot name every plugin that might
 * register the service, so a single sweep at enable is a race the library loses
 * on any server whose economy plugin happens to start later.
 *
 * <p>When it lost, it lost silently and for the whole session: nothing retried,
 * and reloading the configuration re-applied settings without probing again. A
 * server owner with Vault and an economy installed saw every feature that costs
 * money quietly report that no economy existed.
 *
 * <p>So detection stops being a moment and becomes a subscription. Both hooks
 * are needed because the two built-in providers announce themselves
 * differently: Vault's economy appears as a <em>service</em>, and PlayerPoints
 * appears as a <em>plugin</em>.
 *
 * <p>{@link CurrencyRegistry#detect} already refuses to replace a provider that
 * is registered, so running it again is free and cannot displace a provider a
 * plugin registered itself.
 */
public final class EconomyWatcher implements Listener {

    /** The service Vault publishes, named as text so Vault's absence is not a problem. */
    private static final String VAULT_ECONOMY = "net.milkbowl.vault.economy.Economy";

    private final Plugin plugin;

    private EconomyWatcher(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Starts watching for economies that arrive late.
     *
     * @param plugin the library plugin
     */
    public static void install(@NotNull Plugin plugin) {
        Bukkit.getPluginManager().registerEvents(new EconomyWatcher(plugin), plugin);
    }

    /**
     * An economy plugin registered itself with Vault.
     *
     * <p>The only reliable signal, because the plugin doing the registering is
     * one this library cannot know the name of.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onServiceRegister(ServiceRegisterEvent event) {
        if (!VAULT_ECONOMY.equals(event.getProvider().getService().getName())) {
            return;
        }
        CurrencyRegistry.detect(plugin);
    }

    /**
     * A provider that is a plugin rather than a service turned up.
     *
     * <p>PlayerPoints is detected by being installed and enabled, so its own
     * enable is the moment to look again. Vault is named too: a server where
     * Vault enables after this library needs the sweep repeated even though the
     * economy behind it was registered before.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginEnable(PluginEnableEvent event) {
        String name = event.getPlugin().getName();
        if (name.equals("Vault") || name.equals("PlayerPoints")) {
            CurrencyRegistry.detect(plugin);
        }
    }
}
