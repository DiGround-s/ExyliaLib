package net.exylia.lib.block.internal;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Which Nexo, Oraxen, ItemsAdder or CraftEngine block or furniture a world
 * block or entity is, behind {@code CustomBlocks}.
 *
 * <p>Same rules as {@code item/internal/CustomItems}: none of them is a
 * dependency, each is reached by reflection through its own class loader once
 * it is enabled, methods are looked up on the declared types, and an API that
 * changed shape is reported once and treated as absent. Each lookup (block,
 * furniture by block, furniture by entity) is resolved on its own, so a
 * provider missing one of them still answers the others.
 */
public final class CustomBlockIds {

    private static final Logger LOGGER = Logger.getLogger("ExyliaLib");
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private CustomBlockIds() {
    }

    /** A static call followed by getters, ending in the id's text. */
    private record Chain(Method first, Method... then) {

        @Nullable String id(Object argument) throws ReflectiveOperationException {
            Object value = first.invoke(null, argument);
            for (Method step : then) {
                if (value == null) return null;
                value = step.invoke(value);
            }
            return value == null ? null : value.toString();
        }
    }

    /** The resolved lookups of one provider; any may be {@code null}. */
    private record Bridge(@Nullable Chain block, @Nullable Chain furnitureAt, @Nullable Chain furniture) {
    }

    private enum Provider {
        NEXO("Nexo", "nexo", List.of("com.nexomc.nexo.api.events.custom_block.NexoBlockPlaceEvent"), "getBlock") {
            @Override
            Bridge bridge(ClassLoader loader) {
                return new Bridge(
                        chain(loader, "com.nexomc.nexo.api.NexoBlocks", "customBlockMechanic", Block.class, "getItemID"),
                        chain(loader, "com.nexomc.nexo.api.NexoFurniture", "furnitureMechanic", Block.class, "getItemID"),
                        chain(loader, "com.nexomc.nexo.api.NexoFurniture", "furnitureMechanic", Entity.class, "getItemID"));
            }
        },
        ORAXEN("Oraxen", "oraxen", List.of(
                "io.th0rgal.oraxen.api.events.noteblock.OraxenNoteBlockPlaceEvent",
                "io.th0rgal.oraxen.api.events.stringblock.OraxenStringBlockPlaceEvent",
                "io.th0rgal.oraxen.api.events.chorusblock.OraxenChorusBlockPlaceEvent"), "getBlock") {
            @Override
            Bridge bridge(ClassLoader loader) {
                return new Bridge(
                        chain(loader, "io.th0rgal.oraxen.api.OraxenBlocks", "getOraxenBlock", Location.class, "getItemID"),
                        chain(loader, "io.th0rgal.oraxen.api.OraxenFurniture", "getFurnitureMechanic", Block.class, "getItemID"),
                        chain(loader, "io.th0rgal.oraxen.api.OraxenFurniture", "getFurnitureMechanic", Entity.class, "getItemID"));
            }
        },
        ITEMS_ADDER("ItemsAdder", "itemsadder", List.of("dev.lone.itemsadder.api.Events.CustomBlockPlaceEvent"),
                "getBlock") {
            @Override
            Bridge bridge(ClassLoader loader) {
                return new Bridge(
                        chain(loader, "dev.lone.itemsadder.api.CustomBlock", "byAlreadyPlaced", Block.class, "getNamespacedID"),
                        chain(loader, "dev.lone.itemsadder.api.CustomFurniture", "byAlreadySpawned", Block.class, "getNamespacedID"),
                        chain(loader, "dev.lone.itemsadder.api.CustomFurniture", "byAlreadySpawned", Entity.class, "getNamespacedID"));
            }
        },
        CRAFT_ENGINE("CraftEngine", "craftengine", List.of("net.momirealms.craftengine.bukkit.api.event.CustomBlockPlaceEvent"),
                "bukkitBlock") {
            @Override
            Bridge bridge(ClassLoader loader) {
                Chain block = null;
                try {
                    // state.owner() is a Holder<BlockDefinition>; value() is erased to Object,
                    // so id() is asked of BlockDefinition by name.
                    Method state = Class.forName("net.momirealms.craftengine.bukkit.api.CraftEngineBlocks", true, loader)
                            .getMethod("getCustomBlockState", Block.class);
                    Method owner = state.getReturnType().getMethod("owner");
                    Method value = owner.getReturnType().getMethod("value");
                    Method id = Class.forName("net.momirealms.craftengine.core.block.BlockDefinition", true, loader)
                            .getMethod("id");
                    block = new Chain(state, owner, value, id);
                } catch (ReflectiveOperationException | LinkageError changed) {
                    report("api:CraftEngine:block", "This CraftEngine does not offer the block API this library knows ("
                            + changed + "); its blocks will not be recognised.");
                }
                return new Bridge(block, null, chain(loader, "net.momirealms.craftengine.bukkit.api.CraftEngineFurniture",
                        "getLoadedFurnitureByMetaEntity", Entity.class, "id"));
            }
        };

        private final String plugin;
        private final String prefix;
        private final List<String> placeEvents;
        private final String blockGetter;
        private volatile Bridge bridge;
        private volatile Plugin bridged;

        Provider(String plugin, String prefix, List<String> placeEvents, String blockGetter) {
            this.plugin = plugin;
            this.prefix = prefix;
            this.placeEvents = placeEvents;
            this.blockGetter = blockGetter;
        }

        abstract Bridge bridge(ClassLoader loader);

        @Nullable Plugin running() {
            if (Bukkit.getServer() == null) return null;
            Plugin running = Bukkit.getPluginManager().getPlugin(plugin);
            return running != null && running.isEnabled() ? running : null;
        }

        @Nullable Bridge calls() {
            Plugin running = running();
            if (running == null) return null;
            if (bridged != running) {
                bridge = bridge(running.getClass().getClassLoader());
                bridged = running;
            }
            return bridge;
        }

        /** A static lookup and the getter on what it returns; {@code null}, reported once, when either is gone. */
        @Nullable Chain chain(ClassLoader loader, String type, String method, Class<?> argument, String getter) {
            try {
                Method first = Class.forName(type, true, loader).getMethod(method, argument);
                return new Chain(first, first.getReturnType().getMethod(getter));
            } catch (ReflectiveOperationException | LinkageError changed) {
                report("api:" + plugin + ":" + method + ":" + argument.getSimpleName(), "This " + plugin
                        + " does not offer " + type + "." + method + "(" + argument.getSimpleName() + ") ("
                        + changed + "); that lookup is skipped.");
                return null;
            }
        }

        @Nullable String ask(@Nullable Chain chain, Object argument) {
            if (chain == null) return null;
            try {
                String id = chain.id(argument);
                return id == null || id.isBlank() ? null : prefix + ":" + id;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failed) {
                report("call:" + plugin + ":" + chain.first().getName(), plugin + " failed to answer "
                        + chain.first().getName() + ": " + rootOf(failed));
                return null;
            }
        }
    }

    /**
     * The custom block at a position: a provider's block first, then furniture
     * anchored there.
     *
     * @return {@code nexo:<id>}, {@code oraxen:<id>}, {@code itemsadder:<ns>:<id>}
     *         or {@code craftengine:<ns>:<id>}; {@code null} when none claims it
     */
    public static @Nullable String id(@NotNull Block block) {
        for (Provider provider : Provider.values()) {
            Bridge calls = provider.calls();
            if (calls == null) continue;
            String id = provider.ask(calls.block(),
                    provider == Provider.ORAXEN ? block.getLocation() : block);
            if (id == null) id = provider.ask(calls.furnitureAt(), block);
            if (id != null) return id;
        }
        return null;
    }

    /** The furniture an entity belongs to, or {@code null}. */
    public static @Nullable String id(@NotNull Entity entity) {
        for (Provider provider : Provider.values()) {
            Bridge calls = provider.calls();
            if (calls == null) continue;
            String id = provider.ask(calls.furniture(), entity);
            if (id != null) return id;
        }
        return null;
    }

    /** Whether any provider is running, so a caller can skip the lookups entirely. */
    public static boolean anyRunning() {
        for (Provider provider : Provider.values()) {
            if (provider.running() != null) return true;
        }
        return false;
    }

    /**
     * Calls {@code placed} with the block of every custom block a provider
     * places, on the thread that fired the provider's own place event. Hooks
     * providers already running and those enabled later.
     */
    public static void listenPlacements(@NotNull Plugin library, @NotNull Consumer<Block> placed) {
        Listener owner = new Listener() {
        };
        Set<Provider> hooked = ConcurrentHashMap.newKeySet();
        for (Provider provider : Provider.values()) {
            hook(library, owner, provider, hooked, placed);
        }
        Bukkit.getPluginManager().registerEvent(PluginEnableEvent.class, owner, EventPriority.MONITOR,
                (listener, event) -> {
                    String name = ((PluginEnableEvent) event).getPlugin().getName();
                    for (Provider provider : Provider.values()) {
                        if (provider.plugin.equals(name)) hook(library, owner, provider, hooked, placed);
                    }
                }, library);
    }

    @SuppressWarnings("unchecked")
    private static void hook(Plugin library, Listener owner, Provider provider, Set<Provider> hooked,
                             Consumer<Block> placed) {
        Plugin running = provider.running();
        if (running == null || !hooked.add(provider)) return;
        ClassLoader loader = running.getClass().getClassLoader();
        for (String name : provider.placeEvents) {
            try {
                Class<?> type = Class.forName(name, true, loader);
                Method getter = type.getMethod(provider.blockGetter);
                Bukkit.getPluginManager().registerEvent((Class<? extends Event>) type, owner, EventPriority.MONITOR,
                        (listener, event) -> {
                            if (!type.isInstance(event)) return;
                            if (event instanceof Cancellable cancellable && cancellable.isCancelled()) return;
                            try {
                                if (getter.invoke(event) instanceof Block block) placed.accept(block);
                            } catch (ReflectiveOperationException | RuntimeException | LinkageError failed) {
                                report("event:" + name, provider.plugin + " place event could not be read: "
                                        + rootOf(failed));
                            }
                        }, library);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError missing) {
                report("event:" + name, provider.plugin + " has no " + name
                        + "; its placed blocks are only recorded through the vanilla place event.");
            }
        }
    }

    private static String rootOf(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.toString();
    }

    private static void report(String key, String message) {
        if (REPORTED.add(key)) LOGGER.warning(message);
    }
}
