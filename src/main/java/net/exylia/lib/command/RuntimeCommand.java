package net.exylia.lib.command;

import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * One command {@link RuntimeCommands} installs.
 *
 * @param name        what it is typed as, without the slash, in lower case
 * @param aliases     other names it answers to
 * @param permission  the node needed to run and see it; empty for none
 * @param description what {@code /help} says about it
 * @param executor    what it does, given the sender and the arguments
 * @param completer   what it suggests for the arguments typed so far
 * @since 1.333.0
 */
public record RuntimeCommand(@NotNull String name, @NotNull List<String> aliases, @NotNull String permission,
                             @NotNull String description, @NotNull BiConsumer<CommandSender, String[]> executor,
                             @NotNull BiFunction<CommandSender, String[], List<String>> completer) {

    public RuntimeCommand {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(completer, "completer");
        name = clean(name);
        if (name.isEmpty()) throw new IllegalArgumentException("A command needs a name");
        aliases = aliases == null ? List.of() : aliases.stream().map(RuntimeCommand::clean)
                .filter(alias -> !alias.isEmpty()).distinct().toList();
        permission = permission == null ? "" : permission;
        description = description == null ? "" : description;
    }

    /** A command with no aliases, no permission and no suggestions. */
    public static @NotNull RuntimeCommand of(@NotNull String name, @NotNull BiConsumer<CommandSender, String[]> executor) {
        return new RuntimeCommand(name, List.of(), "", "", executor, (sender, args) -> List.of());
    }

    public @NotNull RuntimeCommand withAliases(@NotNull List<String> value) {
        return new RuntimeCommand(name, value, permission, description, executor, completer);
    }

    public @NotNull RuntimeCommand withPermission(@NotNull String value) {
        return new RuntimeCommand(name, aliases, value, description, executor, completer);
    }

    public @NotNull RuntimeCommand withDescription(@NotNull String value) {
        return new RuntimeCommand(name, aliases, permission, value, executor, completer);
    }

    public @NotNull RuntimeCommand withCompleter(@NotNull BiFunction<CommandSender, String[], List<String>> value) {
        return new RuntimeCommand(name, aliases, permission, description, executor, value);
    }

    private static String clean(String name) {
        if (name == null) return "";
        String trimmed = name.trim().toLowerCase(Locale.ROOT);
        return trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
    }
}
