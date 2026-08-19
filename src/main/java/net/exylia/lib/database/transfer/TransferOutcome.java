package net.exylia.lib.database.transfer;

/**
 * How a transfer ended.
 *
 * <p>Three values rather than a boolean, and that is the point of the type.
 * ExyliaCommons' importer logged a failed batch and carried on, and its result
 * still reported success — so an import that lost a thousand rows and one that
 * lost none were the same answer, and the only record of the difference was a
 * console line nobody was reading at the time.
 *
 * @since 1.36.0
 */
public enum TransferOutcome {

    /** Every table was handled and every row was written. */
    SUCCESS,

    /**
     * The transfer ran to the end, but something in it did not.
     *
     * <p>A table in the dump that no model claims, a column the current record
     * no longer has, a row the engine refused. The file was read and whatever
     * could be written was written — this says the result is not the whole of
     * what was asked for, and {@link TransferReport#problems()} says what.
     */
    PARTIAL,

    /**
     * Nothing usable happened.
     *
     * <p>The file could not be opened, its header could not be read, or an
     * import was refused because the target tables already hold rows. A
     * refusal is a failure on purpose: the caller asked for something the
     * module declined to do, and reporting it as a partial success would leave
     * a server owner believing a migration had half happened.
     */
    FAILED
}
