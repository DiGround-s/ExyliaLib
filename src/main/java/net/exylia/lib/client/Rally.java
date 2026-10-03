package net.exylia.lib.client;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;

/**
 * A call for a group to gather at one spot.
 *
 * <p>Drawn as a rally, with a beam, a sound and an announcement, by a client
 * that has one. Every other client that draws waypoints gets a waypoint with
 * the same name that lasts as long, so a plugin never has to send both.
 *
 * @param name     the handle it is removed by
 * @param where    where to gather
 * @param label    what the rally is called on screen
 * @param caller   who called it, shown next to the label
 * @param colour   its colour
 * @param duration how long it stays up
 * @since 1.233.0
 */
public record Rally(@NotNull String name, @NotNull Location where, @NotNull Component label,
                    @NotNull Component caller, @NotNull TextColor colour, @NotNull Duration duration)
        implements ClientElement {

    public Rally {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a rally needs a name");
        }
        if (where == null || where.getWorld() == null) {
            throw new IllegalArgumentException("a rally needs a location in a world");
        }
        if (duration == null || duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("a rally needs a positive duration");
        }
        where = where.clone();
    }

    /**
     * A red rally lasting two minutes.
     *
     * @param name   its handle
     * @param where  where to gather
     * @param label  what it is called
     * @param caller who called it
     * @return the rally
     */
    public static @NotNull Rally at(@NotNull String name, @NotNull Location where,
                                    @NotNull Component label, @NotNull Component caller) {
        return new Rally(name, where, label, caller, NamedTextColor.RED, Duration.ofMinutes(2));
    }

    /**
     * Returns this rally in a colour.
     *
     * @param colour the colour
     * @return the rally
     */
    public @NotNull Rally colour(@NotNull TextColor colour) {
        return new Rally(name, where, label, caller, colour, duration);
    }

    /**
     * Returns this rally lasting a different time.
     *
     * @param duration how long it stays up
     * @return the rally
     */
    public @NotNull Rally lasting(@NotNull Duration duration) {
        return new Rally(name, where, label, caller, colour, duration);
    }
}
