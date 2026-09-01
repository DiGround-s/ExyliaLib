package net.exylia.lib.packet.internal;

import net.exylia.lib.debug.Debug;
import net.exylia.lib.packet.FakeBlocks;
import net.exylia.lib.packet.FakeGameMode;
import net.exylia.lib.packet.GlowingBlocks;
import net.exylia.lib.packet.Movement;
import net.exylia.lib.packet.PluginPackets;
import net.exylia.lib.packet.SilentContainer;
import net.exylia.lib.packet.Visibility;
import net.exylia.lib.packet.VisibilityRule;
import net.exylia.lib.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The packet module's working parts.
 *
 * <p>Holds what every helper remembers — who is hidden from whom, who is
 * frozen where, which blocks each viewer was shown — and answers the packet
 * listener's questions from it. PacketEvents itself is only ever touched
 * through {@link PacketSink}, installed lazily on first use and only after
 * the plugin is confirmed present.
 */
public final class PacketRuntime {

    private static final Map<String, Impl> BY_PLUGIN = new ConcurrentHashMap<>();
    /** One plugin, one rule. */
    private static final Map<String, VisibilityRule> RULES = new ConcurrentHashMap<>();
    /** viewer -> targets they may not see. */
    private static final Map<UUID, Set<UUID>> HIDDEN = new ConcurrentHashMap<>();
    /** entity id -> player, the way back from a packet to its subject. */
    private static final Map<Integer, UUID> ENTITY_IDS = new ConcurrentHashMap<>();
    /** frozen player -> anchor. */
    private static final Map<UUID, Location> ANCHORS = new ConcurrentHashMap<>();
    /** frozen player -> plugin that froze them. */
    private static final Map<UUID, String> FROZEN_BY = new ConcurrentHashMap<>();
    /** viewer -> fake block positions they were shown. */
    private static final Map<UUID, Set<Location>> FAKED = new ConcurrentHashMap<>();
    /** viewer -> outlined position to the client-side entity drawing it. */
    static final Map<UUID, Map<Location, Integer>> OUTLINED = new ConcurrentHashMap<>();
    /** faked spectator -> real game mode at the time. */
    private static final Map<UUID, GameMode> SPECTATING = new ConcurrentHashMap<>();
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private static volatile Plugin lib;
    private static volatile PacketSink sink;
    private static volatile boolean tried;
    private static volatile boolean listening;

    private PacketRuntime() {
    }

    /** Remembers the library plugin; listeners are installed on first use. */
    public static void init(Plugin plugin) {
        lib = plugin;
    }

    public static boolean isAvailable() {
        return sink() != null;
    }

    public static PluginPackets of(Plugin plugin) {
        if (sink() == null && WARNED.add(plugin.getName())) {
            Debug.of(plugin).warn("PacketEvents is not installed: packet helpers do nothing.");
        }
        return BY_PLUGIN.computeIfAbsent(plugin.getName(), name -> new Impl(plugin));
    }

    /** Undoes everything a plugin did, when it is disabled. */
    public static void release(String pluginName) {
        Impl impl = BY_PLUGIN.remove(pluginName);
        RULES.remove(pluginName);
        if (impl != null) {
            impl.clear();
        }
        Mirrors.release(pluginName);
    }

    /** Drops the listeners and everything remembered. */
    public static void shutdown() {
        for (String name : new ArrayList<>(BY_PLUGIN.keySet())) {
            release(name);
        }
        PacketSink current = sink;
        if (current != null) {
            current.close();
        }
        sink = null;
        tried = false;
        listening = false;
        HIDDEN.clear();
        ENTITY_IDS.clear();
        ANCHORS.clear();
        FROZEN_BY.clear();
        FAKED.clear();
        OUTLINED.clear();
        SPECTATING.clear();
        WARNED.clear();
        Mirrors.shutdown();
    }

    /** Installs a sink directly and resets state. For tests. */
    static void install(@Nullable PacketSink value) {
        shutdown();
        sink = value;
        tried = true;
    }

    /**
     * The sink, installed on first use.
     *
     * <p>The plugin-manager check is what keeps {@link PacketHooks} from ever
     * being loaded on a server without PacketEvents.
     */
    static PacketSink sink() {
        if (!tried) {
            synchronized (PacketRuntime.class) {
                if (!tried) {
                    tried = true;
                    if (Bukkit.getPluginManager().getPlugin("packetevents") != null) {
                        sink = PacketHooks.install();
                    }
                }
            }
        }
        ensureListening();
        return sink;
    }

    private static void ensureListening() {
        Plugin plugin = lib;
        if (listening || plugin == null) {
            return;
        }
        synchronized (PacketRuntime.class) {
            if (!listening) {
                listening = true;
                Bukkit.getPluginManager().registerEvents(new BukkitHooks(), plugin);
            }
        }
    }

    // ------------------------------------------------------------------
    // Answers for the packet listener. Cheap, lock-free, any thread.
    // ------------------------------------------------------------------

    static boolean hidesAnything(UUID viewer) {
        Set<UUID> hidden = HIDDEN.get(viewer);
        return hidden != null && !hidden.isEmpty();
    }

    static boolean hidesProfile(UUID viewer, UUID target) {
        Set<UUID> hidden = HIDDEN.get(viewer);
        return hidden != null && hidden.contains(target);
    }

    static boolean hidesEntity(UUID viewer, int entityId) {
        UUID target = ENTITY_IDS.get(entityId);
        return target != null && hidesProfile(viewer, target);
    }

    static @Nullable Location anchorOf(UUID player) {
        return ANCHORS.get(player);
    }

    /** Whether any plugin's rule says {@code viewer} may see {@code target}. */
    static boolean allowed(Player viewer, Player target) {
        if (viewer.equals(target)) {
            return true;
        }
        for (VisibilityRule rule : RULES.values()) {
            if (!rule.canSee(viewer, target)) {
                return false;
            }
        }
        return true;
    }

    /** Forgets a player who left, as a viewer and as a subject. */
    public static void forget(Player player) {
        UUID id = player.getUniqueId();
        HIDDEN.remove(id);
        for (Set<UUID> hidden : HIDDEN.values()) {
            hidden.remove(id);
        }
        ENTITY_IDS.remove(player.getEntityId());
        ANCHORS.remove(id);
        FROZEN_BY.remove(id);
        FAKED.remove(id);
        OUTLINED.remove(id);
        SPECTATING.remove(id);
        Mirrors.forget(player);
    }

    // ------------------------------------------------------------------

    /** Bukkit's side: the safety net under the packet path, and cleanup. */
    static final class BukkitHooks implements Listener {

        @EventHandler(priority = EventPriority.MONITOR)
        public void onQuit(PlayerQuitEvent event) {
            forget(event.getPlayer());
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onWorldChange(PlayerChangedWorldEvent event) {
            // The client dropped every chunk of the old world; so do we.
            FAKED.remove(event.getPlayer().getUniqueId());
            // Its entities went with them: forget without sending a despawn.
            OUTLINED.remove(event.getPlayer().getUniqueId());
        }

        @EventHandler(ignoreCancelled = true)
        public void onMove(PlayerMoveEvent event) {
            Location anchor = ANCHORS.get(event.getPlayer().getUniqueId());
            Location to = event.getTo();
            if (anchor == null || to == null || !anchor.getWorld().equals(to.getWorld())) {
                return;
            }
            if (anchor.distanceSquared(to) > 1e-6) {
                Location back = anchor.clone();
                back.setYaw(to.getYaw());
                back.setPitch(to.getPitch());
                event.setTo(back);
            }
        }
    }

    // ------------------------------------------------------------------

    /** One plugin's helpers. */
    private static final class Impl implements PluginPackets, Visibility, FakeBlocks,
            Movement, FakeGameMode {

        private final Plugin plugin;
        private final String name;
        private final SilentContainer containers;
        private final BlockOutlines outlines;

        Impl(Plugin plugin) {
            this.plugin = plugin;
            this.name = plugin.getName();
            this.containers = new Mirrors(plugin);
            this.outlines = new BlockOutlines(plugin);
        }

        @Override public @NotNull Visibility visibility() { return this; }
        @Override public @NotNull FakeBlocks fakeBlocks() { return this; }
        @Override public @NotNull GlowingBlocks glowingBlocks() { return outlines; }
        @Override public @NotNull Movement movement() { return this; }
        @Override public @NotNull FakeGameMode fakeGameMode() { return this; }
        @Override public @NotNull SilentContainer silentContainer() { return containers; }

        /** Puts back everything this plugin changed. */
        void clear() {
            for (UUID id : new ArrayList<>(FROZEN_BY.keySet())) {
                if (name.equals(FROZEN_BY.get(id))) {
                    ANCHORS.remove(id);
                    FROZEN_BY.remove(id);
                }
            }
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                clear(viewer);
                outlines.clear(viewer);
                if (SPECTATING.containsKey(viewer.getUniqueId())) {
                    spectator(viewer, false);
                }
            }
            // With this plugin's rule gone, everyone it hid may be visible again.
            for (Player target : Bukkit.getOnlinePlayers()) {
                refresh(target);
            }
        }

        // ---- Visibility ----

        @Override
        public void rule(@NotNull VisibilityRule rule) {
            RULES.put(name, rule);
        }

        @Override
        public void refresh(@NotNull Player target) {
            PacketSink out = sink();
            if (out == null) {
                return;
            }
            UUID targetId = target.getUniqueId();
            ENTITY_IDS.put(target.getEntityId(), targetId);
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (viewer.equals(target)) {
                    continue;
                }
                Set<UUID> hidden = HIDDEN.computeIfAbsent(viewer.getUniqueId(),
                        ignored -> ConcurrentHashMap.newKeySet());
                boolean allowed = allowed(viewer, target);
                if (!allowed && hidden.add(targetId)) {
                    // The record first: whatever the tracker sends from here on
                    // is filtered before the despawn even arrives.
                    Tasks.of(plugin).runAtEntity(viewer, () -> {
                        viewer.hidePlayer(plugin, target);
                        out.despawn(viewer, target.getEntityId(), targetId);
                    });
                } else if (allowed && hidden.remove(targetId)) {
                    Tasks.of(plugin).runAtEntity(viewer, () -> viewer.showPlayer(plugin, target));
                }
            }
        }

        @Override
        public boolean canSee(@NotNull Player viewer, @NotNull Player target) {
            return !hidesProfile(viewer.getUniqueId(), target.getUniqueId());
        }

        // ---- FakeBlocks ----

        @Override
        public void show(@NotNull Player viewer, @NotNull Map<Location, BlockData> blocks) {
            PacketSink out = sink();
            if (out == null || blocks.isEmpty()) {
                return;
            }
            Map<Location, BlockData> here = new HashMap<>();
            for (Map.Entry<Location, BlockData> entry : blocks.entrySet()) {
                Location at = entry.getKey();
                if (at.getWorld() != null && at.getWorld().equals(viewer.getWorld())) {
                    here.put(at.getBlock().getLocation(), entry.getValue());
                }
            }
            Set<Location> shown = FAKED.computeIfAbsent(viewer.getUniqueId(),
                    ignored -> ConcurrentHashMap.newKeySet());
            shown.addAll(here.keySet());
            SectionGroups.group(here.keySet()).forEach((section, positions) ->
                    out.blocks(viewer, section, positions, here));
        }

        @Override
        public void clear(@NotNull Player viewer) {
            Set<Location> shown = FAKED.remove(viewer.getUniqueId());
            if (shown != null) {
                restore(viewer, shown);
            }
        }

        @Override
        public void clear(@NotNull Player viewer, @NotNull Collection<Location> positions) {
            Set<Location> shown = FAKED.get(viewer.getUniqueId());
            if (shown == null) {
                return;
            }
            List<Location> restore = new ArrayList<>();
            for (Location at : positions) {
                Location key = at.getBlock().getLocation();
                if (shown.remove(key)) {
                    restore.add(key);
                }
            }
            restore(viewer, restore);
        }

        private void restore(Player viewer, Collection<Location> positions) {
            if (positions.isEmpty() || !viewer.isOnline()) {
                return;
            }
            Tasks.of(plugin).runAtEntity(viewer, () -> {
                for (Location at : positions) {
                    if (at.getWorld().equals(viewer.getWorld())) {
                        viewer.sendBlockChange(at, at.getBlock().getBlockData());
                    }
                }
            });
        }

        // ---- Movement ----

        @Override
        public void freeze(@NotNull Player player) {
            ANCHORS.put(player.getUniqueId(), player.getLocation().clone());
            FROZEN_BY.put(player.getUniqueId(), name);
            sink();
        }

        @Override
        public void unfreeze(@NotNull Player player) {
            if (name.equals(FROZEN_BY.get(player.getUniqueId()))) {
                FROZEN_BY.remove(player.getUniqueId());
                ANCHORS.remove(player.getUniqueId());
            }
        }

        @Override
        public boolean isFrozen(@NotNull Player player) {
            return name.equals(FROZEN_BY.get(player.getUniqueId()));
        }

        // ---- FakeGameMode ----

        @Override
        public void spectator(@NotNull Player player, boolean enabled) {
            PacketSink out = sink();
            if (out == null) {
                return;
            }
            UUID id = player.getUniqueId();
            if (enabled) {
                SPECTATING.putIfAbsent(id, player.getGameMode());
                out.gameMode(player, GameMode.SPECTATOR.getValue());
                out.abilities(player, true, true, true, player.getFlySpeed());
            } else if (SPECTATING.remove(id) != null) {
                out.gameMode(player, player.getGameMode().getValue());
                out.abilities(player, player.isInvulnerable(), player.isFlying(),
                        player.getAllowFlight(), player.getFlySpeed());
            }
        }

        @Override
        public boolean isSpectator(@NotNull Player player) {
            return SPECTATING.containsKey(player.getUniqueId());
        }
    }
}
