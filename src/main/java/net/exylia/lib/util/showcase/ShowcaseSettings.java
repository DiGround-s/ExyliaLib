package net.exylia.lib.util.showcase;

import net.exylia.lib.config.Comment;
import net.exylia.lib.config.Key;
import net.exylia.lib.util.teleport.ExyliaLocation;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a plugin's showcases stand, and how they pace themselves.
 *
 * <p>Nests inside a plugin's own configuration record like any other section:
 *
 * <pre>{@code
 * public record MySettings(ShowcaseSettings showcase) {
 *     public MySettings() {
 *         this(new ShowcaseSettings());
 *     }
 *
 *     public MySettings withShowcase(ShowcaseSettings showcase) {
 *         return new MySettings(showcase);
 *     }
 * }
 * }</pre>
 *
 * @param locations    where showcases stand, as {@code server,world,x,y,z,yaw,pitch}
 * @param pauseSeconds the rest between one turn ending and the next starting
 * @param radius       how close a player has to be for a showcase to play, in blocks
 * @param only         the ids a showcase picks from; empty picks from all of them
 * @since 1.188.0
 */
@Comment("Places that show this plugin's cosmetics off on a loop.")
@Comment("")
@Comment("Nobody has to own anything or do anything: stand near one and it plays,")
@Comment("one cosmetic after another. With nobody near, it plays nothing and costs")
@Comment("one distance check a second.")
public record ShowcaseSettings(

        @Comment("Where showcases stand, as server,world,x,y,z,yaw,pitch.")
        @Comment("Set them in game with this plugin's showcase add admin command,")
        @Comment("which writes where you stand and the way you face.")
        List<String> locations,

        @Key("pause-seconds")
        @Comment("The rest between one turn ending and the next starting, in seconds.")
        double pauseSeconds,

        @Comment("How close a player has to be for a showcase to play and to see it,")
        @Comment("in blocks.")
        double radius,

        @Comment("The ids a showcase picks from. Empty picks from every one.")
        List<String> only) {

    public ShowcaseSettings() {
        this(List.of(), 3.0, 24.0, List.of());
    }

    public ShowcaseSettings {
        locations = locations == null ? List.of() : List.copyOf(locations);
        pauseSeconds = Math.max(0, pauseSeconds);
        radius = Math.max(1, radius);
        only = only == null ? List.of() : List.copyOf(only);
    }

    /** The same settings with showcases standing somewhere else, for a command that moves them. */
    public @NotNull ShowcaseSettings withLocations(@NotNull List<String> locations) {
        return new ShowcaseSettings(locations, pauseSeconds, radius, only);
    }

    /** The rest between turns, in milliseconds. */
    public long pauseMillis() {
        return (long) (pauseSeconds * 1000);
    }

    /**
     * Every location that names a loaded world, in the order they are written.
     *
     * @return the live locations; an entry that is not a location, or whose
     *         world is not loaded yet, is left out
     */
    public @NotNull List<Location> places() {
        List<Location> places = new ArrayList<>(locations.size());
        for (String entry : locations) {
            Location where = parse(entry);
            if (where != null) {
                places.add(where);
            }
        }
        return places;
    }

    /** Whether an entry names the world, loaded or not. */
    static boolean isIn(@NotNull String entry, @NotNull String world) {
        try {
            return world.equals(ExyliaLocation.fromString(entry).world());
        } catch (RuntimeException notALocation) {
            return false;
        }
    }

    static @Nullable Location parse(@NotNull String entry) {
        try {
            return ExyliaLocation.fromString(entry).toBukkitLocation();
        } catch (RuntimeException notALocation) {
            return null;
        }
    }
}
