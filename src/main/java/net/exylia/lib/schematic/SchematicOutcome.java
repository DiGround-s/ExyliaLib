package net.exylia.lib.schematic;

/**
 * How a schematic operation ended.
 *
 * <p>Four answers rather than one boolean, because a caller does different
 * things with each. ExyliaCommons returned {@code false} for all of them, so a
 * menu greying out a button, a command explaining itself and a restart loop
 * deciding whether to retry all had to guess which one they had.
 *
 * @since 1.48.0
 */
public enum SchematicOutcome {

    /** The whole operation completed. */
    SUCCESS,

    /** There is no such schematic. Nothing went wrong. */
    NOT_FOUND,

    /** There is no engine: FastAsyncWorldEdit is absent, unbindable, or Folia. */
    UNSUPPORTED,

    /** It was attempted and did not finish. The reason says why. */
    FAILED
}
