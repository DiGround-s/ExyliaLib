package net.exylia.lib.client;

import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

/**
 * A column of light rising from a spot, drawn by the client.
 *
 * <p>Only clients that have beams draw one; nothing stands in for it
 * elsewhere, so a beam is decoration on top of a waypoint, never instead of
 * one.
 *
 * @param name   the handle it is removed by
 * @param base   where it rises from
 * @param colour its colour
 * @param pulse  whether it pulses
 * @since 1.233.0
 */
public record Beam(@NotNull String name, @NotNull Location base, @NotNull TextColor colour, boolean pulse)
        implements ClientElement {

    public Beam {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a beam needs a name");
        }
        if (base == null || base.getWorld() == null) {
            throw new IllegalArgumentException("a beam needs a location in a world");
        }
        base = base.clone();
    }

    /**
     * A steady beam.
     *
     * @param name   its handle
     * @param base   where it rises from
     * @param colour its colour
     * @return the beam
     */
    public static @NotNull Beam at(@NotNull String name, @NotNull Location base, @NotNull TextColor colour) {
        return new Beam(name, base, colour, false);
    }

    /**
     * Returns this beam pulsing or steady.
     *
     * @param pulse whether it pulses
     * @return the beam
     */
    public @NotNull Beam pulse(boolean pulse) {
        return new Beam(name, base, colour, pulse);
    }
}
