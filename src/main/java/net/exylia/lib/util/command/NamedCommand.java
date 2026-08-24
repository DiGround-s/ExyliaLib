package net.exylia.lib.util.command;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A console command with a name somebody can read.
 *
 * <pre>{@code
 * NamedCommand welcome = NamedCommand.of("Welcome kit", "give %player_name% bread 3");
 * }</pre>
 *
 * <p>A bare {@code List<String>} of commands is what every plugin started with,
 * and it is unreadable the moment there are four of them: an admin editing an
 * arena's setup commands sees four lines of {@code lp user %player_name% parent
 * set …} and has to read each one to find the one they meant. A name costs one
 * field and turns the list into something scannable.
 *
 * <h2>The stored form is ExyliaCommons'</h2>
 * The field names {@link NamedCommands} reads and writes are the old Lombok
 * bean's — {@code id}, {@code name}, {@code command} — because rows written by
 * it are in production databases. Migrating is changing imports.
 *
 * @param id      identity, stable across edits
 * @param name    what an admin calls it, or {@code null}
 * @param command what the console runs, without a leading slash
 * @since 1.56.0
 */
public record NamedCommand(@NotNull String id, @Nullable String name, @Nullable String command) {

    /** Validates the record. */
    public NamedCommand {
        Objects.requireNonNull(id, "id");
    }

    /**
     * A new command under a fresh identity.
     *
     * @param name    what to call it
     * @param command what the console runs
     * @return the command
     */
    public static @NotNull NamedCommand of(@Nullable String name, @Nullable String command) {
        return new NamedCommand(UUID.randomUUID().toString(), name, command);
    }

    /** An empty one, for an editor's add button. */
    public static @NotNull NamedCommand blank() {
        return of(null, null);
    }

    /**
     * The name to show, falling back to the command itself.
     *
     * <p>Never {@code null}: a command nobody named still has to read as
     * something in a menu, and a half-configured row says so.
     *
     * @return something a human reads
     */
    public @NotNull String displayName() {
        if (name != null && !name.isBlank()) {
            return name;
        }
        return command != null && !command.isBlank() ? command : "(not set)";
    }

    /** Whether this row would actually run something. */
    public boolean isRunnable() {
        return command != null && !command.isBlank();
    }

    /** The same command under a new identity, for duplicating a row. */
    public @NotNull NamedCommand copy() {
        return new NamedCommand(UUID.randomUUID().toString(), name, command);
    }

    /** This command with a different name. */
    public @NotNull NamedCommand withName(@Nullable String name) {
        return new NamedCommand(id, name, command);
    }

    /** This command with a different body. */
    public @NotNull NamedCommand withCommand(@Nullable String command) {
        return new NamedCommand(id, name, command);
    }

    /**
     * Just the commands, in order, for a caller that only wants to run them.
     *
     * @param commands the list
     * @return the runnable ones, names dropped
     */
    public static @NotNull List<String> bodies(@NotNull List<NamedCommand> commands) {
        return commands.stream()
                .filter(NamedCommand::isRunnable)
                .map(NamedCommand::command)
                .toList();
    }
}
