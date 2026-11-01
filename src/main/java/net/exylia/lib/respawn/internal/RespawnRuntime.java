package net.exylia.lib.respawn.internal;

import net.exylia.lib.debug.Debug;
import net.exylia.lib.platform.Platform;
import net.exylia.lib.respawn.RespawnContext;
import net.exylia.lib.respawn.RespawnRule;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.teleport.TeleportCause;
import net.exylia.lib.util.teleport.Teleports;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerRespawnEvent.RespawnReason;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Drives respawn rules and callbacks.
 *
 * <p>Paper: each rule is a {@link PlayerRespawnEvent} listener of its plugin at
 * its priority, and callbacks run on {@code PlayerPostRespawnEvent} (one tick
 * after the respawn event where that event does not exist).
 *
 * <p>Folia fires neither event on a death respawn. A death is recorded, a light
 * global timer hops onto each dead player's thread until they stand again, and
 * there the rules run against where vanilla put them; a different answer is a
 * {@link Teleports} move with no countdown. Whichever path claims the death
 * first owns it, so a platform that fires the event never runs both.
 */
@ApiStatus.Internal
public final class RespawnRuntime implements Listener {

    /** How often the Folia watch looks at dead players. */
    static final long POLL_TICKS = 2;

    /**
     * How far from the stored respawn point a landing still counts as a bed or
     * anchor respawn. Vanilla stands the player up within two blocks of it.
     */
    private static final double BED_REACH_SQUARED = 9.0;

    private static final List<Rule> RULES = new CopyOnWriteArrayList<>();
    private static final List<After> AFTERS = new CopyOnWriteArrayList<>();
    private static final AtomicLong SEQUENCE = new AtomicLong();

    /** Folia: players who died and have not been seen standing yet. */
    static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    /** Respawns that went through the event and still owe their callbacks. */
    private static final Set<UUID> AWAITING_AFTER = ConcurrentHashMap.newKeySet();

    private static volatile Plugin library;
    private static volatile boolean folia;
    private static TaskHandle poll;

    private RespawnRuntime() {
    }

    /** One rule. */
    public record Rule(Plugin plugin, EventPriority priority, RespawnRule rule, Set<RespawnReason> reasons,
                       long order, Listener listener) {
    }

    /** One callback. */
    public record After(Plugin plugin, Consumer<Player> task) {
    }

    /** A Folia death waiting for its respawn. */
    record Pending(Location death, @Nullable Location point, AtomicBoolean hopping) {
    }

    /**
     * Registers the listeners, once, against the library.
     *
     * @param plugin the library
     */
    public static void init(@NotNull Plugin plugin) {
        init(plugin, Platform.isFolia());
    }

    static synchronized void init(Plugin plugin, boolean onFolia) {
        if (library != null) return;
        library = plugin;
        folia = onFolia;
        Bukkit.getPluginManager().registerEvents(new RespawnRuntime(), plugin);
        try {
            Class.forName("com.destroystokyo.paper.event.player.PlayerPostRespawnEvent");
            Bukkit.getPluginManager().registerEvents(new PostRespawn(), plugin);
        } catch (ClassNotFoundException | LinkageError spigot) {
            // The one-tick fallback in onRespawned runs the callbacks instead.
        }
    }

    /** Test seam: forgets everything. */
    static synchronized void resetForTests() {
        RULES.clear();
        AFTERS.clear();
        PENDING.clear();
        AWAITING_AFTER.clear();
        if (poll != null) poll.cancel();
        poll = null;
        library = null;
    }

    // ------------------------------------------------------------ registration

    public static @NotNull Rule addRule(@NotNull Plugin plugin, @NotNull EventPriority priority,
                                        @NotNull RespawnRule rule, @NotNull Set<RespawnReason> reasons) {
        Listener listener = new Listener() {
        };
        Rule added = new Rule(plugin, priority, rule, Set.copyOf(reasons), SEQUENCE.incrementAndGet(), listener);
        RULES.add(added);
        // A real listener of the owning plugin: Bukkit orders it against every
        // other respawn listener and drops it when the plugin is disabled.
        Bukkit.getPluginManager().registerEvent(PlayerRespawnEvent.class, listener, priority,
                (ignored, event) -> {
                    if (event instanceof PlayerRespawnEvent respawn) onEvent(added, respawn);
                }, plugin);
        return added;
    }

    public static @NotNull After addAfter(@NotNull Plugin plugin, @NotNull Consumer<Player> task) {
        After added = new After(plugin, task);
        AFTERS.add(added);
        return added;
    }

    public static void remove(@NotNull Object registration) {
        if (registration instanceof Rule rule) {
            RULES.remove(rule);
            PlayerRespawnEvent.getHandlerList().unregister(rule.listener());
        } else {
            AFTERS.remove(registration);
        }
    }

    /** Drops what a disabled plugin registered; Bukkit already dropped its listeners. */
    public static void release(@NotNull String pluginName) {
        RULES.removeIf(rule -> rule.plugin().getName().equals(pluginName));
        AFTERS.removeIf(after -> after.plugin().getName().equals(pluginName));
    }

    // ------------------------------------------------------------ the event path

    /** One rule, as the respawn event's listener. */
    static void onEvent(Rule rule, PlayerRespawnEvent event) {
        RespawnReason reason = event.getRespawnReason();
        if (!rule.reasons().contains(reason)) return;
        Player player = event.getPlayer();
        Location death = reason == RespawnReason.DEATH ? player.getLastDeathLocation() : null;
        Location chosen = ask(rule, new RespawnContext(player, event.getRespawnLocation().clone(),
                event.isBedSpawn() || event.isAnchorSpawn(), death, reason));
        if (chosen != null) event.setRespawnLocation(chosen);
    }

    /** Test seam: runs every rule for an event in the order Bukkit would. */
    static void fireRules(PlayerRespawnEvent event) {
        for (Rule rule : ordered()) onEvent(rule, event);
    }

    /** The event fired, so this death is not the watch's to handle. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onRespawn(PlayerRespawnEvent event) {
        PENDING.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawned(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        AWAITING_AFTER.add(id);
        Plugin owner = library;
        // Paper answers this the same tick through PlayerPostRespawnEvent; the
        // tick later is for servers that never fire it (Spigot, Folia's End exit).
        if (owner != null) {
            Tasks.of(owner).runAtEntityLater(player, 1L, () -> {
                if (AWAITING_AFTER.remove(id)) runAfters(player);
            });
        }
    }

    /** Paper's post-respawn event, in its own class so Spigot never resolves it. */
    public static final class PostRespawn implements Listener {
        @EventHandler(priority = EventPriority.MONITOR)
        public void onPostRespawn(com.destroystokyo.paper.event.player.PlayerPostRespawnEvent event) {
            if (AWAITING_AFTER.remove(event.getPlayer().getUniqueId())) runAfters(event.getPlayer());
        }
    }

    // ------------------------------------------------------------ the Folia path

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (folia) watch(event.getPlayer());
    }

    /** A player who left on the death screen comes back on it. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (folia && event.getPlayer().isDead()) watch(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        PENDING.remove(id);
        AWAITING_AFTER.remove(id);
    }

    /** Whether the server has the unchecked read (1.21.4); older ones only have the checked one. */
    private static final boolean UNCHECKED_RESPAWN = hasUncheckedRespawn();

    private static boolean hasUncheckedRespawn() {
        try {
            Player.class.getMethod("getPotentialRespawnLocation");
            return true;
        } catch (NoSuchMethodException | LinkageError older) {
            return false;
        }
    }

    /**
     * The stored respawn point. On a server without the unchecked read
     * (1.21.1, Paper only: Folia starts at 1.21.4) the checked one is safe,
     * since there is one main thread and the bed's block is on it.
     */
    private static @Nullable Location potentialRespawn(Player player) {
        return UNCHECKED_RESPAWN ? Unchecked.of(player) : player.getRespawnLocation();
    }

    @net.exylia.lib.platform.RequiresServer("1.21.4")
    private static final class Unchecked {
        static Location of(Player player) {
            return player.getPotentialRespawnLocation();
        }
    }

    private static void watch(Player player) {
        Location at = player.getLocation();
        // The stored point, unchecked: validating it reads the bed's block,
        // which on Folia may belong to another region's thread.
        Location point = potentialRespawn(player);
        Pending pending = new Pending(at == null ? null : at.clone(), point, new AtomicBoolean());
        synchronized (RespawnRuntime.class) {
            PENDING.put(player.getUniqueId(), pending);
            Plugin owner = library;
            if (poll == null && owner != null) {
                poll = Tasks.of(owner).runTimer(POLL_TICKS, POLL_TICKS, RespawnRuntime::poll);
            }
        }
    }

    /**
     * Global thread: hops onto each dead player's own thread, one hop at a
     * time per player, and only there reads whether they stand again. Stops
     * itself once nobody is dead.
     */
    private static void poll() {
        synchronized (RespawnRuntime.class) {
            if (PENDING.isEmpty()) {
                if (poll != null) poll.cancel();
                poll = null;
                return;
            }
        }
        Plugin owner = library;
        if (owner == null) return;
        for (Map.Entry<UUID, Pending> entry : PENDING.entrySet()) {
            Pending pending = entry.getValue();
            if (!pending.hopping().compareAndSet(false, true)) continue;
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) {
                PENDING.remove(entry.getKey(), pending);
                continue;
            }
            Tasks.of(owner).runAtEntity(player, () -> {
                pending.hopping().set(false);
                if (!player.isDead() && player.isValid()) stoodUp(player, pending);
            }, () -> pending.hopping().set(false));
        }
    }

    /** On the player's thread, once they stand where vanilla respawned them. */
    private static void stoodUp(Player player, Pending pending) {
        if (!PENDING.remove(player.getUniqueId(), pending)) return;
        Location landing = player.getLocation().clone();
        Location point = pending.point();
        boolean bed = point != null && point.getWorld() != null && point.getWorld().equals(landing.getWorld())
                && point.distanceSquared(landing) <= BED_REACH_SQUARED;
        Location target = landing;
        for (Rule rule : ordered()) {
            if (!rule.reasons().contains(RespawnReason.DEATH) || !rule.plugin().isEnabled()) continue;
            Location chosen = ask(rule, new RespawnContext(player, target.clone(), bed, pending.death(),
                    RespawnReason.DEATH));
            if (chosen != null) target = chosen;
        }
        if (target.equals(landing) || library == null) {
            runAfters(player);
            return;
        }
        Plugin owner = library;
        // No countdown, nothing that cancels it, no message, and PLUGIN from the
        // library: region holds let another plugin's PLUGIN move through.
        Teleports.of(owner).to(player, target).warmup(0).cancelOnMove(false).cancelOnDamage(false)
                .cause(TeleportCause.PLUGIN).start().future()
                .whenComplete((result, failure) -> Tasks.of(owner).runAtEntity(player, () -> runAfters(player)));
    }

    // ------------------------------------------------------------ shared

    private static List<Rule> ordered() {
        List<Rule> rules = new ArrayList<>(RULES);
        rules.sort(Comparator.comparingInt((Rule rule) -> rule.priority().getSlot())
                .thenComparingLong(Rule::order));
        return rules;
    }

    private static @Nullable Location ask(Rule rule, RespawnContext context) {
        try {
            Location chosen = rule.rule().apply(context);
            return chosen == null || chosen.getWorld() == null ? null : chosen.clone();
        } catch (RuntimeException | LinkageError failed) {
            Debug.of(rule.plugin()).error("A respawn rule failed for " + context.player().getName(), failed);
            return null;
        }
    }

    static void runAfters(Player player) {
        if (!player.isOnline()) return;
        for (After after : AFTERS) {
            if (!after.plugin().isEnabled()) continue;
            try {
                after.task().accept(player);
            } catch (RuntimeException | LinkageError failed) {
                Debug.of(after.plugin()).error("A respawn callback failed for " + player.getName(), failed);
            }
        }
    }

    /** Test seam: whether the Folia watch is running. */
    static boolean polling() {
        return poll != null;
    }
}
