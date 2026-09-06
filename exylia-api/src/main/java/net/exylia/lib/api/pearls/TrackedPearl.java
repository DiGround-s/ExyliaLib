package net.exylia.lib.api.pearls;

import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

/**
 * What ExyliaPearls remembers about a pearl still in flight.
 *
 * <p>A snapshot, taken when you asked. The plugin replaces this record every
 * time the pearl passes somewhere the thrower would fit, so a copy held across
 * ticks describes a pearl that has since moved on.
 *
 * <p>Both locations are copies: moving them changes nothing, and the plugin
 * will not see the change.
 *
 * @param origin   where the pearl was thrown from, the last resort if nothing
 *                 better was ever recorded
 * @param lastSafe the most recent place along the flight that could hold the
 *                 thrower, and where they are sent if the pearl lands inside a
 *                 block
 * @since 1.0.0
 */
public record TrackedPearl(@NotNull Location origin, @NotNull Location lastSafe) {
}
