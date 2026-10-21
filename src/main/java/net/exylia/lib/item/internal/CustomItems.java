package net.exylia.lib.item.internal;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Items that belong to a resource-pack plugin: Nexo, Oraxen, ItemsAdder and
 * CraftEngine.
 *
 * <p>A server running one of them writes its items by id, and every place that
 * reads a {@code material} accepts that spelling:
 *
 * <pre>{@code
 * material: "nexo:ruby_sword"
 * material: "oraxen:amethyst_pickaxe"
 * material: "itemsadder:iasurvival:ruby"   # or ia:
 * material: "craftengine:default:topaz"    # or ce:
 * }</pre>
 *
 * <p>What comes back is the plugin's own item — model, components, its own name
 * and lore — and whatever wrote the {@code material} then writes on top of it,
 * exactly as it would on a diamond sword. The other direction is
 * {@link #written(ItemStack)}: an icon picker holding one of these items stores
 * its id rather than its bytes, so the icon follows the pack when the pack
 * changes.
 *
 * <p>None of these plugins is a dependency. Each is reached by reflection over
 * the two or three static calls of its public API, loaded through the plugin's
 * own class loader, and only once the plugin is enabled. They publish their API
 * from four different repositories, one of which is not reachable from a build;
 * compiling against them would make this library's build depend on all four
 * being up. An API that changes shape is reported once and treated as absent.
 *
 * <p>Thread contract: the lookups ask the providers, which build items from
 * their own registries; call them where an item would be built anyway.
 */
public final class CustomItems {

    private static final Logger LOGGER = Logger.getLogger("ExyliaLib");
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private CustomItems() {
    }

    /** One supported plugin, and how its API is spelled. */
    private enum Provider {
        NEXO("Nexo", "com.nexomc.nexo.api.events.NexoItemsLoadedEvent", "nexo") {
            @Override
            Bridge bridge(ClassLoader loader) throws ReflectiveOperationException {
                Class<?> api = Class.forName("com.nexomc.nexo.api.NexoItems", true, loader);
                Method byId = api.getMethod("itemFromId", String.class);
                return new Bridge(byId, byId.getReturnType().getMethod("build"), null,
                        api.getMethod("idFromItem", ItemStack.class), null);
            }
        },
        ORAXEN("Oraxen", "io.th0rgal.oraxen.api.events.OraxenItemsLoadedEvent", "oraxen") {
            @Override
            Bridge bridge(ClassLoader loader) throws ReflectiveOperationException {
                Class<?> api = Class.forName("io.th0rgal.oraxen.api.OraxenItems", true, loader);
                Method byId = api.getMethod("getItemById", String.class);
                return new Bridge(byId, byId.getReturnType().getMethod("build"), null,
                        api.getMethod("getIdByItem", ItemStack.class), null);
            }
        },
        ITEMS_ADDER("ItemsAdder", "dev.lone.itemsadder.api.Events.ItemsAdderLoadDataEvent", "itemsadder", "ia") {
            @Override
            Bridge bridge(ClassLoader loader) throws ReflectiveOperationException {
                Class<?> api = Class.forName("dev.lone.itemsadder.api.CustomStack", true, loader);
                return new Bridge(api.getMethod("getInstance", String.class), api.getMethod("getItemStack"), null,
                        api.getMethod("byItemStack", ItemStack.class), api.getMethod("getNamespacedID"));
            }
        },
        CRAFT_ENGINE("CraftEngine", "net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent",
                "craftengine", "ce") {
            @Override
            Bridge bridge(ClassLoader loader) throws ReflectiveOperationException {
                Class<?> api = Class.forName("net.momirealms.craftengine.bukkit.api.CraftEngineItems", true, loader);
                Class<?> key = Class.forName("net.momirealms.craftengine.core.util.Key", true, loader);
                Method byId = api.getMethod("byId", key);
                // Renamed between releases: CustomItem.buildItemStack() became
                // ItemDefinition.buildBukkitItem(). Both are asked of the
                // declared type, never of the implementation.
                Method build;
                try {
                    build = byId.getReturnType().getMethod("buildBukkitItem");
                } catch (NoSuchMethodException older) {
                    build = byId.getReturnType().getMethod("buildItemStack");
                }
                return new Bridge(byId, build, key.getMethod("of", String.class),
                        api.getMethod("getCustomItemId", ItemStack.class), null);
            }
        };

        private final String plugin;
        private final String loadEvent;
        private final String[] prefixes;
        private volatile Bridge bridge;
        private volatile Plugin bridged;

        Provider(String plugin, String loadEvent, String... prefixes) {
            this.plugin = plugin;
            this.loadEvent = loadEvent;
            this.prefixes = prefixes;
        }

        abstract Bridge bridge(ClassLoader loader) throws ReflectiveOperationException;

        static @Nullable Provider byPrefix(String prefix) {
            for (Provider provider : values()) {
                for (String candidate : provider.prefixes) {
                    if (candidate.equalsIgnoreCase(prefix)) {
                        return provider;
                    }
                }
            }
            return null;
        }

        /** The running plugin, or {@code null} when it is not installed or not enabled. */
        @Nullable Plugin running() {
            if (Bukkit.getServer() == null) {
                return null;
            }
            Plugin running = Bukkit.getPluginManager().getPlugin(plugin);
            return running != null && running.isEnabled() ? running : null;
        }

        /**
         * The calls into the running plugin, resolved once per plugin instance
         * so a plugin reloaded in place is asked through its new classes.
         */
        @Nullable Bridge calls() {
            Plugin running = running();
            if (running == null) {
                return null;
            }
            if (bridged != running) {
                Bridge resolved;
                try {
                    resolved = bridge(running.getClass().getClassLoader());
                } catch (ReflectiveOperationException | LinkageError changed) {
                    report("api:" + plugin, "This " + plugin + " does not offer the item API this library knows ("
                            + changed + "); its items will not be read.");
                    resolved = null;
                }
                bridge = resolved;
                bridged = running;
            }
            return bridge;
        }
    }

    /**
     * The reflective calls into one provider.
     *
     * @param byId   static, id (or key) to the provider's item object
     * @param build  on that object, to an {@link ItemStack}
     * @param key    static, String to the provider's key type; {@code null} when ids are strings
     * @param idOf   static, {@link ItemStack} to an id or to an object holding it
     * @param idText on what {@code idOf} returned, to the id; {@code null} when it already is one
     */
    private record Bridge(Method byId, Method build, @Nullable Method key, Method idOf, @Nullable Method idText) {

        @Nullable ItemStack item(String id) throws ReflectiveOperationException {
            Object found = byId.invoke(null, key == null ? id : key.invoke(null, id));
            return found == null ? null : (ItemStack) build.invoke(found);
        }

        @Nullable String id(ItemStack item) throws ReflectiveOperationException {
            Object found = idOf.invoke(null, item);
            if (found == null) {
                return null;
            }
            return idText == null ? found.toString() : (String) idText.invoke(found);
        }
    }

    /**
     * Whether a {@code material} value names an item of one of these plugins,
     * whether or not that plugin is running.
     *
     * @param written the value as written
     * @return {@code true} for {@code nexo:…}, {@code oraxen:…}, {@code itemsadder:…},
     *         {@code ia:…}, {@code craftengine:…} and {@code ce:…}
     */
    public static boolean isCustom(@NotNull String written) {
        return provider(written.trim()) != null;
    }

    /**
     * The plugin a custom value belongs to, as a person reads it.
     *
     * @param written a value {@link #isCustom} accepted
     * @return {@code "Nexo"}, {@code "ItemsAdder"}…, or {@code null} when it is not one
     */
    public static @Nullable String plugin(@NotNull String written) {
        Provider provider = provider(written.trim());
        return provider == null ? null : provider.plugin;
    }

    /**
     * The id inside a custom value: {@code ruby_sword} for {@code nexo:ruby_sword}.
     *
     * @param written a value {@link #isCustom} accepted
     * @return what follows the prefix, or the value unchanged when it is not custom
     */
    public static @NotNull String id(@NotNull String written) {
        String trimmed = written.trim();
        return provider(trimmed) == null ? trimmed : trimmed.substring(trimmed.indexOf(':') + 1);
    }

    /**
     * Builds the item a custom value names.
     *
     * <p>Ids are tried as written, then in lower case: the libraries that keep
     * a material upper-case it, and the providers' ids are lower case.
     *
     * @param written the value as written
     * @return a fresh copy of the provider's item, or {@code null} when the value
     *         is not custom, its plugin is not running, or it has no such item
     */
    public static @Nullable ItemStack item(@NotNull String written) {
        String trimmed = written.trim();
        Provider provider = provider(trimmed);
        if (provider == null) {
            return null;
        }
        Bridge calls = provider.calls();
        if (calls == null) {
            return null;
        }
        String id = trimmed.substring(trimmed.indexOf(':') + 1);
        try {
            ItemStack item = calls.item(id);
            String lower = id.toLowerCase(Locale.ROOT);
            if (item == null && !lower.equals(id)) {
                item = calls.item(lower);
            }
            return item == null ? null : item.clone();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failed) {
            report("item:" + trimmed, provider.plugin + " failed to build \"" + id + "\": " + rootOf(failed));
            return null;
        }
    }

    /**
     * The value to store for an item one of these plugins made.
     *
     * @param item the item as held
     * @return {@code nexo:ruby_sword} and the like, or {@code null} when no
     *         running provider claims it
     */
    public static @Nullable String written(@NotNull ItemStack item) {
        for (Provider provider : Provider.values()) {
            Bridge calls = provider.calls();
            if (calls == null) {
                continue;
            }
            try {
                String id = calls.id(item);
                if (id != null && !id.isBlank()) {
                    return provider.prefixes[0] + ":" + id;
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failed) {
                report("id:" + provider.plugin, provider.plugin + " failed to read an item's id: "
                        + rootOf(failed));
            }
        }
        return null;
    }

    /**
     * Runs {@code onLoad} each time a provider finishes loading or reloading
     * its items.
     *
     * <p>ItemsAdder and Oraxen load after the server has started, so anything
     * drawn before that — a static menu item cached at startup — holds the
     * fallback until it is told. Providers enabled later are hooked when they
     * enable.
     *
     * @param library the plugin the listeners belong to
     * @param onLoad  what to run, on the thread the provider fires its event on
     */
    public static void listen(@NotNull Plugin library, @NotNull Runnable onLoad) {
        Listener owner = new Listener() {
        };
        Set<Provider> hooked = ConcurrentHashMap.newKeySet();
        for (Provider provider : Provider.values()) {
            hook(library, owner, provider, hooked, onLoad);
        }
        Bukkit.getPluginManager().registerEvent(PluginEnableEvent.class, owner, EventPriority.MONITOR,
                (listener, event) -> {
                    String name = ((PluginEnableEvent) event).getPlugin().getName();
                    for (Provider provider : Provider.values()) {
                        if (provider.plugin.equals(name)) {
                            hook(library, owner, provider, hooked, onLoad);
                        }
                    }
                }, library);
    }

    @SuppressWarnings("unchecked")
    private static void hook(Plugin library, Listener owner, Provider provider, Set<Provider> hooked,
                             Runnable onLoad) {
        Plugin running = provider.running();
        if (running == null || !hooked.add(provider)) {
            return;
        }
        try {
            Class<?> type = Class.forName(provider.loadEvent, true, running.getClass().getClassLoader());
            Bukkit.getPluginManager().registerEvent((Class<? extends Event>) type, owner, EventPriority.MONITOR,
                    (listener, event) -> {
                        if (type.isInstance(event)) {
                            onLoad.run();
                        }
                    }, library);
            LOGGER.info("Hooked " + provider.plugin + ": its items can be written as "
                    + provider.prefixes[0] + ":<id>.");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError missing) {
            hooked.remove(provider);
            report("event:" + provider.plugin, provider.plugin + " has no " + provider.loadEvent
                    + "; items drawn before it loads will refresh on the next reload.");
        }
    }

    private static @Nullable Provider provider(String trimmed) {
        int colon = trimmed.indexOf(':');
        return colon <= 0 || colon == trimmed.length() - 1 ? null : Provider.byPrefix(trimmed.substring(0, colon));
    }

    private static String rootOf(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.toString();
    }

    private static void report(String key, String message) {
        if (REPORTED.add(key)) {
            LOGGER.warning(message);
        }
    }
}
