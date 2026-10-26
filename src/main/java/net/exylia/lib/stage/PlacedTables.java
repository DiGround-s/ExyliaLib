package net.exylia.lib.stage;

import net.exylia.lib.hologram.Hologram;
import net.exylia.lib.hologram.HologramConfig;
import net.exylia.lib.hologram.Holograms;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Tables that stand in the world: a place where one game is played, set down by an admin, with a
 * hologram over it that says whether it is free and takes the click that sits a player down. One
 * player plays at a time; the {@link Stage}s drawn for them can be {@linkplain Stage#watchedWithin
 * watched} by whoever stands round.
 *
 * <pre>{@code
 * tables = new PlacedTables(plugin, "tables.yml", 2.5, new PlacedTables.Hooks() {
 *     public HologramConfig look(PlacedTables.Placed table) { return config.get().tableHologram(); }
 *     public List<String> lines(PlacedTables.Placed table, UUID occupant) { ... free or in use ... }
 *     public void clicked(Player player, PlacedTables.Placed table) { startAt(player, table); }
 * });
 *
 * tables.place("blackjack-1", "blackjack", admin.getLocation());   // where the player will stand
 * if (tables.claim(table.id(), player.getUniqueId())) { ... play ... }
 * tables.release(table.id(), player.getUniqueId());
 * }</pre>
 *
 * <p>The spot is where the player stands, facing the way their board is drawn; the hologram stands
 * {@code ahead} blocks in front of it, over the board. Tables are kept in a YAML file in the plugin's
 * folder; one in a world that is not loaded is kept in the file and shown once it is (on the next
 * {@link #refresh}). Safe from any thread.
 *
 * @since 1.367.0
 */
public final class PlacedTables {

    /** One table: where the player stands to play, and what. */
    public record Placed(@NotNull String id, @NotNull String game, @NotNull String world,
                         double x, double y, double z, float yaw) {

        /** Where the player stands, looking at the board; {@code null} while its world is not loaded. */
        public @Nullable Location spot() {
            World loaded = Bukkit.getWorld(world);
            return loaded == null ? null : new Location(loaded, x, y, z, yaw, 0);
        }
    }

    /** What the plugin decides: how the hologram looks and reads, and what a click on it does. */
    public interface Hooks {

        /** The hologram's look: its lines are replaced by {@link #lines}. */
        @NotNull HologramConfig look(@NotNull Placed table);

        /** Its lines, free ({@code occupant} {@code null}) or in use. */
        @NotNull List<String> lines(@NotNull Placed table, @Nullable UUID occupant);

        /** A player clicked the hologram, on their thread. */
        void clicked(@NotNull Player player, @NotNull Placed table);
    }

    private final Plugin plugin;
    private final File file;
    private final double ahead;
    private final Hooks hooks;
    private final Map<String, Placed> tables = new ConcurrentHashMap<>();
    private final Map<String, UUID> occupants = new ConcurrentHashMap<>();

    /**
     * Loads the tables and puts their holograms up.
     *
     * @param fileName the YAML file in the plugin's folder
     * @param ahead    how far in front of the spot the hologram stands, in blocks
     */
    public PlacedTables(@NotNull Plugin plugin, @NotNull String fileName, double ahead, @NotNull Hooks hooks) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.file = new File(plugin.getDataFolder(), fileName);
        this.ahead = ahead;
        this.hooks = Objects.requireNonNull(hooks, "hooks");
        load();
        refresh();
    }

    /** Sets a table down at {@code spot}, or moves the one with that id there. */
    public @NotNull Placed place(@NotNull String id, @NotNull String game, @NotNull Location spot) {
        Placed table = new Placed(id, game, Objects.requireNonNull(spot.getWorld(), "world").getName(),
                spot.getX(), spot.getY(), spot.getZ(), spot.getYaw());
        tables.put(id, table);
        save();
        show(table);
        return table;
    }

    /** Takes a table away; whether there was one. Whoever plays there finishes their game. */
    public boolean remove(@NotNull String id) {
        if (tables.remove(id) == null) return false;
        occupants.remove(id);
        Holograms.remove(plugin, hologramId(id));
        save();
        return true;
    }

    public @NotNull Optional<Placed> get(@NotNull String id) {
        return Optional.ofNullable(tables.get(id));
    }

    /** Every table, by id. */
    public @NotNull List<Placed> all() {
        List<Placed> all = new ArrayList<>(tables.values());
        all.sort(Comparator.comparing(Placed::id));
        return all;
    }

    /** Who plays at a table, or {@code null} while it is free. */
    public @Nullable UUID occupant(@NotNull String id) {
        return occupants.get(id);
    }

    /** The table a player plays at, if any. */
    public @NotNull Optional<Placed> of(@NotNull UUID player) {
        return occupants.entrySet().stream().filter(entry -> entry.getValue().equals(player))
                .map(entry -> tables.get(entry.getKey())).filter(Objects::nonNull).findFirst();
    }

    /** Sits a player at a table: whether it was free, or theirs already. */
    public boolean claim(@NotNull String id, @NotNull UUID player) {
        if (!tables.containsKey(id)) return false;
        UUID before = occupants.putIfAbsent(id, player);
        if (before != null) return before.equals(player);
        show(tables.get(id));
        return true;
    }

    /** Frees a table, if that player was the one at it. */
    public void release(@NotNull String id, @NotNull UUID player) {
        if (occupants.remove(id, player)) {
            Placed table = tables.get(id);
            if (table != null) show(table);
        }
    }

    /** Frees a table whoever was at it, for one whose player is gone without the plugin hearing it. */
    public void free(@NotNull String id) {
        if (occupants.remove(id) != null) {
            Placed table = tables.get(id);
            if (table != null) show(table);
        }
    }

    /** Puts every hologram up again, for a reload or a world that loaded since. */
    public void refresh() {
        tables.values().forEach(this::show);
    }

    /** Takes every hologram down, for a shutdown. The tables stay in the file. */
    public void close() {
        tables.keySet().forEach(id -> Holograms.remove(plugin, hologramId(id)));
        occupants.clear();
    }

    private void show(Placed table) {
        Location spot = table.spot();
        if (spot == null) return;
        Location over = spot.clone().add(spot.getDirection().setY(0).normalize().multiply(ahead));
        // A FIXED line faces south at yaw 0: turned round, it faces whoever stands at the spot.
        over.setYaw(table.yaw() + 180);
        Hologram hologram = Holograms.show(plugin, hologramId(table.id()), over, hooks.look(table));
        hologram.lines(hooks.lines(table, occupants.get(table.id())));
        hologram.onClick(player -> {
            Placed now = tables.get(table.id());
            if (now != null) hooks.clicked(player, now);
        });
    }

    private static String hologramId(String id) {
        return "table:" + id;
    }

    private void load() {
        if (!file.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String id : yaml.getKeys(false)) {
            ConfigurationSection section = yaml.getConfigurationSection(id);
            if (section == null) continue;
            tables.put(id, new Placed(id, section.getString("game", ""), section.getString("world", "world"),
                    section.getDouble("x"), section.getDouble("y"), section.getDouble("z"),
                    (float) section.getDouble("yaw")));
        }
    }

    private synchronized void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Placed table : all()) {
            ConfigurationSection section = yaml.createSection(table.id());
            section.set("game", table.game());
            section.set("world", table.world());
            section.set("x", table.x());
            section.set("y", table.y());
            section.set("z", table.z());
            section.set("yaw", (double) table.yaw());
        }
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not save " + file.getName(), e);
        }
    }
}
