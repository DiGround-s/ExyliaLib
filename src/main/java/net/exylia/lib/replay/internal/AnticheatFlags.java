package net.exylia.lib.replay.internal;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * The flag events of the anticheats servers actually run, turned into black
 * box flags without a line of code in any plugin.
 *
 * <p>None of them is a dependency: each is listened to by its event's class
 * name, once its plugin is enabled, and what a flag says is read through
 * method handles looked up once per anticheat. An anticheat that renamed a
 * method is logged once and left out, never a failure on every flag.
 */
@ApiStatus.Internal
final class AnticheatFlags implements Listener {

    /**
     * Where each one keeps who, which check and what it said. A path is the
     * getters to call one after the other; the letter, when there is one, is
     * a character put after the check's name (Vulcan's "Speed A").
     */
    private record Source(String plugin, String event, List<String> player, List<String> check,
                          @Nullable List<String> letter, @Nullable String detail) {
    }

    private static final List<Source> SOURCES = List.of(
            // Grim's Bukkit event is fired by Grim's own bridge from its event
            // bus; the user is a GrimUser, not a Player.
            new Source("GrimAC", "ac.grim.grimac.api.events.FlagEvent",
                    List.of("getUser", "getUniqueId"), List.of("getCheck", "getCheckName"), null, "getVerbose"),
            new Source("Vulcan", "me.frep.vulcan.api.event.VulcanFlagEvent",
                    List.of("getPlayer", "getUniqueId"), List.of("getCheck", "getDisplayName"),
                    List.of("getCheck", "getDisplayType"), "getInfo"),
            new Source("Spartan", "me.vagdedes.spartan.api.PlayerViolationEvent",
                    List.of("getPlayer", "getUniqueId"), List.of("getHackType", "toString"), null, "getMessage"),
            new Source("Matrix", "me.rerere.matrix.api.events.PlayerViolationEvent",
                    List.of("getPlayer", "getUniqueId"), List.of("getHackType", "toString"), null, "getMessage"));

    private final Plugin library;
    private final Set<String> listening = ConcurrentHashMap.newKeySet();

    private AnticheatFlags(Plugin library) {
        this.library = library;
    }

    static void register(Plugin library) {
        AnticheatFlags flags = new AnticheatFlags(library);
        Bukkit.getPluginManager().registerEvents(flags, library);
        for (Source source : SOURCES) {
            Plugin plugin = Bukkit.getPluginManager().getPlugin(source.plugin);
            if (plugin != null && plugin.isEnabled()) flags.listen(source, plugin);
        }
    }

    /** An anticheat that loads after the library. */
    @EventHandler
    public void onEnable(PluginEnableEvent event) {
        for (Source source : SOURCES) {
            if (source.plugin.equals(event.getPlugin().getName())) listen(source, event.getPlugin());
        }
    }

    private void listen(Source source, Plugin plugin) {
        if (!listening.add(source.plugin)) return;
        try {
            Class<?> type = Class.forName(source.event, true, plugin.getClass().getClassLoader());
            if (!Event.class.isAssignableFrom(type)) return;
            MethodHandle player = path(type, source.player);
            MethodHandle check = path(type, source.check);
            MethodHandle letter = source.letter == null ? null : optional(type, source.letter);
            MethodHandle detail = source.detail == null ? null : optional(type, List.of(source.detail));
            EventExecutor executor = (listener, event) -> {
                if (!type.isInstance(event)) return;
                read(event, player, check, letter, detail);
            };
            @SuppressWarnings("unchecked")
            Class<? extends Event> eventType = (Class<? extends Event>) type;
            Bukkit.getPluginManager().registerEvent(eventType, this, EventPriority.MONITOR, executor, library, true);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException changed) {
            library.getLogger().log(Level.INFO, "Replays: " + source.plugin
                    + " flags are not recorded, its API is not the one expected (" + changed + ").");
        }
    }

    private static void read(Event event, MethodHandle player, MethodHandle check, @Nullable MethodHandle letter,
                             @Nullable MethodHandle detail) {
        // Without a box nothing is kept, and nothing is worth reading.
        if (ReplayRuntime.box() == null) return;
        try {
            Object who = player.invoke(event);
            Object name = check.invoke(event);
            if (!(who instanceof UUID id) || name == null) return;
            String named = name.toString();
            Object type = letter == null ? null : letter.invoke(event);
            if (type instanceof Character c && Character.isLetterOrDigit(c)) named = named + " " + c;
            Object said = detail == null ? null : detail.invoke(event);
            ReplayRuntime.flag(id, named, said == null ? null : said.toString());
        } catch (Throwable ignored) {
            // One flag that could not be read is one flag missing from a
            // replay, not an error in the anticheat's thread.
        }
    }

    /** The getters of a path, chained into one handle from the event to the value. */
    private static MethodHandle path(Class<?> type, List<String> names) throws ReflectiveOperationException {
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        MethodHandle chain = null;
        Class<?> on = type;
        for (String name : names) {
            Method method = on.getMethod(name);
            MethodHandle step = lookup.unreflect(method).asType(
                    java.lang.invoke.MethodType.methodType(Object.class, Object.class));
            chain = chain == null ? step : MethodHandles.filterReturnValue(chain, step);
            on = method.getReturnType();
        }
        return chain;
    }

    private static @Nullable MethodHandle optional(Class<?> type, List<String> names) {
        try {
            return path(type, names);
        } catch (ReflectiveOperationException missing) {
            return null;
        }
    }
}
