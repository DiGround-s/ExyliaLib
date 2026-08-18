package net.exylia.lib.input;

/**
 * A programming error in an input request, not something a player did.
 *
 * <p>Thrown for what is the caller's fault and should fail loudly during
 * development: a form with two fields under the same key, a range whose minimum
 * is above its maximum, a choice with no options, a null prompt.
 *
 * <p>Everything a player can cause — an unparseable answer, a cancel, a
 * timeout — is an {@link InputResult}, never an exception. A player typing
 * nonsense is the expected use of a text box, not a bug.
 *
 * @since 1.31.0
 */
public final class InputException extends RuntimeException {

    public InputException(String message) {
        super(message);
    }
}
