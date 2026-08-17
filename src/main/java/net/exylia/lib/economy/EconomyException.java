package net.exylia.lib.economy;

/**
 * A programming error in an economy call, not an economy outcome.
 *
 * <p>Thrown for the things that are the caller's fault and should fail loudly
 * during development: a negative amount, a null player, a currency id nobody
 * registered. The ordinary reasons an economy operation fails — not enough
 * money, no provider — are returned in an {@link EconomyResponse}, never thrown,
 * because they are the expected outcome of a purchase rather than a bug.
 *
 * @since 1.26.0
 */
public final class EconomyException extends RuntimeException {

    public EconomyException(String message) {
        super(message);
    }
}
