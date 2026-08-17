package net.exylia.lib.database.internal;

/**
 * Lets tests in other packages reset the missing-index diagnostic.
 *
 * <p>Lives here because {@code IndexCoverage}'s state is package-private on
 * purpose: it is deliberately as long-lived as the record class it describes —
 * a warning is about a shape of query, not an occurrence of one — and the
 * production API should not grow a way to clear it just so a test can assert
 * that a line fires exactly once.
 */
public final class IndexCoverageAccess {

    private IndexCoverageAccess() {
    }

    /** Forgets every computed coverage, and with it what has already been reported. */
    public static void forgetAll() {
        IndexCoverage.forgetAll();
    }
}
