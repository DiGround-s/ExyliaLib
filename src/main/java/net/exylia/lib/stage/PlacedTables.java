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
import java.util.Arrays;
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
 * hologram over it that says who sits there and takes the click that sits a player down. The
 * {@link Stage}s drawn for its players can be {@linkplain Stage#watchedWithin watched} by whoever
 * stands round.
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
 * <h2>Seats</h2>
 * A table of one seat (what {@link #place} sets down) is played where it was placed: the spot is where
 * the player stands, facing the way their board is drawn, and the hologram stands {@code ahead} blocks
 * in front of it. A table for several players is placed at its centre and given seats one by one
 * ({@link #addSeat}), each where its player sits facing their board; its hologram stands over the
 * centre. Players take the first free seat ({@link #sit}) and keep it until {@link #release}d.
 * {@link Chairs} sits them down on it.
 *
 * <p>Tables are kept in a YAML file in the plugin's folder; one in a world that is not loaded is kept
 * in the file and shown once it is (on the next {@link #refresh}). Safe from any thread.
 *
 * @since 1.367.0
 */
public final class PlacedTables {

    /**
     * One table: where it stands, what is played at it, and its seats.
     *
     * @param seats where each player sits, facing their board; empty for a table of one seat at its spot
     */
    public record Placed(@NotNull String id, @NotNull String game, @NotNull String world,
                         double x, double y, double z, float yaw, @NotNull List<Spot> seats) {

        public Placed {
            seats = List.copyOf(seats);
        }

        /** A table of one seat, at its spot. */
        public Placed(@NotNull String id, @NotNull String game, @NotNull String world,
                      double x, double y, double z, float yaw) {
            this(id, game, world, x, y, z, yaw, List.of());
        }

        /**
         * Where it was placed: the player's spot, looking at the board, for a table of one seat; its
         * centre for one with seats. {@code null} while its world is not loaded.
         */
        public @Nullable Location spot() {
            World loaded = Bukkit.getWorld(world);
            return loaded == null ? null : new Location(loaded, x, y, z, yaw, 0);
        }

        /** How many players it seats: one for a table without seats of its own. @since 1.369.0 */
        public int seatCount() {
            return Math.max(1, seats.size());
        }

        /** Where a seat's player sits or stands, looking at their board; {@code null} while its world is not loaded. @since 1.369.0 */
        public @Nullable Location seat(int index) {
            if (seats.isEmpty()) return index == 0 ? spot() : null;
            World loaded = Bukkit.getWorld(world);
            if (loaded == null || index < 0 || index >= seats.size()) return null;
            Spot seat = seats.get(index);
            return new Location(loaded, seat.x(), seat.y(), seat.z(), seat.yaw(), 0);
        }
    }

    /** One seat of a table, in its world. @since 1.369.0 */
    public record Spot(double x, double y, double z, float yaw) {
    }

    /** What the plugin decides: how the hologram looks and reads, and what a click on it does. */
    public interface Hooks {

        /** The hologram's look: its lines are replaced by {@link #lines}. */
        @NotNull HologramConfig look(@NotNull Placed table);

        /** Its lines, free ({@code occupant} {@code null}) or in use. Implement this or the one by seats. */
        default @NotNull List<String> lines(@NotNull Placed table, @Nullable UUID occupant) {
            return List.of();
        }

        /**
         * Its lines, from who sits in each seat ({@code null} for a free one). Defaults to the first
         * seated player's lines.
         *
         * @since 1.369.0
         */
        default @NotNull List<String> lines(@NotNull Placed table, @NotNull List<@Nullable UUID> seated) {
            return lines(table, seated.stream().filter(Objects::nonNull).findFirst().orElse(null));
        }

        /**
         * How far above its configured place the hologram rises, in blocks: a table being played may lift
         * it clear of the boards. Defaults to none.
         *
         * @since 1.369.0
         */
        default double lift(@NotNull Placed table, @NotNull List<@Nullable UUID> seated) {
            return 0;
        }

        /**
         * The small hologram over one seat, {@code occupant} {@code null} while it is free: where to sit.
         * Clicking it is clicking the table. Defaults to the library's lines ({@code tables.seat-free} and
         * {@code tables.seat-taken} in its messages); an empty list floats nothing.
         *
         * @since 1.371.0
         */
        default @NotNull List<String> seatLines(@NotNull Placed table, int seat, @Nullable UUID occupant) {
            net.exylia.lib.text.LibraryMessages.Tables text = net.exylia.lib.text.LibraryMessages.get().tables();
            if (occupant == null) return text.seatFree();
            Player player = Bukkit.getPlayer(occupant);
            net.exylia.lib.text.Values values = net.exylia.lib.text.Values.of().putText("player", player == null ? "?" : player.getName());
            return text.seatTaken().stream().map(values::apply).toList();
        }

        /** A player clicked the hologram, on their thread. */
        void clicked(@NotNull Player player, @NotNull Placed table);
    }

    /**
     * What {@link #setup} says and names, from the plugin's messages. Tools: {@code seatTool} in most of
     * the hotbar, {@code doneTool} and {@code cancelTool} in its last two slots. Lines: {@code started}
     * ({@code %table%}), {@code seatAdded} ({@code %seat%}, {@code %seats%}), {@code seatRemoved}
     * ({@code %seats%}), {@code boardSet}, {@code noSeats}, {@code cancelled}; markers drawn for the admin:
     * {@code seatMarker} ({@code %seat%}) and {@code boardMarker}.
     *
     * @since 1.371.0
     */
    public record SetupText(@NotNull String seatTool, @NotNull List<String> seatLore,
                            @NotNull String doneTool, @NotNull List<String> doneLore,
                            @NotNull String cancelTool, @NotNull List<String> cancelLore,
                            @NotNull String started, @NotNull String seatAdded, @NotNull String seatRemoved,
                            @NotNull String boardSet, @NotNull String noSeats, @NotNull String cancelled,
                            @NotNull String seatMarker, @NotNull String boardMarker) {
    }

    private final Plugin plugin;
    private final File file;
    private final double ahead;
    private final Hooks hooks;
    private final Map<String, Placed> tables = new ConcurrentHashMap<>();
    private TableSetup setup;
    /** Who sits in each seat of a table someone sits at; a free seat is {@code null}. */
    private final Map<String, UUID[]> occupants = new ConcurrentHashMap<>();

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

    /**
     * The look the Exylia plugins give a table's hologram: lines turned to every viewer, a soft panel in
     * the palette that lights while aimed at, and a click sound.
     *
     * @param height how far above the spot its lowest line stands, in blocks
     * @since 1.369.0
     */
    public static @NotNull HologramConfig look(double height) {
        // Turned to the viewer round the vertical axis only, and never grown on hover: it stands still.
        HologramConfig.Properties still = new HologramConfig.Properties("VERTICAL", "CENTER", 1.0, 1.0, 1.0, true,
                false, 200, 0.25, 255, false, "#00000000", -1, false);
        return new HologramConfig(true, HologramConfig.Kind.TEXT, List.of(), "", "", false, 24, 0, height, 0,
                still, new HologramConfig.Refresh(),
                new HologramConfig.Click(List.of(), 1.8, 400, 1.0, HologramConfig.Click.STYLE_BUTTON,
                        "{primary}", "{secondary}", "UI_BUTTON_CLICK;0.5;1.5", Map.of(), 6));
    }

    /** The look of the small hologram over a seat. */
    private static final HologramConfig SEAT_LOOK = new HologramConfig(true, HologramConfig.Kind.TEXT, List.of(), "",
            "", false, 16, 0, 1.15, 0,
            new HologramConfig.Properties("VERTICAL", "CENTER", 1.0, 1.0, 1.0, true, false, 200, 0.25, 255, false,
                    "#00000000", -1, false),
            new HologramConfig.Refresh(),
            new HologramConfig.Click(List.of(), 0.9, 400, 1.0, HologramConfig.Click.STYLE_NONE, "{primary}",
                    "{secondary}", "UI_BUTTON_CLICK;0.5;1.5", Map.of(), 6));

    /**
     * The line tag that floats a game's icon in a hologram: {@code <head:...>} for a head value
     * ({@code basehead-}, {@code playerhead-}, {@code urlhead-}), {@code <item:...>} for anything else.
     *
     * @since 1.369.0
     */
    public static @NotNull String icon(@NotNull String icon) {
        String lower = icon.toLowerCase(java.util.Locale.ROOT);
        boolean head = lower.startsWith("basehead-") || lower.startsWith("playerhead-") || lower.startsWith("urlhead-");
        return (head ? "<head:" : "<item:") + icon + ">";
    }

    /** Sets a table down at {@code spot}, or moves the one with that id there; either way without seats. */
    public synchronized @NotNull Placed place(@NotNull String id, @NotNull String game, @NotNull Location spot) {
        Placed table = new Placed(id, game, Objects.requireNonNull(spot.getWorld(), "world").getName(),
                spot.getX(), spot.getY(), spot.getZ(), spot.getYaw());
        tables.put(id, table);
        occupants.remove(id);
        save();
        show(table);
        return table;
    }

    /**
     * Sets a table down with its seats, or replaces the one with that id: {@code board} is where its
     * hologram stands (the middle of the table), each seat where its player sits looking at their board.
     *
     * @since 1.371.0
     */
    public synchronized @NotNull Placed place(@NotNull String id, @NotNull String game, @NotNull Location board,
                                              @NotNull List<Location> seats) {
        hideSeats(tables.get(id));
        List<Spot> spots = seats.stream().map(seat -> new Spot(seat.getX(), seat.getY(), seat.getZ(), seat.getYaw())).toList();
        Placed table = new Placed(id, game, Objects.requireNonNull(board.getWorld(), "world").getName(),
                board.getX(), board.getY(), board.getZ(), board.getYaw(), spots);
        tables.put(id, table);
        occupants.remove(id);
        save();
        show(table);
        return table;
    }

    /**
     * Turns the admin's hotbar into the tools that set a table down by clicking it (see
     * {@link TableSetup}): left-click chairs for seats, right-click where the board goes, DONE. The table
     * is set down, or the one with that id replaced, once they finish; {@code done} runs then, on their
     * thread. Ends by itself when they quit; {@link #cancelSetup} ends it early.
     *
     * @since 1.371.0
     */
    public void setup(@NotNull Player admin, @NotNull String id, @NotNull String game, @NotNull SetupText text,
                      @NotNull java.util.function.Consumer<Placed> done) {
        synchronized (this) {
            if (setup == null) setup = new TableSetup(plugin, this);
        }
        setup.start(admin, id, game, text, done);
    }

    /**
     * {@link #setup(Player, String, String, SetupText, java.util.function.Consumer)} in the library's own
     * words ({@code tables} in its messages, in the server's language).
     *
     * @since 1.373.0
     */
    public void setup(@NotNull Player admin, @NotNull String id, @NotNull String game,
                      @NotNull java.util.function.Consumer<Placed> done) {
        setup(admin, id, game, net.exylia.lib.text.LibraryMessages.get().tables().setup(), done);
    }

    /** Ends an admin's setup without setting anything down. @since 1.371.0 */
    public void cancelSetup(@NotNull Player admin) {
        if (setup != null) setup.end(admin);
    }

    /**
     * The first free id for a new table of a game: {@code blackjack-1}, {@code blackjack-2}...
     *
     * @since 1.371.0
     */
    public @NotNull String nextId(@NotNull String game) {
        for (int n = 1; ; n++) {
            String id = game + "-" + n;
            if (!tables.containsKey(id)) return id;
        }
    }

    /**
     * The table nearest to a place, its hologram or any of its seats within {@code radius} blocks.
     *
     * @since 1.371.0
     */
    public @NotNull Optional<Placed> nearest(@NotNull Location at, double radius) {
        if (at.getWorld() == null) return Optional.empty();
        Placed best = null;
        double bestDistance = radius * radius;
        for (Placed table : tables.values()) {
            if (!table.world().equals(at.getWorld().getName())) continue;
            List<Location> places = new ArrayList<>();
            places.add(table.spot());
            for (int seat = 0; seat < table.seats().size(); seat++) places.add(table.seat(seat));
            for (Location place : places) {
                if (place == null) continue;
                double distance = place.distanceSquared(at);
                if (distance <= bestDistance) {
                    bestDistance = distance;
                    best = table;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Adds a seat where {@code at} is, looking its way: the next a player is given.
     *
     * @return the table with it, or empty when there is no such table or the seat is in another world
     * @since 1.369.0
     */
    public synchronized @NotNull Optional<Placed> addSeat(@NotNull String id, @NotNull Location at) {
        Placed table = tables.get(id);
        if (table == null || at.getWorld() == null || !at.getWorld().getName().equals(table.world())) return Optional.empty();
        List<Spot> seats = new ArrayList<>(table.seats());
        seats.add(new Spot(at.getX(), at.getY(), at.getZ(), at.getYaw()));
        Placed seated = new Placed(id, table.game(), table.world(), table.x(), table.y(), table.z(), table.yaw(), seats);
        tables.put(id, seated);
        UUID[] before = occupants.get(id);
        if (before != null) occupants.put(id, Arrays.copyOf(before, seated.seatCount()));
        save();
        show(seated);
        return Optional.of(seated);
    }

    /**
     * Takes every seat of a table away, making it a table of one seat at its spot again.
     *
     * @return whether there was such a table
     * @since 1.369.0
     */
    public synchronized boolean clearSeats(@NotNull String id) {
        Placed table = tables.get(id);
        if (table == null) return false;
        Placed bare = new Placed(id, table.game(), table.world(), table.x(), table.y(), table.z(), table.yaw());
        hideSeats(table);
        tables.put(id, bare);
        occupants.remove(id);
        save();
        show(bare);
        return true;
    }

    /** Takes a table away; whether there was one. Whoever plays there finishes their game. */
    public synchronized boolean remove(@NotNull String id) {
        Placed removed = tables.remove(id);
        if (removed == null) return false;
        hideSeats(removed);
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

    /** Who sits at a table first, or {@code null} while it is free. */
    public @Nullable UUID occupant(@NotNull String id) {
        return seated(id).stream().filter(Objects::nonNull).findFirst().orElse(null);
    }

    /**
     * Who sits in each of a table's seats, {@code null} for a free one; empty for no such table.
     *
     * @since 1.369.0
     */
    public synchronized @NotNull List<@Nullable UUID> seated(@NotNull String id) {
        Placed table = tables.get(id);
        if (table == null) return List.of();
        UUID[] seats = occupants.get(id);
        return Arrays.asList(seats == null ? new UUID[table.seatCount()] : seats.clone());
    }

    /** The seat a player sits in at a table, or {@code -1}. @since 1.369.0 */
    public int seatOf(@NotNull String id, @NotNull UUID player) {
        return seated(id).indexOf(player);
    }

    /** The table a player sits at, if any. */
    public synchronized @NotNull Optional<Placed> of(@NotNull UUID player) {
        return occupants.entrySet().stream()
                .filter(entry -> Arrays.asList(entry.getValue()).contains(player))
                .map(entry -> tables.get(entry.getKey())).filter(Objects::nonNull).findFirst();
    }

    /**
     * Sits a player in a table's first free seat, or answers the one they already have.
     *
     * @return the seat, or {@code -1} when there is no such table or every seat is taken
     * @since 1.369.0
     */
    public int sit(@NotNull String id, @NotNull UUID player) {
        Placed table;
        int seat;
        synchronized (this) {
            table = tables.get(id);
            if (table == null) return -1;
            UUID[] seats = occupants.computeIfAbsent(id, key -> new UUID[table.seatCount()]);
            int own = Arrays.asList(seats).indexOf(player);
            if (own >= 0) return own;
            seat = Arrays.asList(seats).indexOf(null);
            if (seat < 0) return -1;
            seats[seat] = player;
        }
        show(table);
        return seat;
    }

    /** Sits a player at a table: whether there was a free seat for them, or they had one already. */
    public boolean claim(@NotNull String id, @NotNull UUID player) {
        return sit(id, player) >= 0;
    }

    /** Frees the seat that player sits in at a table, if any. */
    public void release(@NotNull String id, @NotNull UUID player) {
        Placed table;
        synchronized (this) {
            UUID[] seats = occupants.get(id);
            int seat = seats == null ? -1 : Arrays.asList(seats).indexOf(player);
            if (seat < 0) return;
            seats[seat] = null;
            if (Arrays.stream(seats).allMatch(Objects::isNull)) occupants.remove(id);
            table = tables.get(id);
        }
        if (table != null) show(table);
    }

    /** Frees every seat of a table, whoever was at it, for one whose players are gone without the plugin hearing it. */
    public void free(@NotNull String id) {
        if (occupants.remove(id) != null) {
            Placed table = tables.get(id);
            if (table != null) show(table);
        }
    }

    /**
     * Draws one table's hologram again, for lines that read something besides who sits there (a stake,
     * a countdown).
     *
     * @since 1.369.0
     */
    public void update(@NotNull String id) {
        Placed table = tables.get(id);
        if (table != null) show(table);
    }

    /** Puts every hologram up again, for a reload or a world that loaded since. */
    public void refresh() {
        tables.values().forEach(this::show);
    }

    /** Takes every hologram down, for a shutdown. The tables stay in the file. */
    public void close() {
        tables.keySet().forEach(id -> Holograms.remove(plugin, hologramId(id)));
        tables.values().forEach(this::hideSeats);
        occupants.clear();
    }

    private void show(Placed table) {
        Location spot = table.spot();
        if (spot == null) return;
        List<UUID> seated = seated(table.id());
        // One seat: in front of its player, over the board. Several: over the table's centre.
        Location over = table.seats().isEmpty()
                ? spot.clone().add(spot.getDirection().setY(0).normalize().multiply(ahead))
                : spot.clone();
        over.add(0, hooks.lift(table, seated), 0);
        // A FIXED line faces south at yaw 0: turned round, it faces whoever stands at the spot.
        over.setYaw(table.yaw() + 180);
        Hologram hologram = Holograms.show(plugin, hologramId(table.id()), over, hooks.look(table));
        hologram.lines(hooks.lines(table, seated));
        hologram.onClick(player -> {
            Placed now = tables.get(table.id());
            if (now != null) hooks.clicked(player, now);
        });
        for (int seat = 0; seat < table.seats().size(); seat++) {
            String id = seatHologramId(table.id(), seat);
            List<String> lines = hooks.seatLines(table, seat, seat < seated.size() ? seated.get(seat) : null);
            Location at = table.seat(seat);
            if (lines.isEmpty() || at == null) {
                Holograms.remove(plugin, id);
                continue;
            }
            Hologram marker = Holograms.show(plugin, id, at, SEAT_LOOK);
            marker.lines(lines);
            marker.onClick(player -> {
                Placed now = tables.get(table.id());
                if (now != null) hooks.clicked(player, now);
            });
        }
    }

    private static String hologramId(String id) {
        return "table:" + id;
    }

    private static String seatHologramId(String id, int seat) {
        return "table:" + id + ":seat:" + seat;
    }

    /** Takes a table's seat holograms down, before its seats change or it goes. */
    private void hideSeats(@Nullable Placed table) {
        if (table == null) return;
        for (int seat = 0; seat < table.seats().size(); seat++) Holograms.remove(plugin, seatHologramId(table.id(), seat));
    }

    private void load() {
        if (!file.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String id : yaml.getKeys(false)) {
            ConfigurationSection section = yaml.getConfigurationSection(id);
            if (section == null) continue;
            List<Spot> seats = new ArrayList<>();
            for (Map<?, ?> seat : section.getMapList("seats")) {
                seats.add(new Spot(number(seat.get("x")), number(seat.get("y")), number(seat.get("z")),
                        (float) number(seat.get("yaw"))));
            }
            tables.put(id, new Placed(id, section.getString("game", ""), section.getString("world", "world"),
                    section.getDouble("x"), section.getDouble("y"), section.getDouble("z"),
                    (float) section.getDouble("yaw"), seats));
        }
    }

    private static double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
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
            if (!table.seats().isEmpty()) {
                section.set("seats", table.seats().stream().map(seat -> Map.of("x", seat.x(), "y", seat.y(),
                        "z", seat.z(), "yaw", (double) seat.yaw())).toList());
            }
        }
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not save " + file.getName(), e);
        }
    }
}
