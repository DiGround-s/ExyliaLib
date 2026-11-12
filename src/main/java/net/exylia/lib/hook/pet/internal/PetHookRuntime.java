package net.exylia.lib.hook.pet.internal;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * Whose pets are put away, and how each pet plugin is asked to do it.
 *
 * <p>No pet plugin is a dependency: each is reached by reflection once it is
 * enabled. One whose API changed is logged once and left out.
 */
@ApiStatus.Internal
public final class PetHookRuntime implements Listener {

    /** One pet plugin, compiled against the classes it shipped. */
    interface Source {
        /** Despawns the player's active pets, each mapped to how to call it back. */
        Map<Object, Consumer<Player>> putAway(Player player) throws Throwable;

        /** The cancellable event the plugin fires before a pet appears. */
        Class<? extends Event> spawnEvent();

        /** Whose pet that event spawns; {@code null} when it names nobody. */
        Spawn read(Event event) throws Throwable;
    }

    record Spawn(UUID owner, Object pet, Consumer<Player> callBack) {
    }

    @FunctionalInterface
    interface Compiler {
        Source compile(ClassLoader loader) throws ReflectiveOperationException;
    }

    private static final Map<String, Compiler> SOURCES = Map.of(
            "MCPets", PetHookRuntime::mcPets,
            "MyPet", PetHookRuntime::myPet);

    private static final Map<UUID, Set<String>> HIDDEN = new ConcurrentHashMap<>();
    /** Pets put away or held back, by pet so a pet held back twice comes back once. */
    private static final Map<UUID, Map<Object, Consumer<Player>>> AWAY = new ConcurrentHashMap<>();
    private static final Map<String, Source> HOOKED = new ConcurrentHashMap<>();

    private static Plugin library;

    private PetHookRuntime() {
    }

    public static void register(Plugin lib) {
        library = lib;
        PetHookRuntime runtime = new PetHookRuntime();
        Bukkit.getPluginManager().registerEvents(runtime, lib);
        for (String name : SOURCES.keySet()) {
            Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
            if (plugin != null && plugin.isEnabled()) runtime.listen(name, plugin);
        }
    }

    /** A pet plugin that loads after the library. */
    @EventHandler
    public void onEnable(PluginEnableEvent event) {
        String name = event.getPlugin().getName();
        if (SOURCES.containsKey(name)) listen(name, event.getPlugin());
    }

    private void listen(String name, Plugin plugin) {
        if (HOOKED.containsKey(name)) return;
        try {
            Source source = SOURCES.get(name).compile(plugin.getClass().getClassLoader());
            Bukkit.getPluginManager().registerEvent(source.spawnEvent(), this, EventPriority.HIGHEST,
                    (listener, event) -> holdBack(source, event), library, true);
            HOOKED.put(name, source);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException changed) {
            library.getLogger().log(Level.INFO, "Pet hooks: " + name + " pets are not put away for vanished"
                    + " players, its API is not the one expected (" + changed + ").");
        }
    }

    private static void holdBack(Source source, Event event) {
        if (HIDDEN.isEmpty() || !source.spawnEvent().isInstance(event)) return;
        try {
            Spawn spawn = source.read(event);
            if (spawn == null || !HIDDEN.containsKey(spawn.owner())) return;
            ((Cancellable) event).setCancelled(true);
            AWAY.computeIfAbsent(spawn.owner(), id -> new ConcurrentHashMap<>()).put(spawn.pet(), spawn.callBack());
        } catch (Throwable ignored) {
            // A spawn that could not be read is a pet let through, never an
            // error on the pet plugin's thread.
        }
    }

    public static boolean hide(String owner, Player player) {
        UUID uuid = player.getUniqueId();
        boolean first = !HIDDEN.containsKey(uuid);
        boolean[] added = new boolean[1];
        HIDDEN.compute(uuid, (id, owners) -> {
            Set<String> held = owners == null ? new HashSet<>() : owners;
            added[0] = held.add(owner);
            return held;
        });
        if (first) {
            for (Map.Entry<String, Source> hooked : HOOKED.entrySet()) {
                try {
                    Map<Object, Consumer<Player>> away = hooked.getValue().putAway(player);
                    if (!away.isEmpty()) AWAY.computeIfAbsent(uuid, id -> new ConcurrentHashMap<>()).putAll(away);
                } catch (Throwable failed) {
                    library.getLogger().log(Level.FINE, "Pet hooks: " + hooked.getKey() + " could not put "
                            + player.getName() + "'s pets away", failed);
                }
            }
        }
        return added[0];
    }

    public static void show(String owner, Player player) {
        UUID uuid = player.getUniqueId();
        Set<String> left = HIDDEN.computeIfPresent(uuid,
                (id, owners) -> owners.remove(owner) && owners.isEmpty() ? null : owners);
        if (left != null) return;
        Map<Object, Consumer<Player>> away = AWAY.remove(uuid);
        if (away == null) return;
        for (Consumer<Player> callBack : away.values()) {
            try {
                callBack.accept(player);
            } catch (Throwable failed) {
                library.getLogger().log(Level.FINE, "Pet hooks: a pet of " + player.getName()
                        + " could not be called back", failed);
            }
        }
    }

    public static boolean isHidden(UUID player) {
        return HIDDEN.containsKey(player);
    }

    public static List<String> hooked() {
        return HOOKED.keySet().stream().sorted().toList();
    }

    /** A plugin going away drops its holds; pets come back on the owner's next spawn or relog. */
    public static void release(String owner) {
        for (UUID player : HIDDEN.keySet()) {
            HIDDEN.computeIfPresent(player, (id, owners) -> owners.remove(owner) && owners.isEmpty() ? null : owners);
            if (!HIDDEN.containsKey(player)) AWAY.remove(player);
        }
    }

    /** The pet plugin saved what the player had out; their pets come back with them. */
    public static void forget(UUID player) {
        HIDDEN.remove(player);
        AWAY.remove(player);
    }

    // MCPets: Pet.getActivePetsForOwner(UUID), despawn(TELEPORT) keeps the pet
    // as the one the player has out, spawn(Player, Location) calls it back.
    private static Source mcPets(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> pet = Class.forName("fr.nocsy.mcpets.data.Pet", true, loader);
        Class<?> reasons = Class.forName("fr.nocsy.mcpets.data.PetDespawnReason", true, loader);
        Class<? extends Event> spawnEvent = event(loader, "fr.nocsy.mcpets.events.PetSpawnEvent");
        Method active = pet.getMethod("getActivePetsForOwner", UUID.class);
        Method despawn = pet.getMethod("despawn", reasons);
        Method spawn = pet.getMethod("spawn", Player.class, org.bukkit.Location.class);
        Method owner = pet.getMethod("getOwner");
        Method eventPet = spawnEvent.getMethod("getPet");
        Object teleport = reasons.getField("TELEPORT").get(null);
        Function<Object, Consumer<Player>> callBack = p -> player -> invoke(spawn, p, player, player.getLocation());
        return new Source() {
            @Override
            public Map<Object, Consumer<Player>> putAway(Player player) throws Throwable {
                Map<Object, Consumer<Player>> away = new HashMap<>();
                for (Object p : List.copyOf((List<?>) active.invoke(null, player.getUniqueId()))) {
                    despawn.invoke(p, teleport);
                    away.put(p, callBack.apply(p));
                }
                return away;
            }

            @Override
            public Class<? extends Event> spawnEvent() {
                return spawnEvent;
            }

            @Override
            public Spawn read(Event event) throws Throwable {
                Object p = eventPet.invoke(event);
                return p != null && owner.invoke(p) instanceof UUID id ? new Spawn(id, p, callBack.apply(p)) : null;
            }
        };
    }

    // MyPet: one pet per player; removePet(true) keeps it as the one they want
    // out across a relog, createEntity() calls it back.
    private static Source myPet(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> api = Class.forName("de.Keyle.MyPet.MyPetApi", true, loader);
        Class<?> players = Class.forName("de.Keyle.MyPet.api.repository.PlayerManager", true, loader);
        Class<?> petPlayer = Class.forName("de.Keyle.MyPet.api.player.MyPetPlayer", true, loader);
        Class<?> pet = Class.forName("de.Keyle.MyPet.api.entity.MyPet", true, loader);
        Class<? extends Event> spawnEvent = event(loader, "de.Keyle.MyPet.api.event.MyPetCallEvent");
        Method manager = api.getMethod("getPlayerManager");
        Method of = players.getMethod("getMyPetPlayer", Player.class);
        Method has = petPlayer.getMethod("hasMyPet");
        Method get = petPlayer.getMethod("getMyPet");
        Method status = pet.getMethod("getStatus");
        Method remove = pet.getMethod("removePet", boolean.class);
        Method create = pet.getMethod("createEntity");
        Method eventPlayer = spawnEvent.getMethod("getPlayer");
        Method eventPet = spawnEvent.getMethod("getMyPet");
        Function<Object, Consumer<Player>> callBack = p -> player -> invoke(create, p);
        return new Source() {
            @Override
            public Map<Object, Consumer<Player>> putAway(Player player) throws Throwable {
                Object owner = of.invoke(manager.invoke(null), player);
                if (owner == null || !(boolean) has.invoke(owner)) return Map.of();
                Object p = get.invoke(owner);
                if (!"Here".equals(String.valueOf(status.invoke(p)))) return Map.of();
                remove.invoke(p, true);
                return Map.of(p, callBack.apply(p));
            }

            @Override
            public Class<? extends Event> spawnEvent() {
                return spawnEvent;
            }

            @Override
            public Spawn read(Event event) throws Throwable {
                Object p = eventPet.invoke(event);
                return eventPlayer.invoke(event) instanceof Player player && pet.isInstance(p)
                        ? new Spawn(player.getUniqueId(), p, callBack.apply(p)) : null;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Event> event(ClassLoader loader, String name) throws ReflectiveOperationException {
        Class<?> type = Class.forName(name, true, loader);
        if (!Event.class.isAssignableFrom(type) || !Cancellable.class.isAssignableFrom(type)) {
            throw new ClassCastException(type + " is not a cancellable event");
        }
        return (Class<? extends Event>) type;
    }

    private static void invoke(Method method, Object target, Object... args) {
        try {
            method.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
