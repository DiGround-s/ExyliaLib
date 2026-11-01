package net.exylia.lib.respawn;

/**
 * A registered rule or callback. Plugins rarely need it: everything a plugin
 * registered goes away when it is disabled.
 *
 * @since 1.440.0
 */
public final class RespawnHook {

    private final Runnable cancel;

    RespawnHook(Runnable cancel) {
        this.cancel = cancel;
    }

    /** Stops it from running again. Safe to call more than once. */
    public void cancel() {
        cancel.run();
    }
}
