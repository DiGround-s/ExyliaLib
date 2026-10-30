package net.exylia.lib.hook.skill.internal;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Who is suspended, and the skill plugins' events that answer to it.
 *
 * <p>None of the skill plugins is a dependency: each event is listened to by
 * its class name once its plugin is enabled, and read through methods looked
 * up once. A plugin whose API changed is logged once and left out.
 */
@ApiStatus.Internal
public final class SkillHookRuntime implements Listener {

    /** What a source does to one of its events; only called while somebody is suspended. */
    @FunctionalInterface
    interface Hold {
        void apply(Event event) throws Throwable;
    }

    @FunctionalInterface
    interface Compiler {
        Hold compile(Class<?> type) throws ReflectiveOperationException;
    }

    private record Source(String plugin, String event, Compiler hold) {
    }

    private static final List<Source> SOURCES = List.of(
            new Source("AuraSkills", "dev.aurelium.auraskills.api.event.skill.XpGainEvent", SkillHookRuntime::cancel),
            new Source("AuraSkills", "dev.aurelium.auraskills.api.event.mana.ManaAbilityActivateEvent",
                    SkillHookRuntime::cancel),
            // Stats and abilities reach a fight as damage modifiers; dropping them leaves the vanilla hit.
            new Source("AuraSkills", "dev.aurelium.auraskills.api.event.damage.DamageEvent",
                    SkillHookRuntime::trimAuraDamage),
            new Source("mcMMO", "com.gmail.nossr50.events.experience.McMMOPlayerXpGainEvent",
                    SkillHookRuntime::cancel),
            new Source("mcMMO", "com.gmail.nossr50.events.skills.abilities.McMMOPlayerAbilityActivateEvent",
                    SkillHookRuntime::cancel),
            // Passives: dodge, roll, critical strikes, disarm...
            new Source("mcMMO", "com.gmail.nossr50.events.skills.secondaryabilities.SubSkillEvent",
                    SkillHookRuntime::cancel));

    /** Player to the plugins suspending them; a player nobody suspends has no entry. */
    private static final Map<UUID, Set<String>> SUSPENDED = new ConcurrentHashMap<>();
    private static final Set<String> HOOKED = ConcurrentHashMap.newKeySet();
    private static final Set<String> LISTENING = ConcurrentHashMap.newKeySet();

    private final Plugin library;

    private SkillHookRuntime(Plugin library) {
        this.library = library;
    }

    public static void register(Plugin library) {
        SkillHookRuntime runtime = new SkillHookRuntime(library);
        Bukkit.getPluginManager().registerEvents(runtime, library);
        for (Source source : SOURCES) {
            Plugin plugin = Bukkit.getPluginManager().getPlugin(source.plugin);
            if (plugin != null && plugin.isEnabled()) runtime.listen(source, plugin);
        }
    }

    /** A skill plugin that loads after the library. */
    @EventHandler
    public void onEnable(PluginEnableEvent event) {
        for (Source source : SOURCES) {
            if (source.plugin.equals(event.getPlugin().getName())) listen(source, event.getPlugin());
        }
    }

    private void listen(Source source, Plugin plugin) {
        if (!LISTENING.add(source.event)) return;
        try {
            Class<?> type = Class.forName(source.event, true, plugin.getClass().getClassLoader());
            if (!Event.class.isAssignableFrom(type)) throw new ClassCastException(type + " is not an event");
            Hold hold = source.hold.compile(type);
            @SuppressWarnings("unchecked")
            Class<? extends Event> eventType = (Class<? extends Event>) type;
            Bukkit.getPluginManager().registerEvent(eventType, this, EventPriority.HIGHEST, executor(type, hold),
                    library, true);
            HOOKED.add(source.plugin);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException changed) {
            library.getLogger().log(Level.INFO, "Skill hooks: " + source.plugin + " is not held back during"
                    + " minigames, its API is not the one expected (" + changed + ").");
        }
    }

    static EventExecutor executor(Class<?> type, Hold hold) {
        return (listener, event) -> {
            // A handler list shared with sibling events hands those over too.
            if (SUSPENDED.isEmpty() || !type.isInstance(event)) return;
            try {
                hold.apply(event);
            } catch (Throwable ignored) {
                // One event that could not be read is one bonus let through,
                // never an error on the skill plugin's thread.
            }
        };
    }

    static Hold cancel(Class<?> type) throws ReflectiveOperationException {
        if (!Cancellable.class.isAssignableFrom(type)) throw new NoSuchMethodException(type + " is not cancellable");
        Method player = type.getMethod("getPlayer");
        return event -> {
            if (player.invoke(event) instanceof Player p && isSuspended(p.getUniqueId())) {
                ((Cancellable) event).setCancelled(true);
            }
        };
    }

    static Hold trimAuraDamage(Class<?> type) throws ReflectiveOperationException {
        Method meta = type.getMethod("getDamageMeta");
        Class<?> damage = meta.getReturnType();
        Method attacker = damage.getMethod("getAttackerAsPlayer");
        Method target = damage.getMethod("getTargetAsPlayer");
        Method clearAttack = damage.getMethod("clearAttackModifiers");
        Method clearDefense = damage.getMethod("clearDefenseModifiers");
        return event -> {
            Object hit = meta.invoke(event);
            if (hit == null) return;
            if (attacker.invoke(hit) instanceof Player p && isSuspended(p.getUniqueId())) clearAttack.invoke(hit);
            if (target.invoke(hit) instanceof Player p && isSuspended(p.getUniqueId())) clearDefense.invoke(hit);
        };
    }

    public static boolean suspend(String owner, UUID player) {
        boolean[] added = new boolean[1];
        SUSPENDED.compute(player, (id, owners) -> {
            Set<String> held = owners == null ? new HashSet<>() : owners;
            added[0] = held.add(owner);
            return held;
        });
        return added[0];
    }

    public static void resume(String owner, UUID player) {
        SUSPENDED.computeIfPresent(player, (id, owners) -> owners.remove(owner) && owners.isEmpty() ? null : owners);
    }

    public static boolean isSuspended(UUID player) {
        return SUSPENDED.containsKey(player);
    }

    public static List<String> hooked() {
        return HOOKED.stream().sorted().toList();
    }

    /** A plugin going away resumes everyone it suspended. */
    public static void release(String owner) {
        for (UUID player : SUSPENDED.keySet()) resume(owner, player);
    }

    public static void forget(UUID player) {
        SUSPENDED.remove(player);
    }
}
