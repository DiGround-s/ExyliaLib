package net.exylia.lib.movement;

import net.exylia.lib.effect.Effects;
import net.exylia.lib.util.Cooldowns;
import net.exylia.lib.util.PluginCooldowns;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Blocks that throw a player when they step on them.
 *
 * <pre>{@code
 * pads = JumpPads.of(this)
 *         .cooldown(Duration.ofSeconds(1))
 *         .when(lobby::contains)
 *         .pads(settings.pads())            // the plugin's own list, from its file or table
 *         .start();
 *
 * // an in-game editor
 * pads.put(JumpPad.of("spawn-east", PadTrigger.at(block.getLocation()), 1.6, 0.9).towards(-90));
 * pads.remove("spawn-east");
 * }</pre>
 *
 * <p>The pads are the plugin's data: it hands them in at start and after every
 * edit it stores. Nothing here is saved. A pad is one block ({@link PadTrigger.At},
 * matching the plate a player stands in or the block under their feet) or a
 * material rule ({@link PadTrigger.Materials}, every plate on slime); a block
 * pad wins over a material rule at the same spot.
 *
 * <p>Only a move into another block is looked at, and an empty list costs one
 * field read per move. The fall that ends a launch costs no health. Creative
 * and spectator players are not thrown.
 *
 * @since 1.559.0
 */
public final class JumpPads {

    private static final String COOLDOWN = "jump-pad";
    private static final List<JumpPads> RUNNING = new CopyOnWriteArrayList<>();

    private final Plugin plugin;
    private final Duration cooldown;
    private final @Nullable String permission;
    private final Predicate<Player> when;
    private final PluginCooldowns cooldowns;
    private final FallGuard falls;
    private final Events events = new Events();
    /** Replaced whole on every edit, so a move reads one consistent snapshot. */
    private volatile Snapshot pads = Snapshot.EMPTY;
    private volatile boolean stopped;

    private record Snapshot(Map<String, JumpPad> byId, Map<PadTrigger.At, JumpPad> blocks, List<JumpPad> rules) {
        static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of(), List.of());

        static Snapshot of(Collection<JumpPad> pads) {
            Map<String, JumpPad> byId = new LinkedHashMap<>();
            for (JumpPad pad : pads) byId.put(pad.id(), pad);
            Map<PadTrigger.At, JumpPad> blocks = new HashMap<>();
            List<JumpPad> rules = new ArrayList<>();
            for (JumpPad pad : byId.values()) {
                if (pad.trigger() instanceof PadTrigger.At at) blocks.put(at, pad);
                else rules.add(pad);
            }
            return new Snapshot(Map.copyOf(byId), Map.copyOf(blocks), List.copyOf(rules));
        }

        boolean isEmpty() {
            return byId.isEmpty();
        }
    }

    private JumpPads(Builder builder) {
        this.plugin = builder.plugin;
        this.cooldown = builder.cooldown;
        this.permission = builder.permission;
        this.when = builder.when;
        this.cooldowns = Cooldowns.forPlugin(plugin);
        this.falls = new FallGuard(plugin);
        this.pads = Snapshot.of(builder.pads);
    }

    /** Starts describing a plugin's jump pads. */
    public static @NotNull Builder of(@NotNull Plugin plugin) {
        return new Builder(plugin);
    }

    /** Replaces every pad; a reload of the plugin's list. */
    public void set(@NotNull Collection<JumpPad> pads) {
        this.pads = Snapshot.of(pads);
    }

    /** Adds a pad, or replaces the one with the same id. */
    public synchronized void put(@NotNull JumpPad pad) {
        Map<String, JumpPad> next = new LinkedHashMap<>(pads.byId());
        next.put(pad.id(), pad);
        this.pads = Snapshot.of(next.values());
    }

    /** Removes a pad by id; whether there was one. */
    public synchronized boolean remove(@NotNull String id) {
        Map<String, JumpPad> next = new LinkedHashMap<>(pads.byId());
        if (next.remove(id) == null) return false;
        this.pads = Snapshot.of(next.values());
        return true;
    }

    /** Every pad, in the order handed in. */
    public @NotNull List<JumpPad> pads() {
        return List.copyOf(pads.byId().values());
    }

    /** A pad by id. */
    public @NotNull Optional<JumpPad> pad(@NotNull String id) {
        return Optional.ofNullable(pads.byId().get(id));
    }

    /**
     * The pad a player standing at {@code feet} would set off, for an editor
     * that asks "which pad is this". Reads the blocks, so on the thread that
     * owns the location.
     */
    public @NotNull Optional<JumpPad> padAt(@NotNull Location feet) {
        World world = feet.getWorld();
        if (world == null) return Optional.empty();
        int x = feet.getBlockX(), y = feet.getBlockY(), z = feet.getBlockZ();
        return Optional.ofNullable(match(pads, world.getName(), x, y, z,
                world.getBlockAt(x, y, z).getType(), world.getBlockAt(x, y - 1, z).getType(),
                world.getBlockAt(x, y - 2, z).getType()));
    }

    /** Stops listening. Idempotent; done by the library when the plugin disables. */
    public void stop() {
        if (stopped) return;
        stopped = true;
        RUNNING.remove(this);
        HandlerList.unregisterAll(events);
        falls.clear();
    }

    /** Ends every set of pads of a plugin that is disabling; called by the library. */
    @ApiStatus.Internal
    public static void release(@NotNull Plugin plugin) {
        for (JumpPads pads : RUNNING) {
            if (pads.plugin.equals(plugin)) pads.stop();
        }
    }

    /**
     * Which pad fires for a player whose feet are in block {@code x,y,z}:
     * a block pad at the feet, then one under them, then a material rule at the
     * feet (a plate), then one under them (a full block such as slime).
     */
    static @Nullable JumpPad match(Snapshot pads, String world, int x, int y, int z,
                                   Material feet, Material under, Material belowUnder) {
        JumpPad pad = pads.blocks().get(new PadTrigger.At(world, x, y, z));
        if (pad == null) pad = pads.blocks().get(new PadTrigger.At(world, x, y - 1, z));
        if (pad != null) return pad;
        for (JumpPad rule : pads.rules()) {
            if (rule.trigger() instanceof PadTrigger.Materials m && m.pad().contains(feet)
                    && (m.below().isEmpty() || m.below().contains(under))) return rule;
        }
        for (JumpPad rule : pads.rules()) {
            if (rule.trigger() instanceof PadTrigger.Materials m && m.pad().contains(under)
                    && (m.below().isEmpty() || m.below().contains(belowUnder))) return rule;
        }
        return null;
    }

    /** {@link #match} over a plain list, for tests. */
    static @Nullable JumpPad match(Collection<JumpPad> pads, String world, int x, int y, int z,
                                   Material feet, Material under, Material belowUnder) {
        return match(Snapshot.of(pads), world, x, y, z, feet, under, belowUnder);
    }

    private boolean mayLaunch(Player player) {
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) return false;
        if (permission != null && !player.hasPermission(permission)) return false;
        try {
            return when.test(player);
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private void launch(Player player, JumpPad pad) {
        Vector velocity = pad.velocity(player.getLocation().getYaw());
        player.setVelocity(new Vector());
        player.setVelocity(velocity);
        player.setFallDistance(0f);
        falls.launched(player);
        Effects.play(pad.effect(), player);
    }

    private final class Events implements Listener {

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onMove(PlayerMoveEvent event) {
            Player player = event.getPlayer();
            falls.moved(player);
            Snapshot current = pads;
            if (current.isEmpty() || !DoubleJump.changedBlock(event)) return;
            Location to = event.getTo();
            World world = to.getWorld();
            int x = to.getBlockX(), y = to.getBlockY(), z = to.getBlockZ();
            JumpPad pad = match(current, world.getName(), x, y, z, world.getBlockAt(x, y, z).getType(),
                    world.getBlockAt(x, y - 1, z).getType(), world.getBlockAt(x, y - 2, z).getType());
            if (pad == null || !mayLaunch(player)) return;
            if (!cooldowns.tryStart(player, COOLDOWN, cooldown)) return;
            launch(player, pad);
        }

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onFall(EntityDamageEvent event) {
            if (event.getCause() == EntityDamageEvent.DamageCause.FALL
                    && event.getEntity() instanceof Player player && falls.absorbs(player)) {
                event.setCancelled(true);
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onQuit(PlayerQuitEvent event) {
            falls.forget(event.getPlayer().getUniqueId());
        }
    }

    /** Describes a plugin's jump pads; nothing listens until {@link #start()}. */
    public static final class Builder {
        private final Plugin plugin;
        private Duration cooldown = Duration.ofSeconds(1);
        private @Nullable String permission;
        private Predicate<Player> when = player -> true;
        private Collection<JumpPad> pads = List.of();

        private Builder(Plugin plugin) {
            this.plugin = Objects.requireNonNull(plugin, "plugin");
        }

        /** Time before the same player is thrown again by any pad; one second by default. */
        public @NotNull Builder cooldown(@NotNull Duration cooldown) {
            this.cooldown = Objects.requireNonNull(cooldown, "cooldown");
            return this;
        }

        /** A permission a player must hold to be thrown; none by default. */
        public @NotNull Builder permission(@Nullable String permission) {
            this.permission = permission == null || permission.isBlank() ? null : permission;
            return this;
        }

        /** Who is thrown, on top of the permission; read on the player's thread, one that throws counts as no. */
        public @NotNull Builder when(@NotNull Predicate<Player> when) {
            this.when = Objects.requireNonNull(when, "when");
            return this;
        }

        /** The pads to start with. */
        public @NotNull Builder pads(@NotNull Collection<JumpPad> pads) {
            this.pads = List.copyOf(pads);
            return this;
        }

        /** Starts listening. */
        public @NotNull JumpPads start() {
            JumpPads jumpPads = new JumpPads(this);
            Bukkit.getPluginManager().registerEvents(jumpPads.events, plugin);
            RUNNING.add(jumpPads);
            return jumpPads;
        }
    }
}
