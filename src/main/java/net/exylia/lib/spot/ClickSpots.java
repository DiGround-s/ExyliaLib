package net.exylia.lib.spot;

import net.exylia.lib.block.Blocks;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.hologram.Hologram;
import net.exylia.lib.hologram.HologramConfig;
import net.exylia.lib.hologram.Holograms;
import net.exylia.lib.npc.PlacedNpcClickEvent;
import net.exylia.lib.spot.internal.SpotsMenu;
import net.exylia.lib.stage.PlacedTables;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Phrases;
import net.exylia.lib.util.wizard.WizardTarget;
import net.exylia.lib.util.wizard.internal.WizardRuntime;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Places in the world that open something when a player clicks them: a block,
 * an entity or a placed NPC, with an optional hologram over it.
 *
 * <p>A world table is the case it was made for: the game itself lives on a
 * screen, and the place only has to open it. The plugin says which kinds of
 * spot exist (its games) and what a click does; this class keeps the file,
 * protects the block, catches the clicks (also inside another plugin's region,
 * which cancels them first) and draws the admin screens.
 *
 * <pre>{@code
 * spots = new ClickSpots(plugin, "tables.yml", new ClickSpots.Hooks() {
 *     public List<ClickSpots.Kind> kinds() { return games(); }
 *     public void clicked(Player player, ClickSpots.Spot spot) { openGame(player, spot.kind()); }
 * });
 * spots.openAdmin(player, () -> hub(player));   // list, create, move, remove
 * }</pre>
 *
 * <p>A file written by {@link PlacedTables} is read too: each old table becomes
 * a block spot at its board, or at the block in front of its play spot, and the
 * file is kept as {@code <file>.old} before it is first rewritten.
 *
 * @since 1.603.0
 */
public final class ClickSpots {

    /** A kind of spot the admin picks from, usually a game. */
    public record Kind(@NotNull String id, @NotNull String name, @NotNull String icon) {
    }

    /** One placed spot. A block spot has no entity and no NPC. */
    public record Spot(@NotNull String id, @NotNull String kind, @NotNull String world, int x, int y, int z,
                       @Nullable UUID entity, @Nullable String npc, boolean hologram) {

        /** Where it is, or {@code null} while its world is not loaded. */
        public @Nullable Location location() {
            World loaded = Bukkit.getWorld(world);
            return loaded == null ? null : new Location(loaded, x, y, z);
        }

        public boolean isBlock() {
            return entity == null && npc == null;
        }

        /** {@code block}, {@code entity} or {@code npc}. */
        public @NotNull String anchor() {
            return npc != null ? "npc" : entity != null ? "entity" : "block";
        }

        @NotNull Spot kind(@NotNull String kind) {
            return new Spot(id, kind, world, x, y, z, entity, npc, hologram);
        }

        @NotNull Spot hologram(boolean on) {
            return new Spot(id, kind, world, x, y, z, entity, npc, on);
        }

        static @NotNull Spot at(@NotNull String id, @NotNull String kind, @NotNull WizardTarget target,
                                boolean hologram) {
            Location where = target.location();
            return new Spot(id, kind, Objects.requireNonNull(where.getWorld(), "world").getName(),
                    where.getBlockX(), where.getBlockY(), where.getBlockZ(), target.entity(), target.npc(), hologram);
        }
    }

    /** What the plugin decides. */
    public interface Hooks {

        /** The kinds an admin can place, in picker order. */
        @NotNull List<Kind> kinds();

        /** A player clicked the spot, on their thread. */
        void clicked(@NotNull Player player, @NotNull Spot spot);

        /** How many players are at the spot's game now; shown in the hologram and the admin screens. */
        default int playersAt(@NotNull Spot spot) {
            return 0;
        }

        /** The hologram's lines, read again every two seconds. */
        default @NotNull List<String> lines(@NotNull Spot spot, @NotNull Kind kind) {
            int players = playersAt(spot);
            return List.of("{primary}&l" + kind.name(),
                    players > 0 ? Phrases.tr("{success}{0} {letters}playing now", players)
                            : Phrases.tr("{muted}Nobody playing yet"),
                    Phrases.tr("{secondary}» {letters}Click to play"));
        }

        /** The hologram's look. */
        default @NotNull HologramConfig look() {
            return PlacedTables.look(0);
        }
    }

    /** Whether a block is air, for the legacy import. */
    @FunctionalInterface
    interface Air {
        boolean at(String world, int x, int y, int z);
    }

    /** What a file held. */
    record Read(Map<String, Spot> spots, boolean legacy) {
    }

    private static final Map<String, ClickSpots> BY_OWNER = new ConcurrentHashMap<>();
    private static final long CLICK_GAP_MS = 300;

    private final Plugin plugin;
    private final File file;
    private final double legacyAhead;
    private final Hooks hooks;
    private final Map<String, Spot> spots = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastClick = new ConcurrentHashMap<>();
    private final Listener listener = new Clicks();
    private volatile String permission;
    private volatile @Nullable TaskHandle refresh;

    /** Loads the spots in {@code file} and puts them up. */
    public ClickSpots(@NotNull Plugin plugin, @NotNull String file, @NotNull Hooks hooks) {
        this(plugin, file, 0, hooks);
    }

    /**
     * Loads the spots in {@code file} and puts them up.
     *
     * @param legacyAhead the {@code ahead} the plugin gave {@link PlacedTables} over this file, used to
     *                    find the block in front of an old table without seats
     */
    public ClickSpots(@NotNull Plugin plugin, @NotNull String file, double legacyAhead, @NotNull Hooks hooks) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.file = new File(plugin.getDataFolder(), Objects.requireNonNull(file, "file"));
        this.legacyAhead = legacyAhead;
        this.hooks = Objects.requireNonNull(hooks, "hooks");
        this.permission = plugin.getName().toLowerCase(Locale.ROOT) + ".admin";
        BY_OWNER.put(plugin.getName(), this);
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        load();
        refresh = Tasks.of(plugin).runTimer(40, 40, this::refresh);
    }

    /** The spots of one plugin, for the library's screens. */
    public static @NotNull Optional<ClickSpots> of(@NotNull String plugin) {
        return Optional.ofNullable(BY_OWNER.get(plugin));
    }

    /** The permission the admin screens ask for; {@code <plugin>.admin} by default. */
    public @NotNull ClickSpots permission(@NotNull String permission) {
        this.permission = Objects.requireNonNull(permission, "permission");
        return this;
    }

    public @NotNull String permission() {
        return permission;
    }

    public @NotNull Plugin plugin() {
        return plugin;
    }

    public @NotNull Hooks hooks() {
        return hooks;
    }

    // ------------------------------------------------------------------ reading

    public @NotNull Optional<Spot> get(@NotNull String id) {
        return Optional.ofNullable(spots.get(id));
    }

    /** Every spot, by id. */
    public @NotNull List<Spot> all() {
        List<Spot> all = new ArrayList<>(spots.values());
        all.sort(Comparator.comparing(Spot::id));
        return all;
    }

    /** The kind a spot is, or {@code null} once the plugin no longer has it. */
    public @Nullable Kind kindOf(@NotNull Spot spot) {
        for (Kind kind : hooks.kinds()) if (kind.id().equals(spot.kind())) return kind;
        return null;
    }

    /** The closest spot within {@code radius} blocks. */
    public @NotNull Optional<Spot> nearest(@NotNull Location from, double radius) {
        Spot best = null;
        double bestDistance = radius * radius;
        for (Spot spot : spots.values()) {
            Location at = spot.location();
            if (at == null || at.getWorld() != from.getWorld()) continue;
            double distance = at.clone().add(0.5, 0, 0.5).distanceSquared(from);
            if (distance <= bestDistance) {
                best = spot;
                bestDistance = distance;
            }
        }
        return Optional.ofNullable(best);
    }

    // ------------------------------------------------------------------ changing

    /** Places a new spot; a blank or taken {@code id} gets the next free {@code <kind>-<n>}. */
    public synchronized @NotNull Spot create(@NotNull String kind, @NotNull WizardTarget target, @Nullable String id) {
        String chosen = id == null || id.isBlank() || spots.containsKey(id) ? freeId(kind) : id;
        Spot spot = Spot.at(chosen, kind, target, true);
        put(spot);
        return spot;
    }

    /** Moves a spot onto a new block, entity or NPC. */
    public synchronized @NotNull Optional<Spot> move(@NotNull String id, @NotNull WizardTarget target) {
        Spot old = spots.get(id);
        if (old == null) return Optional.empty();
        take(old);
        Spot moved = Spot.at(id, old.kind(), target, old.hologram());
        put(moved);
        return Optional.of(moved);
    }

    public synchronized @NotNull Optional<Spot> kind(@NotNull String id, @NotNull String kind) {
        return replace(id, spot -> spot.kind(kind));
    }

    public synchronized @NotNull Optional<Spot> hologram(@NotNull String id, boolean on) {
        return replace(id, spot -> spot.hologram(on));
    }

    public synchronized boolean remove(@NotNull String id) {
        Spot gone = spots.remove(id);
        if (gone == null) return false;
        take(gone);
        save();
        return true;
    }

    /** Reads the file again and puts every spot back up. */
    public synchronized void reload() {
        spots.values().forEach(this::take);
        spots.clear();
        load();
    }

    /** Takes every spot down and stops listening; the file stays. */
    public synchronized void close() {
        TaskHandle running = refresh;
        if (running != null) running.cancel();
        HandlerList.unregisterAll(listener);
        spots.values().forEach(this::take);
        BY_OWNER.remove(plugin.getName(), this);
    }

    // ------------------------------------------------------------------ admin flows

    /** The admin list: every spot, create, and each one's detail. {@code back} adds a back button. */
    public void openAdmin(@NotNull Player viewer, @Nullable Runnable back) {
        SpotsMenu.list(viewer, this, back);
    }

    /** Opens one spot's detail screen. */
    public void openDetail(@NotNull Player viewer, @NotNull String id) {
        SpotsMenu.detail(viewer, this, id);
    }

    /** Starts the place wizard for a new spot of {@code kind}; {@code id} may be {@code null}. */
    public void startCreate(@NotNull Player viewer, @NotNull String kind, @Nullable String id) {
        SpotsMenu.create(viewer, this, kind, id);
    }

    /** Asks to confirm, then removes the spot. */
    public void confirmRemove(@NotNull Player viewer, @NotNull String id) {
        SpotsMenu.remove(viewer, this, id);
    }

    // ------------------------------------------------------------------ internals

    private Optional<Spot> replace(String id, java.util.function.UnaryOperator<Spot> change) {
        Spot old = spots.get(id);
        if (old == null) return Optional.empty();
        take(old);
        Spot changed = change.apply(old);
        put(changed);
        return Optional.of(changed);
    }

    private void put(Spot spot) {
        spots.put(spot.id(), spot);
        show(spot);
        save();
    }

    private String freeId(String kind) {
        String base = kind.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        if (base.isEmpty()) base = "spot";
        int n = 1;
        while (spots.containsKey(base + "-" + n)) n++;
        return base + "-" + n;
    }

    private String hologramId(String id) {
        return "spot:" + id;
    }

    private void show(Spot spot) {
        Location at = spot.location();
        if (at == null) return;
        if (spot.isBlock()) {
            Blocks.of(plugin).at(at).onClick(click -> clicked(click.player(), spot.id())).protect(true).register();
        }
        if (!spot.hologram()) return;
        Kind kind = kindOf(spot);
        if (kind == null) return;
        Location over = spot.isBlock() ? at.clone().add(0.5, 1.4, 0.5) : at.clone().add(0.5, 2.4, 0.5);
        Hologram hologram = Holograms.show(plugin, hologramId(spot.id()), over, hooks.look());
        hologram.lines(hooks.lines(spot, kind));
        hologram.onClick(player -> clicked(player, spot.id()));
    }

    private void take(Spot spot) {
        Holograms.remove(plugin, hologramId(spot.id()));
        Location at = spot.location();
        if (at != null && spot.isBlock()) Blocks.of(plugin).unregister(at);
    }

    private void refresh() {
        for (Spot spot : spots.values()) {
            if (!spot.hologram()) continue;
            Kind kind = kindOf(spot);
            if (kind == null) continue;
            Holograms.get(plugin, hologramId(spot.id())).ifPresent(h -> h.lines(hooks.lines(spot, kind)));
        }
    }

    private void clicked(Player player, String id) {
        Spot spot = spots.get(id);
        if (spot == null || WizardRuntime.isRunning(player.getUniqueId())) return;
        long now = System.currentTimeMillis();
        Long last = lastClick.put(player.getUniqueId(), now);
        if (last != null && now - last < CLICK_GAP_MS) return;
        Tasks.of(plugin).runAtEntity(player, () -> hooks.clicked(player, spot));
    }

    private @Nullable Spot byEntity(UUID entity) {
        for (Spot spot : spots.values()) if (entity.equals(spot.entity())) return spot;
        return null;
    }

    private void load() {
        if (!file.exists()) return;
        Read read = read(YamlConfiguration.loadConfiguration(file), legacyAhead, (world, x, y, z) -> {
            World loaded = Bukkit.getWorld(world);
            if (loaded == null) return false;
            Block block = loaded.getBlockAt(x, y, z);
            return block.getType().isAir();
        });
        spots.putAll(read.spots());
        if (read.legacy()) {
            File backup = new File(file.getParentFile(), file.getName() + ".old");
            try {
                if (!backup.exists()) Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
                save();
                Debug.of(plugin).log("Moved " + read.spots().size() + " world tables to click spots; the old file is "
                        + backup.getName() + ".");
            } catch (IOException failed) {
                Debug.of(plugin).error("Could not back up " + file.getName() + "; kept it as it was.", failed);
            }
        }
        spots.values().forEach(this::show);
    }

    private synchronized void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        write(yaml, all());
        try {
            yaml.save(file);
        } catch (IOException failed) {
            Debug.of(plugin).error("Could not save " + file.getName() + ".", failed);
        }
    }

    // ------------------------------------------------------------------ the file

    /** Reads every spot, and every table {@link PlacedTables} wrote, from a file. */
    static @NotNull Read read(@NotNull ConfigurationSection yaml, double ahead, @NotNull Air air) {
        Map<String, Spot> read = new LinkedHashMap<>();
        boolean legacy = false;
        for (String id : yaml.getKeys(false)) {
            ConfigurationSection entry = yaml.getConfigurationSection(id);
            if (entry == null) continue;
            String world = entry.getString("world");
            if (world == null) continue;
            if (entry.contains("anchor")) {
                String kind = entry.getString("kind", "");
                String entity = entry.getString("entity");
                UUID uuid = null;
                try {
                    if (entity != null) uuid = UUID.fromString(entity);
                } catch (IllegalArgumentException bad) {
                    // A hand-edited id: the spot stays, as a block.
                }
                read.put(id, new Spot(id, kind, world, entry.getInt("x"), entry.getInt("y"), entry.getInt("z"),
                        uuid, entry.getString("npc"), entry.getBoolean("hologram", true)));
                continue;
            }
            legacy = true;
            read.put(id, legacy(id, entry, world, ahead, air));
        }
        return new Read(read, legacy);
    }

    /** An old table: its board, or the block in front of its play spot, stepped down onto something solid. */
    private static Spot legacy(String id, ConfigurationSection entry, String world, double ahead, Air air) {
        double x = entry.getDouble("x");
        double y = entry.getDouble("y");
        double z = entry.getDouble("z");
        List<?> seats = entry.getList("seats");
        if (seats == null || seats.isEmpty()) {
            double yaw = Math.toRadians(entry.getDouble("yaw"));
            x += -Math.sin(yaw) * ahead;
            z += Math.cos(yaw) * ahead;
        }
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);
        for (int step = 0; step < 2 && air.at(world, bx, by, bz); step++) by--;
        return new Spot(id, entry.getString("game", entry.getString("kind", "")), world, bx, by, bz, null, null, true);
    }

    static void write(@NotNull ConfigurationSection yaml, @NotNull Collection<Spot> spots) {
        for (Spot spot : spots) {
            ConfigurationSection entry = yaml.createSection(spot.id());
            entry.set("kind", spot.kind());
            entry.set("anchor", spot.anchor());
            entry.set("world", spot.world());
            entry.set("x", spot.x());
            entry.set("y", spot.y());
            entry.set("z", spot.z());
            if (spot.entity() != null) entry.set("entity", spot.entity().toString());
            if (spot.npc() != null) entry.set("npc", spot.npc());
            entry.set("hologram", spot.hologram());
        }
    }

    /** Entity and NPC clicks; never ignoring cancelled ones, since a region plugin cancels them first. */
    private final class Clicks implements Listener {

        @EventHandler(priority = EventPriority.HIGH)
        public void onEntity(PlayerInteractEntityEvent event) {
            if (event.getHand() != EquipmentSlot.HAND) return;
            Spot spot = byEntity(event.getRightClicked().getUniqueId());
            if (spot == null) return;
            event.setCancelled(true);
            clicked(event.getPlayer(), spot.id());
        }

        @EventHandler(priority = EventPriority.HIGH)
        public void onHit(EntityDamageByEntityEvent event) {
            if (!(event.getDamager() instanceof Player player)) return;
            Spot spot = byEntity(event.getEntity().getUniqueId());
            if (spot == null) return;
            event.setCancelled(true);
            clicked(player, spot.id());
        }

        @EventHandler
        public void onNpc(PlacedNpcClickEvent event) {
            String key = event.owner().getName() + ":" + event.npcId();
            for (Spot spot : spots.values()) {
                if (key.equals(spot.npc())) {
                    clicked(event.getPlayer(), spot.id());
                    return;
                }
            }
        }
    }
}
