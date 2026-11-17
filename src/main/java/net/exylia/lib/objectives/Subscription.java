package net.exylia.lib.objectives;

/**
 * A registered objective listener. Plugins rarely need it: every subscription
 * goes away when its plugin is disabled.
 *
 * @since 1.620.0
 */
public final class Subscription {

    private final Runnable cancel;

    Subscription(Runnable cancel) {
        this.cancel = cancel;
    }

    /** Stops it from being called again. Safe to call more than once, from any thread. */
    public void cancel() {
        cancel.run();
    }
}
