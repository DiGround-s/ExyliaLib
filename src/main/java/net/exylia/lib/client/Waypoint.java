package net.exylia.lib.client;

import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.UUID;

/**
 * A marker shown on a modified client's world and minimap.
 *
 * <p>Waypoints are the one thing every modified client agrees on, so this is
 * the shape both Lunar and Feather are given:
 *
 * <pre>{@code
 * Clients.waypoints().show(player, Waypoint.at("Koth", arena.centre())
 *         .colour("#8a51c4")
 *         .lasting(Duration.ofMinutes(5)));
 * }</pre>
 *
 * <p>Vanilla players are not an error: they simply see nothing, and the call
 * costs a map lookup.
 *
 * @param name          what the client labels it with, and the handle it is
 *                      removed by
 * @param x             block coordinates
 * @param y             block coordinates
 * @param z             block coordinates
 * @param worldName     the world it belongs to, so it is hidden elsewhere
 * @param worldId       the world id, which is what Feather matches on
 * @param colour        the marker colour
 * @param duration      how long it lives, or {@code null} to stay until removed
 * @param preventRemoval whether the player is allowed to delete it themselves;
 *                      Lunar only, Feather has no such field and ignores it
 * @param hidden        whether it is created already hidden; Lunar only, for
 *                      the same reason
 * @since 1.7.0
 */
public record Waypoint(
        @NotNull String name,
        int x,
        int y,
        int z,
        @NotNull String worldName,
        @Nullable UUID worldId,
        @NotNull Colour colour,
        @Nullable Duration duration,
        boolean preventRemoval,
        boolean hidden) {

    public Waypoint {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a waypoint needs a name");
        }
        if (worldName == null) {
            worldName = "world";
        }
        if (colour == null) {
            colour = Colour.WHITE;
        }
    }

    /**
     * A waypoint at a location, white and permanent.
     *
     * @param name  what the client labels it with
     * @param where where it points at
     * @return the waypoint
     */
    public static @NotNull Waypoint at(@NotNull String name, @NotNull Location where) {
        return new Waypoint(name,
                where.getBlockX(), where.getBlockY(), where.getBlockZ(),
                where.getWorld() == null ? "world" : where.getWorld().getName(),
                where.getWorld() == null ? null : where.getWorld().getUID(),
                Colour.WHITE, null, false, false);
    }

    /**
     * A waypoint at raw coordinates, for a world the caller names itself.
     *
     * @param name  what the client labels it with
     * @param x     block coordinates
     * @param y     block coordinates
     * @param z     block coordinates
     * @param world the world name
     * @return the waypoint
     */
    public static @NotNull Waypoint at(@NotNull String name, int x, int y, int z,
                                       @NotNull String world) {
        return new Waypoint(name, x, y, z, world, null, Colour.WHITE, null, false, false);
    }

    /**
     * The same waypoint in another colour.
     *
     * @param hex a colour such as {@code '#8a51c4'}
     * @return a new waypoint
     */
    public @NotNull Waypoint colour(@NotNull String hex) {
        return colour(Colour.hex(hex));
    }

    /**
     * The same waypoint in another colour.
     *
     * @param colour the colour
     * @return a new waypoint
     */
    public @NotNull Waypoint colour(@NotNull Colour colour) {
        return new Waypoint(name, x, y, z, worldName, worldId, colour, duration,
                preventRemoval, hidden);
    }

    /**
     * The same waypoint, expiring on its own.
     *
     * <p>Only Feather enforces this client-side; on Lunar, which has no such
     * field, the library takes it down itself when the time is up, so the
     * behaviour matches either way. Either way it is a marker on a screen and
     * not a promise: a player who logs out before it expires comes back to a
     * clean minimap, and nothing puts it back.
     *
     * @param duration how long it lives
     * @return a new waypoint
     */
    public @NotNull Waypoint lasting(@NotNull Duration duration) {
        return new Waypoint(name, x, y, z, worldName, worldId, colour, duration,
                preventRemoval, hidden);
    }

    /**
     * The same waypoint, which the player cannot delete.
     *
     * @return a new waypoint
     */
    public @NotNull Waypoint locked() {
        return new Waypoint(name, x, y, z, worldName, worldId, colour, duration, true, hidden);
    }

    /**
     * The same waypoint, created already hidden.
     *
     * @return a new waypoint
     */
    public @NotNull Waypoint startHidden() {
        return new Waypoint(name, x, y, z, worldName, worldId, colour, duration,
                preventRemoval, true);
    }

    /**
     * A colour, as the clients want it.
     *
     * @param red   0 to 255
     * @param green 0 to 255
     * @param blue  0 to 255
     * @param alpha 0 to 255
     * @param chroma whether the client cycles the hue itself
     */
    public record Colour(int red, int green, int blue, int alpha, boolean chroma) {

        /** Plain white, the default. */
        public static final Colour WHITE = new Colour(255, 255, 255, 255, false);

        public Colour {
            red = Math.clamp(red, 0, 255);
            green = Math.clamp(green, 0, 255);
            blue = Math.clamp(blue, 0, 255);
            alpha = Math.clamp(alpha, 0, 255);
        }

        /**
         * A colour from red, green and blue.
         *
         * @param red   0 to 255
         * @param green 0 to 255
         * @param blue  0 to 255
         * @return the colour
         */
        public static @NotNull Colour of(int red, int green, int blue) {
            return new Colour(red, green, blue, 255, false);
        }

        /**
         * A colour written the way a config writes it.
         *
         * <p>A colour nobody can read is not worth refusing a waypoint over, so
         * anything unparseable comes back white.
         *
         * @param hex such as {@code '#8a51c4'}
         * @return the colour
         */
        public static @NotNull Colour hex(@NotNull String hex) {
            String clean = hex.startsWith("#") ? hex.substring(1) : hex;
            try {
                if (clean.length() == 8) {
                    return new Colour(
                            Integer.parseInt(clean.substring(2, 4), 16),
                            Integer.parseInt(clean.substring(4, 6), 16),
                            Integer.parseInt(clean.substring(6, 8), 16),
                            Integer.parseInt(clean.substring(0, 2), 16), false);
                }
                return new Colour(
                        Integer.parseInt(clean.substring(0, 2), 16),
                        Integer.parseInt(clean.substring(2, 4), 16),
                        Integer.parseInt(clean.substring(4, 6), 16), 255, false);
            } catch (RuntimeException ignored) {
                return WHITE;
            }
        }

        /**
         * A colour the client cycles through the rainbow.
         *
         * @return the colour
         */
        public static @NotNull Colour rainbow() {
            return new Colour(255, 255, 255, 255, true);
        }

        /** Packed as {@code 0xAARRGGBB}, which is what both clients read. */
        public int argb() {
            return (alpha << 24) | (red << 16) | (green << 8) | blue;
        }
    }
}
