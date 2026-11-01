package net.exylia.lib.util.internal;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Reflective hooks into claim plugins, resolved once per plugin instance.
 *
 * <p>Shared by {@link ClaimPvp} and {@link ClaimBuild}: a hook is built through
 * the plugin's own class loader the first time it is asked for, so a reloaded
 * plugin is resolved again and a missing one costs a plugin lookup. A hook that
 * cannot be built, or {@link #broken breaks} when asked, is logged once and
 * reads as absent from then on.
 *
 * @param <H> the hook type
 */
public final class ClaimHooks<H> {

    private static final Logger LOGGER = Logger.getLogger("ExyliaLib");

    /** Builds a hook from the enabled plugin. */
    @FunctionalInterface
    public interface Factory<H> {
        @Nullable H create(@NotNull Plugin plugin) throws ReflectiveOperationException;
    }

    private record Resolved<H>(Plugin plugin, H hook) {
    }

    private final String feature;
    private final String whenAbsent;
    private final Map<String, Resolved<H>> hooks = new HashMap<>();

    /**
     * @param feature    what the hooks serve, for the log ("PvP zones")
     * @param whenAbsent how a missing hook is read, for the log ("allowing PvP everywhere")
     */
    public ClaimHooks(@NotNull String feature, @NotNull String whenAbsent) {
        this.feature = feature;
        this.whenAbsent = whenAbsent;
    }

    /** The enabled plugin by name, or {@code null}. */
    public static @Nullable Plugin enabled(@NotNull String pluginName) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(pluginName);
        return plugin != null && plugin.isEnabled() ? plugin : null;
    }

    /** The hook for this plugin instance, building it on first use; {@code null} when it cannot be built. */
    public @Nullable H hook(@NotNull Plugin plugin, @NotNull Factory<H> factory) {
        String name = plugin.getName();
        Resolved<H> resolved;
        synchronized (hooks) {
            resolved = hooks.get(name);
        }
        if (resolved != null && resolved.plugin == plugin) {
            return resolved.hook;
        }
        H hook;
        try {
            hook = factory.create(plugin);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            LOGGER.warning(feature + ": could not hook " + name + " " + plugin.getPluginMeta().getVersion()
                    + "; it is treated as " + whenAbsent + " (" + failure + ")");
            hook = null;
        }
        synchronized (hooks) {
            hooks.put(name, new Resolved<>(plugin, hook));
        }
        return hook;
    }

    /** Forgets a hook that failed when asked, so it is never asked again for this plugin instance. */
    public void broken(@NotNull Plugin plugin, @NotNull Throwable failure) {
        LOGGER.warning(feature + ": " + plugin.getName() + " failed when asked and is ignored from now on (" + failure + ")");
        synchronized (hooks) {
            hooks.put(plugin.getName(), new Resolved<>(plugin, null));
        }
    }

    /** A class of the plugin, loaded through its own class loader. */
    public static @NotNull Class<?> type(@NotNull Plugin plugin, @NotNull String name) throws ClassNotFoundException {
        return Class.forName(name, true, plugin.getClass().getClassLoader());
    }
}
