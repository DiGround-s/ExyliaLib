package net.exylia.lib.placeholder.internal;

import java.util.logging.Logger;

/**
 * Where the module reports a resolver that misbehaves.
 *
 * <p>Kept separate so both the public API and the PlaceholderAPI bridge can
 * reach it without one depending on the other.
 */
public final class Loggers {

    private static volatile Logger logger = Logger.getLogger("ExyliaLib");

    private Loggers() {
    }

    /** Returns the active logger. */
    public static Logger get() {
        return logger;
    }

    /** Sets the logger, called by ExyliaLib at startup. */
    public static void set(Logger value) {
        logger = value;
    }
}
