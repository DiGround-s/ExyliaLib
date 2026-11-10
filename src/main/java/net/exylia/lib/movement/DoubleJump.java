package net.exylia.lib.movement;

import net.exylia.lib.effect.EffectConfig;
import net.exylia.lib.effect.Effects;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.Cooldowns;
import net.exylia.lib.util.PluginCooldowns;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * A second jump in the air: pressing jump again while airborne launches the
 * player forward and up.
 *
 * <pre>{@code
 * doubleJump = DoubleJump.of(this)
 *         .forward(0.9).vertical(0.75)
 *         .cooldown(Duration.ofSeconds(1))
 *         .effect(settings.doubleJumpEffect())
 *         .permission("lobby.doublejump")
 *         .when(player -> lobby.contains(player) && prefs.doubleJump(player))
 *         .start();
 *
 * doubleJump.refresh(player);   // after something the predicate reads changed
 * }</pre>
 *
 * <h2>How</h2>
 * The flight toggle: an eligible player on the ground is allowed to fly, and
 * the client's "start flying" on the second press is cancelled and turned into
 * the launch. Flight stays off until they land.
 *
 * <h2>Whose flight it touches</h2>
 * Only the flight it gave. A player in creative or spectator is never touched;
 * one whose flight another plugin allowed (a {@code /fly}) is left alone; and
 * a player who stops being eligible (left the area, lost the permission, the
 * predicate said no) loses the flight it gave on the next move, refresh,
 * world change or game-mode change. A quitting player loses it before their
 * data is saved, and disabling the plugin takes it off everybody.
 *
 * <h2>Threads</h2>
 * Events run on each player's own thread, which is what Folia needs;
 * {@link #refresh(Player)} may be called from any thread.
 *
 * @since 1.559.0
 */
public final class DoubleJump {

    private static final String COOLDOWN = "double-jump";
    private static final List<DoubleJump> RUNNING = new CopyOnWriteArrayList<>();

    private final Plugin plugin;
    private final double forward;
    private final double vertical;
    private final Duration cooldown;
    private final EffectConfig effect;
    private final @Nullable String permission;
    private final Predicate<Player> when;
    private final PluginCooldowns cooldowns;
    private final FallGuard falls;
    /** Players this module allowed to fly, and nobody else. */
    private final Set<UUID> armed = ConcurrentHashMap.newKeySet();
    /** Players who jumped and are re-armed once they touch the ground. */
    private final Set<UUID> landing = ConcurrentHashMap.newKeySet();
    private final Events events = new Events();
    private volatile boolean stopped;

    private DoubleJump(Builder builder) {
        this.plugin = builder.plugin;
        this.forward = builder.forward;
        this.vertical = builder.vertical;
        this.cooldown = builder.cooldown;
        this.effect = builder.effect;
        this.permission = builder.permission;
        this.when = builder.when;
        this.cooldowns = Cooldowns.forPlugin(plugin);
        this.falls = new FallGuard(plugin);
    }

    /** Starts describing a double jump for a plugin. */
    public static @NotNull Builder of(@NotNull Plugin plugin) {
        return new Builder(plugin);
    }

    /**
     * Whether a player may double jump right now: online, in survival or
     * adventure, holding the permission and passing the predicate.
     */
    public boolean allows(@NotNull Player player) {
        if (stopped || !player.isOnline()) return false;
        GameMode mode = player.getGameMode();
        if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) return false;
        if (permission != null && !player.hasPermission(permission)) return false;
        try {
            return when.test(player);
        } catch (RuntimeException failure) {
            return false;
        }
    }

    /**
     * Gives or takes the flight trick to match {@link #allows}, now. For
     * whatever the predicate reads changing without the player moving: a
     * preference toggled in a menu, a permission granted. Any thread.
     */
    public void refresh(@NotNull Player player) {
        Tasks.of(plugin).runAtEntity(player, () -> sync(player));
    }

    /** {@link #refresh} for every online player. */
    public void refreshAll() {
        for (Player player : Bukkit.getOnlinePlayers()) refresh(player);
    }

    /** Takes the flight it gave off everybody and stops listening. Idempotent. */
    public void stop() {
        if (stopped) return;
        stopped = true;
        RUNNING.remove(this);
        HandlerList.unregisterAll(events);
        for (UUID id : Set.copyOf(armed)) {
            Player player = Bukkit.getPlayer(id);
            if (player == null) continue;
            try {
                disarm(player);
            } catch (RuntimeException wrongThread) {
                // Shutting down off the player's thread on Folia: a quit clears it anyway.
            }
        }
        armed.clear();
        landing.clear();
        falls.clear();
    }

    /** Ends every double jump of a plugin that is disabling; called by the library. */
    @ApiStatus.Internal
    public static void release(@NotNull Plugin plugin) {
        for (DoubleJump jump : RUNNING) {
            if (jump.plugin.equals(plugin)) jump.stop();
        }
    }

    private void sync(Player player) {
        if (stopped || !player.isOnline()) return;
        boolean armedNow = armed.contains(player.getUniqueId());
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
            // Their flight is the game mode's now; forget it without touching it.
            armed.remove(player.getUniqueId());
            return;
        }
        if (!allows(player)) {
            if (armedNow) disarm(player);
            return;
        }
        if (!armedNow && player.isOnGround() && !player.getAllowFlight()) {
            player.setAllowFlight(true);
            armed.add(player.getUniqueId());
        }
    }

    private void disarm(Player player) {
        if (!armed.remove(player.getUniqueId())) return;
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) return;
        // The quit and shutdown paths call this from wherever they run; the
        // flight flag is the player's own state and is safe to clear there.
        player.setFlying(false);
        player.setAllowFlight(false);
    }

    private void jump(Player player) {
        player.setFlying(false);
        player.setAllowFlight(false);
        armed.remove(player.getUniqueId());
        landing.add(player.getUniqueId());
        if (!cooldowns.tryStart(player, COOLDOWN, cooldown)) return;
        player.setVelocity(velocity(player.getLocation().getDirection(), forward, vertical));
        falls.launched(player);
        Effects.play(effect, player);
    }

    /** The launch: the look direction flattened to the ground, times {@code forward}, with {@code vertical} up. */
    static @NotNull Vector velocity(@NotNull Vector look, double forward, double vertical) {
        Vector flat = new Vector(look.getX(), 0, look.getZ());
        if (flat.lengthSquared() > 1.0E-6) flat.normalize().multiply(forward);
        else flat.zero();
        return flat.setY(vertical);
    }

    private final class Events implements Listener {

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onToggle(PlayerToggleFlightEvent event) {
            Player player = event.getPlayer();
            if (!event.isFlying() || !armed.contains(player.getUniqueId())) return;
            event.setCancelled(true);
            if (!allows(player)) {
                disarm(player);
                return;
            }
            jump(player);
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onMove(PlayerMoveEvent event) {
            Player player = event.getPlayer();
            falls.moved(player);
            boolean landed = player.isOnGround() && landing.remove(player.getUniqueId());
            if (landed || changedBlock(event)) sync(player);
        }

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onFall(EntityDamageEvent event) {
            if (event.getCause() == EntityDamageEvent.DamageCause.FALL
                    && event.getEntity() instanceof Player player && falls.absorbs(player)) {
                event.setCancelled(true);
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onJoin(PlayerJoinEvent event) {
            // A tick later: whatever sets a joining player up (game mode, flight) runs first.
            Player player = event.getPlayer();
            Tasks.of(plugin).runAtEntityLater(player, 1L, () -> sync(player));
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onGameMode(PlayerGameModeChangeEvent event) {
            Player player = event.getPlayer();
            Tasks.of(plugin).runAtEntityLater(player, 1L, () -> sync(player));
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onWorld(PlayerChangedWorldEvent event) {
            Player player = event.getPlayer();
            Tasks.of(plugin).runAtEntityLater(player, 1L, () -> sync(player));
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onQuit(PlayerQuitEvent event) {
            // Before the player's data is saved, so the flight never outlives the session.
            disarm(event.getPlayer());
            falls.forget(event.getPlayer().getUniqueId());
            landing.remove(event.getPlayer().getUniqueId());
        }
    }

    static boolean changedBlock(PlayerMoveEvent event) {
        var from = event.getFrom();
        var to = event.getTo();
        return from.getBlockX() != to.getBlockX() || from.getBlockY() != to.getBlockY()
                || from.getBlockZ() != to.getBlockZ() || from.getWorld() != to.getWorld();
    }

    /** Describes a double jump; nothing listens until {@link #start()}. */
    public static final class Builder {
        private final Plugin plugin;
        private double forward = 0.9;
        private double vertical = 0.75;
        private Duration cooldown = Duration.ofSeconds(1);
        private EffectConfig effect = new EffectConfig();
        private @Nullable String permission;
        private Predicate<Player> when = player -> true;

        private Builder(Plugin plugin) {
            this.plugin = Objects.requireNonNull(plugin, "plugin");
        }

        /** Horizontal speed along the look direction; 0.9 by default. */
        public @NotNull Builder forward(double forward) {
            this.forward = forward;
            return this;
        }

        /** Upward speed; 0.75 by default. */
        public @NotNull Builder vertical(double vertical) {
            this.vertical = vertical;
            return this;
        }

        /** Time between two jumps; one second by default. A press inside it does nothing. */
        public @NotNull Builder cooldown(@NotNull Duration cooldown) {
            this.cooldown = Objects.requireNonNull(cooldown, "cooldown");
            return this;
        }

        /** What a jump looks and sounds like, to the jumper (sound, particle, title...); nothing by default. */
        public @NotNull Builder effect(@NotNull EffectConfig effect) {
            this.effect = Objects.requireNonNull(effect, "effect");
            return this;
        }

        /** A permission a player must hold; none by default. */
        public @NotNull Builder permission(@Nullable String permission) {
            this.permission = permission == null || permission.isBlank() ? null : permission;
            return this;
        }

        /**
         * Who may jump, on top of the permission: an area, a preference, a
         * state. Read on the player's thread on every block they cross, so it
         * reads memory, never a database; one that throws counts as no.
         */
        public @NotNull Builder when(@NotNull Predicate<Player> when) {
            this.when = Objects.requireNonNull(when, "when");
            return this;
        }

        /** Starts listening and arms the players already online. */
        public @NotNull DoubleJump start() {
            DoubleJump jump = new DoubleJump(this);
            Bukkit.getPluginManager().registerEvents(jump.events, plugin);
            RUNNING.add(jump);
            jump.refreshAll();
            return jump;
        }
    }
}
