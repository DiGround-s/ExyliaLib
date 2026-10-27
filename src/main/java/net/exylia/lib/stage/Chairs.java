package net.exylia.lib.stage;

import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import org.bukkit.Location;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Players sat down on a spot: a client only draws someone seated while they ride something, so each
 * rides an invisible seat (a block display of air) whose origin is where their hips rest. Put the spot
 * on the chair: a stair's top, a slab's, a block's.
 *
 * <pre>{@code
 * Chairs chairs = Chairs.of(plugin);
 * chairs.sit(player, seat, () -> leaveTable(player));   // on the player's thread, once they stand at the seat
 * chairs.lock(player, true);                             // while a match is played: sneaking does not stand them up
 * chairs.stand(player);                                  // the plugin stands them up; stoodUp is not run
 * }</pre>
 *
 * <p>Sneaking stands an unlocked player up and runs their {@code stoodUp}. Quitting, dying and the
 * plugin being disabled take the seat away without it. The seat is never saved with the world.
 *
 * @since 1.369.0
 */
public final class Chairs implements Listener {

    private static final Map<String, Chairs> BY_PLUGIN = new ConcurrentHashMap<>();

    private final Plugin plugin;
    private final TaskScheduler tasks;
    private final Map<UUID, Chair> seated = new ConcurrentHashMap<>();

    private static final class Chair {
        final Entity seat;
        final @Nullable Runnable stoodUp;
        volatile boolean locked;

        Chair(Entity seat, @Nullable Runnable stoodUp) {
            this.seat = seat;
            this.stoodUp = stoodUp;
        }
    }

    private Chairs(Plugin plugin) {
        this.plugin = plugin;
        this.tasks = Tasks.of(plugin);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /** The plugin's chairs, made on first use. */
    public static @NotNull Chairs of(@NotNull Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        return BY_PLUGIN.computeIfAbsent(plugin.getName(), name -> new Chairs(plugin));
    }

    /**
     * Sits a player down at {@code at}. Runs on the player's thread, with them standing at the spot
     * (their region is the seat's). Unlocked.
     *
     * @param stoodUp what runs when they stand up by sneaking, on their thread; {@code null} for nothing
     * @return whether they sat: {@code false} when they already ride something or another plugin refused
     */
    public boolean sit(@NotNull Player player, @NotNull Location at, @Nullable Runnable stoodUp) {
        Objects.requireNonNull(at.getWorld(), "world");
        stand(player);
        if (player.isInsideVehicle()) return false;
        BlockDisplay seat = at.getWorld().spawn(at, BlockDisplay.class, display -> display.setPersistent(false));
        Chair chair = new Chair(seat, stoodUp);
        seated.put(player.getUniqueId(), chair);
        if (!seat.addPassenger(player)) {
            seated.remove(player.getUniqueId(), chair);
            seat.remove();
            return false;
        }
        return true;
    }

    /** Whether sneaking stands the player up: not while locked. Nothing when they are not seated. */
    public void lock(@NotNull Player player, boolean locked) {
        Chair chair = seated.get(player.getUniqueId());
        if (chair != null) chair.locked = locked;
    }

    public boolean isSeated(@NotNull UUID player) {
        return seated.containsKey(player);
    }

    /** Stands a player up, without running their {@code stoodUp}. Safe from any thread. */
    public void stand(@NotNull Player player) {
        Chair chair = seated.remove(player.getUniqueId());
        if (chair != null) tasks.runAtEntity(chair.seat, () -> {
            chair.seat.eject();
            chair.seat.remove();
        });
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDismount(EntityDismountEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Chair chair = seated.get(player.getUniqueId());
        if (chair == null || event.getDismounted() != chair.seat) return;
        if (chair.locked && event.isCancellable() && player.isOnline() && !player.isDead()) {
            event.setCancelled(true);
            return;
        }
        if (!seated.remove(player.getUniqueId(), chair)) return;
        // Not while the dismount is still being run: the seat goes, and the plugin hears of it, next tick.
        tasks.runAtEntity(chair.seat, chair.seat::remove);
        if (chair.stoodUp != null) tasks.runAtEntity(player, chair.stoodUp);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Chair chair = seated.remove(event.getPlayer().getUniqueId());
        if (chair != null) {
            chair.seat.eject();
            chair.seat.remove();
        }
    }

    /** Takes a disabled plugin's seats away. Called by the library. */
    public static void release(@NotNull Plugin plugin) {
        Chairs chairs = BY_PLUGIN.remove(plugin.getName());
        if (chairs == null) return;
        for (Chair chair : List.copyOf(chairs.seated.values())) {
            try {
                chair.seat.eject();
                chair.seat.remove();
            } catch (RuntimeException ignored) {
                // Off its region on Folia: it is never saved, so a restart takes it anyway.
            }
        }
        chairs.seated.clear();
    }
}
