package net.exylia.lib.util.teleport;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * A place, which may be on another server.
 *
 * <pre>{@code
 * ExyliaLocation spawn = ExyliaLocation.fromString(config.spawn());
 *
 * if (spawn.isLocal()) {
 *     teleports.to(player, spawn).warmup(3.0).start();
 * }
 * }</pre>
 *
 * <h2>Why not just a Location</h2>
 * A {@link Location} needs a loaded {@link World}, which a lobby does not have
 * for an arena that lives on another server, and it cannot say which server it
 * belongs to at all. This can be read, stored and compared with nothing loaded,
 * which is what a network of servers writing to one database needs.
 *
 * <p>A {@code null} {@link #server()} means "this server", so nothing that only
 * ever runs locally has to know the module can cross a network.
 *
 * <h2>Why the text format is frozen</h2>
 * ExyliaCommons already wrote these strings into databases and configuration
 * files across the ecosystem, in two shapes: six parts for a local place and
 * seven when the first one names a server. Both are accepted here, byte for
 * byte, and {@link #toString()} keeps emitting the seven-part form.
 *
 * <p>Changing either would not break a compile — it would orphan every warp,
 * home and arena a server already has stored, silently, at the moment somebody
 * updated a jar. That is why the parser is deliberately dull.
 *
 * @param server the server this place is on, or {@code null} for this one
 * @param world  the world's name, which does not have to be loaded
 * @param x      the x coordinate
 * @param y      the y coordinate
 * @param z      the z coordinate
 * @param yaw    which way to face, in degrees
 * @param pitch  how far up or down to look, in degrees
 * @since 1.34.0
 */
public record ExyliaLocation(@Nullable String server, @NotNull String world,
                             double x, double y, double z, float yaw, float pitch) {

    /**
     * What a stored string writes instead of a server name when the place is
     * on whichever server reads it.
     */
    private static final String LOCAL_MARKER = "-";

    public ExyliaLocation {
        Objects.requireNonNull(world, "world");
        // An empty or placeholder server name is how a config says "here". Kept
        // as null so isLocal() never has to know about the spelling.
        if (server != null && (server.isBlank() || server.equals(LOCAL_MARKER))) {
            server = null;
        }
    }

    /**
     * A place on this server, taken from a live location.
     *
     * @param location where
     * @return the place
     */
    public static @NotNull ExyliaLocation of(@NotNull Location location) {
        return of(null, location);
    }

    /**
     * A place on a named server, taken from a live location.
     *
     * @param server   which server, or {@code null} for this one
     * @param location where
     * @return the place
     */
    public static @NotNull ExyliaLocation of(@Nullable String server, @NotNull Location location) {
        Objects.requireNonNull(location, "location");
        World world = location.getWorld();
        if (world == null) {
            throw new IllegalArgumentException("The location has no world, so it cannot be stored");
        }
        return new ExyliaLocation(server, world.getName(), location.getX(), location.getY(),
                location.getZ(), location.getYaw(), location.getPitch());
    }

    /**
     * Reads one of the two stored formats.
     *
     * <p>Six parts is a local place, {@code world,x,y,z,yaw,pitch}. Seven puts
     * a server name in front, where {@code -} means this server. Both are what
     * ExyliaCommons wrote, so both keep working.
     *
     * @param text the stored string
     * @return the place
     * @throws IllegalArgumentException when it is neither shape, or a number
     *                                  will not parse
     */
    public static @NotNull ExyliaLocation fromString(@NotNull String text) {
        Objects.requireNonNull(text, "text");
        String[] parts = text.split(",");
        if (parts.length != 6 && parts.length != 7) {
            throw new IllegalArgumentException(
                    "A stored location is 6 or 7 comma-separated parts, got " + parts.length
                            + ": " + text);
        }
        int offset = parts.length == 7 ? 1 : 0;
        String server = offset == 1 ? parts[0].trim() : null;
        String world = parts[offset].trim();
        if (world.isEmpty()) {
            throw new IllegalArgumentException("A stored location needs a world name: " + text);
        }
        try {
            return new ExyliaLocation(server, world,
                    Double.parseDouble(parts[offset + 1].trim()),
                    Double.parseDouble(parts[offset + 2].trim()),
                    Double.parseDouble(parts[offset + 3].trim()),
                    Float.parseFloat(parts[offset + 4].trim()),
                    Float.parseFloat(parts[offset + 5].trim()));
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException(
                    "A stored location has a part that is not a number: " + text, notANumber);
        }
    }

    /** Whether this place is on the server reading it. */
    public boolean isLocal() {
        return server == null;
    }

    /**
     * Whether this place is on the server with this name.
     *
     * <p>A local place answers {@code true} for whatever the caller says it is
     * running as: a stored {@code -} means "wherever you are".
     *
     * @param currentServer this server's name
     * @return whether they are the same server
     */
    public boolean isSameServer(@NotNull String currentServer) {
        return server == null || server.equalsIgnoreCase(currentServer);
    }

    /**
     * This place as a live location, when the world is loaded here.
     *
     * @return the location, or {@code null} when the world is not loaded — which
     *         is also the answer for a place on another server
     */
    public @Nullable Location toBukkitLocation() {
        World loaded = Bukkit.getWorld(world);
        if (loaded == null) {
            return null;
        }
        return new Location(loaded, x, y, z, yaw, pitch);
    }

    /**
     * The seven-part stored form, with {@code -} standing in for this server.
     *
     * <p>Always seven parts, never six: writing the shorter one would produce
     * strings that an older ExyliaCommons could read but that lose the server
     * of anything crossing a network.
     */
    @Override
    public @NotNull String toString() {
        return (server == null ? LOCAL_MARKER : server) + ','
                + world + ',' + x + ',' + y + ',' + z + ',' + yaw + ',' + pitch;
    }
}
