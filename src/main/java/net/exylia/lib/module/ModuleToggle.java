package net.exylia.lib.module;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * What {@link PluginModules#setEnabled(String, boolean)} did.
 *
 * @param status  what happened to the module that was asked for
 * @param changed every module whose running state changed, the one asked for
 *                included: on {@code ENABLED} it and the dependants that came
 *                back with it, on {@code DISABLED} the dependants that went off
 *                first and then it
 * @param missing on {@code BLOCKED}, the plugins and modules it needs that are
 *                not running; empty otherwise
 * @param menus   the menus of the modules that came on and were never handed
 *                out before, for the plugin to compile ({@code menus.load(toggle.menus())})
 * @since 1.557.0
 */
public record ModuleToggle(@NotNull Status status, @NotNull List<String> changed,
                           @NotNull List<String> missing, @NotNull List<String> menus) {

    public ModuleToggle {
        changed = List.copyOf(changed);
        missing = List.copyOf(missing);
        menus = List.copyOf(menus);
    }

    /** What happened to the module that was asked for. */
    public enum Status {
        /** It is running now. */
        ENABLED,
        /** It is off now, with its dependants. */
        DISABLED,
        /** It already was in the state asked for. */
        UNCHANGED,
        /** It stays off: something in {@link ModuleToggle#missing()} is not running. */
        BLOCKED,
        /** It threw while enabling, was released, and stays off. */
        FAILED,
        /** No module has that id. */
        UNKNOWN
    }

    /** Whether the module asked for is now in the state asked for. */
    public boolean succeeded() {
        return status == Status.ENABLED || status == Status.DISABLED || status == Status.UNCHANGED;
    }

    static @NotNull ModuleToggle of(@NotNull Status status) {
        return new ModuleToggle(status, List.of(), List.of(), List.of());
    }
}
