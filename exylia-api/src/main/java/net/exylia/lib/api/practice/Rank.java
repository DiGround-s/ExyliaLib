package net.exylia.lib.api.practice;

import org.jetbrains.annotations.NotNull;

/**
 * Where a player's ELO puts them on the visible ladder.
 *
 * <p>Derived, not stored: the server's ranks file cuts the ELO range into named
 * bands and each band into divisions, and this is the answer for one ELO under
 * the file as it is loaded right now. An admin editing that file moves every
 * player at once, so treat a rank as a rendering of {@link PlayerStats#elo()}
 * rather than as something a player holds.
 *
 * <p>{@code displayName}, {@code divisionName} and {@code icon} are written by
 * the server in MiniMessage — a rank is typically a gradient — so send them
 * through a formatter rather than printing them literally.
 *
 * <p>Not every rank has divisions. When one does not, {@code divisionName} is
 * empty, {@code divisionNumber} is {@code 0} and {@code lpMax} is {@code 0};
 * show the raw ELO in that case, because {@code lp} has nothing to be out of.
 *
 * @param id             the rank id, as the ranks file names it
 * @param displayName    the rank's name, in MiniMessage
 * @param divisionName   the division's name, in MiniMessage, or empty
 * @param divisionNumber which division within the rank, counting down towards
 *                       the top, or {@code 0} when the rank has none
 * @param icon           the division's icon, or the rank's when it has no
 *                       divisions, in MiniMessage
 * @param lp             how far into the division the player is
 * @param lpMax          how wide the division is, or {@code 0} when the rank
 *                       has no divisions
 * @since 1.0.0
 */
public record Rank(
        @NotNull String id,
        @NotNull String displayName,
        @NotNull String divisionName,
        int divisionNumber,
        @NotNull String icon,
        int lp,
        int lpMax) {
}
