package net.exylia.lib.practicebot;

/**
 * The server is already running as many bots as it allows.
 *
 * <p>Not a failure worth logging a stack trace over - it is the expected answer
 * when a busy server is asked for one bot too many. Tell the player to try again
 * in a moment.
 *
 * @since 1.73.0
 */
public class BotLimitReachedException extends RuntimeException {

    private final int capacity;

    public BotLimitReachedException(int capacity) {
        super("bot limit reached: " + capacity);
        this.capacity = capacity;
    }

    /** The cap that was hit. */
    public int capacity() {
        return capacity;
    }
}
