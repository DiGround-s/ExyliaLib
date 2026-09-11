package net.exylia.lib.api.staff;

/**
 * What became of a report filed through
 * {@link StaffService#report(org.bukkit.entity.Player, org.bukkit.entity.Player, String)}.
 *
 * <p>Every refusal but {@link #CANCELLED} and {@link #MODULE_DISABLED} has
 * already been explained to the reporter in the plugin's own words, the way
 * {@code /report} explains it, so a caller only has to say something for those
 * two.
 *
 * @since 1.3.0
 */
public enum ReportResult {

    /**
     * The report was accepted and is on its way to staff.
     *
     * <p>Stored asynchronously: the staff alert and the reporter's confirmation
     * follow once the row is written.
     */
    SUBMITTED,

    /** The reporter named themselves. */
    SELF,

    /** The target is staff, and the owner does not allow reporting staff. */
    STAFF_PROTECTED,

    /** The reporter filed another report too recently. */
    ON_COOLDOWN,

    /** The reporter already has as many open reports as the owner allows. */
    TOO_MANY_OPEN,

    /** A handler cancelled the {@link net.exylia.lib.api.staff.event.PlayerReportEvent}. */
    CANCELLED,

    /** The reports module is off. */
    MODULE_DISABLED
}
