package net.exylia.lib.action.internal;

import net.exylia.lib.action.ActionHandler;
import net.exylia.lib.action.ActionId;

import java.util.concurrent.atomic.AtomicBoolean;

/** One registered handler, deactivated when its owning plugin disables. */
public final class RegisteredAction {
    private final ActionId id;
    private final String owner;
    private final ActionHandler delegate;
    private final AtomicBoolean active = new AtomicBoolean(true);

    RegisteredAction(ActionId id, String owner, ActionHandler delegate) {
        this.id = id;
        this.owner = owner;
        this.delegate = delegate;
    }

    public ActionId id() { return id; }
    String owner() { return owner; }
    void deactivate() { active.set(false); }

    /**
     * A compiled call keeps this wrapper, not the raw handler. Thus even an old
     * menu retained past a plugin disable cannot call code from its dead
     * classloader.
     */
    public ActionHandler handler() {
        return (context, arguments) -> active.get()
                ? delegate.execute(context, arguments)
                : ActionHandler.completed(net.exylia.lib.action.ActionResult.failed(
                        "Action " + id + " belongs to a plugin that is no longer enabled"));
    }
}
