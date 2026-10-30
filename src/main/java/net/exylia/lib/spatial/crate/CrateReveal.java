package net.exylia.lib.spatial.crate;

/**
 * What became of a prize once handed over.
 *
 * @param duplicate whether the player already owned it
 * @param refund    the keys a duplicate gave back
 * @since 1.416.0
 */
public record CrateReveal(boolean duplicate, int refund) {

    /** Something new. */
    public static final CrateReveal NEW = new CrateReveal(false, 0);
}
