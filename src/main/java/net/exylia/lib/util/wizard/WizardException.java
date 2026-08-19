package net.exylia.lib.util.wizard;

/**
 * A programming error in a wizard definition, not something a player did.
 *
 * <p>Thrown for what is the caller's fault and should fail loudly the moment
 * the plugin loads its configuration: two steps declared under the same key, a
 * {@code when} guarded by a key nothing asked for yet, a step whose consumer
 * never chose what to ask, a wizard with no steps at all.
 *
 * <p>Everything a player can cause &mdash; a typo, a cancel, walking away until
 * the run times out &mdash; is a {@link WizardResult}, never an exception. The
 * whole point of this module is that a player cannot break a flow by answering
 * badly, so a bad answer is data and a bad definition is a crash.
 *
 * @since 1.34.0
 */
public final class WizardException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message what is wrong with the definition
     */
    public WizardException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a cause.
     *
     * @param message what is wrong with the definition
     * @param cause   what made it fail
     */
    public WizardException(String message, Throwable cause) {
        super(message, cause);
    }
}
