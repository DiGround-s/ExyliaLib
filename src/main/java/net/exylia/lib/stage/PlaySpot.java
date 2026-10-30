package net.exylia.lib.stage;

import net.exylia.lib.platform.Platform;
import net.exylia.lib.util.teleport.Teleports;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * Where a plugin's rooms are set up, when an admin chose a place for them, and the way back for each
 * player sent there.
 *
 * <pre>{@code
 * spot = new PlaySpot(plugin, "play-spot.yml", uuid -> games.isPlaying(uuid));
 * spot.set(admin.getLocation());                      // /<plugin>admin spot set
 *
 * spot.send(player).thenAccept(arrived -> ...);       // there, or where they stand when none is set
 * spot.giveBack(player);                              // the game ended, they left, quit or died
 * spot.close();                                        // onDisable: everyone sent there goes back
 * }</pre>
 *
 * <p>Every player goes to the same spot: a room is drawn for its player alone and everyone else is
 * hidden from them ({@link Enclosure}), so several play there at once, each in their own.
 *
 * <p>Where each player came from is written to the file before they are moved, and taken out once they
 * are back. A player who quit, crashed or was there when the server stopped hard is sent back when they
 * next join (unless {@code playing} says the plugin still has them), and one who died respawns there.
 *
 * @since 1.420.0
 */
public final class PlaySpot implements Listener {

    private final Plugin plugin;
    private final File file;
    private final Predicate<UUID> playing;
    private final Map<UUID, Location> returns = new ConcurrentHashMap<>();
    private volatile Saved spot;

    private record Saved(String world, double x, double y, double z, float yaw) {
        @Nullable Location location() {
            World loaded = Bukkit.getWorld(world);
            return loaded == null ? null : new Location(loaded, x, y, z, yaw, 0);
        }
    }

    /**
     * @param plugin  the plugin
     * @param name    its file in the plugin's folder, such as {@code play-spot.yml}
     * @param playing whether the plugin still holds a player right now; one it does is not sent back on join
     */
    public PlaySpot(@NotNull Plugin plugin, @NotNull String name, @NotNull Predicate<UUID> playing) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), name);
        this.playing = playing;
        load();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /** The spot, while one is set and its world is loaded. */
    public @NotNull Optional<Location> spot() {
        Saved saved = spot;
        return Optional.ofNullable(saved == null ? null : saved.location());
    }

    /** Sets the spot: where the player stands, facing their way (the pitch is dropped). */
    public void set(@NotNull Location at) {
        spot = new Saved(at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), at.getYaw());
        save();
    }

    /** Takes the spot away: rooms open where their player stands again. */
    public void clear() {
        spot = null;
        save();
    }

    /** Whether a player was sent to the spot and is not back yet. */
    public boolean isAway(@NotNull UUID player) {
        return returns.containsKey(player);
    }

    /**
     * Sends a player to the spot and remembers where they were; completes with where they will play: the
     * spot, or where they stand when none is set. Completes with {@code null} when the move failed. On the
     * player's thread.
     */
    public @NotNull CompletableFuture<Location> send(@NotNull Player player) {
        Location at = spot().orElse(null);
        if (at == null) return CompletableFuture.completedFuture(player.getLocation());
        // A second game in a row keeps the first way back.
        returns.putIfAbsent(player.getUniqueId(), player.getLocation().clone());
        save();
        return Teleports.of(plugin).to(player, at).warmup(0).start().future().thenApply(result -> {
            if (result.isSuccess()) return at.clone();
            forget(player.getUniqueId());
            return null;
        });
    }

    /** Sends a player back where they were before {@link #send}; nothing when they were never sent. */
    public @NotNull CompletableFuture<Boolean> giveBack(@NotNull Player player) {
        Location home = returns.get(player.getUniqueId());
        if (home == null || home.getWorld() == null) return CompletableFuture.completedFuture(false);
        // Dead: the respawn takes them home instead, see onRespawn.
        if (player.isDead()) return CompletableFuture.completedFuture(false);
        return Teleports.of(plugin).to(player, home).warmup(0).start().future().thenApply(result -> {
            if (result.isSuccess()) forget(player.getUniqueId());
            return result.isSuccess();
        });
    }

    /** Sends back everyone still away, for a shutdown; the file keeps whoever could not be moved. */
    public void close() {
        HandlerList.unregisterAll(this);
        for (Map.Entry<UUID, Location> entry : Map.copyOf(returns).entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || player.isDead() || entry.getValue().getWorld() == null) continue;
            // Synchronous on purpose (an allowed exception to the Teleports rule): the plugin is going down
            // and no task scheduled now would run. On Folia it is left to the next join.
            if (Platform.isFolia()) continue;
            if (player.teleport(entry.getValue())) returns.remove(entry.getKey());
        }
        save();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!returns.containsKey(player.getUniqueId()) || playing.test(player.getUniqueId())) return;
        // The library defers a move made on join by a tick, so this is safe here.
        giveBack(player);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Location home = returns.get(id);
        if (home == null || home.getWorld() == null || playing.test(id)) return;
        event.setRespawnLocation(home);
        forget(id);
    }

    private void forget(UUID player) {
        if (returns.remove(player) != null) save();
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection at = yaml.getConfigurationSection("spot");
        if (at != null && at.getString("world") != null) {
            spot = new Saved(at.getString("world"), at.getDouble("x"), at.getDouble("y"), at.getDouble("z"),
                    (float) at.getDouble("yaw"));
        }
        ConfigurationSection back = yaml.getConfigurationSection("returns");
        if (back == null) return;
        for (String key : back.getKeys(false)) {
            ConfigurationSection row = back.getConfigurationSection(key);
            World world = row == null ? null : Bukkit.getWorld(row.getString("world", ""));
            if (world == null) continue;
            try {
                returns.put(UUID.fromString(key), new Location(world, row.getDouble("x"), row.getDouble("y"),
                        row.getDouble("z"), (float) row.getDouble("yaw"), (float) row.getDouble("pitch")));
            } catch (IllegalArgumentException ignored) {
                // Not a player's row.
            }
        }
    }

    private synchronized void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        Saved saved = spot;
        if (saved != null) {
            yaml.set("spot.world", saved.world());
            yaml.set("spot.x", saved.x());
            yaml.set("spot.y", saved.y());
            yaml.set("spot.z", saved.z());
            yaml.set("spot.yaw", (double) saved.yaw());
        }
        returns.forEach((id, at) -> {
            String row = "returns." + id;
            yaml.set(row + ".world", at.getWorld().getName());
            yaml.set(row + ".x", at.getX());
            yaml.set(row + ".y", at.getY());
            yaml.set(row + ".z", at.getZ());
            yaml.set(row + ".yaw", (double) at.getYaw());
            yaml.set(row + ".pitch", (double) at.getPitch());
        });
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not save " + file.getName(), e);
        }
    }
}
