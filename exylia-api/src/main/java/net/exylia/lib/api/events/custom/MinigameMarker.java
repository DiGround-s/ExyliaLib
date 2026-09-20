package net.exylia.lib.api.events.custom;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * A place a minigame needs an admin to mark on its arena.
 *
 * <p>An arena is a box and a list of spawn points; anything else the game plays
 * around — a goal, a finish line, a capture zone — is a marker. Declaring one
 * puts it on the arena setup screen, refuses to enable an arena that is missing
 * it, and gets the handler told when a player walks into or out of it through
 * {@link MinigameHandler#onMarkerEnter}. What was marked is read back with
 * {@link MinigameArena#places(String)}.
 *
 * <pre>{@code
 * MinigameMarker.point("finish", "Finish line", "CHECKERED_FLAG")
 *         .count(1, 4)
 *         .radius(2.0);
 * }</pre>
 *
 * @param role   what the handler calls it, and the key it reads it back under
 * @param kind   a box an admin selects, or a block they stand on
 * @param min    how many must be set before the arena may be enabled; {@code 0}
 *               for an optional one
 * @param max    how many may be set at all
 * @param label  what the setup screen calls it
 * @param icon   the material the setup screen draws it with
 * @param radius for a {@link MinigameMarkerKind#POINT}, how far from the block
 *               still counts as standing at it
 * @since 1.7.0
 */
public record MinigameMarker(
        @NotNull String role,
        @NotNull MinigameMarkerKind kind,
        int min,
        int max,
        @NotNull String label,
        @NotNull String icon,
        double radius) {

    private static final double DEFAULT_POINT_RADIUS = 1.5;

    /** Validates and normalises what was handed in. */
    public MinigameMarker {
        if (role == null || role.isBlank()) throw new IllegalArgumentException("A marker needs a role.");
        if (kind == null) throw new IllegalArgumentException("Marker '" + role + "' needs a kind.");
        // The role becomes part of the id each marked place is stored and
        // registered as a region under, so it is held to the same shape a
        // setting key is.
        role = role.toLowerCase(Locale.ROOT);
        if (!role.matches("[a-z0-9-]+")) {
            throw new IllegalArgumentException("The marker role '" + role
                    + "' may only hold lowercase letters, digits and dashes.");
        }
        min = Math.max(0, min);
        max = Math.max(Math.max(1, min), max);
        label = label == null || label.isBlank() ? role : label;
        icon = icon == null || icon.isBlank() ? "TARGET" : icon.toUpperCase(Locale.ROOT);
    }

    /** A box, selected by its two corners. */
    public static @NotNull MinigameMarker area(@NotNull String role, @NotNull String label,
                                               @NotNull String icon) {
        return new MinigameMarker(role, MinigameMarkerKind.AREA, 1, 1, label, icon, 0);
    }

    /** A single block, marked by standing on it. */
    public static @NotNull MinigameMarker point(@NotNull String role, @NotNull String label,
                                                @NotNull String icon) {
        return new MinigameMarker(role, MinigameMarkerKind.POINT, 1, 1, label, icon,
                DEFAULT_POINT_RADIUS);
    }

    /** How many are required, and how many are allowed. */
    public @NotNull MinigameMarker count(int min, int max) {
        return new MinigameMarker(role, kind, min, max, label, icon, radius);
    }

    /** How far from the block still counts as standing at it. */
    public @NotNull MinigameMarker radius(double blocks) {
        return new MinigameMarker(role, kind, min, max, label, icon, blocks);
    }

    /** Whether an arena without this one may be enabled. */
    public boolean isRequired() {
        return min > 0;
    }
}
