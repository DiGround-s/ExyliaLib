package net.exylia.lib.command;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What happened to one command.
 *
 * <p>{@link Status#DISPATCHED} is deliberately not called success. Handing a
 * string to Bukkit tells you the command was found and started, not that it did
 * what the person writing the YAML meant. Claiming more than that is how the
 * previous system reported "success" for proxy commands that nothing was
 * listening to.
 *
 * @since 1.22.0
 */
public record CommandResult(@NotNull Status status, @NotNull String command,
                            @Nullable String detail, @Nullable Throwable error) {

    /** How a command ended. */
    public enum Status {

        /** Handed to the server, or to the proxy, without an error. */
        DISPATCHED,

        /** The command was found but reported failure. */
        REJECTED,

        /** The player it needed had gone offline. */
        NO_PLAYER,

        /** A proxy command with no bridge installed to carry it. */
        NO_TRANSPORT,

        /** The command threw. */
        FAILED
    }

    public static @NotNull CommandResult dispatched(@NotNull String command) {
        return new CommandResult(Status.DISPATCHED, command, null, null);
    }

    public static @NotNull CommandResult rejected(@NotNull String command, @NotNull String detail) {
        return new CommandResult(Status.REJECTED, command, detail, null);
    }

    public static @NotNull CommandResult noPlayer(@NotNull String command) {
        return new CommandResult(Status.NO_PLAYER, command, "player is offline", null);
    }

    public static @NotNull CommandResult noTransport(@NotNull String command, @NotNull String detail) {
        return new CommandResult(Status.NO_TRANSPORT, command, detail, null);
    }

    public static @NotNull CommandResult failed(@NotNull String command, @NotNull Throwable error) {
        return new CommandResult(Status.FAILED, command, error.getMessage(), error);
    }

    /** Returns whether the command was handed off without an error. */
    public boolean isDispatched() {
        return status == Status.DISPATCHED;
    }

    /** Returns whether the rest of the list should still run. */
    public boolean continues() {
        return status == Status.DISPATCHED;
    }
}
